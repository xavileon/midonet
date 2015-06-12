# Copyright 2014 Midokura SARL
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
from abc import ABCMeta
from abc import abstractmethod

import mdts.ipc.interface
from mdts.lib.util import ping4_cmd

from threading import Semaphore
import time
import logging
import re
import os

LOG = logging.getLogger(__name__)


class Interface:

    __metaclass__ = ABCMeta

    def __init__(self, ifname=None, hw_addr=None, ipv4_addr=None,
                 ipv4_gw=None, mtu=1500):
        self.hw_addr = hw_addr
        self.ipv4_addr = ipv4_addr
        self.ipv4_gw = ipv4_gw
        self.mtu = mtu
        self.vport_id = None
        # FIXME: hack to not modify tests in the meanwhile. Clean it up ASAP.
        self.interface = {
            'hw_addr': self.hw_addr,
            'ipv4_addr': self.ipv4_addr,
            'ipv4_gw': self.ipv4_gw,
            'mtu': self.mtu
        }
        self._tcpdump_sem = Semaphore(value=0)

    @abstractmethod
    def execute(self, cmdline, timeout=None, sync=False):
        raise NotImplementedError()

    @abstractmethod
    def expect(self, pcap_filter_string, timeout=None, sync=False):
        raise NotImplementedError()

    @abstractmethod
    def clear_arp(self, sync=False):
        raise NotImplementedError()

    @abstractmethod
    def set_up(self):
        raise NotImplementedError()

    @abstractmethod
    def set_down(self):
        raise NotImplementedError()

    @abstractmethod
    def get_vm_ifname(self):
        raise NotImplementedError()

    @abstractmethod
    def get_host_ifname(self):
        raise NotImplementedError()

    def send_arp_request(self, target_ipv4):
        arp_msg = "request, targetip=%s" % target_ipv4
        cmdline = "mz %s -t arp '%s'" % \
                  (self.get_vm_ifname(), arp_msg)
        LOG.debug("cmdline: %s" % cmdline)
        return self.execute(cmdline, sync=True)

    def send_arp_reply(self, src_mac, target_mac, src_ipv4, target_ipv4):
        arp_msg = "reply, smac=%s, tmac=%s, sip=%s, tip=%s" % (
            src_mac, target_mac, src_ipv4, target_ipv4)
        cmdline = "mz %s -t arp '%s'" % (self.get_vm_ifname(), arp_msg)
        return self.execute(cmdline, sync=True)

    def send_ether(self, ether_frame_string, count=1, sync=False):

        """
        Sends ethernet frame by using mz command.

        Args:
            ether_frame_string: hex_string for ethernet frame without
                                 white spaces. See man mz.

            count: Send the packet count times (default: 1, infinite: 0).


        Returns:
            Exit code of mz command

        """
        cmdline = 'mz %s -c %s %s' % (self.get_vm_ifname(),
                                                   count,
                                                   ether_frame_string)
        LOG.debug("sending ethernet frame(s) with mz:  %s" % cmdline)

        LOG.debug("cmdline: %s" % cmdline)
        return self.execute(cmdline, sync=sync)

    def send_packet(self, target_hw, target_ipv4, pkt_type, pkt_parms,
                    payload_size, delay, count, sync=False,
                    src_hw=None, src_ipv4=None):
        src_addrs = ''
        if src_hw:
            src_addrs += '-a %s ' % src_hw
        if src_ipv4:
            src_addrs += '-A %s ' % src_ipv4

        payload = '0'
        if payload_size:
            payload = ''
            for len in xrange(payload_size):
                payload += '0'

        # FIXME: change documentation and comment regargin hex_payload_file
        # Remove from headers hex_payload_file
        cmdline = 'mz %s %s -b %s -B %s -t %s "%s" -P "%s" -d %ss -c %s' % (
            self.get_vm_ifname(),
            src_addrs,
            target_hw,
            target_ipv4,
            pkt_type,
            pkt_parms,
            payload_size,
            delay,
            count
        )
        LOG.debug("cmdline: %s" % cmdline)
        return self.execute(cmdline, sync=sync)

    def send_udp(self, target_hw, target_ipv4, iplen=None,
                 payload_size=1,
                 src_port=9, dst_port=9, extra_params=None, delay=1, count=1,
                 sync=False, src_hw=None, src_ipv4=None):
        """ Sends UDP packets to target mac addr / ip address.

        Sends UDP packets from this interface to the target HW mac and ip
        address. Beware that the target hardware mac address may be a mac
        address of the target interface if it is connected to the same bridge
        (belongs to the same segment), or the router's incoming port mac if
        the receiver is in a different segment.

        NOTE: Currently the underlying layer uses mz for sending udp packets. mz
        requires that at least ip packet length to be specified. Ip packet length
        is computed as  28 + pay load file size where
            - 20 bytes for UDP packet frame
            - 8 bytes for addresses

        Args:
            target_hw: The target HW for this UDP message. Either the receiving
                interface's mac address if it is in the same network segment, or
                the router's incoming port's mac address
            target_ipv4: An IP address of the receiver.
            hex_payload_file: A name of the file containing hexadecimal pay load.
            iplen: The UDP packet length (see NOTE above for how to compute the
                length). Passing None will omit the parameter.
            src_port: A UDP source port. Passing None will omit the parameter.
            dst_port: A UDP destination port. Passing None will omit the
                parameter.
            extra_params: Comma-separated extra UDP packet parameters.
            delay: A message-sending delay.
            count: A message count.
            sync: Whether this call blocks (synchronous call) or not.
        """
        return self.send_protocol('udp', target_hw, target_ipv4, iplen,
                 payload_size, src_port, dst_port, extra_params,
                 delay, count, sync, src_hw, src_ipv4)

    def send_tcp(self, target_hw, target_ipv4, iplen,
                 payload_size=1,
                 src_port=9, dst_port=9, extra_params=None, delay=1, count=1,
                 sync=False):

        return self.send_protocol('tcp', target_hw, target_ipv4, iplen,
                 payload_size, src_port, dst_port, extra_params,
                 delay, count, sync)

    def send_protocol(self, protocol_name, target_hw, target_ipv4, iplen,
                 payload_size=1,
                 src_port=9, dst_port=9, extra_params=None, delay=1, count=1,
                 sync=False, src_hw=None, src_ipv4=None):
        params = []
        if src_port: params.append('sp=%d' % src_port)
        if dst_port: params.append('dp=%d' % dst_port)
        if iplen: params.append('iplen=%d' % iplen)
        if extra_params: params.append(extra_params)
        protocol_params = ','.join(params)
        return self.send_packet(target_hw=target_hw,
                                target_ipv4=target_ipv4,
                                pkt_type=protocol_name,
                                pkt_parms=protocol_params,
                                payload_size=payload_size,
                                delay=delay, count=count, sync=sync,
                                src_hw = src_hw, src_ipv4 = src_ipv4)

    # def make_web_request_get_backend(self, dst_ip_port, src_port):
    #     """
    #     @type dst_ip_port (str, int)
    #     @type src_port int
    #
    #     Make a HTTP GET (TCP connection) to dst_ip on port dst_port from src_port.
    #
    #     Returns: (IP address of backend we hit <string>, port of backend we hit <int>)
    #     """
    #     timeout_secs = 5
    #     res = self.make_web_request_to(dst_ip_port, src_port, timeout_secs).result().split(":")
    #     ip_addr = res[0]
    #     port = int(res[1])
    #     return ip_addr, port
    #
    # def make_web_request_to(self, dst_ip_port, src_port, timeout_secs = 5):
    #     """
    #     @type dst_ip_port (str, int)
    #     @type src_port int
    #
    #     Make a HTTP GET (TCP connection) to dst_ip on port dst_port.
    #
    #     Returns: A future
    #     """
    #     dst_ip, dst_port = dst_ip_port
    #     return self.execute("curl -s -m %s --local-port %r http://%s:%s" %
    #                         (timeout_secs, src_port, dst_ip, dst_port))

    def start_server(self, port):
        future = self.execute(
            'sh -c \'while true; do echo %s | nc -l %s %d; done\'' % (
                self.vm_id,
                self.get_ip(),
                port
            ), sync=False)
        result, exec_id = future.result()
        return exec_id

    # FIXME: compute_host reference should be here to avoid too much calls
    # or methods in vmguest or others
    def stop_server(self):
        # Get the pid of the netcat process listening
        pid = self.execute(
            'netstat -ntlp | grep LISTEN | awk \'{print $7}\'|cut -d/ -f1')
        # Kill the parent process (the sh process that started the server)
        ppid = self.execute(
            'ps -x -o \'%p %r %c\' | grep %s | awk \'{print $2}\'' % pid,
            sync=True)
        self.execute("kill -9 -- -%s" % ppid, sync=True)

    def make_request_to(self, dst_ip, dst_port, timeout=5):
        return self.execute('nc %s %d' % (dst_ip, dst_port), timeout, sync=True)

    # def start_web_server(self, port):
    #     """
    #     @type port int
    #
    #     Listens for a TCP connection on the specified port. Returns a
    #     simple 200 OK with the listening namespace's ip address / port if it receives a GET.
    #     """
    #     self
    #     this_file_dir = os.path.dirname(os.path.realpath(__file__))
    #     web_server_location = os.path.join(this_file_dir, "../tests/utils/nsinfo_web_server.py")
    #     web_start_command = "python %s %s" % (web_server_location, port)
    #     return self.execute_interactive(web_start_command)

    def ping4(self, target_iface, interval=0.5, count=1, sync=False,
              size=56, should_succeed=True, do_arp=False):
        return self.ping_ipv4_addr(target_iface.get_ip(),
                                   interval,
                                   count,
                                   sync,
                                   size,
                                   should_succeed,
                                   do_arp)

    def ping_ipv4_addr(self, ipv4_addr, interval=0.5, count=1, sync=False,
                       size=56, should_succeed=True, do_arp=False):
        """Ping an IPv4 address."""

        if do_arp:
            # MidoNet requires some time to learn a new MAC address
            # since it has to write to Zookeeper and get an answer
            # We are advancing here MAC learning by sending an ARP
            # request one second before sending the ping. MN-662.
            self.send_arp_request(ipv4_addr)
            time.sleep(1)

        ping_cmd = ping4_cmd(ipv4_addr, interval, count, size)
        return self.execute(ping_cmd, should_succeed=should_succeed, sync=sync)

    def get_mtu(self):
        mtu = self.execute(
            "ip link ls dev %s | head -n1 | cut -d' ' -f5" %
            self.get_vm_ifname(),
            sync=True)
        LOG.debug("Infered mtu = %s" % mtu)
        return int(mtu)

    # TODO this function may not exactly belong here, but to host
    def get_num_routes(self):
        num_routes = self.execute('ip route | wc -l', sync=True)
        LOG.debug("Infered num_routes = %s" % num_routes)
        return int(num_routes)

    def get_cidr(self):
        cidr = self.execute(
            "ip addr ls dev %s | grep inet | awk '{print $2}'" %
            self.get_vm_ifname(),
            sync=True)
        LOG.debug("Infered cidr = %s" % cidr)
        return cidr

    def get_ip(self):
        cidr = self.get_cidr()
        ip = cidr.split('/')[0]
        LOG.debug("Infered ip = %s" % ip)
        return ip

    def get_mac_addr(self):
        mac_addr = self.execute(
            "ip addr ls dev %s | grep link | awk '{print $2}'" %
            self.get_vm_ifname(),
            sync=True)
        LOG.debug("Infered mac_addr = %s" % mac_addr)
        return mac_addr

    def __repr__(self):
        return "[iface=%r, vport_id=%r, mac_addr=%r]" % (
            self.get_vm_ifname(),
            self.vport_id,
            self.get_mac_addr())
