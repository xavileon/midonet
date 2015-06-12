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

import os
import ConfigParser


conf_file = os.getenv('MDTS_CONF_FILE', 'mdts.conf')
conf = ConfigParser.ConfigParser()
conf.read(conf_file)

# TODO: FIX this hardcoded GLOBAL VARIABLES
TEST_TENANT_NAME_PREFIX = 'MMM-TEST'

NS_BGP_PEERS = ["ns000"]

IP_ZOOKEEPER_HOSTS = ["10.0.0.2", "10.0.0.3", "10.0.0.4"]
NS_ZOOKEEPER_HOSTS = ["ns002", "ns003", "ns004"]

IP_CASSANDRA_HOSTS = ["10.0.0.5", "10.0.0.6", "10.0.0.7"]
NS_CASSANDRA_HOSTS = ["ns005", "ns006", "ns007"]


def is_vxlan_enabled():
    """Returns boolean to indicate if vxlan tunnels are enabled"""
    return conf.getboolean('functional_tests', 'vxlan')
