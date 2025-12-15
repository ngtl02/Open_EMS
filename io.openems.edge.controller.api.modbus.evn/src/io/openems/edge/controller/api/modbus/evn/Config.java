package io.openems.edge.controller.api.modbus.evn;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(//
        name = "Controller Api Modbus/TCP EVN", //
        description = "This controller provides EVN-specific Modbus/TCP monitoring with fixed register mapping and remote power control. Automatically discovers all meters (meterX) and PV inverters (pvInverterX) from the system.")
@interface Config {

    @AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
    String id() default "ctrlEvnModbus0";

    @AttributeDefinition(name = "Alias", description = "Human-readable name of this Component")
    String alias() default "ModbusTcp EVN";

    @AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
    boolean enabled() default true;

    @AttributeDefinition(name = "Port", description = "Port on which the Modbus TCP server should listen.")
    int port() default 502;

    @AttributeDefinition(name = "Max concurrent connections", description = "Maximum number of concurrent Modbus connections.")
    int maxConcurrentConnections() default 5;

    @AttributeDefinition(name = "Redistribute on Fault", description = "Redistribute power to healthy inverters when one is faulted")
    boolean redistributeOnFault() default true;

    String webconsole_configurationFactory_nameHint() default "Controller Api Modbus/TCP EVN [{id}]";
}
