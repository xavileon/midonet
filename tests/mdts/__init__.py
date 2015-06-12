# Copyright 2015 Midokura SARL
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

from mdts.services import service
from mdts.tests.utils import check_all_midolman_hosts
from ConfigParser import SafeConfigParser
import os


def setup_package():
    """
    Setup method at the tests module level (init)
    :return:
    """
    # Read configuration
    conf_file = os.getenv('MDTS_CONF_FILE', 'mdts.conf')
    config = SafeConfigParser()
    config.read(conf_file)

    # TODO: Check all services (not only midolman) are online

    check_all_midolman_hosts(True)
