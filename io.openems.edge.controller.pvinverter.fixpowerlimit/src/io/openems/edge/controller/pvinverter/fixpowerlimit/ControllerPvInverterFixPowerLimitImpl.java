package io.openems.edge.controller.pvinverter.fixpowerlimit;

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
 * Controller for Active Power control of all PV inverters.
 * 
 * <p>
 * This controller implements closed-loop control to make meter0's active power
 * match the target setpoint. The setpoint represents the desired grid export power.
 * 
 * <p>
 * Control Logic:
 * <ul>
 * <li>If meter0.activePower < setpoint: Need MORE export → increase inverter limits</li>
 * <li>If meter0.activePower > setpoint: Need LESS export → decrease inverter limits</li>
 * </ul>
 * 
 * <p>
 * Features:
 * <ul>
 * <li>Auto-discovers all PV inverters (pvInverterX)</li>
 * <li>Reads setpoints from EVN controller when P_OUT_ENABLED = true</li>
 * <li>Implements closed-loop control using meter0 feedback</li>
 * <li>Falls back to local fixed values when EVN control is disabled</li>
 * <li>Distributes power proportionally based on inverter max power rating</li>
 * </ul>
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
        name = "Controller.PvInverter.FixPowerLimit", //
        immediate = true, //
        configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class ControllerPvInverterFixPowerLimitImpl extends AbstractOpenemsComponent
        implements ControllerPvInverterFixPowerLimit, Controller, OpenemsComponent {

    private final Logger log = LoggerFactory.getLogger(ControllerPvInverterFixPowerLimitImpl.class);

    private static final String PVINVERTER_ID_PREFIX = "pvInverter";
    private static final String DEFAULT_METER_ID = "meter0";
    private static final String EVN_CONTROLLER_ID = "ctrlEvnModbus0";
    
    // Control parameters
    private static final float P_GAIN = 0.8f;  // Proportional gain for closed-loop (higher = faster response)
    private static final int DEADBAND_W = 100; // Deadband in Watts

    @Reference
    private ComponentManager componentManager;

    private Config config;

    // Discovered inverters
    private List<ManagedSymmetricPvInverter> inverters = new ArrayList<>();
    
    // Auto-calculated total system power
    private int totalSystemPowerW = 0;

    public ControllerPvInverterFixPowerLimitImpl() {
        super(//
                OpenemsComponent.ChannelId.values(), //
                Controller.ChannelId.values(), //
                ControllerPvInverterFixPowerLimit.ChannelId.values() //
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
                + ", Total system power: " + this.totalSystemPowerW + "W");
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
        
        // Calculate total system power from inverters (all inverters save to MaxApparentPower)
        this.totalSystemPowerW = this.inverters.stream()
                .mapToInt(inv -> inv.getMaxApparentPower().orElse(0))
                .sum();
    }

    @Override
    @Deactivate
    protected void deactivate() {
        // Reset all inverter limits (remove limits)
        for (ManagedSymmetricPvInverter inv : this.inverters) {
            try {
                inv.setActivePowerLimit(null);
                inv.setActivePowerLimitPercent(null);
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
        int targetGridPowerW = 0;
        int targetPercent = -1; // -1 means not using percent mode
        boolean evnEnabled = false;

        // Check EVN control
        if (this.config.allowEvnControl()) {
            try {
                ControllerApiModbusEvn evn = this.componentManager.getComponent(EVN_CONTROLLER_ID);
                evnEnabled = evn.getPOutEnabled().orElse(false);

                if (evnEnabled) {
                    float evnPercent = evn.getPOutSetpointPercent().orElse(0f);
                    int evnWatt = evn.getPOutSetpointWatt().orElse(0);

                    if (evnPercent > 0) {
                        // Percentage mode from EVN
                        targetPercent = (int) evnPercent;
                    } else {
                        // Watt mode from EVN - use closed-loop control
                        targetGridPowerW = evnWatt;
                    }
                }
            } catch (OpenemsNamedException e) {
                this.logDebug(this.log, "EVN controller not found, using local control");
            }
        }

        // Local control mode
        if (!evnEnabled) {
            if (this.config.usePercentage()) {
                targetPercent = this.config.powerLimitPercent();
            } else {
                targetGridPowerW = this.config.powerLimit();
            }
        }

        // Apply control
        if (targetPercent >= 0) {
            // Percentage mode - apply directly to inverters
            this.applyPercentageLimit(targetPercent);
        } else {
            // Watt mode - closed-loop control with meter feedback
            this.applyClosedLoopControl(targetGridPowerW);
        }
    }

    /**
     * Apply percentage limit to all inverters.
     * In percentage mode, we directly set the percentage without meter feedback.
     */
    private void applyPercentageLimit(int percent) {
        for (ManagedSymmetricPvInverter inv : this.inverters) {
            try {
                inv.setActivePowerLimitPercent(percent);
            } catch (Exception e) {
                this.logError(this.log, "Error setting limit for " + inv.id() + ": " + e.getMessage());
            }
        }
        this.logDebug(this.log, "Applied " + percent + "% limit to " + this.inverters.size() + " inverters");
    }

    /**
     * Closed-loop control to match grid power to setpoint.
     * Uses meter feedback to adjust inverter limits proportionally.
     * 
     * <p>
     * The setpoint is the TARGET active power at meter0 (grid connection point).
     * This controller adjusts inverter limits to make meter0.activePower match setpoint.
     * 
     * @param targetGridPowerW Target grid power at meter0 in Watts (positive = export to grid)
     */
    private void applyClosedLoopControl(int targetGridPowerW) throws OpenemsNamedException {
        // Get actual grid power from meter0
        ElectricityMeter meter = this.componentManager.getComponent(DEFAULT_METER_ID);
        int actualGridPowerW = meter.getActivePower().orElse(0);

        // Calculate error: how much MORE power we need at the grid point
        // Positive error = need more export = increase inverter output
        // Negative error = need less export = decrease inverter output
        int errorW = targetGridPowerW - actualGridPowerW;

        // Check deadband - no action needed if within tolerance
        if (Math.abs(errorW) <= DEADBAND_W) {
            this.logDebug(this.log, String.format(
                    "Within deadband: target=%dW, actual=%dW, error=%dW",
                    targetGridPowerW, actualGridPowerW, errorW));
            return;
        }

        // Get current total inverter production
        int currentTotalProductionW = 0;
        for (ManagedSymmetricPvInverter inv : this.inverters) {
            currentTotalProductionW += inv.getActivePower().orElse(0);
        }

        // Calculate new total limit based on error
        // If error is positive (need more), increase limits
        // If error is negative (need less), decrease limits
        int adjustmentW = (int) (errorW * P_GAIN);
        int newTotalLimitW = currentTotalProductionW + adjustmentW;
        
        // Clamp to valid range [0, totalSystemPowerW]
        newTotalLimitW = Math.max(0, Math.min(newTotalLimitW, this.totalSystemPowerW));

        // Distribute to each inverter proportionally based on max power rating
        for (ManagedSymmetricPvInverter inv : this.inverters) {
            int maxP = inv.getMaxApparentPower().orElse(0);
            if (maxP <= 0) {
                continue; // Skip if no max power defined
            }
            
            // Calculate ratio based on max power
            float ratio = (this.totalSystemPowerW > 0) 
                    ? (float) maxP / this.totalSystemPowerW 
                    : 1.0f / this.inverters.size();
            
            // Calculate new limit for this inverter
            int newLimit = (int) (newTotalLimitW * ratio);
            newLimit = Math.max(0, Math.min(newLimit, maxP)); // Clamp to valid range

            try {
                inv.setActivePowerLimit(newLimit);
            } catch (Exception e) {
                this.logError(this.log, "Error setting limit for " + inv.id() + ": " + e.getMessage());
            }
        }

        this.logDebug(this.log, String.format(
                "P Closed-loop: target=%dW, actual=%dW, error=%dW, currentProd=%dW, newLimit=%dW",
                targetGridPowerW, actualGridPowerW, errorW, currentTotalProductionW, newTotalLimitW));
    }
}
