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
import importlib
import logging

from docker import Client
import re
import time

cli = Client(base_url='unix://var/run/docker.sock')
LOG = logging.getLogger(__name__)


class Service(object):

    def __init__(self, container_id):
        self.cli = cli
        self.info = cli.inspect_container(container_id)
        links = self.info['HostConfig']['Links']
        self.links = {}
        for link in links or []:
            linked_name = re.split(':', link.translate({ord('/'): None}))[0]
            linked_container_info = cli.inspect_container(linked_name)
            linked_container = load_from_id(linked_container_info['Id'])
            linked_containers = self.links.setdefault(
                linked_container.get_type(),
                {})
            linked_containers[linked_name] = linked_container

    # Helper methods to abstract from docker internals
    def get_type(self):
        return str(self.info['Config']['Labels']['type'])

    def get_name(self):
        return str(self.info['Name'].translate({ord('/'): None}))

    def get_container_id(self):
        return str(self.info['Id'])

    def get_linked_container(self, type, name):
        return self.links[type][name]

    def get_ip_address(self):
        return str(self.info['NetworkSettings']['IPAddress'])

    def get_mac_address(self):
        return str(self.info['NetworkSettings']['MacAddress'])

    def get_ports(self):
        return self.info['NetworkSettings']['Ports']

    def get_hostname(self):
        return str(self.info['Config']['Hostname'])

    def get_environment(self):
        return self.info['Config']['Env']

    def get_info(self):
        return self.info

    def get_service_name(self):
        raise NotImplementedError()

    def start(self, wait=False):
        self.exec_command('service %s start' % self.get_service_name())
        if wait:
            self.wait_for_status('up')

    def stop(self, wait=False):
        self.exec_command('service %s stop' % self.get_service_name())
        if wait:
            self.wait_for_status('down')

    def restart(self, wait=False):
        self.exec_command('service %s restart' % self.get_service_name())
        if wait:
            self.wait_for_status('up')

    def exec_command(self, cmd, stdout=True, stderr=False, tty=False,
                     detach=False, stream=False):
        """

        :param cmd:
        :param stdout:
        :param stderr:
        :param tty:
        :param detach:
        :param stream:
        :return: if detach: exec_id of the docker command for future inspect
                 if stream: a stream generator with the output
                 else: the result of the command
        """
        # Use raw representation to account for " and ' inside the cmd
        cmd = "sh -c \"%s\"" % cmd

        LOG.debug('[%s] executing command: %s',
                  self.get_name(),
                  cmd)

        exec_id = cli.exec_create(self.get_name(),
                                  cmd,
                                  stdout=stdout,
                                  stderr=stderr,
                                  tty=tty)

        result = cli.exec_start(exec_id, detach=detach, stream=stream)
        if stream:
            self._ensure_command_running(exec_id) # TODO
            # Result is a data blocking stream, exec_id for future checks
            return result, exec_id

        result = result.rstrip()
        LOG.debug('[%s] result = %s',
                  self.get_name(),
                  result)
        # FIXME: different return result depending on params might be confusing
        # Awful pattern
        # Result is a string with the command output
        # return_code is the exit code
        return result

    def _ensure_command_running(self, exec_id):
        timeout = 10
        wait_time = 1
        while not cli.exec_inspect(exec_id)['Running']:
            if timeout == 0:
                LOG.debug('Command %s did not start' % exec_id)
                raise Exception('Command %s did not start' % exec_id)
            timeout -= wait_time
            time.sleep(wait_time)
        LOG.debug('Command started')
        return True

    def check_exit_status(self, exec_id, timeout=5):
        wait_time = 1
        exec_info = cli.exec_inspect(exec_id)
        cmdline = exec_info['ProcessConfig']['entrypoint']
        for arg in exec_info['ProcessConfig']['arguments']:
            cmdline += " " + arg

        # Wait for command to finish after a certain amount of time
        while cli.exec_inspect(exec_id)['Running']:
            if timeout == 0:
                LOG.debug('Command %s timed out.' % cmdline)
                return -1
            timeout -= wait_time
            time.sleep(wait_time)
            LOG.debug('Command %s still running... [timeout in %d]' % (
                cmdline,
                timeout
            ))
        exec_info = cli.exec_inspect(exec_id)
        LOG.debug('Command %s %s' % (
            cmdline,
            'succeeded' if exec_info['ExitCode'] == 0 else 'failed'
        ))
        return exec_info['ExitCode']

    def wait_for_status(self, status, timeout=120, wait_time=5):
        while self.get_service_status() != status:
            if timeout == 0:
                raise RuntimeError("Service %s: timeout waiting to be %s" % (
                    self.get_name(),
                    status))
            timeout -= wait_time
            time.sleep(wait_time)
        LOG.debug("Service %s: status is now %s" % (self.get_name(), status))

    # TODO: Make it generic so you can fail whatever component
    # (even packet failure in an interface)
    def inject_failure(self):
        # put iptables rule or just set the interface down
        cmdline = "ip link set dev eth0 down"
        result = self.exec_command(cmdline, stream=False)
        self.wait_for_status('down')

    def eject_failure(self):
        cmdline = "ip link set dev eth0 up"
        result = self.exec_command(cmdline, stream=False)
        self.wait_for_status('up')

    def get_service_status(self):
        """
        Return the status of this service (FIXME: change it by constants?)

        :return: str specifying "up" or "down"
        """
        raise NotImplementedError()


def load_from_config(container_config):
    container = cli.inspect_container(container_config['name'])
    return load_from_id(container['Id'])


def load_from_name(container_name):
    container_info = cli.inspect_container(container_name)
    return load_from_id(container_info['Id'])


def load_from_id(container_id):
    container_info = cli.inspect_container(container_id)
    fqn = container_info['Config']['Labels']['interface']
    module_name, class_name = tuple(fqn.rsplit('.', 1))
    _module = importlib.import_module(module_name)
    _class = getattr(_module, class_name)
    return _class(container_id)


loaded_containers = {}

# FIXME: this factory is not the best option, quick hack
def load_all(container_type=None):
    global loaded_containers
    if loaded_containers:
        if container_type:
            return loaded_containers[container_type]
        return loaded_containers

    running_containers = cli.containers()
    containers = {}
    for container in running_containers:
        current_type = container['Labels']['type']
        container_instance = load_from_id(container['Id'])
        containers.setdefault(current_type, []).append(container_instance)
    return containers

loaded_containers = load_all()
