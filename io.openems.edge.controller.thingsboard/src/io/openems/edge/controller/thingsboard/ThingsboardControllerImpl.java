package io.openems.edge.controller.thingsboard;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.Unit;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.controller.api.Controller;

import org.eclipse.paho.mqttv5.client.IMqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;

@Designate(ocd = Config.class, factory = true)
@Component(name = "Controller.IoT.AT-Energy", immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE)
public class ThingsboardControllerImpl extends AbstractOpenemsComponent implements Controller, OpenemsComponent {

    private final Logger log = LoggerFactory.getLogger(ThingsboardControllerImpl.class);

    // Single Device Mode - gửi trực tiếp đến device, không qua Gateway
    private static final String TB_TOPIC_TELEMETRY = "v1/devices/me/telemetry";

    @Reference
    protected ComponentManager componentManager;

    private Config config;

    private IMqttAsyncClient mqttClient;

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
            // Auto-detect các component PvInverter và Meter
            List<OpenemsComponent> targetComponents = this.getTargetComponents();
            if (targetComponents.isEmpty()) {
                return;
            }

            long timestamp = System.currentTimeMillis();
            JsonObject values = new JsonObject();
            boolean hasData = false;

            for (OpenemsComponent component : targetComponents) {
                String componentPrefix = component.id() + "/";

                for (Channel<?> channel : component.channels()) {
                    // Bỏ qua các channel WRITE_ONLY - chỉ gửi channel có thể đọc
                    AccessMode accessMode = channel.channelId().doc().getAccessMode();
                    if (accessMode == AccessMode.WRITE_ONLY) {
                        continue;
                    }

                    Object rawValue = channel.value().get();
                    if (rawValue == null) {
                        continue;
                    }

                    // Lấy unit và chuyển đổi giá trị
                    Unit unit = channel.channelId().doc().getUnit();
                    Object convertedValue = convertUnitValue(rawValue, unit);

                    if (convertedValue != null) {
                        String key = componentPrefix + channel.channelId().id();
                        if (convertedValue instanceof Number) {
                            values.addProperty(key, (Number) convertedValue);
                        } else if (convertedValue instanceof Boolean) {
                            values.addProperty(key, (Boolean) convertedValue);
                        } else {
                            values.addProperty(key, convertedValue.toString());
                        }
                        hasData = true;
                    }
                }
            }

