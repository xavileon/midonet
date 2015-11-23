/*
 * Copyright 2015 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.midolman

import java.nio.channels.spi.SelectorProvider
import java.util.UUID
import java.util.concurrent.{ThreadFactory, Executors, ExecutorService}
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}

import org.midonet.conf.HostIdGenerator

import scala.concurrent.{Future, ExecutionContext}

import akka.actor.{OneForOneStrategy, SupervisorStrategy, ActorSystem}
import com.codahale.metrics.MetricRegistry
import com.google.inject.name.Names
import com.google.inject.AbstractModule
import com.lmax.disruptor._
import com.typesafe.config.ConfigFactory
import org.slf4j.{LoggerFactory, Logger}

import org.midonet.cluster.backend.cassandra.CassandraClient
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.datapath._
import org.midonet.midolman.datapath.DisruptorDatapathChannel.PacketContextHolder
import org.midonet.midolman.host.scanner.{DefaultInterfaceScanner, InterfaceScanner}
import org.midonet.midolman.host.services.HostService
import org.midonet.midolman.io._
import org.midonet.midolman.logging.{FlowTracingSchema, FlowTracingAppender}
import org.midonet.midolman.monitoring.FlowRecorderFactory
import org.midonet.midolman.monitoring.metrics.PacketPipelineMetrics
import org.midonet.midolman.openstack.metadata.{DatapathInterface, Plumber}
import org.midonet.midolman.services._
import org.midonet.midolman.state._
import org.midonet.midolman.topology.{VirtualToPhysicalMapper, VirtualTopology}
import org.midonet.netlink.{NetlinkUtil, NetlinkProtocol, NetlinkChannelFactory}
import org.midonet.odp.OvsNetlinkFamilies
import org.midonet.Util
import org.midonet.util.concurrent._
import org.midonet.util.eventloop.{SimpleSelectLoop, SelectLoop}
import org.midonet.util.functors.Predicate
import org.midonet.util._

class MidolmanModule(config: MidolmanConfig,
                     metricRegistry: MetricRegistry) extends AbstractModule {
    private val log: Logger = LoggerFactory.getLogger(classOf[MidolmanModule])

    override def configure(): Unit = {
        bind(classOf[MidolmanConfig]).toInstance(config)
        val host = hostId()
        bind(classOf[HostIdProvider]).toInstance(new HostIdProvider {
            override def hostId(): UUID = host
        })

        bind(classOf[NanoClock]).toInstance(NanoClock.DEFAULT)
        bind(classOf[UnixClock]).toInstance(UnixClock.DEFAULT)

        val metrics = new PacketPipelineMetrics(
            metricRegistry, config.simulationThreads)
        bind(classOf[MetricRegistry]).toInstance(metricRegistry)
        bind(classOf[PacketPipelineMetrics]).toInstance(metrics)

        // We add an extra slot so that channels can return tokens
        // they obtained due to the multiplier effect but didn't use.
        val counter = new StatisticalCounter(config.simulationThreads + 1)
        val policy = htbPolicy(counter)
        bind(classOf[StatisticalCounter]).toInstance(counter)
        bind(classOf[TokenBucketPolicy]).toInstance(policy)

        val channelFactory = netlinkChannelFactory()
        val families = ovsNetlinkFamilies(channelFactory)
        val dpState = datapathStateDriver(channelFactory, families)
        bind(classOf[NetlinkChannelFactory]).toInstance(channelFactory)
        bind(classOf[OvsNetlinkFamilies]).toInstance(families)
        bind(classOf[DatapathStateDriver]).toInstance(dpState)
        bind(classOf[DatapathState]).to(classOf[DatapathStateDriver])
        bind(classOf[VirtualPortsResolver]).to(classOf[DatapathStateDriver])
        bind(classOf[UnderlayResolver]).to(classOf[DatapathStateDriver])

        val capacity = Util.findNextPositivePowerOfTwo(
            config.datapath.globalIncomingBurstCapacity)
        val ringBuffer = RingBuffer
            .createMultiProducer(DisruptorDatapathChannel.Factory, capacity)
        val barrier = ringBuffer.newBarrier()
        val fp = flowProcessor(dpState, families, channelFactory)
        val channel = datapathChannel(
            ringBuffer, barrier, fp, dpState, families, channelFactory, metrics)
        bind(classOf[FlowProcessor]).toInstance(fp)
        bind(classOf[DatapathChannel]).toInstance(channel)

        bind(classOf[DatapathConnectionPool]).toInstance(connectionPool())
        val dpConnectionManager = upcallDatapathConnectionManager(policy)
        bind(classOf[UpcallDatapathConnectionManager]).toInstance(
            dpConnectionManager)

        bind(classOf[DatapathConnectionService]).asEagerSingleton()

        bind(classOf[FlowStateStorageFactory]).toInstance(flowStateStorageFactory())

        bindActorService()

        val scanner = interfaceScanner(channelFactory)
        bind(classOf[InterfaceScanner]).toInstance(scanner)

        bindHostService()

        bind(classOf[Plumber]).toInstance(plumber(dpState))
        bind(classOf[DatapathInterface]).toInstance(
            datapathInterface(scanner, dpState, dpConnectionManager))

        val as = actorSystem()
        bind(classOf[ActorSystem]).toInstance(as)
        bind(classOf[SupervisorStrategy]).toInstance(crashStrategy())
        val backChannel = simulationBackChannel(as)
        bind(classOf[SimulationBackChannel]).toInstance(backChannel)

        bind(classOf[FlowTracingAppender]).toInstance(flowTracingAppender())
        bind(classOf[FlowRecorderFactory]).asEagerSingleton()

        bind(classOf[PeerResolver]).asEagerSingleton()

        bindNatAllocator()
        bindSelectLoopService()
        bindVirtualTopology()
        bindVirtualToPhysicalMapper()

        bind(classOf[MidolmanService]).asEagerSingleton()
    }

    protected def hostId() =
        HostIdGenerator.getHostId

    protected def htbPolicy(counter: StatisticalCounter) = {
        val multiplier = 8
        // Here we check whether increments to our slot in the StatisticalCounter
        // should be atomic or not, depending on whether multiple threads will
        // be accessing it (true in the one_to_one" configuration setting).
        val atomic = config.inputChannelThreading match {
            case "one_to_many" => false
            case "one_to_one" => true
            case s => throw new IllegalArgumentException(
                        "Unknown value for input_channel_threading: " + s)
        }

        new TokenBucketPolicy(
            config,
            new TokenBucketSystemRate(counter, multiplier),
            multiplier,
            tb => new Bucket(tb, multiplier, counter, config.simulationThreads, atomic))
    }

    protected def netlinkChannelFactory() =
        new NetlinkChannelFactory

    protected def ovsNetlinkFamilies(channelFactory: NetlinkChannelFactory) = {
        val channel = channelFactory.create(
            blocking = true, NetlinkProtocol.NETLINK_GENERIC, NetlinkUtil.NO_NOTIFICATION)
        try {
            try {
                val families = OvsNetlinkFamilies.discover(channel)
                log.debug(families.toString)
                families
            } finally {
                channel.close()
            }
        }  catch { case e: Exception =>
            throw new RuntimeException(e)
        }
    }

    protected def datapathStateDriver(
            channelFactory: NetlinkChannelFactory,
            families: OvsNetlinkFamilies) =
        DatapathBootstrap.bootstrap(
            config, channelFactory, families)

    protected def flowProcessor(
            dpState: DatapathState,
            families: OvsNetlinkFamilies,
            channelFactory: NetlinkChannelFactory) =
        new FlowProcessor(
            dpState,
            families,
            maxPendingRequests = config.datapath.globalIncomingBurstCapacity * 2,
            maxRequestSize = 512,
            channelFactory,
            SelectorProvider.provider,
            NanoClock.DEFAULT)

    protected def createProcessors(
            ringBuffer: RingBuffer[PacketContextHolder],
            barrier: SequenceBarrier,
            flowProcessor: FlowProcessor,
            dpState: DatapathState,
            families: OvsNetlinkFamilies,
            channelFactory: NetlinkChannelFactory,
            metrics: PacketPipelineMetrics) = {
        val threads = Math.max(config.outputChannels, 1)
        val processors = new Array[EventProcessor](threads)
        if (threads  == 1) {
            val handler = new AggregateEventPollerHandler(
                    flowProcessor,
                    new EventPollerHandlerAdapter(new PacketExecutor(
                        dpState, families, 1, 0, channelFactory, metrics)))
            processors(0) = new BackChannelEventProcessor(
                ringBuffer, handler, flowProcessor)
        } else {
            val numPacketHandlers = threads  - 1
            for (i <- 0 until numPacketHandlers) {
                val pexec = new PacketExecutor(
                    dpState, families, numPacketHandlers, i, channelFactory, metrics)
                processors(i) = new BatchEventProcessor(ringBuffer, barrier, pexec)
            }
            processors(numPacketHandlers) = new BackChannelEventProcessor(
                ringBuffer, flowProcessor, flowProcessor)
        }
        processors
    }

    protected def datapathChannel(
            ringBuffer: RingBuffer[PacketContextHolder],
            barrier: SequenceBarrier,
            flowProcessor: FlowProcessor,
            dpState: DatapathState,
            families: OvsNetlinkFamilies,
            channelFactory: NetlinkChannelFactory,
            metrics: PacketPipelineMetrics): DatapathChannel = {
        new DisruptorDatapathChannel(
            ringBuffer,
            createProcessors(
                ringBuffer,
                barrier,
                flowProcessor,
                dpState,
                families,
                channelFactory,
                metrics))
    }

    protected def upcallDatapathConnectionManager(
            tbPolicy: TokenBucketPolicy) =
        config.inputChannelThreading match {
            case "one_to_many" =>
                new OneToManyDpConnManager(config, tbPolicy)
            case "one_to_one" =>
                new OneToOneDpConnManager(config, tbPolicy)
            case s =>
                throw new IllegalArgumentException(
                    "Unknown value for input_channel_threading: " + s)
        }

    protected def flowStateStorageFactory() = {
        val cass = new CassandraClient(
            config.zookeeper,
            config.cassandra,
            "MidonetFlowState",
            FlowStateStorage.SCHEMA,
            FlowStateStorage.SCHEMA_TABLE_NAMES)
        new FlowStateStorageFactory() {
            override def create(): Future[FlowStateStorage] =
                cass.connect().map(FlowStateStorage(_))(ExecutionContext.callingThread)
        }
    }

    protected def connectionPool(): DatapathConnectionPool =
        new OneToOneConnectionPool(
            "netlink.requests", config.outputChannels, config)

    protected def interfaceScanner(channelFactory: NetlinkChannelFactory): InterfaceScanner =
        new DefaultInterfaceScanner(
            channelFactory,
            NetlinkUtil.DEFAULT_MAX_REQUESTS,
            NetlinkUtil.DEFAULT_MAX_REQUEST_SIZE,
            NanoClock.DEFAULT)

    protected def bindHostService(): Unit =
        bind(classOf[HostService]).asEagerSingleton()

    protected def datapathInterface(
            scanner: InterfaceScanner,
            dpState: DatapathState,
            dpConnManager: UpcallDatapathConnectionManager) =
        new DatapathInterface(scanner, dpState, dpConnManager)

    protected def plumber(dpState: DatapathState) =
        new Plumber(dpState)

    protected def actorSystem() =
        ActorSystem.create("midolman", ConfigFactory.load().getConfig("midolman"))

    protected def bindActorService(): Unit =
        bind(classOf[MidolmanActorsService]).asEagerSingleton()

    protected def simulationBackChannel(as: ActorSystem): SimulationBackChannel = {
        val backchannel = ShardedSimulationBackChannel(as)
        bind(classOf[ShardedSimulationBackChannel]).toInstance(backchannel)
        backchannel
    }

    protected def flowTracingAppender() = {
        val cass = new CassandraClient(
            config.zookeeper,
            config.cassandra,
            FlowTracingSchema.KEYSPACE_NAME,
            FlowTracingSchema.SCHEMA,
            FlowTracingSchema.SCHEMA_TABLE_NAMES)
        new FlowTracingAppender(cass.connect())
    }

    protected def bindNatAllocator(): Unit =
        bind(classOf[NatBlockAllocator])
            .to(classOf[ZkNatBlockAllocator])
            .asEagerSingleton()

    protected def bindSelectLoopService(): Unit = {
        bind(classOf[SelectLoop])
            .annotatedWith(classOf[SelectLoopService.ZEBRA_SERVER_LOOP])
            .to(classOf[SimpleSelectLoop])
            .asEagerSingleton()
        bind(classOf[SelectLoopService]).asEagerSingleton()
    }

    protected def bindVirtualTopology(): Unit = {
        val vtThread: AtomicLong = new AtomicLong(-1)
        bind(classOf[ExecutorService])
            .annotatedWith(Names.named(VirtualTopology.VtExecutorName))
            .toInstance(Executors.newSingleThreadExecutor(
                new NamedThreadFactory("devices-service", isDaemon = true)))
        bind(classOf[Predicate]).annotatedWith(
            Names.named(VirtualTopology.VtExecutorCheckerName))
            .toInstance(new Predicate() {
                def check(): Boolean =
                    vtThread.get < 0 || vtThread.get == Thread.currentThread.getId
            })
        val ioThreadIndex: AtomicInteger = new AtomicInteger(0)
        bind(classOf[ExecutorService])
            .annotatedWith(Names.named(VirtualTopology.IoExecutorName))
            .toInstance(Executors.newCachedThreadPool(
                new ThreadFactory() {
                def newThread(r: Runnable): Thread = {
                    val name = "devices-io-" + ioThreadIndex.getAndIncrement
                    val t = new Thread(r, name)
                    t.setDaemon(true)
                    t
                }
            }))
        bind(classOf[VirtualTopology]).asEagerSingleton()
    }

    protected def bindVirtualToPhysicalMapper(): Unit = {
        bind(classOf[VirtualToPhysicalMapper]).asEagerSingleton()
    }

    protected def crashStrategy(): SupervisorStrategy =
        new OneForOneStrategy()({ case t =>
            log.warn("Actor crashed, aborting", t)
            System.exit(-1)
            akka.actor.SupervisorStrategy.stop
        })
}
