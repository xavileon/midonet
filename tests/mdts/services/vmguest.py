#
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
#
import subprocess
from concurrent.futures import ThreadPoolExecutor
import time

from mdts.services.interface import Interface
import logging

LOG = logging.getLogger(__name__)

EXECUTOR = ThreadPoolExecutor(max_workers=10)

class VMGuest(Interface):

    def __init__(self, compute_host, vm_id, **iface_kwargs):
        super(VMGuest, self).__init__(**iface_kwargs)
        self.compute_host = compute_host
        self.vm_id = vm_id

        # Setup hw_addr, ip_addr, ip_gw if defined in **iface_kwargs
        # using compute_host proxy

    # Public methods
    def get_vm_id(self):
        return self.vm_id

    def get_vm_ns(self):
        return 'vm'+self.vm_id

    def destroy(self):
        self.compute_host.destroy_vm(self)

    def handle_sync(funk):
        def wrapped(self, *args, **kwargs):
            future = funk(self, *args, **kwargs)
            if kwargs.get('sync'):
                return future.result()
            else:
                return future
        return wrapped

    @handle_sync
    def expect(self, pcap_filter_string, timeout=None, sync=False):
        return EXECUTOR.submit(self.do_expect, pcap_filter_string, timeout)

    @handle_sync
    def execute(self, cmdline, timeout=None, should_succeed=True, sync=False):
        return EXECUTOR.submit(self.do_execute, cmdline, timeout,
                               should_succeed, stream=not sync)

    @handle_sync
    def clear_arp(self, sync=False):
        return EXECUTOR.submit(self.do_clear_arp)

    def print_exec(self, exec_id):
        import pprint
        from pprint import saferepr
        LOG.debug(saferepr(self.compute_host.cli.exec_inspect(exec_id)))

    # FIXME: the default number of packets to wait for is 1
    # but the default number of packets sent is 3. In some cases, the other
    # remaining two paquets (not captured by this expect) will be captured
    # by the following expect. If this second expect doesn't actually expect
    # any packet, it will miserably fail.
    # Root cause: tests are not properly isolated. There is no barrier between
    # "different" tests inside a single test.
    def do_expect(self, pcap_filter_string, timeout):
        """
        Expects packet with pcap_filter_string with tcpdump.
        See man pcap-filter for more details as to what you can match.


        Args:
            pcap_filter_string: capture filter to pass to tcpdump
                                See man pcap-filter
            timeout: in second

        Returns:
            True: when packet arrives
            False: when packet doesn't arrive within timeout
        """
        cmdline = 'tcpdump -n -l -i %s -c 1 %s' % (
            self.get_vm_ifname(),
            pcap_filter_string)
        try:
            log_stream, exec_id = self.do_execute(cmdline, timeout, stream=True)
            LOG.debug('running tcp dump=%s', cmdline)
        finally:
            self._tcpdump_sem.release()

        try:
            LOG.debug('tcp dump running OK')
            # FIXME: wrap it in a function so we don't access members directly
            LOG.debug('Gathering results from stream of %s...' % cmdline)
            result = ""
            for log_line in log_stream:
                result += log_line
                LOG.debug('Result is: %s' % log_line.rstrip())
        except StopIteration:
            LOG.debug("Stream didn't block, command %s " % cmdline +
                      " timed out before pulling results.")

        return_code = self.compute_host.check_exit_status(exec_id)
        if return_code != 0:
            LOG.debug('%s return_code = %s != 0, no packets received... %r' % (
                cmdline,
                return_code,
                result
            ))
            return False

        LOG.debug('%s return_code = %s output = %r' % (
            cmdline,
            return_code,
            result))
        return True

    # Inherited methods
    # FIXME: remove sync or look where it is used
    def do_execute(self, cmdline, timeout=None, should_succeed=True,
                   stream=False):
        """
        Execute in the underlying host
        :param cmdline:
        :param timeout:
        :return:
        """
        cmdline = 'ip netns exec %s %s' % (
            self.get_vm_ns(),
            ('timeout %d ' % timeout if timeout else '') + cmdline)
        result = self.compute_host.exec_command(cmdline,
                                                detach=False,
                                                stream=stream)
        return result

    def do_clear_arp(self):
        cmdline = 'ip neigh flush all'
        LOG.debug('VethNs: flushing arp cache: ' + cmdline)
        return self.do_execute(cmdline)

    def set_up(self):
        return self.do_execute("ip link set dev %s up" % self.get_vm_ifname())

    def set_down(self):
        return self.do_execute("ip link set dev %s down" % self.get_vm_ifname())

    def get_vm_ifname(self):
        return 'peth'+self.vm_id

    def get_host_ifname(self):
        return 'veth'+self.vm_id

