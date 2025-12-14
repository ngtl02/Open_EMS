package io.openems.edge.modem.manager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsException;
import io.openems.common.jsonrpc.serialization.EmptyObject;
import io.openems.common.session.Role;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.jsonapi.ComponentJsonApi;
import io.openems.edge.common.jsonapi.EdgeGuards;
import io.openems.edge.common.jsonapi.JsonApiBuilder;
import io.openems.edge.modem.manager.jsonrpc.GetModemConfig;
import io.openems.edge.modem.manager.jsonrpc.GetModemStatus;
import io.openems.edge.modem.manager.jsonrpc.SetModemConfig;

/**
 * 4G/LTE Modem Manager Implementation using Quectel PPP.
 * 
 * <p>
 * Manages 4G modem connection by editing /etc/ppp/quectel-pppd.sh
 * 
 * <p>
 * IMPORTANT: Requires sudoers configuration for passwordless access.
 * Run the setup script: scripts/setup-modem-sudoers.sh
 */
@Designate(ocd = Config.class, factory = true)
@Component(
        name = "Modem.4G.Manager",
        immediate = true,
        configurationPolicy = ConfigurationPolicy.REQUIRE)
public class Modem4GManagerImpl extends AbstractOpenemsComponent
        implements Modem4GManager, OpenemsComponent, ComponentJsonApi {

    private final Logger log = LoggerFactory.getLogger(Modem4GManagerImpl.class);

    /** Path to Quectel PPP script */
    private static final String SCRIPT_PATH = "/etc/ppp/quectel-pppd.sh";

    private Config config;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    
    // Cached modem path (e.g., "/org/freedesktop/ModemManager1/Modem/0")
    private String modemPath = null;

    public Modem4GManagerImpl() {
        super(OpenemsComponent.ChannelId.values(), Modem4GManager.ChannelId.values());
    }

    @Activate
    void activate(ComponentContext context, Config config) {
        this.logInfo(this.log, "Activating 4G Modem Manager...");
        super.activate(context, config.id(), config.alias(), config.enabled());
        this.config = config;

        if (!config.enabled()) {
            return;
        }

        // Start monitoring task
        this.executor.scheduleWithFixedDelay(() -> {
            try {
                this.updateModemStatus();
            } catch (Exception e) {
                this.logWarn(this.log, "Error in modem monitor: " + e.getMessage());
            }
        }, 1, 10, TimeUnit.SECONDS);

        // Apply initial configuration
        this.executor.schedule(() -> {
            try {
                this.applyConfiguration();
            } catch (Exception e) {
                this.logError(this.log, "Failed to configure modem: " + e.getMessage());
            }
        }, 5, TimeUnit.SECONDS);

        this.logInfo(this.log, "4G Modem Manager activated. APN: " + config.apn());
    }

    @Modified
    void modified(ComponentContext context, Config config) {
        super.modified(context, config.id(), config.alias(), config.enabled());
        this.config = config;
        
        // Re-apply configuration on change
        this.executor.submit(() -> {
            try {
                this.applyConfiguration();
            } catch (Exception e) {
                this.logError(this.log, "Failed to reconfigure modem: " + e.getMessage());
            }
        });
    }

    @Deactivate
    protected void deactivate() {
        this.executor.shutdownNow();
        super.deactivate();
    }

    @Override
    public void buildJsonApiRoutes(JsonApiBuilder builder) {
        // Get modem status
        builder.handleRequest(new GetModemStatus(), endpoint -> {
            endpoint.setDescription("Gets current 4G modem status.");
            endpoint.setGuards(EdgeGuards.roleIsAtleast(Role.GUEST));
        }, call -> {
            return this.handleGetModemStatus();
        });

        // Get modem configuration
        builder.handleRequest(new GetModemConfig(), endpoint -> {
            endpoint.setDescription("Gets current 4G modem APN configuration.");
            endpoint.setGuards(EdgeGuards.roleIsAtleast(Role.OWNER));
        }, call -> {
            return this.handleGetModemConfig();
        });

        // Set modem configuration
        builder.handleRequest(new SetModemConfig(), endpoint -> {
            endpoint.setDescription("Sets 4G modem APN configuration.");
            endpoint.setGuards(EdgeGuards.roleIsAtleast(Role.ADMIN));
        }, call -> {
            this.handleSetModemConfig(call.getRequest());
            return EmptyObject.INSTANCE;
        });
    }

    // ==================== Status Monitoring ====================

    /**
     * Updates modem status channels using mmcli.
     */
    private void updateModemStatus() {
        try {
            // 1. Detect modem
            if (this.modemPath == null) {
                this.detectModem();
            }

            if (this.modemPath == null) {
                this.channel(Modem4GManager.ChannelId.MODEM_STATUS).setNextValue(ModemStatus.NO_MODEM);
                this.channel(Modem4GManager.ChannelId.NO_MODEM_DETECTED).setNextValue(true);
                return;
            }

            this.channel(Modem4GManager.ChannelId.NO_MODEM_DETECTED).setNextValue(false);

            // 2. Get modem info using mmcli
            String modemInfo = executeCommand("sudo mmcli -m 0 --output-keyvalue");
            
            // Parse signal strength
            int signalPercent = parseSignalStrength(modemInfo);
            this.channel(Modem4GManager.ChannelId.SIGNAL_STRENGTH).setNextValue(signalPercent);

            // Parse operator
            String operator = parseValue(modemInfo, "modem.3gpp.operator-name");
            this.channel(Modem4GManager.ChannelId.OPERATOR_NAME).setNextValue(operator);

            // Parse connection type
            String accessTech = parseValue(modemInfo, "modem.generic.access-technologies");
            this.channel(Modem4GManager.ChannelId.CONNECTION_TYPE).setNextValue(accessTech);

            // Parse model
            String model = parseValue(modemInfo, "modem.generic.model");
            this.channel(Modem4GManager.ChannelId.MODEM_MODEL).setNextValue(model);

            // Parse IMEI
            String imei = parseValue(modemInfo, "modem.3gpp.imei");
            this.channel(Modem4GManager.ChannelId.IMEI).setNextValue(imei);

            // Parse state
            String state = parseValue(modemInfo, "modem.generic.state");
            ModemStatus status = mapModemState(state);
            this.channel(Modem4GManager.ChannelId.MODEM_STATUS).setNextValue(status.name());

            // 3. Get IP address from ppp0 if connected
            if (status == ModemStatus.CONNECTED) {
                String ip = getPppIpAddress();
                this.channel(Modem4GManager.ChannelId.IP_ADDRESS).setNextValue(ip);
            } else {
                this.channel(Modem4GManager.ChannelId.IP_ADDRESS).setNextValue("Not connected");
            }

            // 4. Current APN
            this.channel(Modem4GManager.ChannelId.CURRENT_APN).setNextValue(this.config.apn());

        } catch (Exception e) {
            this.channel(Modem4GManager.ChannelId.LAST_ERROR).setNextValue(e.getMessage());
            this.logWarn(this.log, "Modem status update failed: " + e.getMessage());
        }
    }

    /**
     * Detects modem using mmcli -L.
     */
    private void detectModem() {
        try {
            String output = executeCommand("sudo mmcli -L");
            // Parse output like: "/org/freedesktop/ModemManager1/Modem/0 [Quectel] EG25"
            if (output.contains("/org/freedesktop/ModemManager1/Modem/")) {
                Pattern pattern = Pattern.compile("(/org/freedesktop/ModemManager1/Modem/\\d+)");
                Matcher matcher = pattern.matcher(output);
                if (matcher.find()) {
                    this.modemPath = matcher.group(1);
                    this.logInfo(this.log, "Modem detected: " + this.modemPath);
                }
            } else {
                this.modemPath = null;
                this.logWarn(this.log, "No modem detected");
            }
        } catch (Exception e) {
            this.modemPath = null;
            this.logWarn(this.log, "Modem detection failed: " + e.getMessage());
        }
    }

    // ==================== APN Configuration ====================

    /**
     * Applies APN configuration by editing quectel-pppd.sh.
     */
    private void applyConfiguration() throws Exception {
        String apn = this.config.apn();
        String username = this.config.username();
        String password = this.config.password();

        this.logInfo(this.log, "Applying 4G configuration: APN=" + apn);

        // Check if script exists
        String checkScript = executeCommand("test -f " + SCRIPT_PATH + " && echo 'exists' || echo 'not found'");
        if (!checkScript.contains("exists")) {
            throw new IOException("PPP script not found: " + SCRIPT_PATH);
        }

        // Update QL_APN, QL_USER, QL_PASSWORD in the script
        executeCommand("sudo sed -i 's/^QL_APN=.*/QL_APN=" + escapeForSed(apn) + "/' " + SCRIPT_PATH);
        executeCommand("sudo sed -i 's/^QL_USER=.*/QL_USER=" + escapeForSed(username) + "/' " + SCRIPT_PATH);
        executeCommand("sudo sed -i 's/^QL_PASSWORD=.*/QL_PASSWORD=" + escapeForSed(password) + "/' " + SCRIPT_PATH);

        this.logInfo(this.log, "Configuration updated. Restarting PPP connection...");

        // Restart PPP connection
        restartPppConnection();

        // Wait and refresh status
        Thread.sleep(5000);
        this.updateModemStatus();
    }

    /**
     * Restarts PPP connection.
     */
    private void restartPppConnection() throws Exception {
        // Kill existing pppd process (ignore error if not running)
        try {
            executeCommand("sudo killall pppd");
            Thread.sleep(1000);
        } catch (Exception e) {
            // pppd may not be running, ignore
        }

        // Start new connection
        executeCommand("sudo " + SCRIPT_PATH + " &");
        this.logInfo(this.log, "PPP connection started");
    }

    /**
     * Escapes special characters for sed command.
     */
    private String escapeForSed(String value) {
        if (value == null) {
            return "";
        }
        // Escape special sed characters: / \ & 
        return value
                .replace("\\", "\\\\")
                .replace("/", "\\/")
                .replace("&", "\\&");
    }

    // ==================== JSON-RPC Handlers ====================

    private GetModemStatus.Response handleGetModemStatus() {
        return new GetModemStatus.Response(
                getChannelString(Modem4GManager.ChannelId.MODEM_STATUS),
                getChannelInt(Modem4GManager.ChannelId.SIGNAL_STRENGTH),
                getChannelString(Modem4GManager.ChannelId.OPERATOR_NAME),
                getChannelString(Modem4GManager.ChannelId.IP_ADDRESS),
                getChannelString(Modem4GManager.ChannelId.CONNECTION_TYPE),
                getChannelString(Modem4GManager.ChannelId.MODEM_MODEL),
                getChannelString(Modem4GManager.ChannelId.IMEI),
                getChannelString(Modem4GManager.ChannelId.LAST_ERROR));
    }

    private GetModemConfig.Response handleGetModemConfig() {
        return new GetModemConfig.Response(
                this.config.apn(),
                this.config.username(),
                this.config.password());
    }

    private void handleSetModemConfig(SetModemConfig.Request request) throws OpenemsException {
        this.logInfo(this.log, "SetModemConfig received: APN=" + request.apn());
        
        try {
            // Apply configuration directly (update script and restart)
            String apn = request.apn();
            String username = request.username();
            String password = request.password();

            // Update the script
            executeCommand("sudo sed -i 's/^QL_APN=.*/QL_APN=" + escapeForSed(apn) + "/' " + SCRIPT_PATH);
            executeCommand("sudo sed -i 's/^QL_USER=.*/QL_USER=" + escapeForSed(username) + "/' " + SCRIPT_PATH);
            executeCommand("sudo sed -i 's/^QL_PASSWORD=.*/QL_PASSWORD=" + escapeForSed(password) + "/' " + SCRIPT_PATH);

            // Restart connection
            this.restartPppConnection();
            
            this.updateModemStatus();
        } catch (Exception e) {
            throw new OpenemsException("Failed to set modem config: " + e.getMessage());
        }
    }

    // ==================== Helper Methods ====================

    private String getChannelString(Modem4GManager.ChannelId channelId) {
        Object val = this.channel(channelId).value().get();
        return val != null ? val.toString() : "";
    }

    private int getChannelInt(Modem4GManager.ChannelId channelId) {
        Object val = this.channel(channelId).value().get();
        return val instanceof Number ? ((Number) val).intValue() : 0;
    }

    private int parseSignalStrength(String modemInfo) {
        // Parse "modem.generic.signal-quality.value : 75"
        String value = parseValue(modemInfo, "modem.generic.signal-quality.value");
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String parseValue(String output, String key) {
        for (String line : output.split("\n")) {
            if (line.contains(key)) {
                String[] parts = line.split(":", 2);
                if (parts.length > 1) {
                    return parts[1].trim();
                }
            }
        }
        return "";
    }

    private ModemStatus mapModemState(String state) {
        if (state == null || state.isEmpty()) {
            return ModemStatus.NO_MODEM;
        }
        state = state.toLowerCase();
        if (state.contains("connected")) {
            return ModemStatus.CONNECTED;
        } else if (state.contains("connecting") || state.contains("searching")) {
            return ModemStatus.CONNECTING;
        } else if (state.contains("disabled") || state.contains("failed")) {
            return ModemStatus.ERROR;
        }
        return ModemStatus.DISCONNECTED;
    }

    /**
     * Gets IP address from ppp0 interface.
     */
    private String getPppIpAddress() {
        try {
            String output = executeCommand("ip addr show ppp0 2>/dev/null | grep 'inet ' | awk '{print $2}'");
            if (!output.isEmpty()) {
                // Remove CIDR notation if present
                if (output.contains("/")) {
                    output = output.split("/")[0];
                }
                return output.trim();
            }
        } catch (Exception e) {
            // Ignore
        }
        return "";
    }

    private String executeCommand(String command) throws IOException, InterruptedException {
        this.log.debug("Executing: {}", command);
        ProcessBuilder builder = new ProcessBuilder("bash", "-c", command);
        Process process = builder.start();

        boolean finished = process.waitFor(15, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Command timed out: " + command);
        }

        // Read output
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }

        // Read error if exit code is non-zero
        if (process.exitValue() != 0) {
            BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            StringBuilder errSb = new StringBuilder();
            while ((line = errReader.readLine()) != null) {
                errSb.append(line).append("\n");
            }
            this.log.warn("Command failed: {} - {}", command, errSb.toString().trim());
        }

        return sb.toString().trim();
    }
}
