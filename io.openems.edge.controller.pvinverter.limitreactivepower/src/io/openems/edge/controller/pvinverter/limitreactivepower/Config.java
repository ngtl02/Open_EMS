package io.openems.edge.controller.pvinverter.limitreactivepower;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(//
        name = "Controller PV-Inverter Limit Reactive Power", //
        description = "Defines a fixed reactive power limitation to PV inverter. Supports both absolute (var) and percentage (%) modes.")
@interface Config {

    @AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
    String id() default "ctrlPvInverterLimitReactivePower0";

    @AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
    String alias() default "";

    @AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
    boolean enabled() default true;

    @AttributeDefinition(name = "PV-Inverter-ID", description = "ID of PV-Inverter device.")
    String pvInverter_id();

    @AttributeDefinition(name = "Use Percentage?", description = "If true, use percentage mode; if false, use absolute var mode")
    boolean usePercent() default false;

    @AttributeDefinition(name = "Reactive Power Limit [var]", description = "Reactive power limit in var (used when 'Use Percentage' is false)")
    int reactivePowerLimit() default 0;

    @AttributeDefinition(name = "Reactive Power Limit [%]", description = "Reactive power limit as percentage of max power (0-100, used when 'Use Percentage' is true)")
    int reactivePowerLimitPercent() default 100;

    String webconsole_configurationFactory_nameHint() default "Controller PV-Inverter Limit Reactive Power [{id}]";
}
