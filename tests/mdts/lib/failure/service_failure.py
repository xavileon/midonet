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

import logging
import subprocess
import time

from mdts.lib import subprocess_compat
from mdts.lib.failure.failure_base import FailureBase
from mdts.tests.utils import check_all_zookeeper_hosts
from mdts.tests.utils import check_zookeeper_host
from mdts.services import service

LOG = logging.getLogger(__name__)

class ServiceFailure(FailureBase):
    """Emulate a service failure by setting the interface down

    @netns      network namespace name
    @interface  interface name
    @ip         ip of the zookeeper node
    """
    def __init__(self, service_name):
        super(ServiceFailure, self).__init__("%s failure" % service_name)
        self.service_name = service_name
        self.service = service.load_from_name(service_name)

    # Maybe just make Services class to inherit from FailureBase
    def inject(self):
        self.service.inject_failure()

    def eject(self):
        self.service.eject_failure()

