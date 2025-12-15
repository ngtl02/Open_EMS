package io.openems.edge.controller.pvinverter.limitreactivepower;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(//
        name = "Controller PV-Inverter Limit Reactive Power", //
        description = "Controls reactive power of all PV inverters. Reads setpoints from EVN controller or uses fixed values.")
@interface Config {

    @AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
    String id() default "ctrlPvInverterLimitReactivePower0";

    @AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
    String alias() default "";

    @AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
    boolean enabled() default true;


    @AttributeDefinition(name = "Allow EVN Control", description = "If true, allow EVN to control when enabled. If false, always use local control.")
    boolean allowEvnControl() default true;

    @AttributeDefinition(name = "Use Percentage (Local)", description = "Use percentage mode when local control")
    boolean usePercent() default false;

    @AttributeDefinition(name = "Reactive Power Limit [var] (Local)", description = "Reactive power limit in var when local control")
    int reactivePowerLimit() default 0;

    @AttributeDefinition(name = "Reactive Power Limit [%] (Local)", description = "Reactive power limit as percentage when local control")
    int reactivePowerLimitPercent() default 100;

    String webconsole_configurationFactory_nameHint() default "Controller PV-Inverter Limit Reactive Power [{id}]";
}
