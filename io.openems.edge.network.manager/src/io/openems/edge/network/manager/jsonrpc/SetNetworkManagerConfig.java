package io.openems.edge.network.manager.jsonrpc;

import static io.openems.common.jsonrpc.serialization.JsonSerializerUtil.jsonObjectSerializer;

import io.openems.common.jsonrpc.serialization.EmptyObject;
import io.openems.common.jsonrpc.serialization.EndpointRequestType;
import io.openems.common.jsonrpc.serialization.JsonSerializer;
import io.openems.common.utils.JsonUtils;
import io.openems.edge.network.manager.Mode;
import io.openems.edge.network.manager.jsonrpc.SetNetworkManagerConfig.Request;

/**
 * Updates the network manager configuration.
 * 
 * <pre>
 * {
 *   "jsonrpc": "2.0",
 *   "id": "UUID",
 *   "method": "setNetworkManagerConfig",
 *   "params": {
 *     "interface": "eth0" | "eth1",
 *     "mode": "NONE" | "DHCP" | "STATIC",
 *     "ip": "192.168.1.50",
 *     "subnetMask": "255.255.255.0",
 *     "gateway": "192.168.1.1",
 *     "dnsPrimary": "8.8.8.8",
 *     "dnsSecondary": "8.8.4.4"
 *   }
 * }
 * </pre>
 */
public class SetNetworkManagerConfig implements EndpointRequestType<Request, EmptyObject> {

    public static final String METHOD = "setNetworkManagerConfig";

    @Override
    public String getMethod() {
        return METHOD;
    }

    @Override
    public JsonSerializer<Request> getRequestSerializer() {
        return Request.serializer();
    }

    @Override
    public JsonSerializer<EmptyObject> getResponseSerializer() {
        return EmptyObject.serializer();
    }

    public record Request(
            String interfaceName,
            String mode,
            String ip,
            String subnetMask,
            String gateway,
            String dnsPrimary,
            String dnsSecondary) {
        public static JsonSerializer<Request> serializer() {
            return jsonObjectSerializer(Request.class,
                    json -> new Request(
                            json.getString("interface"),
                            json.getString("mode"),
                            json.getOptionalString("ip").orElse(null),
                            json.getOptionalString("subnetMask").orElse(null),
                            json.getOptionalString("gateway").orElse(null),
                            json.getOptionalString("dnsPrimary").orElse(null),
                            json.getOptionalString("dnsSecondary").orElse(null)),
                    obj -> JsonUtils.buildJsonObject()
                            .addProperty("interface", obj.interfaceName())
                            .addProperty("mode", obj.mode())
                            .addPropertyIfNotNull("ip", obj.ip())
                            .addPropertyIfNotNull("subnetMask", obj.subnetMask())
                            .addPropertyIfNotNull("gateway", obj.gateway())
                            .addPropertyIfNotNull("dnsPrimary", obj.dnsPrimary())
                            .addPropertyIfNotNull("dnsSecondary", obj.dnsSecondary())
                            .build());
        }

        /**
         * Parses the mode string to Mode enum.
         * 
         * @return the Mode enum value
         */
        public Mode getMode() {
            try {
                return Mode.valueOf(this.mode.toUpperCase());
            } catch (IllegalArgumentException e) {
                return Mode.NONE;
            }
        }
    }
}
