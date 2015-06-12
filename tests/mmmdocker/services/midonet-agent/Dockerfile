FROM ubuntu-upstart:14.04
MAINTAINER Xavi León "xavi.leon@midokura.com"

# Add the apt configuration file
ADD conf/midonet.list /etc/apt/sources.list.d/midonet.list
RUN apt-get install -qy curl
RUN curl -k http://repo.midonet.org/packages.midokura.key | apt-key add -

# Install dependencies and default packages from repos
RUN apt-get -q update
RUN apt-get install -qy midolman zkdump mz tcpdump

# Workaround to circumvent limitations with AppArmor and docker
RUN mv /usr/sbin/tcpdump /usr/bin/tcpdump
RUN mv /sbin/dhclient /usr/bin/dhclient

ADD bin/clean.sh /clean.sh
ADD bin/run-midolman.sh /run-midolman.sh

CMD ["/run-midolman.sh"]
