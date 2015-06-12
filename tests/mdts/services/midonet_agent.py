#
# Copyright 2015 Midokura SARL
# 
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
# 
#    http://www.apache.org/licenses/LICENSE-2.0
# 
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
import random

from mdts.services.service import Service
from mdts.services.vmguest import VMGuest
from midonetclient.api import MidonetApi
import uuid
import logging

LOG = logging.getLogger(__name__)


class MidonetAgentHost(Service):

    def __init__(self, container_id):
        super(MidonetAgentHost, self).__init__(container_id)
        self.api_container = self.get_linked_container('midonet-api', 'midonet-api')
        self.api = self.api_container.get_midonet_api()
        self.midonet_host_id = self.exec_command(
            'cat /etc/midonet_host_id.properties \
             | tail -n1 \
             | cut -d= -f2')
        self.num_interfaces = 0
        # FIXME: do not rely on the docker naming, discover it or do something
        self.compute_num = int(self.get_name().split('midonet-agent-')[1])
        # Disable ipv6 on the host
        self.exec_command(
            'sysctl -w net.ipv6.conf.default.disable_ipv6=1')
        self.exec_command(
            'sysctl -w net.ipv6.conf.all.disable_ipv6=1')

    def get_service_status(self):
        hosts = self.api.get_hosts()
        for h in hosts:
            if h.get_id() == self.midonet_host_id:
                LOG.debug('Host %s found! is alive? %s' % (
                    self.midonet_host_id,
                    h.is_alive()
                ))
                return 'up' if h.is_alive() else 'down'
        LOG.error('Host %s not found.' % self.midonet_host_id)
        raise RuntimeError('Host %s not found!')

    def get_service_name(self):
        """
        Return the name of the upstart service to be started/stopped
        :return: str Name of the service
        """
        return 'midolman'

    def get_midonet_host_id(self):
        return str(self.midonet_host_id)

    def create_vmguest(self, **iface_kwargs):
        """

        :param vm_id:
        :return:
        """
        self.num_interfaces += 1
        vm_id = str(uuid.uuid4())[:8]
        # TODO: Create namespaces and interfaces in the host. Create a VMGuest
        # object which handles the interface communication
        if 'ifname' not in iface_kwargs or iface_kwargs['ifname'] is None:
            vm_id = str(uuid.uuid4())[:8]
        else:
            vm_id = iface_kwargs['ifname']

        self.exec_command(
            'ip link add dev veth%s type veth peer name peth%s' % (
                vm_id, vm_id))

        # Disable ipv6 for the namespace

        self.exec_command('ip netns add vm%s' % vm_id)
        self.exec_command('ip netns exec vm%s sysctl -w net.ipv6.conf.default.disable_ipv6=1' % vm_id)
        self.exec_command('ip netns exec vm%s sysctl -w net.ipv6.conf.all.disable_ipv6=1' % vm_id)
        # MAC Address of hosts
        # aa:bb:cc:RR:HH:II where:
        # aa:bb:cc -> constant
        # RR -> random number to avoid collisions when reusing interface ids
        # HH -> id of the host
        # II -> id of the interface inside the host (increasing monotonic)
        if 'hw_addr' not in iface_kwargs:
            iface_kwargs['hw_addr'] = 'aa:bb:cc:%0.2X:%0.2X:%0.2X' % (
                random.randint(0, 255),
                self.compute_num % 255,
                self.num_interfaces % 255
            )

        # FIXME: define veth, peth and vm names consistently and in one place
        self.exec_command(
            'ip link set address %s dev peth%s' % (
                iface_kwargs['hw_addr'],
                vm_id))
        # set veth up
        self.exec_command('ip link set veth%s up' % vm_id)
        self.exec_command('ip link set dev peth%s up netns vm%s' %
                          (vm_id, vm_id))

        # FIXME: move it to guest?
        # FIXME: hack for the yaml physical topology definition, fix it
        if 'ipv4_addr' in iface_kwargs and len(iface_kwargs['ipv4_addr']) > 0:
            self.exec_command(
                'ip netns exec vm%s ip addr add %s dev peth%s' % (
                    vm_id,
                    iface_kwargs['ipv4_addr'][0],
                    vm_id
                )
            )

        if 'ipv4_gw' in iface_kwargs:
            self.exec_command(
                'ip netns exec vm%s ip route add default via %s' % (
                    vm_id,
                    iface_kwargs['ipv4_gw']
                )
            )

        if 'mtu' in iface_kwargs:
            self.exec_command(
                'ip netns exec vm%s ip link set mtu %s dev peth%s' % (
                    vm_id,
                    iface_kwargs['mtu'],
                    vm_id
                )
            )

        return VMGuest(self, vm_id, **iface_kwargs)

    def destroy_vm(self, vm_guest):
        self.exec_command('ip netns exec vm%s ip link set dev peth%s down' % (
            vm_guest.get_vm_id(),
            vm_guest.get_vm_id()
        ))
        self.exec_command('ip netns del vm%s' % vm_guest.get_vm_id())

    def bind_port(self, interface, mn_port_id):
        self.api = self.api_container.get_midonet_api()
        host_ifname = interface.get_host_ifname()
        host_id = self.get_midonet_host_id()
        self.api.get_host(host_id) \
            .add_host_interface_port() \
            .port_id(mn_port_id) \
            .interface_name(host_ifname).create()
