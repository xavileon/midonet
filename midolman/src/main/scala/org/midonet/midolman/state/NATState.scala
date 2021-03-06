/*
 * Copyright 2014 Midokura SARL
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

package org.midonet.midolman.state

import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

import com.google.common.hash.Hashing

import org.midonet.midolman.rules.NatTarget
import org.midonet.midolman.simulation.PacketContext
import org.midonet.midolman.state.FlowState.FlowStateKey
import org.midonet.midolman.state.NatState._
import org.midonet.odp.FlowMatch
import org.midonet.odp.FlowMatch.Field
import org.midonet.packets.NatState._
import org.midonet.packets._
import org.midonet.sdn.flows.FlowTagger.TagTypes
import org.midonet.sdn.state.FlowStateTransaction
import org.midonet.util.collection.{Reducer, ReusablePool}


object NatState {
    private val WILDCARD_PORT = 0


    object NatKey extends NatKeyAllocator[NatKey] {
        final val USHORT = 0xffff // Constant used to prevent sign extension

        def apply(wcMatch: FlowMatch, deviceId: UUID, keyType: KeyType): NatKey = {
            val key = NatKey(keyType,
                             wcMatch.getNetworkSrcIP.asInstanceOf[IPv4Addr],
                             if (keyType eq FWD_STICKY_DNAT) WILDCARD_PORT
                             else wcMatch.getSrcPort,
                             wcMatch.getNetworkDstIP.asInstanceOf[IPv4Addr],
                             if (keyType eq REV_STICKY_DNAT) WILDCARD_PORT
                             else wcMatch.getDstPort,
                             wcMatch.getNetworkProto.byteValue(),
                             deviceId)

            if (wcMatch.getNetworkProto == ICMP.PROTOCOL_NUMBER)
                processIcmp(key, wcMatch)

            key
        }

        private def natKeyLongHash(key: NatKey): Long = {
            Hashing.murmur3_128(TagTypes.NatFlowState).newHasher().
                putByte(keyTypeToByte(key.keyType)).
                putInt(key.networkSrc.hashCode).
                putInt(key.networkDst.hashCode).
                putInt(key.transportSrc).
                putInt(key.transportDst).
                putByte(key.networkProtocol).
                putLong(key.deviceId.getMostSignificantBits).
                putLong(key.deviceId.getLeastSignificantBits).hash().asLong
        }

        // Used when accepting new messages from other agents
        def apply(keyType: KeyType, networkSrc: IPv4Addr, transportSrc: Int,
                  networkDst: IPv4Addr, transportDst: Int, networkProtocol: Byte,
                  deviceId: UUID): NatKey =
            new NatKeyStore(keyType,
                            networkSrc, transportSrc,
                            networkDst, transportDst,
                            networkProtocol, deviceId) with FlowStateKey {
                override lazy val toLongHash = natKeyLongHash(this)
            }

        def apply(nks: NatKeyStore): NatKey = {
            new NatKeyStore(nks.keyType,
                            nks.networkSrc, nks.transportSrc,
                            nks.networkDst, nks.transportDst,
                            nks.networkProtocol, nks.deviceId) with FlowStateKey {
                override lazy val toLongHash = natKeyLongHash(this)
            }
        }

        private def processIcmp(natKey: NatKey, wcMatch: FlowMatch): Unit =
            wcMatch.getSrcPort.byteValue() match {
                case ICMP.TYPE_ECHO_REPLY | ICMP.TYPE_ECHO_REQUEST =>
                    val port = wcMatch.getIcmpIdentifier.intValue() & USHORT
                    natKey.transportSrc = port
                    natKey.transportDst = port
                case ICMP.TYPE_PARAMETER_PROBLEM | ICMP.TYPE_UNREACH |
                     ICMP.TYPE_TIME_EXCEEDED if wcMatch.getIcmpData ne null =>
                    // The nat mapping lookup should be done based on the
                    // contents of the ICMP data field.
                    val bb = ByteBuffer.wrap(wcMatch.getIcmpData)
                    val ipv4 = new IPv4
                    ipv4.deserializeHeader(bb)
                    // Invert src and dst because the icmpData contains the
                    // original msg that the ICMP ERROR replies to.
                    natKey.networkSrc = ipv4.getDestinationIPAddress
                    natKey.networkDst = ipv4.getSourceIPAddress
                    natKey.networkProtocol = ipv4.getProtocol
                    if (natKey.networkProtocol == ICMP.PROTOCOL_NUMBER) {
                        // If replying to a prev. ICMP, mapping was done
                        // against the icmp id.
                        val icmp = new ICMP
                        icmp.deserialize(bb)
                        val port = icmp.getIdentifier & USHORT
                        natKey.transportSrc = port
                        natKey.transportDst = port
                    } else {
                        val packet = bb.slice
                        natKey.transportDst = TCP.getSourcePort(packet)
                        natKey.transportSrc = TCP.getDestinationPort(packet)
                    }
                case _ =>
                    natKey.transportSrc = 0
                    natKey.transportDst = 0
            }
        }

    type NatKey = NatKeyStore with FlowStateKey

    class NatKeyOps(val natKey: NatKey) extends AnyVal {
        def returnKey(binding: NatBinding): NatKey = natKey.keyType match {
            case FWD_SNAT =>
                NatKey(natKey.keyType.inverse,
                       natKey.networkDst,
                       natKey.transportDst,
                       binding.networkAddress,
                       binding.transportPort,
                       natKey.networkProtocol,
                       natKey.deviceId)
            case FWD_DNAT | FWD_STICKY_DNAT =>
                NatKey(natKey.keyType.inverse,
                       binding.networkAddress,
                       binding.transportPort,
                       natKey.networkSrc,
                       natKey.transportSrc,
                       natKey.networkProtocol,
                       natKey.deviceId)
            case _ => throw new UnsupportedOperationException
        }

        def returnBinding: NatBinding = natKey.keyType match {
            case FWD_SNAT =>
                NatBinding(natKey.networkSrc, natKey.transportSrc)
            case FWD_DNAT | FWD_STICKY_DNAT =>
                NatBinding(natKey.networkDst, natKey.transportDst)
            case _ => throw new UnsupportedOperationException
        }
    }

    implicit def fromNatKeyOps(nkOps: NatKeyOps): NatKey = nkOps.natKey

    implicit def toNatKeyOps(nk: NatKey): NatKeyOps = new NatKeyOps(nk)

    def releaseBinding(key: NatKey, binding: NatBinding, natLeaser: NatLeaser): Unit =
        if ((key.keyType eq FWD_SNAT) &&
            key.networkProtocol != ICMP.PROTOCOL_NUMBER) {
                natLeaser.freeNatBinding(key.deviceId, key.networkDst,
                                         key.transportDst, binding)
        }

    private def keyTypeToByte(keyType: KeyType): Byte =
        keyType match {
            case FWD_SNAT => 1
            case FWD_DNAT => 2
            case FWD_STICKY_DNAT => 3
            case REV_SNAT => 4
            case REV_DNAT => 5
            case REV_STICKY_DNAT => 6
        }

    private def byteToKeyType(byte: Byte): KeyType =
        byte match {
            case 1 => FWD_SNAT
            case 2 => FWD_DNAT
            case 3 => FWD_STICKY_DNAT
            case 4 => REV_SNAT
            case 5 => REV_DNAT
            case 6 => REV_STICKY_DNAT
        }

    val NO_BYTES = new Array[Byte](0)
    class NatKeySerializer
            extends FlowStateStore.StateSerializer[NatKey] {
        val Size = 34

        // NOTE: here we use a ReusablePool of buffers that doesn't need to
        // be explicitly pushed back to the pool when done with it, it will get
        // reused as needed. This makes sense here because these buffers
        // shouldn't live past a simulation lifetime.
        // Using 32 buffers means we can only handle 16 NAT rules, as each
        // NAT hop consumes two buffers (one for the forward nat rule, and one
        // for the reverse nat rule). This is a defensive measure, as we
        // shouldn't have more than 1 NAT on a normal topology.
        private val NumBuffers = 32
        private val bufferProvider: ThreadLocal[ReusablePool[ByteBuffer]] =
            new ThreadLocal[ReusablePool[ByteBuffer]] {
                override def initialValue(): ReusablePool[ByteBuffer] = {
                    new ReusablePool[ByteBuffer](
                        NumBuffers, () => ByteBuffer.allocate(Size))
                }
            }

        override def toBytes(value: NatKey): Array[Byte] =
            if (value == null) {
                NO_BYTES
            } else {
                val bb = bufferProvider.get.next
                bb.clear()
                bb.put(keyTypeToByte(value.keyType))
                bb.putInt(value.networkSrc.toInt)
                bb.putInt(value.transportSrc)
                bb.putInt(value.networkDst.toInt)
                bb.putInt(value.transportDst)
                bb.put(value.networkProtocol)
                bb.putLong(value.deviceId.getMostSignificantBits)
                bb.putLong(value.deviceId.getLeastSignificantBits)
                bb.array
            }

        override def fromBytes(bytes: Array[Byte]): NatKey =
            if (bytes == null || bytes.length != Size) {
                null
            } else {
                val bb = ByteBuffer.wrap(bytes)
                NatKey(byteToKeyType(bb.get),
                       IPv4Addr(bb.getInt),
                       bb.getInt,
                       IPv4Addr(bb.getInt),
                       bb.getInt,
                       bb.get(),
                       new UUID(bb.getLong(), bb.getLong()))
            }
    }

    class NatBindingSerializer
            extends FlowStateStore.StateSerializer[NatBinding] {
        val Size = 8

        // NOTE: here we use a ReusablePool of buffers that doesn't need to
        // be explicitly pushed back to the pool when done with it, it will get
        // reused as needed. This makes sense here because these buffers
        // shouldn't live past a simulation lifetime.
        // Using 32 buffers means we can only handle 16 NAT rules, as each
        // NAT hop consumes two buffers (one for the forward nat rule, and one
        // for the reverse nat rule). This is a defensive measure, as we
        // shouldn't have more than 1 NAT on a normal topology.
        private val NumBuffers = 32
        private val bufferProvider: ThreadLocal[ReusablePool[ByteBuffer]] =
            new ThreadLocal[ReusablePool[ByteBuffer]] {
                override def initialValue(): ReusablePool[ByteBuffer] = {
                    new ReusablePool[ByteBuffer](
                        NumBuffers, () => ByteBuffer.allocate(Size))
                }
            }

        override def toBytes(value: NatBinding): Array[Byte] =
            if (value == null) {
                NO_BYTES
            } else {
                val bb = bufferProvider.get.next
                bb.clear()
                bb.putInt(value.networkAddress.toInt)
                bb.putInt(value.transportPort)
                bb.array
            }

        override def fromBytes(bytes: Array[Byte]): NatBinding =
            if (bytes == null || bytes.length != Size) {
                null
            } else {
                val bb = ByteBuffer.wrap(bytes)
                NatBinding(IPv4Addr(bb.getInt), bb.getInt)
            }
    }
}

trait NatState extends FlowState { this: PacketContext =>

    var natTx: FlowStateTransaction[NatKey, NatBinding] = _
    var natLeaser: NatLeaser = _

    private val releaseUncommittedLeases =
        new Reducer[NatKey, NatBinding, Unit]() {
            override def apply(u: Unit, k: NatKey, v: NatBinding): Unit = {
                releaseBinding(k, v, natLeaser)
            }
        }

    abstract override def clear(): Unit = {
        super.clear()
        if (natTx ne null) {
            if (!natTx.isCommitted) {
                natTx.fold((), releaseUncommittedLeases)
            }
            natTx.flush()
        }

    }

    def resetNatState(): Unit = {
        natTx = null
        natLeaser = null
    }

    def applyDnat(natTargets: Array[NatTarget]): Boolean =
        applyDnat(FWD_DNAT, natTargets)

    def applyStickyDnat(natTargets: Array[NatTarget]): Boolean =
        applyDnat(FWD_STICKY_DNAT, natTargets)

    private def applyDnat(natType: KeyType,
                          natTargets: Array[NatTarget]): Boolean =
        if (isNatSupported) {
            val natKey = NatKey(wcmatch, currentDevice, natType)
            val binding = getOrAllocateNatBinding(natKey, natTargets)
            dnatTransformation(natKey, binding)
            true
        } else false

    private def dnatTransformation(natKey: NatKey, binding: NatBinding): Unit = {
        wcmatch.setNetworkDst(binding.networkAddress)
        markNwDstRewritten()
        if (!isIcmp)
            wcmatch.setDstPort(binding.transportPort)
    }

    def applySnat(natTargets: Array[NatTarget]): Boolean =
        if (isNatSupported) {
            val natKey = NatKey(wcmatch, currentDevice, FWD_SNAT)
            val binding = getOrAllocateNatBinding(natKey, natTargets)
            snatTransformation(natKey, binding)
            true
        } else false

    private def snatTransformation(natKey: NatKey, binding: NatBinding): Unit = {
        wcmatch.setNetworkSrc(binding.networkAddress)
        if (!isIcmp)
            wcmatch.setSrcPort(binding.transportPort)
    }

    def reverseDnat(): Boolean =
        reverseDnat(REV_DNAT)

    def reverseStickyDnat(): Boolean =
        reverseDnat(REV_STICKY_DNAT)

    private def reverseDnat(natType: KeyType): Boolean = {
        if (isNatSupported) {
            val natKey = NatKey(wcmatch, currentDevice, natType)
            addFlowTag(natKey)
            val binding = natTx.get(natKey)
            if (binding ne null)
                return reverseDnatTransformation(natKey, binding)
        }
        false
    }

    private def reverseDnatTransformation(natKey: NatKey, binding: NatBinding): Boolean = {
        log.debug("Found reverse DNAT. Use {} for {}", binding, natKey)
        if (isIcmp) {

            if (natKey.keyType ne REV_STICKY_DNAT) {
                val changedIp = if (wcmatch.getNetworkSrcIP == natKey.networkSrc) {
                    wcmatch.setNetworkSrc(binding.networkAddress)
                    true
                } else {
                    false
                }
                val transformedICMP = transformICMPData(dnatTransformer,
                                                        natKey.networkSrc,
                                                        binding.networkAddress,
                                                        binding.transportPort)
                changedIp || transformedICMP
            } else {
                false
            }
        } else {
            wcmatch.setNetworkSrc(binding.networkAddress)
            wcmatch.setSrcPort(binding.transportPort)
            true
        }
    }

    def reverseSnat(): Boolean = {
        if (isNatSupported) {
            val natKey = NatKey(wcmatch, currentDevice: UUID, REV_SNAT)
            addFlowTag(natKey)
            val binding = natTx.get(natKey)
            if (binding ne null)
                return reverseSnatTransformation(natKey, binding)
        }
        false
    }

    private def reverseSnatTransformation(natKey: NatKey, binding: NatBinding): Boolean = {
        log.debug("Found reverse SNAT. Use {} for {}", binding, natKey)
        if (isIcmp) {
            val changedIp = if (wcmatch.getNetworkDstIP == natKey.networkDst) {
                wcmatch.setNetworkDst(binding.networkAddress)
                markNwDstRewritten()
                true
            } else {
                false
            }
            val transformedICMP = transformICMPData(snatTransformer,
                                                    natKey.networkDst,
                                                    binding.networkAddress,
                                                    binding.transportPort)
            changedIp || transformedICMP
        } else {
            wcmatch.setNetworkDst(binding.networkAddress)
            markNwDstRewritten()
            wcmatch.setDstPort(binding.transportPort)
            true
        }
    }

    def applyIfExists(natKey: NatKey): Boolean = {
        val binding = natTx.get(natKey)
        if (binding eq null) {
            addFlowTag(natKey)
            false
        } else natKey.keyType match {
            case FWD_DNAT | FWD_STICKY_DNAT =>
                dnatTransformation(natKey, binding)
                refKey(natKey, binding)
                true
            case REV_DNAT | REV_STICKY_DNAT =>
                reverseDnatTransformation(natKey, binding)
            case FWD_SNAT =>
                snatTransformation(natKey, binding)
                refKey(natKey, binding)
                true
            case REV_SNAT =>
                reverseSnatTransformation(natKey, binding)
        }
    }

    def deleteNatBinding(natKey: NatKeyOps): Unit = {
        val binding = natTx.get(natKey)
        if (binding ne null) {
            addFlowTag(natKey)
            natTx.remove(natKey)
            val returnKey = natKey.returnKey(binding)
            natTx.remove(returnKey)
            addFlowTag(returnKey)
        }
    }

    def isNatSupported: Boolean =
        if (IPv4.ETHERTYPE == wcmatch.getEtherType) {
            val nwProto = wcmatch.getNetworkProto
            if (nwProto == ICMP.PROTOCOL_NUMBER) {
                val supported = wcmatch.getSrcPort.byteValue() match {
                    case ICMP.TYPE_ECHO_REPLY | ICMP.TYPE_ECHO_REQUEST =>
                        wcmatch.isUsed(Field.IcmpId)
                    case ICMP.TYPE_PARAMETER_PROBLEM |
                         ICMP.TYPE_TIME_EXCEEDED |
                         ICMP.TYPE_UNREACH =>
                        wcmatch.isUsed(Field.IcmpData)
                    case _ =>
                        false
                }

                if (!supported) {
                    log.debug("ICMP message not supported in NAT rules {}",
                              wcmatch)
                }
                supported
            } else {
                nwProto == UDP.PROTOCOL_NUMBER || nwProto == TCP.PROTOCOL_NUMBER
            }
        } else false

    // Default port value, signifying no port set.
    // In an ideal world, I'd use a Option, but that would allocate
    val NO_NAT_PORT = Int.MaxValue

    val snatTransformer = (ipv4: IPv4, from: IPv4Addr, to: IPv4Addr,
                           port: Int) => {
        if (ipv4.getSourceIPAddress() == from) {
            ipv4.setSourceAddress(to)

            ipv4.getPayload match {
                case transport: Transport if port != NO_NAT_PORT =>
                    transport.setSourcePort(port)
                    transport.clearChecksum()
                case _ =>
            }
            true
        } else {
            log.debug("Source address {} doesn't match {}",
                      ipv4.getSourceIPAddress(), from)
            false
        }
    }

    val dnatTransformer = (ipv4: IPv4, from: IPv4Addr, to: IPv4Addr,
                           port: Int) =>
        if (ipv4.getDestinationIPAddress() == from) {
            ipv4.setDestinationAddress(to)

            ipv4.getPayload match {
                case transport: Transport if port != NO_NAT_PORT =>
                    transport.setDestinationPort(port)
                    transport.clearChecksum()
                case _ =>
            }
            true
        } else {
            log.debug("Destination address {} doesn't match {}",
                      ipv4.getDestinationIPAddress(), from)
            false
        }

    def snatOnICMPData(from: IPv4Addr, to: IPv4Addr): Boolean =
        transformICMPData(snatTransformer, from, to)

    def dnatOnICMPData(from: IPv4Addr, to: IPv4Addr): Boolean =
        transformICMPData(dnatTransformer, from, to)

    private def transformICMPData(transformer: (IPv4, IPv4Addr, IPv4Addr, Int) => Boolean,
                                  from: IPv4Addr,
                                  to: IPv4Addr,
                                  port: Int = NO_NAT_PORT): Boolean =
        wcmatch.getSrcPort.byteValue() match {
            case ICMP.TYPE_PARAMETER_PROBLEM | ICMP.TYPE_TIME_EXCEEDED |
                 ICMP.TYPE_UNREACH if wcmatch.getIcmpData ne null =>
                val data = wcmatch.getIcmpData
                val bb = ByteBuffer.wrap(data)
                val icmpPayload = new IPv4
                icmpPayload.deserialize(bb)

                if (transformer(icmpPayload, from, to, port)) {
                    icmpPayload.clearChecksum()
                    wcmatch.setIcmpData(icmpPayload.serialize())
                    true
                } else {
                    false
                }
            case _ => false
        }

    private def getOrAllocateNatBinding(natKey: NatKey,
                                        natTargets: Array[NatTarget]): NatBinding = {
        var binding = natTx.get(natKey)
        if (binding eq null) {
            binding = tryAllocateNatBinding(natKey, natTargets)
            natTx.putAndRef(natKey, binding)
            log.debug("Obtained NAT key {}->{}", natKey, binding)

            val returnKey = natKey.returnKey(binding)
            val inverseBinding = natKey.returnBinding
            natTx.putAndRef(returnKey, inverseBinding)
            log.debug("With reverse NAT key {}->{}", returnKey, inverseBinding)

            addFlowTag(natKey)
            addFlowTag(returnKey)
        } else {
            log.debug("Found existing mapping for NAT {}->{}", natKey, binding)
            refKey(natKey, binding)
        }
        binding
    }

    private def refKey(natKey: NatKey, binding: NatBinding): Unit = {
        natTx.ref(natKey)
        addFlowTag(natKey)
        val returnKey = natKey.returnKey(binding)
        natTx.ref(returnKey)
        addFlowTag(returnKey)
    }

    private def tryAllocateNatBinding(key: NatKey,
                                      nats: Array[NatTarget]): NatBinding =
        if (isIcmp) {
            val nat = chooseRandomNatTarget(nats)
            NatBinding(chooseRandomIp(nat), key.transportDst)
        } else if (key.keyType eq FWD_SNAT) {
            natLeaser.allocateNatBinding(key.deviceId, key.networkDst,
                                         key.transportDst, nats)
        } else {
            val nat = chooseRandomNatTarget(nats)
            NatBinding(chooseRandomIp(nat), chooseRandomPort(nat))
        }

    def isIcmp = wcmatch.getNetworkProto == ICMP.PROTOCOL_NUMBER

    private def chooseRandomNatTarget(nats: Array[NatTarget]): NatTarget =
        nats(ThreadLocalRandom.current().nextInt(nats.size))

    private def chooseRandomIp(nat: NatTarget): IPv4Addr =
        nat.nwStart.randomTo(nat.nwEnd, ThreadLocalRandom.current())

    private def chooseRandomPort(nat: NatTarget): Int = {
        val tpStart = nat.tpStart
        val tpEnd = nat.tpEnd
        ThreadLocalRandom.current().nextInt(tpEnd - tpStart + 1) + tpStart
    }
}
