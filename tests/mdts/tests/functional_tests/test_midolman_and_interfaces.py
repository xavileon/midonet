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
Tests if Midolman agent / interface status update
"""
import random

from hamcrest import assert_that
from hamcrest import none
from hamcrest import not_none
from nose.plugins.attrib import attr

from mdts.lib.physical_topology_manager import PhysicalTopologyManager
from mdts.services import service
from mdts.tests.utils.utils import get_midonet_api
# FIXME: remove these from mdts.tests.utils, no longer necessary
#from mdts.tests.utils.utils import start_midolman_agents
#from mdts.tests.utils.utils import stop_midolman_agents
#from mdts.tests.utils.utils import check_all_midolman_hosts

import time


# Only a physical topology containing a single interface.
PTM = PhysicalTopologyManager(
        '../topologies/mmm_physical_test_midolman_and_interfaces.yaml')


# We don't build a Physical Topology Manager in the setup. Instead we create
# a new interface inside 'test_new_interface_becomes_visible'.
def teardown():
    time.sleep(2)
    PTM.destroy()


@attr(version="v1.2.0", slow=False)
def test_host_status():
    """
    Title: Test host status update

    Scenario:
    When: The test starts up,
    Then: check if all Midolman agents are alive,
    Then: stops all Midolman agents,
    Then: check if all Midolman agents are now dead,
    Then: restarts all Midolman agetns,
    And: check again if all Midolman agents are alive,
    """
    agents = service.get_all_containers('midolman')
    for agent in agents:
        assert agent.get_service_status() == 'up'

    for agent in agents:
        agent.stop(wait=True)

    for agent in agents:
        assert agent.get_service_status() == 'down'

    for agent in agents:
        agent.start(wait=True)

    # FIXME: remove this timeout if tests pass
    # time.sleep(30)
    for agent in agents:
        assert agent.get_service_status() == 'up'


def get_interface(midonet_api, host_name, interface_name):
    """Returns an interface with the given name.

    Args:
        midonet_api: A MidonetApi instance
        host_name: A MidoNet host name.
        interface_name: An interface name.

        Returns:
            An interface if one is found with the specified host, otherwise
            None.
    """
    host = None
    for h in midonet_api.get_hosts():
        if h.get_id() == host_name:
            host = h
    # No matching host found. Return None.
    if not host:
        return None

    interface = None
    for i in host.get_interfaces():
        if interface_name in i.get_name():
            interface = i
            break

    return interface


@attr(version="v1.2.0", slow=False)
def test_new_interface_becomes_visible():
    """
    Title: Test new interface becomes visible

    Scenario:
    When: On start up, a Midolman sees no interface,
    Then: adds a new interface,
    And: Midolman detects a new interface.
    """

    # FIXME: pick the midonet-agent from binding manager (when parallel)
    midonet_api = get_midonet_api()
    agent = service.get_container('midonet-agent', 1)
    iface_name = 'interface%d' % random.randint(1, 100)
    new_interface = get_interface(
        midonet_api,
        agent.get_midonet_host_id(),
        iface_name)
    # Test that no interface with name 'interface_01' exists.
    assert_that(new_interface, none(), iface_name)

    # Create a new interface 'interface_01'.
    iface = agent.create_vmguest(ifname=iface_name)
    time.sleep(5)
    new_interface = get_interface(
        midonet_api,
        agent.get_midonet_host_id(),
        iface_name)

    # Test that the created interface is visible.
    assert_that(new_interface, not_none(), iface_name)

    agent.destroy_vm(iface)