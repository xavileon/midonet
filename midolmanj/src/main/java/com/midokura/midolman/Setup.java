// Copyright 2012 Midokura Inc.

package com.midokura.midolman;

import java.io.FileReader;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.Options;
import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.HierarchicalINIConfiguration;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.PathBuilder;
import com.midokura.midolman.util.Sudo;

public class Setup {

    static final Logger log = LoggerFactory.getLogger(Setup.class);

    private static final String MIDONET_QDISC_CREATE = "midonet_qdisc_create";
    private static final String NOVA_QDISC_CREATE = "nova_qdisc_create";
    private static final String QDISC_DESTROY = "qdisc_destroy";

    private ScheduledExecutorService executor;
    private HierarchicalConfiguration config;

    private void run(String[] args) throws Exception {
        Options options = new Options();
        options.addOption("c", "configFile", true, "config file path");
        CommandLineParser parser = new GnuParser();
        CommandLine cl = parser.parse(options, args);
        String configFilePath = cl.getOptionValue('c', "./conf/midolman.conf");

        config = new HierarchicalINIConfiguration(configFilePath);
        executor = Executors.newScheduledThreadPool(1);

        args = cl.getArgs();
        if (args.length == 0)
            return;
        String command = args[0].toLowerCase();
        if (command.equals(MIDONET_QDISC_CREATE))
            setupTrafficPriorityQdiscsMidonet();
        else if (command.equals(NOVA_QDISC_CREATE))
            setupTrafficPriorityQdiscsNova();
        else if (command.equals(QDISC_DESTROY))
            removeTrafficPriorityQdiscs();
        else
            System.out.println("Unrecognized command. Exiting.");
    }

    private static List<String> getTopLevelPaths(String basePath) {
        PathBuilder pathMgr = new PathBuilder(basePath);
        List<String> paths = new ArrayList<String>();
        paths.add(pathMgr.getAdRoutesPath());
        paths.add(pathMgr.getBgpPath());
        paths.add(pathMgr.getBridgesPath());
        paths.add(pathMgr.getChainsPath());
        paths.add(pathMgr.getFiltersPath());
        paths.add(pathMgr.getRulesPath());
        paths.add(pathMgr.getGrePath());
        paths.add(pathMgr.getPortsPath());
        paths.add(pathMgr.getPortSetsPath());
        paths.add(pathMgr.getRoutersPath());
        paths.add(pathMgr.getRoutesPath());
        paths.add(pathMgr.getVRNPortLocationsPath());
        paths.add(pathMgr.getVpnPath());
        paths.add(pathMgr.getAgentPath());
        paths.add(pathMgr.getAgentPortPath());
        paths.add(pathMgr.getAgentVpnPath());
        paths.add(pathMgr.getPortGroupsPath());
        paths.add(pathMgr.getHostsPath());
        paths.add(pathMgr.getTenantsPath());
        return paths;
    }

    public static void createZkDirectoryStructure(Directory rootDir,
            String basePath) throws KeeperException, InterruptedException {
        List<String> paths = Setup.getTopLevelPaths(basePath);
        for (String path : paths) {
            try {
                rootDir.add(path, null, CreateMode.PERSISTENT);
            } catch (KeeperException.NodeExistsException ex) {
                // Don't exit even if the node exists.
                log.warn("createZkDirectoryStructure: {} already exists.",
                        path);
            }
        }
    }

    protected void setupTrafficPriorityQdiscsMidonet()
            throws IOException, URISyntaxException, InterruptedException {
        int markValue = 0x00ACCABA;  // Midokura's OUI.
        String iface = config.configurationAt("midolman")
                             .getString("control_interface", "eth0");

        // Add a prio qdisc to root, and have marked packets prioritized.
        Sudo.sudoExec("tc qdisc add dev " + iface + " root handle 1: prio");
        Sudo.sudoExec("tc filter add dev " + iface +
                 " parent 1: protocol ip prio 1 handle " + markValue +
                 " fw flowid 1:1");

        // Add rules to mark ZooKeeper packets.
        String zkHosts = config.configurationAt("zookeeper")
                               .getString("zookeeper_hosts", "127.0.0.1:2181");
        for (String zkServer : zkHosts.split(",")) {
            String[] hostport = zkServer.split(":");
            assert hostport.length == 2;
            setupTrafficPriorityRule(hostport[0], hostport[1]);
        }

        // Add rules to mark memcached packets.
        String mcHosts = config.configurationAt("memcache")
                               .getString("memcache_hosts", "127.0.0.1:11211");
        for (String mcServer : mcHosts.split(",")) {
            String[] hostport = mcServer.split(":");
            setupTrafficPriorityRule(hostport[0], hostport[1]);
        }
    }

    protected void setupTrafficPriorityQdiscsNova()
            throws IOException, InterruptedException, URISyntaxException {
        FileReader confFile = new FileReader("/etc/nova/nova.conf");
        char[] confBytes = new char[5000];
        int confByteLength = confFile.read(confBytes);
        String[] allArgs = (new String(confBytes, 0, confByteLength)).split("\n");
        for (String arg : allArgs) {
            // RabbitMQ
            if (arg.startsWith("--rabbit_host")) {
                String[] flaghost = arg.split("=");
                setupTrafficPriorityRule(flaghost[1], "5672");
            }

            // mysql
            if (arg.startsWith("--sql_connection")) {
                String[] flagurl = arg.split("=");
                URI mysqlUrl = new URI(flagurl[1]);
                int port = mysqlUrl.getPort();
                if (port == -1)
                    port = 3306;
                setupTrafficPriorityRule(mysqlUrl.getHost(), Integer.toString(port));
            }

            // VNC
            if (arg.startsWith("--sql_connection")) {
                String[] flagurl = arg.split("=");
                URI vncUrl = new URI(flagurl[1]);
                int port = vncUrl.getPort();
                if (port == -1)
                    port = 6080;
                setupTrafficPriorityRule(vncUrl.getHost(), Integer.toString(port));
            }

            // EC2
            if (arg.startsWith("--ec2_url")) {
                String[] flagurl = arg.split("=");
                URI ec2Url = new URI(flagurl[1]);
                int port = ec2Url.getPort();
                if (port == -1)
                    port = 8773;
                setupTrafficPriorityRule(ec2Url.getHost(), Integer.toString(port));
            }
        }
    }

    protected void removeTrafficPriorityQdiscs()
            throws IOException, InterruptedException {
        // Clear existing qdiscs
        String iface = config.configurationAt("midolman")
                             .getString("control_interface", "eth0");
        Sudo.sudoExec("tc qdisc del dev " + iface + " root");
    }

    protected static void setupTrafficPriorityRule(String host, String port)
            throws IOException, InterruptedException {
        int markValue = 0x00ACCABA;  // Midokura's OUI.
        Sudo.sudoExec(
                "iptables -t mangle -A POSTROUTING -p tcp -m tcp -d " +
                host + " --dport " + port + " -j MARK --set-mark " + markValue);
    }

    public static void main(String[] args) {
        try {
            new Setup().run(args);
        } catch (Exception e) {
            log.error("main caught", e);
            System.exit(-1);
        }
    }

}
