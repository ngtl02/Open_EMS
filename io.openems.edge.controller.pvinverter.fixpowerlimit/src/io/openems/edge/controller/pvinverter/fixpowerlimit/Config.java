package io.openems.edge.controller.pvinverter.fixpowerlimit;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(//
        name = "Controller PV-Inverter Fix Power Limit", //
        description = "Controls active power of all PV inverters. Reads setpoints from EVN controller or uses fixed values.")
@interface Config {

    @AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
    String id() default "ctrlPvInverterFixPowerLimit0";

    @AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
    String alias() default "";

    @AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
    boolean enabled() default true;


    @AttributeDefinition(name = "Allow EVN Control", description = "If true, allow EVN to control when enabled. If false, always use local control.")
    boolean allowEvnControl() default true;

    @AttributeDefinition(name = "Use Percentage (Local)", description = "If true, use percentage limit when local control")
    boolean usePercentage() default false;

    @AttributeDefinition(name = "Power Limit [W] (Local)", description = "Fixed power limit in Watts when local control (EVN disabled)")
    int powerLimit() default 0;

    @AttributeDefinition(name = "Power Limit [%] (Local)", description = "Fixed power limit as percentage 0-100 when local control")
    int powerLimitPercent() default 100;

    String webconsole_configurationFactory_nameHint() default "Controller PV-Inverter Fix Power Limit [{id}]";
}