package io.openems.edge.controller.pvinverter.fixpowerlimit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * Controller for Active Power (P) control of all PV inverters.
 * 
 * <p>
 * This controller implements closed-loop control to make the grid connection
 * point (meter0) match the target setpoint from EVN or local configuration.
 * 
 * <p>
 * Features:
 * <ul>
 * <li>Detect which setpoint changed (Watt or Percent)</li>
 * <li>Convert Percent to Watt: targetW = percent/100 × totalSystemPower</li>
 * <li>Dynamic inverter handling: only control working inverters</li>
 * <li>Rate limiting to prevent oscillation</li>
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
    private static final int DEADBAND_W = 100;
    private static final int MAX_ADJUSTMENT_PER_CYCLE_W = 10000; // Max 2kW change per cycle
    private static final float EPSILON = 0.01f; // For float comparison

    @Reference
    private ComponentManager componentManager;

    private Config config;

    // All discovered inverters
    private List<ManagedSymmetricPvInverter> allInverters = new ArrayList<>();
    
    // Total system power (all inverters)
    private int totalSystemPowerW = 0;
    
    // Previous setpoints for change detection
    private float lastEvnPercent = 0f;
    private int lastEvnWatt = 0;
    
    // Track which mode is active
    private boolean usePercentMode = false;
    
    // Store previous limit per inverter ID (use Map for dynamic handling)
    private Map<String, Integer> previousLimits = new HashMap<>();
    
    // Discovery debounce
    private long lastDiscoveryTime = 0;
    
    // Flag to indicate if EVN has sent any command
    private boolean evnCommandReceived = false;

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
                + ", Found " + this.allInverters.size() + " inverters"
                + ", Total system power: " + this.totalSystemPowerW + "W");
    }

    private void discoverInverters() {
        this.allInverters.clear();

        for (OpenemsComponent comp : this.componentManager.getAllComponents()) {
            if (comp instanceof ManagedSymmetricPvInverter
                    && comp.isEnabled()) {
                this.allInverters.add((ManagedSymmetricPvInverter) comp);
            }
        }

        // Sort by ID
        this.allInverters.sort((a, b) -> a.id().compareTo(b.id()));
        
        // Calculate total system power
        this.totalSystemPowerW = this.allInverters.stream()
                .mapToInt(inv -> inv.getMaxApparentPower().orElse(0))
                .sum();
        
        // Initialize previous limits for new inverters
        for (ManagedSymmetricPvInverter inv : this.allInverters) {
            if (!this.previousLimits.containsKey(inv.id())) {
                // New inverter - initialize with max power (no limit)
                int maxP = inv.getMaxApparentPower().orElse(0);
                this.previousLimits.put(inv.id(), maxP);
            }
        }
    }

    /**
     * Get list of currently working inverters.
     * An inverter is considered "working" if:
     * - It's enabled
     * - It has valid ActivePower reading (not null/undefined)
     * - No fault state (optional: can add more checks)
     */
    private List<ManagedSymmetricPvInverter> getWorkingInverters() {
        List<ManagedSymmetricPvInverter> working = new ArrayList<>();
        
        for (ManagedSymmetricPvInverter inv : this.allInverters) {
            // Check if inverter is responding (has valid power reading)
            if (inv.getActivePower().isDefined()) {
                working.add(inv);
            } else {
                this.logDebug(this.log, "Inverter " + inv.id() + " not responding, excluded from control");
            }
        }
        
        return working;
    }

    @Override
    @Deactivate
    protected void deactivate() {
        // Reset all inverter limits
        for (ManagedSymmetricPvInverter inv : this.allInverters) {
            try {
                inv.setActivePowerLimit(null);
            } catch (Exception e) {
                this.logError(this.log, "Error resetting inverter " + inv.id() + ": " + e.getMessage());
            }
        }
        super.deactivate();
    }

    @Override
    public void run() throws OpenemsNamedException {
        // Refresh inverters periodically
        if (System.currentTimeMillis() - this.lastDiscoveryTime > 30000) {
            this.discoverInverters();
            this.lastDiscoveryTime = System.currentTimeMillis();
        }

        // Get working inverters for this cycle
        List<ManagedSymmetricPvInverter> workingInverters = this.getWorkingInverters();
        
        if (workingInverters.isEmpty()) {
            this.logWarn(this.log, "No working PV inverters found");
            return;
        }

        // Determine target setpoint
        int targetGridPowerW = 0;
        boolean evnEnabled = false;
        boolean hasValidSetpoint = false;

        // Check EVN control
        if (this.config.allowEvnControl()) {
            try {
                ControllerApiModbusEvn evn = this.componentManager.getComponent(EVN_CONTROLLER_ID);
                evnEnabled = evn.getPOutEnabled().orElse(false);

                if (evnEnabled) {
                    float evnPercent = evn.getPOutSetpointPercent().orElse(0f);
                    int evnWatt = evn.getPOutSetpointWatt().orElse(0);

                    // Detect which setpoint changed (use epsilon for float)
                    boolean percentChanged = Math.abs(evnPercent - this.lastEvnPercent) > EPSILON;
                    boolean wattChanged = (evnWatt != this.lastEvnWatt);
                    
                    if (percentChanged || wattChanged) {
                        this.evnCommandReceived = true;
                    }
                    
                    if (percentChanged && !wattChanged) {
                        this.usePercentMode = true;
                    } else if (wattChanged && !percentChanged) {
                        this.usePercentMode = false;
                    } else if (percentChanged && wattChanged) {
                        // Both changed → prefer non-zero
                        this.usePercentMode = (evnPercent != 0 && evnWatt == 0);
                    }
                    
                    // Update last values
                    this.lastEvnPercent = evnPercent;
                    this.lastEvnWatt = evnWatt;
                    
                    // Check if we have valid setpoint
                    if (evnPercent != 0 || evnWatt != 0 || this.evnCommandReceived) {
                        hasValidSetpoint = true;
                        
                        if (this.usePercentMode) {
                            targetGridPowerW = (int) (evnPercent / 100f * this.totalSystemPowerW);
                        } else {
                            targetGridPowerW = evnWatt;
                        }
                    }
                }
            } catch (OpenemsNamedException e) {
                this.logDebug(this.log, "EVN controller not found, using local control");
            }
        }

        // Local control mode (fallback)
        if (!evnEnabled || !hasValidSetpoint) {
            hasValidSetpoint = true;
            if (this.config.usePercentage()) {
                targetGridPowerW = (int) (this.config.powerLimitPercent() / 100f * this.totalSystemPowerW);
            } else {
                targetGridPowerW = this.config.powerLimit();
            }
        }

        if (!hasValidSetpoint) {
            return; // No control needed
        }

        // Apply closed-loop control
        this.applyClosedLoopControl(targetGridPowerW, workingInverters);
    }

    /**
     * Closed-loop control to match grid power to setpoint.
     */
    private void applyClosedLoopControl(int targetGridPowerW, List<ManagedSymmetricPvInverter> workingInverters) 
            throws OpenemsNamedException {
        
        // Get actual grid power from configured meter
        ElectricityMeter meter = this.componentManager.getComponent(this.config.meter_id());
        int actualGridPowerW = meter.getActivePower().orElse(0);

        // Calculate error
        int errorW = targetGridPowerW - actualGridPowerW;

        // Check deadband
        if (Math.abs(errorW) <= DEADBAND_W) {
            return;
        }

        // Calculate adjustment per inverter (only working ones)
        int numWorkingInverters = workingInverters.size();
        if (numWorkingInverters == 0) {
            return;
        }
        
        int adjustmentPerInverterW = errorW / numWorkingInverters;
        
        // Apply rate limiting
        adjustmentPerInverterW = Math.max(-MAX_ADJUSTMENT_PER_CYCLE_W, 
                Math.min(adjustmentPerInverterW, MAX_ADJUSTMENT_PER_CYCLE_W));

        // Apply to each working inverter
        for (ManagedSymmetricPvInverter inv : workingInverters) {
            int maxP = inv.getMaxApparentPower().orElse(0);
            if (maxP <= 0) {
                continue;
            }
            
            // Get previous limit (default to max power if not found)
            int prevLimit = this.previousLimits.getOrDefault(inv.id(), maxP);
            
            // Calculate new limit
            int newLimit = prevLimit + adjustmentPerInverterW;
            
            // Clamp to valid range [0, maxP]
            newLimit = Math.max(0, Math.min(newLimit, maxP));

            try {
                inv.setActivePowerLimit(newLimit);
                this.previousLimits.put(inv.id(), newLimit);
            } catch (Exception e) {
                this.logError(this.log, "Error setting limit for " + inv.id() + ": " + e.getMessage());
            }
        }

        this.logDebug(this.log, String.format(
                "P Control: target=%dW, actual=%dW, error=%dW, working=%d/%d, adj/inv=%dW",
                targetGridPowerW, actualGridPowerW, errorW, 
                numWorkingInverters, this.allInverters.size(), adjustmentPerInverterW));
    }
}
