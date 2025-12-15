package io.openems.edge.controller.pvinverter.limitreactivepower;

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

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.controller.api.Controller;
import io.openems.edge.controller.api.modbus.evn.ControllerApiModbusEvn;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;

/**
 * Controller for Reactive Power control of all PV inverters.
 * 
 * <p>
 * This controller implements closed-loop control to make meter0's reactive power
 * match the target setpoint. The setpoint represents the desired grid reactive power.
 * 
 * <p>
 * Control Logic:
 * <ul>
 * <li>If meter0.reactivePower < setpoint: Need MORE Q → increase inverter Q limits</li>
 * <li>If meter0.reactivePower > setpoint: Need LESS Q → decrease inverter Q limits</li>
 * </ul>
 * 
 * <p>
 * Features:
 * <ul>
 * <li>Auto-discovers all PV inverters (pvInverterX)</li>
 * <li>Reads setpoints from EVN controller when Q_OUT_ENABLED = true</li>
 * <li>Implements closed-loop control using meter0 feedback</li>
 * <li>Falls back to local fixed values when EVN control is disabled</li>
 * <li>Distributes reactive power proportionally based on inverter max Q rating</li>
 * <li>Supports both positive (inductive) and negative (capacitive) reactive power</li>
 * </ul>
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
        name = "Controller.PvInverter.LimitReactivePower", //
        immediate = true, //
        configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class ControllerPvInverterLimitReactivePowerImpl extends AbstractOpenemsComponent
        implements ControllerPvInverterLimitReactivePower, Controller, OpenemsComponent {

    private final Logger log = LoggerFactory.getLogger(ControllerPvInverterLimitReactivePowerImpl.class);

    private static final String PVINVERTER_ID_PREFIX = "pvInverter";
    private static final String DEFAULT_METER_ID = "meter0";
    private static final String EVN_CONTROLLER_ID = "ctrlEvnModbus0";
    
    // Control parameters
    private static final float Q_GAIN = 0.8f;  // Proportional gain for closed-loop
    private static final int DEADBAND_VAR = 50; // Deadband in var

    @Reference
    private ComponentManager componentManager;

    private Config config;

    // Discovered inverters
    private List<ManagedSymmetricPvInverter> inverters = new ArrayList<>();
    
    // Auto-calculated total system reactive power capacity
    private int totalSystemPowerVar = 0;

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
        this.config = config;

        // Discover all PV inverters
        this.discoverInverters();

        this.logInfo(this.log, "Activated with EVN controller: " + EVN_CONTROLLER_ID
                + ", Found " + this.inverters.size() + " inverters"
                + ", Total system Q power: " + this.totalSystemPowerVar + "var");
    }

    private void discoverInverters() {
        this.inverters.clear();

        for (OpenemsComponent comp : this.componentManager.getAllComponents()) {
            if (comp.id().startsWith(PVINVERTER_ID_PREFIX)
                    && comp instanceof ManagedSymmetricPvInverter
                    && comp.isEnabled()) {
                this.inverters.add((ManagedSymmetricPvInverter) comp);
            }
        }

        // Sort by ID
        this.inverters.sort((a, b) -> a.id().compareTo(b.id()));
        
        // Calculate total system reactive power from inverters
        this.totalSystemPowerVar = this.inverters.stream()
                .mapToInt(inv -> inv.getMaxReactivePower().orElse(0))
                .sum();
        
        // If no max reactive power defined, estimate as 50% of apparent power
        if (this.totalSystemPowerVar == 0) {
            this.totalSystemPowerVar = this.inverters.stream()
                    .mapToInt(inv -> (int)(inv.getMaxApparentPower().orElse(0) * 0.5))
                    .sum();
        }
    }

    @Override
    @Deactivate
    protected void deactivate() {
        // Reset all inverter limits (remove limits)
        for (ManagedSymmetricPvInverter inv : this.inverters) {
            try {
                inv.setReactivePowerLimit(null);
                inv.setReactivePowerLimitPercent(null);
            } catch (Exception e) {
                this.logError(this.log, "Error resetting inverter " + inv.id() + ": " + e.getMessage());
            }
        }
        super.deactivate();
    }

    @Override
    public void run() throws OpenemsNamedException {
        // Refresh inverters occasionally
        if (System.currentTimeMillis() % 30000 < 1000) {
            this.discoverInverters();
        }

        if (this.inverters.isEmpty()) {
            this.logWarn(this.log, "No PV inverters found");
            return;
        }

        // Determine target setpoint
        int targetGridPowerVar = 0;
        int targetPercent = Integer.MIN_VALUE; // Use MIN_VALUE to indicate not using percent mode
        boolean evnEnabled = false;

        // Check EVN control
        if (this.config.allowEvnControl()) {
            try {
                ControllerApiModbusEvn evn = this.componentManager.getComponent(EVN_CONTROLLER_ID);
                evnEnabled = evn.getQOutEnabled().orElse(false);

                if (evnEnabled) {
                    float evnPercent = evn.getQOutSetpointPercent().orElse(0f);
                    int evnVar = evn.getQOutSetpointVar().orElse(0);

                    if (evnPercent != 0) {
                        // Percentage mode from EVN (can be negative for capacitive)
                        targetPercent = (int) evnPercent;
                    } else {
                        // Var mode from EVN - use closed-loop control
                        targetGridPowerVar = evnVar;
                    }
                }
            } catch (OpenemsNamedException e) {
                this.logDebug(this.log, "EVN controller not found, using local control");
            }
        }

        // Local control mode
        if (!evnEnabled) {
            if (this.config.usePercent()) {
                targetPercent = this.config.reactivePowerLimitPercent();
            } else {
                targetGridPowerVar = this.config.reactivePowerLimit();
            }
        }

        // Apply control
        if (targetPercent != Integer.MIN_VALUE) {
            // Percentage mode - apply directly to inverters
            this.applyPercentageLimit(targetPercent);
        } else {
            // Var mode - closed-loop control with meter feedback
            this.applyClosedLoopControl(targetGridPowerVar);
        }
    }

    /**
     * Apply percentage limit to all inverters.
     * In percentage mode, we directly set the percentage without meter feedback.
     */
    private void applyPercentageLimit(int percent) {
        for (ManagedSymmetricPvInverter inv : this.inverters) {
            try {
                inv.setReactivePowerLimitPercent(percent);
            } catch (Exception e) {
                this.logError(this.log, "Error setting Q limit for " + inv.id() + ": " + e.getMessage());
            }
        }
        this.logDebug(this.log, "Applied Q " + percent + "% limit to " + this.inverters.size() + " inverters");
    }

    /**
     * Closed-loop control to match grid reactive power to setpoint.
     * Uses meter feedback to adjust inverter limits proportionally.
     * 
     * <p>
     * The setpoint is the TARGET reactive power at meter0 (grid connection point).
     * This controller adjusts inverter Q limits to make meter0.reactivePower match setpoint.
     * 
     * @param targetGridPowerVar Target grid reactive power at meter0 in var
     *                          (positive = inductive/lagging, negative = capacitive/leading)
     */
    private void applyClosedLoopControl(int targetGridPowerVar) throws OpenemsNamedException {
        // Get actual grid reactive power from meter0
        ElectricityMeter meter = this.componentManager.getComponent(DEFAULT_METER_ID);
        int actualGridPowerVar = meter.getReactivePower().orElse(0);

        // Calculate error: how much MORE reactive power we need at the grid point
        // Positive error = need more Q = increase inverter Q output
        // Negative error = need less Q = decrease inverter Q output
        int errorVar = targetGridPowerVar - actualGridPowerVar;

        // Check deadband - no action needed if within tolerance
        if (Math.abs(errorVar) <= DEADBAND_VAR) {
            this.logDebug(this.log, String.format(
                    "Q within deadband: target=%dvar, actual=%dvar, error=%dvar",
                    targetGridPowerVar, actualGridPowerVar, errorVar));
            return;
        }

        // Get current total inverter reactive power
        int currentTotalReactivePowerVar = 0;
        for (ManagedSymmetricPvInverter inv : this.inverters) {
            currentTotalReactivePowerVar += inv.getReactivePower().orElse(0);
        }

        // Calculate new total limit based on error
        int adjustmentVar = (int) (errorVar * Q_GAIN);
        int newTotalLimitVar = currentTotalReactivePowerVar + adjustmentVar;
        
        // Clamp to valid range [-totalSystemPowerVar, +totalSystemPowerVar]
        // Reactive power can be negative (capacitive) or positive (inductive)
        newTotalLimitVar = Math.max(-this.totalSystemPowerVar, Math.min(newTotalLimitVar, this.totalSystemPowerVar));

        // Distribute to each inverter proportionally based on max reactive power rating
        for (ManagedSymmetricPvInverter inv : this.inverters) {
            int maxQ = inv.getMaxReactivePower().orElse(0);
            if (maxQ == 0) {
                // Estimate as 50% of max apparent power if not defined
                maxQ = (int)(inv.getMaxApparentPower().orElse(0) * 0.5);
            }
            if (maxQ <= 0) {
                continue; // Skip if still no max Q defined
            }
            
            // Calculate ratio based on max reactive power
            float ratio = (this.totalSystemPowerVar > 0) 
                    ? (float) maxQ / this.totalSystemPowerVar 
                    : 1.0f / this.inverters.size();
            
            // Calculate new limit for this inverter
            int newLimit = (int) (newTotalLimitVar * ratio);
            newLimit = Math.max(-maxQ, Math.min(newLimit, maxQ)); // Clamp to valid range

            try {
                inv.setReactivePowerLimit(newLimit);
            } catch (Exception e) {
                this.logError(this.log, "Error setting Q limit for " + inv.id() + ": " + e.getMessage());
            }
        }

        this.logDebug(this.log, String.format(
                "Q Closed-loop: target=%dvar, actual=%dvar, error=%dvar, currentQ=%dvar, newLimit=%dvar",
                targetGridPowerVar, actualGridPowerVar, errorVar, currentTotalReactivePowerVar, newTotalLimitVar));
    }
}
