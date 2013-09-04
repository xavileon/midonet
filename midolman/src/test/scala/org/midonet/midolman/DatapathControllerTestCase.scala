/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman

import akka.testkit.TestProbe
import collection.mutable

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.ShouldMatchers

import org.midonet.midolman.topology.rcu.{Host => RCUHost}
import org.midonet.midolman.topology.LocalPortActive
import org.midonet.midolman.topology.VirtualToPhysicalMapper._
import org.midonet.cluster.data.{Bridge => ClusterBridge,
    Ports => ClusterPorts}
import org.midonet.cluster.data.host.Host
import org.midonet.odp.{Datapath, Ports}
import org.midonet.odp.ports.NetDevPort


@RunWith(classOf[JUnitRunner])
class DatapathControllerTestCase extends MidolmanTestCase with ShouldMatchers {

  import scala.collection.JavaConversions._
  import DatapathController._

  private var portEventsProbe: TestProbe = null
  override def beforeTest() {
      portEventsProbe = newProbe()
      actors().eventStream.subscribe(portEventsProbe.ref,
          classOf[LocalPortActive])
  }

  def testDatapathEmptyDefault() {

    val host = new Host(hostId()).setName("myself")
    clusterDataClient().hostsCreate(hostId(), host)

    dpConn().datapathsEnumerate().get() should have size 0

    // send initialization message and wait
    initializeDatapath()

    // validate the final datapath state
    val datapaths: mutable.Set[Datapath] = dpConn().datapathsEnumerate().get()

    datapaths should have size 1
    datapaths.head should have('name("midonet"))

    val ports = datapathPorts(datapaths.head)
    ports should have size 2
    ports should contain key ("tngre-mm")
    ports should contain key ("midonet")
  }

  def testDatapathAddMappingAfter() {

    val host = new Host(hostId()).setName("myself")
    clusterDataClient().hostsCreate(hostId(), host)

    initializeDatapath() should not be null

    // make a bridge
    val bridge = new ClusterBridge().setName("test")
    bridge.setId(clusterDataClient().bridgesCreate(bridge))

    // make a port on the bridge
    val port = ClusterPorts.materializedBridgePort(bridge)
    port.setId(clusterDataClient().portsCreate(port))

    materializePort(port, host, "tapDevice")

    val eventProbe = newProbe()
    actors().eventStream.subscribe(eventProbe.ref, classOf[DpPortCreate])
    requestOfType[DpPortCreate](eventProbe)
    portEventsProbe.expectMsgClass(classOf[LocalPortActive])

    // validate the final datapath state
    val datapaths: mutable.Set[Datapath] = dpConn().datapathsEnumerate().get()

    datapaths should have size 1
    datapaths.head should have('name("midonet"))

    val ports = datapathPorts(datapaths.head)
    /* int port "midonet" + tunnel port "tngre-mm" + netdev port "tapDevice" */
    ports should have size 3
    ports should contain key ("midonet")
    ports should contain key ("tapDevice")
    ports should contain key ("tngre-mm")
  }

  def testDatapathEmpty() {
    val host = new Host(hostId()).setName("myself")
    clusterDataClient().hostsCreate(hostId(), host)

    clusterDataClient().hostsAddDatapathMapping(hostId, "test")
    dpConn().datapathsEnumerate().get() should have size 0

    // send initialization message and wait
    initializeDatapath() should not be (null)

    // validate the final datapath state
    val datapaths: mutable.Set[Datapath] = dpConn().datapathsEnumerate().get()

    datapaths should have size 1
    datapaths.head should have('name("test"))

    val ports = datapathPorts(datapaths.head)
    ports should have size 2
    ports should contain key ("tngre-mm")
    ports should contain key ("test")
  }

  def testDatapathEmptyOnePort() {
    val host = new Host(hostId()).setName("myself")
    clusterDataClient().hostsCreate(hostId(), host)

    val bridge = new ClusterBridge().setName("test")
    bridge.setId(clusterDataClient().bridgesCreate(bridge))

    // make a port on the bridge
    val port = ClusterPorts.materializedBridgePort(bridge)
    port.setId(clusterDataClient().portsCreate(port))

    clusterDataClient().hostsAddDatapathMapping(hostId, "test")
    materializePort(port, host, "port1")

    dpConn().datapathsEnumerate().get() should have size 0

    // send initialization message and wait
    initializeDatapath() should not be (null)
    portEventsProbe.expectMsgClass(classOf[LocalPortActive])

    // validate the final datapath state
    val datapaths: mutable.Set[Datapath] = dpConn().datapathsEnumerate().get()

    datapaths should have size 1
    datapaths.head should have('name("test"))

    val ports = datapathPorts(datapaths.head)
    ports should have size 3
    ports should contain key ("tngre-mm")
    ports should contain key ("test")
    ports should contain key ("port1")
  }

  def testDatapathExistingMore() {
    val host = new Host(hostId()).setName("myself")
    clusterDataClient().hostsCreate(hostId(), host)

    val bridge = new ClusterBridge().setName("test")
    bridge.setId(clusterDataClient().bridgesCreate(bridge))

    // make a port on the bridge
    val port = ClusterPorts.materializedBridgePort(bridge)
    port.setId(clusterDataClient().portsCreate(port))

    clusterDataClient().hostsAddDatapathMapping(hostId, "test")
    materializePort(port, host, "port1")

    val dp = dpConn().datapathsCreate("test").get()
    dpConn().portsCreate(dp, Ports.newNetDevPort("port2")).get()
    dpConn().portsCreate(dp, Ports.newNetDevPort("port3")).get()

    dpConn().datapathsEnumerate().get() should have size 1
    dpConn().portsEnumerate(dp).get() should have size 3

    // send initialization message and wait
    initializeDatapath() should not be (null)

    // validate the final datapath state
    val datapaths: mutable.Set[Datapath] = dpConn().datapathsEnumerate().get()
      portEventsProbe.expectMsgClass(classOf[LocalPortActive])

    datapaths should have size 1
    datapaths.head should have('name("test"))

    val ports = datapathPorts(datapaths.head)
    ports should have size 3
    ports should contain key ("tngre-mm")
    ports should contain key ("test")
    ports should contain key ("port1")
  }

  def testDatapathBasicOperations() {

    val host = new Host(hostId()).setName("myself")
    clusterDataClient().hostsCreate(hostId(), host)

    clusterDataClient().hostsAddDatapathMapping(hostId, "test")

    initializeDatapath() should not be (null)

    var opReply = ask[DpPortReply](
        dpController(), CreatePortNetdev(Ports.newNetDevPort("netdev"), None))

    opReply should not be (null)
    val netdevPort: NetDevPort = opReply.request.port.asInstanceOf[NetDevPort]

    // validate the final datapath state
    val datapaths: mutable.Set[Datapath] = dpConn().datapathsEnumerate().get()

    datapaths should have size 1
    datapaths.head should have('name("test"))

    var ports = datapathPorts(datapaths.head)
    ports should have size 3
    ports should contain key ("tngre-mm")
    ports should contain key ("test")
    ports should contain key ("netdev")

    val nextRequest = DeletePortNetdev(netdevPort, None)
    opReply = ask[DpPortReply](dpController(), nextRequest)
    opReply should not be (null)

    ports = datapathPorts(datapaths.head)
    ports should have size 2
    ports should contain key ("tngre-mm")
    ports should contain key ("test")
  }

  def testInternalControllerState() {

    val host = new Host(hostId()).setName("myself")
    clusterDataClient().hostsCreate(hostId(), host)

    val bridge = new ClusterBridge().setName("test")
    bridge.setId(clusterDataClient().bridgesCreate(bridge))

    // make a port on the bridge
    val port1 = ClusterPorts.materializedBridgePort(bridge)
    port1.setId(clusterDataClient().portsCreate(port1))

    materializePort(port1, host, "port1")

    initializeDatapath() should not be (null)
    portEventsProbe.expectMsgClass(classOf[LocalPortActive])

    val ports = datapathPorts(dpConn().datapathsEnumerate().get().head)
    ports should contain key ("port1")
    val port1DpId = ports("port1").getPortNo

    dpController().underlyingActor.dpState
        .getDpPortNumberForVport(port1.getId) should be (Some(port1DpId))

    requestOfType[HostRequest](vtpProbe())
    val rcuHost = replyOfType[RCUHost](vtpProbe())

    rcuHost should not be null
    rcuHost.ports should contain key (port1.getId)
    rcuHost.ports should contain value ("port1")

    requestOfType[LocalPortActive](vtpProbe())


    // make a port on the bridge
    val port2 = ClusterPorts.materializedBridgePort(bridge)
    port2.setId(clusterDataClient().portsCreate(port2))

    materializePort(port2, host, "port2")
    replyOfType[RCUHost](vtpProbe())
    requestOfType[LocalPortActive](vtpProbe())

    val newPorts = datapathPorts(dpConn().datapathsEnumerate().get().head)
    newPorts should contain key ("port1")
    newPorts should contain key ("port2")
    val port2DpId = newPorts("port2").getPortNo

    dpController().underlyingActor.dpState
      .getDpPortNumberForVport(port1.getId) should equal(Some(port1DpId))
    dpController().underlyingActor.dpState
      .getDpPortNumberForVport(port2.getId) should equal(Some(port2DpId))
  }

}
