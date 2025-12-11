package io.openems.edge.controller.thingsboard;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(
    name = "Controller ThingsBoard Gateway",
    description = "Đẩy dữ liệu OpenEMS lên ThingsBoard Cloud qua MQTT (Gateway Mode)")
@interface Config {

    @AttributeDefinition(name = "Component-ID", description = "Unique ID của component này")
    String id() default "ctrlThingsboard0";

    @AttributeDefinition(name = "Alias", description = "Tên gợi nhớ")
    String alias() default "";

    @AttributeDefinition(name = "Is Enabled?", description = "Bật/Tắt component")
    boolean enabled() default true;

    @AttributeDefinition(name = "ThingsBoard Host", description = "Địa chỉ Server")
    String host() default "thingsboard.cloud";

    @AttributeDefinition(name = "Port", description = "Cổng MQTT")
    int port() default 1883;

    @AttributeDefinition(name = "Access Token", description = "Token Gateway")
    String accessToken() default "";

    @AttributeDefinition(name = "Cycle Time", description = "Chu kỳ gửi dữ liệu (giây)")
    int cycleTime() default 5;

    // --- THÊM MỚI: DANH SÁCH THIẾT BỊ ---
    @AttributeDefinition(name = "Target Component-IDs", description = "Danh sách ID các thiết bị muốn đẩy lên Cloud (VD: meter0, ess0, _sum)")
    String[] component_ids() default { "_sum" };

    String webconsole_configurationFactory_nameHint() default "Controller ThingsBoard [{id}]";
}