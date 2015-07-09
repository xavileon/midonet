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

"""
Resource manager for physical topology data.
"""

import logging

from mdts.lib.interface import Interface
from mdts.lib.topology_manager import TopologyManager
from mdts.tests.utils import wait_on_futures
from mdts.tests.utils.conf import is_vxlan_enabled
from mdts.services import service


LOG = logging.getLogger(__name__)


class PhysicalTopologyManager(TopologyManager):

    def __init__(self, filename=None, data=None):

        super(PhysicalTopologyManager, self).__init__(filename, data)

        self._hosts = self._data['physical_topology'].get('hosts')
        self._compute_hosts = service.load_all('midonet-agent')
        self._bridges = self._data['physical_topology'].get('bridges') or []
        self._interfaces = {}  # (host_id, interface_id) to interface map

    def get_compute_hosts(self):
        return self._compute_hosts

    def build(self):
        """
        Build physical topology from the data.

        Args:
            filename: filename that defines physical topology
            data: python dictionary object to represent the physical topology

        """

        LOG.debug('-' * 80)
        LOG.debug("build")
        LOG.debug('-' * 80)
        #for b in self._bridges:
        #    bridge = b['bridge']
        #    # TODO(tomohiko) Need to something when not bridge['provided']?
        #    if bridge['provided']:
        #        LOG.info('Skipped building bridge=%r', bridge)

        hosts = service.load_all(container_type='midonet-agent')
        midonet_api_host = service.load_all(container_type='midonet-api')[0]
        midonet_api = midonet_api_host.get_midonet_api()

        if is_vxlan_enabled():
            tz = midonet_api.add_vxlan_tunnel_zone()
        else:
            tz = midonet_api.add_gre_tunnel_zone()
            tz.name('mdts-test')
            tz.create()

        for host in hosts:
            tz_host = tz.add_tunnel_zone_host()
            tz_host.ip_address(host.get_ip_address())
            tz_host.host_id(host.get_midonet_host_id())
            tz_host.create()

        # for h in self._hosts:
        #     host = h['host']
        #     host_container = service.load_from_name('midonet-agent.'+host['id'])
        #     if host.get('tunnel_zone'):
        #         tz_data = host.get('tunnel_zone')
        #         tzs = self._api.get_tunnel_zones()
        #
        #         # Ensure that TZ exists
        #         tz = [t for t in tzs if t.get_name() == tz_data['name']]
        #         if tz == []:
        #             if is_vxlan_enabled():
        #                 tz = self._api.add_vxlan_tunnel_zone()
        #             else:
        #                 tz = self._api.add_gre_tunnel_zone()
        #             tz.name(tz_data['name'])
        #             tz.create()
        #         else:
        #             tz = tz[0]
        #
        #         # Ensure that the host is in the TZ
        #         tz_hosts = tz.get_hosts()
        #         tz_host = filter(
        #             lambda x: x.get_host_id() == host['mn_host_id'],
        #             tz_hosts)
        #         if tz_host == []:
        #             tz_host = tz.add_tunnel_zone_host()
        #             tz_host.ip_address(tz_data['ip_addr'])
        #             tz_host.host_id(host['mn_host_id'])
        #             tz_host.create()

            #if host['provided'] == True:
            #    LOG.info('Skipped building host=%r', host)
            #else:
            #    #TODO(tomoe): when we support provisioning Midolman host with
            #    # this tool.
            #    pass
            #interfaces = host['interfaces']

            #futures = []
            #for i in interfaces:
            #    iface = Interface(i['interface'], host)
            #    self._interfaces[(host['id'], i['interface']['id'])] = iface
            #    f = iface.create()
            #    futures.append(f)

            #wait_on_futures(futures)

        LOG.debug('-' * 80)
        LOG.debug("end build")
        LOG.debug('-' * 80)

    def destroy(self):

        LOG.debug('-' * 80)
        LOG.debug("destroy")
        LOG.debug('-' * 80)

        midonet_api_host = service.load_all(container_type='midonet-api')[0]
        midonet_api = midonet_api_host.get_midonet_api()
        tzs = midonet_api.get_tunnel_zones()
        mdts_tzs = filter(lambda t: t.get_name() == 'mdts-test', tzs)
        map(lambda tz: tz.delete(), mdts_tzs)

        # for h in self._hosts:
        #     host = h['host']
        #
        #     # Delete TZ
        #     if host.get('tunnel_zone'):
        #         tz_data = host.get('tunnel_zone')
        #         tzs = self._api.get_tunnel_zones()
        #         tz = filter(lambda x: x.get_name() == tz_data['name'], tzs)
        #         # Delete tz, which has(have) the name in the config
        #         map(lambda x: x.delete(), tz)
        #
        #     if host['provided'] == True:
        #         LOG.info('Skipped destroying host=%r', host)
        #     else:
        #         #TODO(tomoe): when we support provisioning Midolman host with
        #         # this tool.
        #         pass
        #     interfaces = host['interfaces']
        #
        #     futures = []
        #     for i in interfaces:
        #         iface = Interface(i['interface'], host)
        #         f = iface.delete()
        #         futures.append(f)
        #
        #     wait_on_futures(futures)
        #
        # for b in self._bridges:
        #     bridge = b['bridge']
        #     # TODO(tomohiko) Need to do something when !host['provided']?
        #     if host['provided']:
        #         LOG.info('Skipped destroying bridge=%r', bridge)

        LOG.debug('-' * 80)
        LOG.debug("end destroy")
        LOG.debug('-' * 80)

    def get_interface(self, host_id, interface_id):
        return self._interfaces.get((host_id, interface_id))
