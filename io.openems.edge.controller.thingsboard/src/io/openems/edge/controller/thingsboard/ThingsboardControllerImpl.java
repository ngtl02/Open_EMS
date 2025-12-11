package io.openems.edge.controller.thingsboard;

import java.nio.charset.StandardCharsets;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.openems.common.channel.AccessMode;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.controller.api.Controller;

import org.eclipse.paho.mqttv5.client.IMqttClient;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;

@Designate(ocd = Config.class, factory = true)
@Component(name = "Controller.ThingsBoard", immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE)
public class ThingsboardControllerImpl extends AbstractOpenemsComponent implements Controller, OpenemsComponent {

    private final Logger log = LoggerFactory.getLogger(ThingsboardControllerImpl.class);

    private static final String TB_TOPIC_TELEMETRY = "v1/gateway/telemetry";
    private static final String TB_TOPIC_CONNECT = "v1/gateway/connect";
    private static final String TB_TOPIC_DISCONNECT = "v1/gateway/disconnect";

    @Reference
    protected ComponentManager componentManager;

    private Config config;

    private IMqttClient mqttClient;

    private int cycleCount = 0;

    public ThingsboardControllerImpl() {
        super(
                OpenemsComponent.ChannelId.values(),
                Controller.ChannelId.values());
    }

    @Activate
    void activate(ComponentContext context, Config config) {
        super.activate(context, config.id(), config.alias(), config.enabled());
        this.config = config;
        this.connectMqtt();
    }

    @Deactivate
    protected void deactivate() {
        this.sendDisconnectToAll();
        this.disconnectMqtt();
        super.deactivate();
    }

    @Override
    public void run() throws OpenemsNamedException {
        if (!this.config.enabled())
            return;

        this.cycleCount++;
        if (this.cycleCount < this.config.cycleTime())
            return;
        this.cycleCount = 0;

        if (this.mqttClient == null || !this.mqttClient.isConnected()) {
            this.connectMqtt();
            return;
        }

        try {
            JsonObject finalPayload = new JsonObject();
            long timestamp = System.currentTimeMillis();

            String[] targetIds = this.config.component_ids();
            if (targetIds == null || targetIds.length == 0)
                return;

            for (String componentId : targetIds) {
                OpenemsComponent component = this.getComponentById(componentId);
                if (component == null)
                    continue;

                String deviceName = getDeviceName(component);
                JsonObject values = new JsonObject();
                boolean hasData = false;

                for (Channel<?> channel : component.channels()) {
                    // Bỏ qua các channel WRITE_ONLY - chỉ gửi channel có thể đọc
                    AccessMode accessMode = channel.channelId().doc().getAccessMode();
                    if (accessMode == AccessMode.WRITE_ONLY) {
                        continue;
                    }

                    JsonElement jsonValue = channel.value().asJson();
                    if (!jsonValue.isJsonNull()) {
                        values.add(channel.channelId().id(), jsonValue);
                        hasData = true;
                    }
                }

                if (hasData) {
                    JsonObject telemetryEntry = new JsonObject();
                    telemetryEntry.addProperty("ts", timestamp);
                    telemetryEntry.add("values", values);

                    JsonArray telemetryArray = new JsonArray();
                    telemetryArray.add(telemetryEntry);

                    finalPayload.add(deviceName, telemetryArray);
                }
            }

            if (finalPayload.size() > 0) {
                this.publish(TB_TOPIC_TELEMETRY, finalPayload.toString());
            }

        } catch (Exception e) {
            this.logError(this.log, "Run Error: " + e.getMessage());
        }
    }

    private void publish(String topic, String payload) {
        try {
            if (this.mqttClient != null && this.mqttClient.isConnected()) {
                MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
                message.setQos(1);
                message.setProperties(new MqttProperties());

                this.mqttClient.publish(topic, message);
            }
        } catch (MqttException e) {
            this.logWarn(this.log, "MQTT Publish Error code " + e.getReasonCode() + ": " + e.getMessage());
            this.disconnectMqtt();
        }
    }

    private OpenemsComponent getComponentById(String id) {
        try {
            return this.componentManager.getComponent(id);
        } catch (OpenemsNamedException e) {
            return null;
        }
    }

    private String getDeviceName(OpenemsComponent component) {
        String alias = component.alias();
        return (alias != null && !alias.trim().isEmpty()) ? alias.trim() : component.id();
    }

    private void sendConnectToAll() {
        if (this.config.component_ids() == null)
            return;
        try {
            for (String componentId : this.config.component_ids()) {
                OpenemsComponent component = getComponentById(componentId);
                if (component == null)
                    continue;

                JsonObject json = new JsonObject();
                json.addProperty("device", getDeviceName(component));
                this.publish(TB_TOPIC_CONNECT, json.toString());
            }
            this.logInfo(this.log, "Sent Connect signals.");
        } catch (Exception e) {
            // Ignored
        }
    }

    private void sendDisconnectToAll() {
        if (this.config.component_ids() == null)
            return;
        try {
            for (String componentId : this.config.component_ids()) {
                OpenemsComponent component = getComponentById(componentId);
                String deviceName = (component != null) ? getDeviceName(component) : componentId;

                JsonObject json = new JsonObject();
                json.addProperty("device", deviceName);
                this.publish(TB_TOPIC_DISCONNECT, json.toString());
            }
        } catch (Exception e) {
            // Ignored
        }
    }

    private void connectMqtt() {
        this.disconnectMqtt();
        try {
            String brokerUrl = "tcp://" + this.config.host() + ":" + this.config.port();
            String clientId = "OpenEMS_" + this.id();

            // Khởi tạo MqttClient KHÔNG dùng MemoryPersistence (giống MqttConnector)
            this.mqttClient = new MqttClient(brokerUrl, clientId);

            MqttConnectionOptions options = new MqttConnectionOptions();
            options.setCleanStart(true);
            options.setUserName(this.config.accessToken());
            options.setConnectionTimeout(10);
            options.setKeepAliveInterval(60);
            options.setAutomaticReconnect(true);

            this.mqttClient.connect(options);
            this.logInfo(this.log, "Connected to ThingsBoard Cloud (MQTT v5)!");

            this.sendConnectToAll();

        } catch (MqttException e) {
            this.logError(this.log, "MQTT Connect Error: " + e.getMessage());
        }
    }

    private void disconnectMqtt() {
        try {
            if (this.mqttClient != null) {
                if (this.mqttClient.isConnected()) {
                    this.mqttClient.disconnect();
                }
                this.mqttClient.close();
            }
        } catch (MqttException e) {
            // Ignored
        }
    }
}