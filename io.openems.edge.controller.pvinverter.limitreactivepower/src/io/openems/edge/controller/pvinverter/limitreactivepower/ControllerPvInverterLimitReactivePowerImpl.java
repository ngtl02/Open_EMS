package io.openems.edge.controller.pvinverter.limitreactivepower;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.controller.api.Controller;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;

@Designate(ocd = Config.class, factory = true)
@Component(//
        name = "Controller.PvInverter.LimitReactivePower", //
        immediate = true, //
        configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class ControllerPvInverterLimitReactivePowerImpl extends AbstractOpenemsComponent
        implements ControllerPvInverterLimitReactivePower, Controller, OpenemsComponent {

    private final Logger log = LoggerFactory.getLogger(ControllerPvInverterLimitReactivePowerImpl.class);

    @Reference
    private ComponentManager componentManager;

    private String pvInverterId;
    private boolean usePercent = false;
    private int reactivePowerLimit = 0;
    private int reactivePowerLimitPercent = 100;

    public ControllerPvInverterLimitReactivePowerImpl() {
        super(//
                OpenemsComponent.ChannelId.values(), //
                Controller.ChannelId.values(), //
                ControllerPvInverterLimitReactivePower.ChannelId.values() //
        );
    }

    @Activate
    private void activate(ComponentContext context, Config config) {
        super.activate(context, config.id(), config.alias(), config.enabled());

        this.pvInverterId = config.pvInverter_id();
        this.usePercent = config.usePercent();
        this.reactivePowerLimit = config.reactivePowerLimit();
        this.reactivePowerLimitPercent = config.reactivePowerLimitPercent();
    }

    @Override
    @Deactivate
    protected void deactivate() {
        // Reset limit
        ManagedSymmetricPvInverter pvInverter;
        try {
            pvInverter = this.componentManager.getComponent(this.pvInverterId);
            pvInverter.setReactivePowerLimit(null);
            pvInverter.setReactivePowerLimitPercent(null);
        } catch (OpenemsNamedException e) {
            this.logError(this.log, e.getMessage());
        }

        super.deactivate();
    }

    @Override
    public void run() throws OpenemsNamedException {
        ManagedSymmetricPvInverter pvInverter = this.componentManager.getComponent(this.pvInverterId);

        if (this.usePercent) {
            // Use percentage mode
            this.logInfo(this.log, "Setting Q limit: " + this.reactivePowerLimitPercent + " %");
            pvInverter.setReactivePowerLimitPercent(this.reactivePowerLimitPercent);
        } else {
            // Use absolute var mode
            this.logInfo(this.log, "Setting Q limit: " + this.reactivePowerLimit + " var");
            pvInverter.setReactivePowerLimit(this.reactivePowerLimit);
        }
    }

}