            if (hasData) {
                // Format cho Single Device: {"ts": ..., "values": {...}}
                JsonObject payload = new JsonObject();
                payload.addProperty("ts", timestamp);
                payload.add("values", values);

                this.publish(TB_TOPIC_TELEMETRY, payload.toString());
            }

        } catch (Exception e) {
            this.logError(this.log, "Run Error: " + e.getMessage());
        }
    }

    /**
     * Tự động phát hiện các component PvInverter và Meter đang hoạt động.
     * Lọc theo component ID pattern: bắt đầu bằng "meter" hoặc "pvInverter" (không phân biệt hoa thường).
     * Được gọi mỗi chu kỳ để hỗ trợ runtime detection.
     * 
     * @return danh sách các component cần đẩy dữ liệu
     */
    private List<OpenemsComponent> getTargetComponents() {
        List<OpenemsComponent> result = new ArrayList<>();
        
        for (OpenemsComponent component : this.componentManager.getEnabledComponents()) {
            String id = component.id().toLowerCase();
            // Lọc: component ID bắt đầu bằng "meter", "pvinverter" hoặc "smartlogger"
            if (id.startsWith("meter") || id.startsWith("pvinverter") || id.startsWith("smartlogger")) {
                result.add(component);
            }
        }
        
        return result;
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

    /**
     * Chuyển đổi giá trị theo unit chuẩn hóa.
     * - mA, mV, mW... → A, V, W (chia 1000)
     * - W, var, VA, Wh, varh, VAh → kW, kvar, kVA, kWh, kvarh, kVAh (chia 1000)
     * 
     * @param value giá trị gốc
     * @param unit  đơn vị của giá trị
     * @return giá trị đã chuyển đổi
     */
    private Object convertUnitValue(Object value, Unit unit) {
        if (value == null || unit == null) {
            return value;
        }

        // Nếu không phải số, trả về nguyên giá trị
        if (!(value instanceof Number)) {
            return value;
        }

        double numValue = ((Number) value).doubleValue();

        // Chuyển đổi các unit milli → base (mA→A, mV→V, mW→W, mHz→Hz, mbar→bar, mOhm→Ohm)
        switch (unit) {
            case MILLIAMPERE:
            case MILLIVOLT:
            case MILLIWATT:
            case MILLIHERTZ:
            case MILLIBAR:
            case MILLIOHM:
            case MILLIAMPERE_HOURS:
            case MILLISECONDS:
                return numValue / 1000.0;

            // Chuyển đổi các unit micro → base (uA→A, uV→V, uOhm→Ohm)
            case MICROAMPERE:
            case MICROVOLT:
            case MICROOHM:
                return numValue / 1000000.0;

            // Chuyển đổi các unit dezi → base (dA→A, dV→V, dC→C)
            case DEZIAMPERE:
            case DEZIVOLT:
            case DEZIDEGREE_CELSIUS:
                return numValue / 10.0;

            // Chuyển đổi W → kW, var → kvar, VA → kVA, Wh → kWh, etc.
            case WATT:
            case VOLT_AMPERE:
            case VOLT_AMPERE_REACTIVE:
            case WATT_HOURS:
            case VOLT_AMPERE_HOURS:
            case VOLT_AMPERE_REACTIVE_HOURS:
            case CUMULATED_WATT_HOURS:
            case AMPERE_HOURS:
            case OHM:
                return numValue / 1000.0;

            // Các unit đã là kilo-level hoặc không cần chuyển đổi
            case KILOWATT:
            case KILOVOLT_AMPERE:
            case KILOVOLT_AMPERE_REACTIVE:
            case KILOWATT_HOURS:
            case KILOVOLT_AMPERE_REACTIVE_HOURS:
            case KILOAMPERE_HOURS:
            case KILOOHM:
            case VOLT:
            case AMPERE:
            case HERTZ:
            case DEGREE_CELSIUS:
            case PERCENT:
            case BAR:
            case SECONDS:
            case MINUTE:
            case HOUR:
            case NONE:
            case ON_OFF:
            case THOUSANDTH:
            case TENTHOUSANDTH:
            case DECIMAL_DEGREE:
            case MONEY_PER_MEGAWATT_HOUR:
            case WATT_HOURS_BY_WATT_PEAK:
            case CUMULATED_SECONDS:
            case GRAMS_PER_CUBIC_METER:
            case PARTS_PER_MILLION:
            case KILOJOULES_PER_KILOGRAM:
            default:
                return value;
        }
    }

    private void connectMqtt() {
        this.disconnectMqtt();
        try {
            String brokerUrl = "tcp://" + this.config.host() + ":" + this.config.port();
            String clientId = "OpenEMS_" + this.id();

            // Sử dụng MemoryPersistence thay vì MqttDefaultFilePersistence
            // Điều này tránh lỗi permission trên ARM Linux
            this.mqttClient = new MqttAsyncClient(brokerUrl, clientId, new MemoryPersistence());

            var options = new MqttConnectionOptions();
            options.setUserName(this.config.accessToken());
            // ThingsBoard dùng accessToken làm username, password để trống
            options.setPassword("".getBytes(StandardCharsets.UTF_8));
            options.setAutomaticReconnect(false); // Tự xử lý reconnect
            options.setCleanStart(true);
            options.setConnectionTimeout(30);

            // Kết nối async nhưng đợi hoàn thành với timeout
            this.mqttClient.connect(options).waitForCompletion(30000);
            this.logInfo(this.log, "Connected to ThingsBoard (Single Device Mode)");

        } catch (MqttException e) {
            // Log chi tiết hơn để debug trên ARM Linux
            this.logError(this.log, "MQTT Connect Error [RC:" + e.getReasonCode() + "]: " + e.getMessage());
            
            // Log root cause nếu có
            Throwable cause = e.getCause();
            if (cause != null) {
                this.logError(this.log, "MQTT Root Cause: " + cause.getClass().getName() + " - " + cause.getMessage());
                
                // Check nested causes
                Throwable nested = cause.getCause();
                if (nested != null) {
                    this.logError(this.log, "MQTT Nested Cause: " + nested.getClass().getName() + " - " + nested.getMessage());
                }
            }
            
            // Log stack trace đầy đủ
            this.log.error("MQTT Connection failed - Full stack trace:", e);
        } catch (Exception e) {
            // Catch các exception khác không phải MqttException
            this.logError(this.log, "MQTT Unexpected Error: " + e.getClass().getName() + " - " + e.getMessage());
            this.log.error("Unexpected error during MQTT connection:", e);
        }
    }

    private void disconnectMqtt() {
        try {
            if (this.mqttClient != null) {
                if (this.mqttClient.isConnected()) {
                    this.mqttClient.disconnect().waitForCompletion(5000);
                }
                this.mqttClient.close();
            }
        } catch (MqttException e) {
            // Ignored
        }
        this.mqttClient = null;
    }
}