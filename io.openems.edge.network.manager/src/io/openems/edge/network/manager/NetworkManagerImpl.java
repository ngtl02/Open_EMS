package io.openems.edge.network.manager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.jsonrpc.serialization.EmptyObject;
import io.openems.common.session.Role;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.jsonapi.ComponentJsonApi;
import io.openems.edge.common.jsonapi.EdgeGuards;
import io.openems.edge.common.jsonapi.EdgeKeys;
import io.openems.edge.common.jsonapi.JsonApiBuilder;
import io.openems.edge.network.manager.jsonrpc.GetNetworkManagerConfig;
import io.openems.edge.network.manager.jsonrpc.GetNetworkManagerStatus;
import io.openems.edge.network.manager.jsonrpc.SetNetworkManagerConfig;

@Designate(ocd = Config.class, factory = true)
@Component(name = "Network.Manager", immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE)
public class NetworkManagerImpl extends AbstractOpenemsComponent
        implements NetworkManager, OpenemsComponent, ComponentJsonApi {

    private final Logger log = LoggerFactory.getLogger(NetworkManagerImpl.class);

    // Hardcoded interface names
    private static final String IFACE_ETH0 = "eth0";
    private static final String IFACE_ETH1 = "eth1";
    // 4G prefixes (wwan0, usb0, ppp0...)
    private static final String[] IFACE_MOBILE_PREFIXES = { "wwan", "ppp", "usb", "enx" };

    @Reference
    protected ConfigurationAdmin cm;

    private Config config;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    public NetworkManagerImpl() {
        super(OpenemsComponent.ChannelId.values(), NetworkManager.ChannelId.values());
    }

    @Activate
    void activate(ComponentContext context, Config config) {
        this.logInfo(this.log, "Activating NetworkManager...");
        try {
            super.activate(context, config.id(), config.alias(), config.enabled());
            this.config = config;

            this.logInfo(this.log, "Starting Network Monitoring Task...");
            // Schedule polling task
            this.executor.scheduleWithFixedDelay(() -> {
                try {
                    this.updateIpChannels();
                } catch (Exception e) {
                    this.logWarn(this.log, "Error in network monitor task: " + e.getMessage());
                }
            }, 1, 5, TimeUnit.SECONDS);

            this.logInfo(this.log, "NetworkManager Activated Successfully.");
            this.runTask();
        } catch (Exception e) {
            this.logError(this.log, "Failed to activate NetworkManager: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ... (rest of methods)

    private void executeShellCommand(String command) throws IOException, InterruptedException {
        this.logInfo(this.log, "Executing command: " + command);
        ProcessBuilder builder = new ProcessBuilder("bash", "-c", command);
        Process process = builder.start();

        // Add timeout to prevent hanging forever
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Command timed out: " + command);
        }

        if (process.exitValue() != 0) {
            // Read error stream
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            throw new IOException("Command Failed with exit code " + process.exitValue() + ": " + sb.toString());
        }
        this.logInfo(this.log, "Command executed successfully.");
    }

    @Modified
    void modified(ComponentContext context, Config config) {
        super.modified(context, config.id(), config.alias(), config.enabled());
        this.config = config;
        this.runTask();
    }

    @Deactivate
    protected void deactivate() {
        if (this.executor != null) {
            this.executor.shutdownNow();
        }
        super.deactivate();
    }

    @Override
    public void buildJsonApiRoutes(JsonApiBuilder builder) {
        // Get network status (IP addresses)
        builder.handleRequest(new GetNetworkManagerStatus(), endpoint -> {
            endpoint.setDescription("Gets the current IP status of all network interfaces.");
            endpoint.setGuards(EdgeGuards.roleIsAtleast(Role.GUEST));
        }, call -> {
            return this.handleGetNetworkManagerStatus();
        });

        // Get network configuration
        builder.handleRequest(new GetNetworkManagerConfig(), endpoint -> {
            endpoint.setDescription("Gets the current network manager configuration.");
            endpoint.setGuards(EdgeGuards.roleIsAtleast(Role.OWNER));
        }, call -> {
            return this.handleGetNetworkManagerConfig();
        });

        // Set network configuration
        builder.handleRequest(new SetNetworkManagerConfig(), endpoint -> {
            endpoint.setDescription("Updates the network manager configuration.");
            endpoint.setGuards(EdgeGuards.roleIsAtleast(Role.ADMIN));
        }, call -> {
            this.handleSetNetworkManagerConfig(call.getRequest());
            return EmptyObject.INSTANCE;
        });
    }

    /**
     * Handles GetNetworkManagerStatus request.
     *
     * @return the response with current IP status
     */
    private GetNetworkManagerStatus.Response handleGetNetworkManagerStatus() {
        String lan1Ip = getChannelValueAsString(NetworkManager.ChannelId.LAN1_IP);
        String lan2Ip = getChannelValueAsString(NetworkManager.ChannelId.LAN2_IP);
        String mobileIp = getChannelValueAsString(NetworkManager.ChannelId.MOBILE_IP);
        String lastError = getChannelValueAsString(NetworkManager.ChannelId.LAST_ERROR_MESSAGE);

        return new GetNetworkManagerStatus.Response(lan1Ip, lan2Ip, mobileIp, lastError);
    }

    /**
     * Handles GetNetworkManagerConfig request.
     *
     * @return the response with current configuration
     */
    private GetNetworkManagerConfig.Response handleGetNetworkManagerConfig() {
        var eth0Config = new GetNetworkManagerConfig.InterfaceConfig(
                this.config.eth0Mode().name(),
                this.config.eth0Ip(),
                this.config.eth0SubnetMask(),
                this.config.eth0Gateway(),
                this.config.eth0DnsPrimary(),
                this.config.eth0DnsSecondary());

        var eth1Config = new GetNetworkManagerConfig.InterfaceConfig(
                this.config.eth1Mode().name(),
                this.config.eth1Ip(),
                this.config.eth1SubnetMask(),
                this.config.eth1Gateway(),
                this.config.eth1DnsPrimary(),
                this.config.eth1DnsSecondary());

        return new GetNetworkManagerConfig.Response(eth0Config, eth1Config);
    }

    /**
     * Handles SetNetworkManagerConfig request.
     *
     * @param request the request with new configuration
     * @throws OpenemsNamedException on error
     */
    private void handleSetNetworkManagerConfig(SetNetworkManagerConfig.Request request) throws OpenemsNamedException {
        String interfaceName = request.interfaceName();
        Mode mode = request.getMode();

        if (mode == Mode.NONE) {
            this.logInfo(this.log, "Mode is NONE, skipping network configuration for " + interfaceName);
            return;
        }

        String ip = request.ip();
        String subnetMask = request.subnetMask();
        String gateway = request.gateway();

        String ipWithCidr = buildCidrAddress(ip, subnetMask);
        applyNetworkConfig(interfaceName, mode, ipWithCidr, gateway);
    }

    private String getChannelValueAsString(NetworkManager.ChannelId channelId) {
        Object val = this.channel(channelId).value().get();
        return (val != null) ? val.toString() : null;
    }

    /**
     * Main task: Always updates monitor data, then applies config if needed.
     */
    private void runTask() {
        if (!config.enabled())
            return;

        // 1. ALWAYS READ: Update IP status for all interfaces (eth0, eth1, 4g)
        this.updateIpChannels();

        // 2. OPTIONAL WRITE: Apply config only if Mode is not NONE
        if (config.eth0Mode() != Mode.NONE) {
            String eth0IpWithCidr = buildCidrAddress(config.eth0Ip(), config.eth0SubnetMask());
            applyNetworkConfig(IFACE_ETH0, config.eth0Mode(), eth0IpWithCidr, config.eth0Gateway());
        }

        if (config.eth1Mode() != Mode.NONE) {
            String eth1IpWithCidr = buildCidrAddress(config.eth1Ip(), config.eth1SubnetMask());
            applyNetworkConfig(IFACE_ETH1, config.eth1Mode(), eth1IpWithCidr, config.eth1Gateway());
        }
    }

    /**
     * Scans all network interfaces and populates OpenEMS Channels.
     */
    private void updateIpChannels() {
        try {
            // Reset channels
            this.channel(NetworkManager.ChannelId.LAN1_IP).setNextValue("Disconnected");
            this.channel(NetworkManager.ChannelId.LAN2_IP).setNextValue("Disconnected");
            this.channel(NetworkManager.ChannelId.MOBILE_IP).setNextValue("Disconnected");

            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (!iface.isUp() || iface.isLoopback())
                    continue;

                String ipV4 = getIPv4FromInterface(iface);
                if (ipV4 == null)
                    continue;

                String name = iface.getName();

                // Auto-map based on interface name
                if (name.equals(IFACE_ETH0)) {
                    this.channel(NetworkManager.ChannelId.LAN1_IP).setNextValue(ipV4);
                } else if (name.equals(IFACE_ETH1)) {
                    this.channel(NetworkManager.ChannelId.LAN2_IP).setNextValue(ipV4);
                } else if (isMobileInterface(name)) {
                    this.channel(NetworkManager.ChannelId.MOBILE_IP).setNextValue(ipV4);
                }
            }
        } catch (Exception e) {
            this.logWarn(this.log, "Monitoring Error: " + e.getMessage());
        }
    }

    /**
     * Applies network configuration (DHCP/Static) using nmcli.
     * Now supports DNS configuration.
     */
    private void applyNetworkConfig(String interfaceName, Mode mode, String staticIp, String gateway) {
        CompletableFuture.runAsync(() -> {
            try {
                // Auto-detect the Connection Name (e.g., "Wired connection 1") from Interface
                String connectionName = getConnectionNameFromInterface(interfaceName);
                if (connectionName == null || connectionName.isEmpty()) {
                    this.logWarn(this.log, "No NMCLI connection found for " + interfaceName);
                    return;
                }

                // Check current IP to avoid unnecessary restarts (Basic optimization)
                String currentIp = getIpFromChannel(interfaceName);
                if (mode == Mode.STATIC && currentIp.equals(staticIp.split("/")[0])) {
                    // Already set correctly, skip
                    return;
                }

                String cmd = "";
                if (mode == Mode.DHCP) {
                    this.logInfo(this.log, "Switching " + interfaceName + " to DHCP...");
                    cmd = String.format(
                            "sudo nmcli con mod \"%s\" ipv4.method auto ipv4.addresses \"\" ipv4.gateway \"\" ipv4.dns \"\"",
                            connectionName);
                } else if (mode == Mode.STATIC) {
                    this.logInfo(this.log, "Setting Static IP for " + interfaceName + ": " + staticIp);

                    // Build DNS string from config
                    String dnsString = getDnsString(interfaceName);

                    // Build command with optional gateway and DNS
                    StringBuilder cmdBuilder = new StringBuilder();
                    cmdBuilder.append(
                            String.format("sudo nmcli con mod \"%s\" ipv4.addresses %s", connectionName, staticIp));

                    if (gateway != null && !gateway.isEmpty()) {
                        cmdBuilder.append(String.format(" ipv4.gateway %s", gateway));
                    } else {
                        cmdBuilder.append(" ipv4.gateway \"\"");
                    }

                    if (!dnsString.isEmpty()) {
                        cmdBuilder.append(String.format(" ipv4.dns \"%s\"", dnsString));
                    } else {
                        cmdBuilder.append(" ipv4.dns \"\"");
                    }

                    cmdBuilder.append(" ipv4.method manual");
                    cmd = cmdBuilder.toString();
                }

                // Execute Command
                executeShellCommand(cmd);
                executeShellCommand(String.format("sudo nmcli con up \"%s\"", connectionName));

                // Wait and refresh Monitor
                Thread.sleep(4000);
                this.updateIpChannels();
                this.channel(NetworkManager.ChannelId.LAST_ERROR_MESSAGE)
                        .setNextValue("Success: Config applied to " + interfaceName);

            } catch (Exception e) {
                this.logError(this.log, "Config Failed (" + interfaceName + "): " + e.getMessage());
                this.channel(NetworkManager.ChannelId.LAST_ERROR_MESSAGE).setNextValue(e.getMessage());
            }
        });
    }

    /**
     * Builds DNS string from config for specified interface.
     * 
     * @param interfaceName Interface name (eth0 or eth1)
     * @return DNS string in format "8.8.8.8 8.8.4.4" or empty if no DNS configured
     */
    private String getDnsString(String interfaceName) {
        String primary = "";
        String secondary = "";

        if (interfaceName.equals(IFACE_ETH0)) {
            primary = config.eth0DnsPrimary();
            secondary = config.eth0DnsSecondary();
        } else if (interfaceName.equals(IFACE_ETH1)) {
            primary = config.eth1DnsPrimary();
            secondary = config.eth1DnsSecondary();
        }

        StringBuilder dns = new StringBuilder();
        if (primary != null && !primary.trim().isEmpty()) {
            dns.append(primary.trim());
        }
        if (secondary != null && !secondary.trim().isEmpty()) {
            if (dns.length() > 0) {
                dns.append(" ");
            }
            dns.append(secondary.trim());
        }

        return dns.toString();
    }

    /**
     * Combines IP address and subnet mask into CIDR notation.
     * 
     * @param ip         IP address (e.g., "192.168.1.50")
     * @param subnetMask Subnet mask (e.g., "255.255.255.0")
     * @return CIDR notation (e.g., "192.168.1.50/24")
     */
    private String buildCidrAddress(String ip, String subnetMask) {
        int cidr = subnetMaskToCidr(subnetMask);
        return ip + "/" + cidr;
    }

    /**
     * Converts subnet mask to CIDR prefix length.
     * 
     * @param subnetMask Subnet mask (e.g., "255.255.255.0")
     * @return CIDR prefix (e.g., 24)
     */
    private int subnetMaskToCidr(String subnetMask) {
        try {
            String[] octets = subnetMask.split("\\.");
            int cidr = 0;
            for (String octet : octets) {
                int value = Integer.parseInt(octet);
                cidr += Integer.bitCount(value);
            }
            return cidr;
        } catch (Exception e) {
            this.logWarn(this.log, "Invalid subnet mask: " + subnetMask + ", defaulting to /24");
            return 24; // Default to /24 if invalid
        }
    }

    // --- HELPER METHODS ---

    private String getIpFromChannel(String ifName) {
        NetworkManager.ChannelId channelId = null;

        // 1. Xác định Channel cần lấy dựa trên tên interface
        if (ifName.equals(IFACE_ETH0)) {
            channelId = NetworkManager.ChannelId.LAN1_IP;
        } else if (ifName.equals(IFACE_ETH1)) {
            channelId = NetworkManager.ChannelId.LAN2_IP;
        }

        // 2. Lấy giá trị an toàn (Tránh lỗi Generics orElse)
        if (channelId != null) {
            Object val = this.channel(channelId).value().get();
            return (val != null) ? val.toString() : "";
        }

        return "";
    }

    private String getConnectionNameFromInterface(String interfaceName) {
        try {
            // Command: nmcli -g GENERAL.CONNECTION device show eth0
            String cmd = "nmcli -g GENERAL.CONNECTION device show " + interfaceName;
            ProcessBuilder builder = new ProcessBuilder("bash", "-c", cmd);
            Process process = builder.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            process.waitFor();
            if (line != null)
                return line.trim();
        } catch (Exception e) {
        }
        return null;
    }

    private String getIPv4FromInterface(NetworkInterface iface) {
        Enumeration<InetAddress> addresses = iface.getInetAddresses();
        while (addresses.hasMoreElements()) {
            InetAddress addr = addresses.nextElement();
            if (addr.getAddress().length == 4)
                return addr.getHostAddress();
        }
        return null;
    }

    private boolean isMobileInterface(String name) {
        for (String prefix : IFACE_MOBILE_PREFIXES) {
            if (name.startsWith(prefix))
                return true;
        }
        return false;
    }

    private void executeShellCommand(String command) throws IOException, InterruptedException {
        this.logInfo(this.log, "Executing command: " + command);
        ProcessBuilder builder = new ProcessBuilder("bash", "-c", command);
        Process process = builder.start();

        // Add timeout to prevent hanging forever
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Command timed out: " + command);
        }

        if (process.exitValue() != 0) {
            // Read error stream
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            throw new IOException("Command Failed with exit code " + process.exitValue() + ": " + sb.toString());
        }
        this.logInfo(this.log, "Command executed successfully.");
    }
}
