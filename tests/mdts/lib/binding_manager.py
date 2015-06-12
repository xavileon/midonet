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

from mdts.lib.topology_manager import TopologyManager
from mdts.tests.utils.utils import await_port_active
from mdts.tests.utils.utils import get_midonet_api
from mdts.services import service

import logging
import sys

from time import sleep

LOG = logging.getLogger(__name__)

class BindingManager(TopologyManager):

    def __init__(self, ptm, vtm):

        # Note that this ctor doesn't conform to the super's signature
        # calling super just to get a ref to self._api. perhaps
        # needs to be cleaned up.

        data = {'bogus_data': 'dummy'}
        super(BindingManager, self).__init__(
            None, data)

        self._ptm = ptm
        self._vtm = vtm
        self._port_if_map = {}
        self._vms = []

    def bind(self, filename=None, data=None):
        # Build a new virtual topology at every binding, destroy at the end
        self._ptm.build()
        self._vtm.build()

        self._data = self._get_data(filename, data)
        # Get a new api ref to workaround previous zk failures
        self._api = get_midonet_api()

        bindings = self._data['bindings']
        for b in bindings:
            binding = b['binding']

            host_id = binding['host_id']
            iface_id = binding['interface_id']
            device_name = binding['device_name']
            port_id = binding['port_id']

            self._port_if_map[(device_name, port_id)] = \
                (host_id, iface_id)

            device_port = self._vtm.get_device_port(device_name, port_id)
            mn_vport = device_port._mn_resource
            if mn_vport.get_type() == 'InteriorRouter' or \
               mn_vport.get_type() == 'InteriorBridge':
                LOG.error("Cannot bind interior port")
                sys.exit(-1) # TODO: make this fancier

            mn_vport_id = mn_vport.get_id()
            # FIXME: do not depend on the naming, discover it and do it more generic
            host = service.load_from_name('midonet-agent-'+str(host_id))

            # Clean up yamls or remove them completely, this is so ugly
            _host = filter(
                lambda x: x['host']['id'] == host_id,
                self._ptm._hosts)[0]['host']
            _interface = filter(
                lambda x: x['interface']['id'] == iface_id,
                _host['interfaces']
            )[0]['interface']

            # Remove kwargs we are not interested in
            _interface_vm = dict(_interface)
            del _interface_vm['ipv6_addr']
            del _interface_vm['type']
            del _interface_vm['id']

            iface = host.create_vmguest(**_interface_vm)
            self._port_if_map[(device_name, port_id)] = iface
            iface.vport_id = mn_vport_id
            self._vms.append(iface)
            iface.clear_arp(sync=True)
            iface_name = iface.get_host_ifname()
            #iface.interface['ifname']
            mn_host_id = host.get_midonet_host_id()
            #iface.host['mn_host_id']
            iface.vport_id = mn_vport_id
            host.bind_port(iface, mn_vport_id)
            await_port_active(mn_vport_id)

    def unbind(self):

        bindings = self._data['bindings']

        for vm in self._vms:
            # Remove binding
            compute_host_id = vm.compute_host.get_midonet_host_id()
            for port in self._api.get_host(compute_host_id).get_ports():
                if port.get_interface_name() == vm.get_host_ifname():
                    port.delete()
                    # FIXME: possibly replace vm.vport_id by corresponding
                    # port object so we don't need to store it
                    await_port_active(vm.vport_id, active=False)

            # Remove vm
            vm.destroy()

        # Destroy the virtual topology
        self._vtm.destroy()
        self._ptm.destroy()

#        for b in bindings:
#            binding = b['binding']
#
#            host_id = binding['host_id']
#            host = service.load_from_name('midonet-agent.'+host_id)
#
#            iface_id = binding['interface_id']
#             iface = self._ptm.get_interface(host_id, iface_id)
#             iface_name = iface.interface['ifname']
#             mn_host_id = iface.host['mn_host_id']
#             mn_vport_id = iface.vport_id
#
#             for hip in self._api.get_host(mn_host_id).get_ports():
#                 if hip.get_interface_name() == iface_name:
#                     hip.delete()
#                     iface.vport_id = None
#                     await_port_active(mn_vport_id, active=False)
#
#         self._port_if_map = {}

    def get_iface_for_port(self, device_name, port_id):
        return self._port_if_map[(device_name, port_id)]
        #(host_id, iface_id) = self._port_if_map[(device_name, port_id)]
        #return self._ptm.get_interface(host_id, iface_id)

    def get_iface(self, host_id, iface_id):
        return self._ptm.get_interface(host_id, iface_id)
