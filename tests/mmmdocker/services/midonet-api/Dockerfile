FROM ubuntu:14.04
MAINTAINER Xavi León "xavi.leon@midokura.com"

# Add the apt configuration file
RUN apt-get install -qqy curl
ADD conf/midonet.list /etc/apt/sources.list.d/midonet.list
RUN curl -k http://repo.midonet.org/packages.midokura.key | apt-key add -

# Install dependencies and default packages from repos
RUN apt-get -q update
RUN apt-get install -qqy tomcat7 midonet-api python-midonetclient
ADD conf/midonet-api.xml /etc/tomcat7/Catalina/localhost/midonet-api.xml
RUN mkdir -p /tmp/tomcat7-tomcat7-tmp/

ADD bin/run-midonetapi.sh /run-midonetapi.sh
ADD conf/midonetrc /root/.midonetrc

# Expose tomcat/midonet-api port
EXPOSE 8080

CMD ["/run-midonetapi.sh"]
