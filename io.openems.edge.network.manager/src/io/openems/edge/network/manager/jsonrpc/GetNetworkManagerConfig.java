package io.openems.edge.network.manager.jsonrpc;

import static io.openems.common.jsonrpc.serialization.JsonSerializerUtil.jsonObjectSerializer;

import io.openems.common.jsonrpc.serialization.EmptyObject;
import io.openems.common.jsonrpc.serialization.EndpointRequestType;
import io.openems.common.jsonrpc.serialization.JsonSerializer;
import io.openems.common.utils.JsonUtils;
import io.openems.edge.network.manager.Mode;
import io.openems.edge.network.manager.jsonrpc.GetNetworkManagerConfig.Response;

/**
 * Gets the current network manager configuration.
 * 
 * <pre>
 * {
 *   "jsonrpc": "2.0",
 *   "id": "UUID",
 *   "method": "getNetworkManagerConfig",
 *   "params": {}
 * }
 * </pre>
 */
public class GetNetworkManagerConfig implements EndpointRequestType<EmptyObject, Response> {

    public static final String METHOD = "getNetworkManagerConfig";

    @Override
    public String getMethod() {
        return METHOD;
    }

    @Override
    public JsonSerializer<EmptyObject> getRequestSerializer() {
        return EmptyObject.serializer();
    }

    @Override
    public JsonSerializer<Response> getResponseSerializer() {
        return Response.serializer();
    }

    public record InterfaceConfig(
            String mode,
            String ip,
            String subnetMask,
            String gateway,
            String dnsPrimary,
            String dnsSecondary) {
        public static JsonSerializer<InterfaceConfig> serializer() {
            return jsonObjectSerializer(InterfaceConfig.class,
                    json -> new InterfaceConfig(
                            json.getString("mode"),
                            json.getOptionalString("ip").orElse(null),
                            json.getOptionalString("subnetMask").orElse(null),
                            json.getOptionalString("gateway").orElse(null),
                            json.getOptionalString("dnsPrimary").orElse(null),
                            json.getOptionalString("dnsSecondary").orElse(null)),
                    obj -> JsonUtils.buildJsonObject()
                            .addProperty("mode", obj.mode())
                            .addPropertyIfNotNull("ip", obj.ip())
                            .addPropertyIfNotNull("subnetMask", obj.subnetMask())
                            .addPropertyIfNotNull("gateway", obj.gateway())
                            .addPropertyIfNotNull("dnsPrimary", obj.dnsPrimary())
                            .addPropertyIfNotNull("dnsSecondary", obj.dnsSecondary())
                            .build());
        }
    }

    public record Response(
            InterfaceConfig eth0,
            InterfaceConfig eth1) {
        public static JsonSerializer<Response> serializer() {
            return jsonObjectSerializer(Response.class,
                    json -> new Response(
                            json.getObject("eth0", InterfaceConfig.serializer()),
                            json.getObject("eth1", InterfaceConfig.serializer())),
                    obj -> JsonUtils.buildJsonObject()
                            .add("eth0", InterfaceConfig.serializer().serialize(obj.eth0()))
                            .add("eth1", InterfaceConfig.serializer().serialize(obj.eth1()))
                            .build());
        }
    }
}
