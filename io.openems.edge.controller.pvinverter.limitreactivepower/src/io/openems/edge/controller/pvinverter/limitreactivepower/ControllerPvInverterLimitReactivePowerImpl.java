package io.openems.edge.controller.pvinverter.limitreactivepower;

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
 * Controller for Reactive Power (Q) control of all PV inverters.
 * 
 * <p>
 * This controller implements closed-loop control to make the grid connection
 * point (meter0) match the target setpoint from EVN or local configuration.
 * 
 * <p>
 * Features:
 * <ul>
 * <li>Detect which setpoint changed (Var or Percent)</li>
 * <li>Convert Percent to Var: targetVar = percent/100 × totalSystemPower</li>
 * <li>Dynamic inverter handling: only control working inverters</li>
 * <li>Rate limiting to prevent oscillation</li>
 * <li>Supports positive (inductive) and negative (capacitive) reactive power</li>
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
    private static final int DEADBAND_VAR = 50;
    private static final int MAX_ADJUSTMENT_PER_CYCLE_VAR = 10000; // Max 1kvar change per cycle
    private static final float EPSILON = 0.01f; // For float comparison

    @Reference
    private ComponentManager componentManager;

    private Config config;

    // All discovered inverters
    private List<ManagedSymmetricPvInverter> allInverters = new ArrayList<>();
    
    // Total system reactive power capacity
    private int totalSystemPowerVar = 0;
    
    // Previous setpoints for change detection
    private float lastEvnPercent = 0f;
    private int lastEvnVar = 0;
    
    // Track which mode is active
    private boolean usePercentMode = false;
    
    // Store previous limit per inverter ID (use Map for dynamic handling)
    private Map<String, Integer> previousLimits = new HashMap<>();
    
    // Discovery debounce
    private long lastDiscoveryTime = 0;
    
    // Flag to indicate if EVN has sent any command
    private boolean evnCommandReceived = false;

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
                + ", Found " + this.allInverters.size() + " inverters"
                + ", Total system Q power: " + this.totalSystemPowerVar + "var");
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
        
        // Calculate total system reactive power
        this.totalSystemPowerVar = this.allInverters.stream()
                .mapToInt(inv -> inv.getMaxReactivePower().orElse(0))
                .sum();
        
        // If no max reactive power defined, estimate as 50% of apparent power
        if (this.totalSystemPowerVar == 0) {
            this.totalSystemPowerVar = this.allInverters.stream()
                    .mapToInt(inv -> (int)(inv.getMaxApparentPower().orElse(0) * 0.5))
                    .sum();
        }
        
        // Initialize previous limits for new inverters (default 0 for Q)
        for (ManagedSymmetricPvInverter inv : this.allInverters) {
            if (!this.previousLimits.containsKey(inv.id())) {
                this.previousLimits.put(inv.id(), 0);
            }
        }
    }

    /**
     * Get list of currently working inverters.
     */
    private List<ManagedSymmetricPvInverter> getWorkingInverters() {
        List<ManagedSymmetricPvInverter> working = new ArrayList<>();
        
        for (ManagedSymmetricPvInverter inv : this.allInverters) {
            // Check if inverter is responding (has valid power reading)
            if (inv.getActivePower().isDefined()) {
                working.add(inv);
            } else {
                this.logDebug(this.log, "Inverter " + inv.id() + " not responding, excluded from Q control");
            }
        }
        
        return working;
    }

    @Override
    @Deactivate
    protected void deactivate() {
        for (ManagedSymmetricPvInverter inv : this.allInverters) {
            try {
                inv.setReactivePowerLimit(null);
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
        int targetGridPowerVar = 0;
        boolean evnEnabled = false;
        boolean hasValidSetpoint = false;

        // Check EVN control
        if (this.config.allowEvnControl()) {
            try {
                ControllerApiModbusEvn evn = this.componentManager.getComponent(EVN_CONTROLLER_ID);
                evnEnabled = evn.getQOutEnabled().orElse(false);

                if (evnEnabled) {
                    float evnPercent = evn.getQOutSetpointPercent().orElse(0f);
                    int evnVar = evn.getQOutSetpointVar().orElse(0);

                    // Detect which setpoint changed
                    boolean percentChanged = Math.abs(evnPercent - this.lastEvnPercent) > EPSILON;
                    boolean varChanged = (evnVar != this.lastEvnVar);
                    
                    if (percentChanged || varChanged) {
                        this.evnCommandReceived = true;
                    }
                    
                    if (percentChanged && !varChanged) {
                        this.usePercentMode = true;
                    } else if (varChanged && !percentChanged) {
                        this.usePercentMode = false;
                    } else if (percentChanged && varChanged) {
                        this.usePercentMode = (evnPercent != 0 && evnVar == 0);
                    }
                    
                    this.lastEvnPercent = evnPercent;
                    this.lastEvnVar = evnVar;
                    
                    // Check if we have valid setpoint (Q can be 0 as valid target)
                    if (evnPercent != 0 || evnVar != 0 || this.evnCommandReceived) {
                        hasValidSetpoint = true;
                        
                        if (this.usePercentMode) {
                            targetGridPowerVar = (int) (evnPercent / 100f * this.totalSystemPowerVar);
                        } else {
                            targetGridPowerVar = evnVar;
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
            if (this.config.usePercent()) {
                targetGridPowerVar = (int) (this.config.reactivePowerLimitPercent() / 100f * this.totalSystemPowerVar);
            } else {
                targetGridPowerVar = this.config.reactivePowerLimit();
            }
        }

        if (!hasValidSetpoint) {
            return;
        }

        // Apply closed-loop control
        this.applyClosedLoopControl(targetGridPowerVar, workingInverters);
    }

    /**
     * Closed-loop control to match grid reactive power to setpoint.
     */
    private void applyClosedLoopControl(int targetGridPowerVar, List<ManagedSymmetricPvInverter> workingInverters) 
            throws OpenemsNamedException {
        
        // Get actual grid reactive power from configured meter
        ElectricityMeter meter = this.componentManager.getComponent(this.config.meter_id());
        int actualGridPowerVar = meter.getReactivePower().orElse(0);

        // Calculate error
        int errorVar = targetGridPowerVar - actualGridPowerVar;

        // Check deadband
        if (Math.abs(errorVar) <= DEADBAND_VAR) {
            return;
        }

        // Calculate adjustment per inverter
        int numWorkingInverters = workingInverters.size();
        if (numWorkingInverters == 0) {
            return;
        }
        
        int adjustmentPerInverterVar = errorVar / numWorkingInverters;
        
        // Apply rate limiting
        adjustmentPerInverterVar = Math.max(-MAX_ADJUSTMENT_PER_CYCLE_VAR, 
                Math.min(adjustmentPerInverterVar, MAX_ADJUSTMENT_PER_CYCLE_VAR));

        // Apply to each working inverter
        for (ManagedSymmetricPvInverter inv : workingInverters) {
            // Get max Q capacity
            int maxQ = inv.getMaxReactivePower().orElse(0);
            if (maxQ == 0) {
                maxQ = (int)(inv.getMaxApparentPower().orElse(0) * 0.5);
            }
            if (maxQ <= 0) {
                continue;
            }
            
            // Get previous limit (default 0 for Q)
            int prevLimit = this.previousLimits.getOrDefault(inv.id(), 0);
            
            // Calculate new limit
            int newLimit = prevLimit + adjustmentPerInverterVar;
            
            // Clamp to valid range [-maxQ, +maxQ]
            newLimit = Math.max(-maxQ, Math.min(newLimit, maxQ));

            try {
                inv.setReactivePowerLimit(newLimit);
                this.previousLimits.put(inv.id(), newLimit);
            } catch (Exception e) {
                this.logError(this.log, "Error setting Q limit for " + inv.id() + ": " + e.getMessage());
            }
        }

        this.logDebug(this.log, String.format(
                "Q Control: target=%dvar, actual=%dvar, error=%dvar, working=%d/%d, adj/inv=%dvar",
                targetGridPowerVar, actualGridPowerVar, errorVar,
                numWorkingInverters, this.allInverters.size(), adjustmentPerInverterVar));
    }
}
