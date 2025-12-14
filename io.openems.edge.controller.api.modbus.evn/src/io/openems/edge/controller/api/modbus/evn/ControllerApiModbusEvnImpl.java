package io.openems.edge.controller.api.modbus.evn;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ghgande.j2mod.modbus.ModbusException;
import com.ghgande.j2mod.modbus.slave.ModbusSlave;
import com.ghgande.j2mod.modbus.slave.ModbusSlaveFactory;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.controller.api.Controller;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;

/**
 * EVN Modbus TCP Controller.
 * 
 * <p>
 * Features:
 * <ul>
 * <li>Auto-discovers all meters (meterX) and PV inverters (pvInverterX) from ComponentManager</li>
 * <li>Provides monitoring data via Modbus TCP (FC03/04 - Read)</li>
 * <li>Receives P/Q commands from EVN via Modbus TCP (FC06/16 - Write)</li>
 * <li>Applies P/Q limits to discovered PV Inverters with proportional distribution</li>
 * <li>Supports redistribute on fault mode</li>
 * </ul>
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
        name = "Controller.Api.ModbusTcp.Evn", //
        immediate = true, //
        configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class ControllerApiModbusEvnImpl extends AbstractOpenemsComponent
        implements ControllerApiModbusEvn, Controller, OpenemsComponent {

    private static final Logger LOG = LoggerFactory.getLogger(ControllerApiModbusEvnImpl.class);
    private static final int UNIT_ID = 1;

    /** Prefix for meter component IDs */
    private static final String METER_ID_PREFIX = "meter";
    
    /** Prefix for PV inverter component IDs */
    private static final String PVINVERTER_ID_PREFIX = "pvInverter";

    @Reference
    private ComponentManager componentManager;

    private Config config;
    private ModbusSlave slave;
    private EvnProcessImage evnProcessImage;

    // PV Inverters to control (auto-discovered)
    private List<ManagedSymmetricPvInverter> inverters = new ArrayList<>();

    // Meters for monitoring (auto-discovered)
    private List<ElectricityMeter> meters = new ArrayList<>();

    // Last applied values for change detection
    private float lastAppliedActivePower = Float.NaN;
    private float lastAppliedReactivePower = Float.NaN;
    
    // Track active inverter count for redistribution detection
    private int lastActiveInverterCount = 0;

    public ControllerApiModbusEvnImpl() {
        super(//
                OpenemsComponent.ChannelId.values(), //
                Controller.ChannelId.values(), //
                ControllerApiModbusEvn.ChannelId.values() //
        );
    }

    @Activate
    private void activate(ComponentContext context, Config config) {
        super.activate(context, config.id(), config.alias(), config.enabled());
        this.config = config;

        if (!config.enabled()) {
            return;
        }

        LOG.info("EVN Modbus Controller activating on port {}", config.port());

        // Auto-discover all meters and PV inverters from ComponentManager
        this.discoverComponents();

        try {
            // Create custom EVN process image with discovered components
            this.evnProcessImage = new EvnProcessImage(this.componentManager, this.meters, this.inverters);

            // Create Modbus TCP slave
            this.slave = ModbusSlaveFactory.createTCPSlave(config.port(), config.maxConcurrentConnections());

            // Add EVN process image
            this.slave.addProcessImage(UNIT_ID, this.evnProcessImage);

            // Open the slave
            this.slave.open();

            LOG.info("EVN Modbus TCP Server started on port {}, Unit ID {}", config.port(), UNIT_ID);
            LOG.info("Auto-discovered {} PV Inverters, {} Meters", this.inverters.size(), this.meters.size());
            LOG.info("Redistribute on fault: {}", config.redistributeOnFault());

        } catch (ModbusException e) {
            LOG.error("Failed to start EVN Modbus server: {}", e.getMessage());
        }
    }

    /**
     * Auto-discover all meters and PV inverters from ComponentManager.
     * Only components with IDs starting with "meter" or "pvInverter" are included.
     */
    private void discoverComponents() {
        this.meters.clear();
        this.inverters.clear();

        List<OpenemsComponent> allComponents = this.componentManager.getAllComponents();
        
        for (OpenemsComponent comp : allComponents) {
            String id = comp.id();
            
            // Skip disabled components
            if (!comp.isEnabled()) {
                continue;
            }
            
            // Check for meters (id starts with "meter")
            if (id.startsWith(METER_ID_PREFIX) && comp instanceof ElectricityMeter meter) {
                this.meters.add(meter);
                LOG.info("Auto-discovered Meter [{}] (alias: {})", id, comp.alias());
            }
            
            // Check for PV inverters (id starts with "pvInverter")
            if (id.startsWith(PVINVERTER_ID_PREFIX) && comp instanceof ManagedSymmetricPvInverter inv) {
                this.inverters.add(inv);
                LOG.info("Auto-discovered PV Inverter [{}] (alias: {})", id, comp.alias());
            }
        }
        
        // Sort by ID for consistent ordering
        this.meters.sort((a, b) -> a.id().compareTo(b.id()));
        this.inverters.sort((a, b) -> a.id().compareTo(b.id()));
        
        LOG.info("Discovery complete: {} Meters, {} PV Inverters", this.meters.size(), this.inverters.size());
    }

    /**
     * Refresh the list of discovered components.
     * Called periodically to detect newly added/removed components.
     */
    private void refreshDiscoveredComponents() {
        List<OpenemsComponent> allComponents = this.componentManager.getAllComponents();
        
        // Check for new meters
        for (OpenemsComponent comp : allComponents) {
            String id = comp.id();
            if (!comp.isEnabled()) {
                continue;
            }
            
            if (id.startsWith(METER_ID_PREFIX) && comp instanceof ElectricityMeter meter) {
                if (!this.meters.contains(meter)) {
                    this.meters.add(meter);
                    LOG.info("New Meter discovered: [{}]", id);
                }
            }
            
            if (id.startsWith(PVINVERTER_ID_PREFIX) && comp instanceof ManagedSymmetricPvInverter inv) {
                if (!this.inverters.contains(inv)) {
                    this.inverters.add(inv);
                    LOG.info("New PV Inverter discovered: [{}]", id);
                }
            }
        }
        
        // Remove meters that no longer exist
        this.meters.removeIf(m -> {
            try {
                this.componentManager.getComponent(m.id());
                return false;
            } catch (OpenemsNamedException e) {
                LOG.info("Meter [{}] removed", m.id());
                return true;
            }
        });
        
        // Remove inverters that no longer exist
        this.inverters.removeIf(inv -> {
            try {
                this.componentManager.getComponent(inv.id());
                return false;
            } catch (OpenemsNamedException e) {
                LOG.info("PV Inverter [{}] removed", inv.id());
                return true;
            }
        });
    }

    @Deactivate
    protected void deactivate() {
        LOG.info("EVN Modbus Controller deactivating");

        if (this.slave != null) {
            try {
                this.slave.close();
            } catch (Exception e) {
                LOG.warn("Error closing Modbus slave: {}", e.getMessage());
            }
            ModbusSlaveFactory.close(this.slave);
        }

        this.inverters.clear();
        this.meters.clear();
        super.deactivate();
    }

    @Override
    public void run() throws OpenemsNamedException {
        if (this.evnProcessImage == null) {
            return;
        }

        // Refresh discovered components periodically (every ~30 seconds)
        if (System.currentTimeMillis() % 30000 < 1000) {
            this.refreshDiscoveredComponents();
        }

        // Debug: Log control register values periodically (every 10 seconds approx)
        if (System.currentTimeMillis() % 10000 < 1000) {
            this.evnProcessImage.logControlRegisterValues();
        }

        // Process P-out control if enabled
        if (this.evnProcessImage.isPOutEnabled()) {
            LOG.info("EVN P-out Control ENABLED - Processing...");
            applyPOutControl();
        }

        // Process Q-out control if enabled
        if (this.evnProcessImage.isQOutEnabled()) {
            LOG.info("EVN Q-out Control ENABLED - Processing...");
            applyQOutControl();
        }
    }

    // ==================== Helper class for inverter info ====================

    /**
     * Helper class to store inverter information for proportional distribution.
     */
    private static class InverterInfo {
        final ManagedSymmetricPvInverter inverter;
        final int maxActivePower;
        final int maxReactivePower;

        InverterInfo(ManagedSymmetricPvInverter inv, int maxActivePower, int maxReactivePower) {
            this.inverter = inv;
            this.maxActivePower = maxActivePower;
            this.maxReactivePower = maxReactivePower;
        }
    }

    /**
     * Check if an inverter is active and healthy.
     * Checks:
     * 1. Component is enabled
     * 2. Component does not have faults (hasFaults)
     * 3. Component state is not FAULT level
     * 4. Component is communicating (has valid ActivePower reading)
     */
    private boolean isInverterActive(ManagedSymmetricPvInverter inv) {
        // Check if enabled
        if (!inv.isEnabled()) {
            LOG.debug("Inverter [{}] is disabled", inv.id());
            return false;
        }
        
        // Check for faults using hasFaults()
        if (inv.hasFaults()) {
            LOG.debug("Inverter [{}] has faults", inv.id());
            return false;
        }
        
        // Check component state level - skip if FAULT
        var stateLevel = inv.getState();
        if (stateLevel != null && stateLevel.isAtLeast(io.openems.common.channel.Level.FAULT)) {
            LOG.debug("Inverter [{}] state is FAULT", inv.id());
            return false;
        }
        
        // Check if inverter is communicating (has valid power reading)
        var activePowerOpt = inv.getActivePower();
        if (activePowerOpt == null || !activePowerOpt.isDefined()) {
            LOG.debug("Inverter [{}] has no valid ActivePower - may not be communicating", inv.id());
            return false;
        }
        
        return true;
    }

    /**
     * Check if a meter is healthy and communicating.
     */
    private boolean isMeterHealthy(ElectricityMeter meter) {
        // Check if enabled
        if (!meter.isEnabled()) {
            LOG.debug("Meter [{}] is disabled", meter.id());
            return false;
        }
        
        // Check component state level - skip if FAULT
        var stateLevel = meter.getState();
        if (stateLevel != null && stateLevel.isAtLeast(io.openems.common.channel.Level.FAULT)) {
            LOG.debug("Meter [{}] state is FAULT", meter.id());
            return false;
        }
        
        // Check if meter is communicating (has valid voltage reading)
        try {
            var voltageChannel = meter.channel("VoltageL1");
            if (voltageChannel == null || !voltageChannel.value().isDefined()) {
                LOG.debug("Meter [{}] has no valid VoltageL1 - may not be communicating", meter.id());
                return false;
            }
        } catch (IllegalArgumentException e) {
            // VoltageL1 channel might not exist for some meter types
        }
        
        return true;
    }

    /**
     * Get list of active inverters with their max power values.
     * Only includes inverters that pass all health checks.
     */
    private List<InverterInfo> getActiveInvertersWithInfo() {
        return this.inverters.stream()
                .filter(inv -> {
                    boolean active = isInverterActive(inv);
                    if (!active && this.config.redistributeOnFault()) {
                        LOG.debug("Inverter [{}] is inactive/faulted, skipping for redistribution", inv.id());
                    }
                    return active || !this.config.redistributeOnFault();
                })
                .map(inv -> {
                    int maxP = inv.getMaxActivePower().orElse(0);
                    int maxQ = inv.getMaxReactivePower().orElse(0);
                    return new InverterInfo(inv, maxP, maxQ);
                })
                .collect(Collectors.toList());
    }

    /**
     * Get healthy meters for monitoring.
     */
    private List<ElectricityMeter> getHealthyMeters() {
        return this.meters.stream()
                .filter(this::isMeterHealthy)
                .collect(Collectors.toList());
    }

    // ==================== P-out Control ====================

    /**
     * Apply P-out control from EVN.
     * Supports both percentage mode and absolute kW mode.
     * Priority: Percentage mode if set (> 0), otherwise kW mode.
     * Distributes power proportionally based on each inverter's max active power.
     */
    private void applyPOutControl() {
        float pPercent = this.evnProcessImage.getPOutSetpointPercent();
        float pKw = this.evnProcessImage.getPOutSetpointKw();

        // Determine mode: percentage or absolute
        boolean usePercentMode = pPercent > 0;

        // Get active inverters
        List<InverterInfo> activeInverters = getActiveInvertersWithInfo();
        if (activeInverters.isEmpty()) {
            LOG.warn("EVN P-out: No active inverters available");
            this.lastActiveInverterCount = 0;
            return;
        }

        // Check if inverter count changed (fault or recovery) - force redistribution
        boolean inverterCountChanged = activeInverters.size() != this.lastActiveInverterCount;
        if (inverterCountChanged) {
            LOG.info("EVN P-out: Inverter count changed from {} to {} - redistributing",
                    this.lastActiveInverterCount, activeInverters.size());
        }

        if (usePercentMode) {
            // === PERCENTAGE MODE ===
            // Apply if value changed OR inverter count changed
            boolean valueChanged = Float.isNaN(this.lastAppliedActivePower)
                    || Math.abs(pPercent - this.lastAppliedActivePower) >= 0.1f;
            
            if (!valueChanged && !inverterCountChanged) {
                return; // Nothing changed, skip
            }

            // Apply percentage directly to each inverter
            LOG.info("EVN P-out: Applying {}% limit to {} inverters", pPercent, activeInverters.size());

            for (InverterInfo info : activeInverters) {
                int limitPercent = (int) pPercent;
                try {
                    info.inverter.setActivePowerLimitPercent(limitPercent);
                    int limitWatts = (int) (info.maxActivePower * pPercent / 100.0f);
                    LOG.info("EVN P-out -> Inverter [{}]: {}% => {} W (maxP={} W)",
                            info.inverter.id(), pPercent, limitWatts, info.maxActivePower);
                } catch (Exception e) {
                    LOG.error("Error applying P-out to inverter [{}]: {}", info.inverter.id(), e.getMessage());
                }
            }
            this.lastAppliedActivePower = pPercent;
            this.lastActiveInverterCount = activeInverters.size();

        } else {
            // === ABSOLUTE kW MODE ===
            float totalPowerW = pKw * 1000; // kW -> W

            // Apply if value changed OR inverter count changed
            boolean valueChanged = Float.isNaN(this.lastAppliedActivePower)
                    || Math.abs(totalPowerW - this.lastAppliedActivePower) > 1;
            
            if (!valueChanged && !inverterCountChanged) {
                return; // Nothing changed, skip
            }

            // Calculate total max active power of ACTIVE inverters only
            int totalMaxPower = activeInverters.stream()
                    .mapToInt(i -> i.maxActivePower)
                    .sum();

            if (totalMaxPower <= 0) {
                // Fallback: equal distribution if no max power info
                int perInverter = (int) (totalPowerW / activeInverters.size());
                LOG.info("EVN P-out: Distributing {} kW equally to {} inverters: {} W each",
                        pKw, activeInverters.size(), perInverter);

                for (InverterInfo info : activeInverters) {
                    try {
                        info.inverter.setActivePowerLimit(perInverter);
                        LOG.info("EVN P-out -> Inverter [{}]: {} W (equal)", info.inverter.id(), perInverter);
                    } catch (Exception e) {
                        LOG.error("Error applying P-out to inverter [{}]: {}", info.inverter.id(), e.getMessage());
                    }
                }
            } else {
                // Proportional distribution based on max power ratio
                LOG.info("EVN P-out: Distributing {} kW across {} active inverters (totalMaxPower={} W)",
                        pKw, activeInverters.size(), totalMaxPower);

                for (InverterInfo info : activeInverters) {
                    float ratio = (float) info.maxActivePower / totalMaxPower;
                    int allocatedPower = (int) (totalPowerW * ratio);

                    try {
                        info.inverter.setActivePowerLimit(allocatedPower);
                        LOG.info("EVN P-out -> Inverter [{}]: {} W (ratio: {:.1f}%, maxP={} W)",
                                info.inverter.id(), allocatedPower, ratio * 100, info.maxActivePower);
                    } catch (Exception e) {
                        LOG.error("Error applying P-out to inverter [{}]: {}", info.inverter.id(), e.getMessage());
                    }
                }
            }

            this.lastAppliedActivePower = totalPowerW;
            this.lastActiveInverterCount = activeInverters.size();
        }
    }

    // ==================== Q-out Control ====================

    /**
     * Apply Q-out control from EVN.
     * Supports both percentage mode and absolute kvar mode.
     * Priority: Percentage mode if set (non-zero), otherwise kvar mode.
     * Distributes reactive power proportionally based on each inverter's max
     * reactive power.
     */
    private void applyQOutControl() {
        float qPercent = this.evnProcessImage.getQOutSetpointPercent();
        float qKvar = this.evnProcessImage.getQOutSetpointKvar();

        // Determine mode: percentage or absolute
        boolean usePercentMode = qPercent != 0;

        // Get active inverters
        List<InverterInfo> activeInverters = getActiveInvertersWithInfo();
        if (activeInverters.isEmpty()) {
            LOG.warn("EVN Q-out: No active inverters available");
            return;
        }

        // Check if inverter count changed (fault or recovery) - force redistribution
        // Note: Using same lastActiveInverterCount as P-out since they share the same inverters
        boolean inverterCountChanged = activeInverters.size() != this.lastActiveInverterCount;

        if (usePercentMode) {
            // === PERCENTAGE MODE ===
            boolean valueChanged = Float.isNaN(this.lastAppliedReactivePower)
                    || Math.abs(qPercent - this.lastAppliedReactivePower) >= 0.1f;
            
            if (!valueChanged && !inverterCountChanged) {
                return; // Nothing changed, skip
            }

            LOG.info("EVN Q-out: Applying {}% limit to {} inverters", qPercent, activeInverters.size());

            for (InverterInfo info : activeInverters) {
                int limitPercent = (int) qPercent;
                try {
                    info.inverter.setReactivePowerLimitPercent(limitPercent);
                    int limitVar = (int) (info.maxReactivePower * qPercent / 100.0f);
                    LOG.info("EVN Q-out -> Inverter [{}]: {}% => {} var (maxQ={} var)",
                            info.inverter.id(), qPercent, limitVar, info.maxReactivePower);
                } catch (Exception e) {
                    LOG.error("Error applying Q-out to inverter [{}]: {}", info.inverter.id(), e.getMessage());
                }
            }
            this.lastAppliedReactivePower = qPercent;

        } else {
            // === ABSOLUTE kvar MODE ===
            float totalPowerVar = qKvar * 1000; // kvar -> var

            boolean valueChanged = Float.isNaN(this.lastAppliedReactivePower)
                    || Math.abs(totalPowerVar - this.lastAppliedReactivePower) > 1;
            
            if (!valueChanged && !inverterCountChanged) {
                return; // Nothing changed, skip
            }

            // Calculate total max reactive power of ACTIVE inverters only
            int totalMaxPower = activeInverters.stream()
                    .mapToInt(i -> i.maxReactivePower)
                    .sum();

            if (totalMaxPower <= 0) {
                // Fallback: equal distribution
                int perInverter = (int) (totalPowerVar / activeInverters.size());
                LOG.info("EVN Q-out: Distributing {} kvar equally to {} inverters: {} var each",
                        qKvar, activeInverters.size(), perInverter);

                for (InverterInfo info : activeInverters) {
                    try {
                        info.inverter.setReactivePowerLimit(perInverter);
                        LOG.info("EVN Q-out -> Inverter [{}]: {} var (equal)", info.inverter.id(), perInverter);
                    } catch (Exception e) {
                        LOG.error("Error applying Q-out to inverter [{}]: {}", info.inverter.id(), e.getMessage());
                    }
                }
            } else {
                // Proportional distribution based on max power ratio
                LOG.info("EVN Q-out: Distributing {} kvar across {} active inverters (totalMaxPower={} var)",
                        qKvar, activeInverters.size(), totalMaxPower);

                for (InverterInfo info : activeInverters) {
                    float ratio = (float) info.maxReactivePower / totalMaxPower;
                    int allocatedPower = (int) (totalPowerVar * ratio);

                    try {
                        info.inverter.setReactivePowerLimit(allocatedPower);
                        LOG.info("EVN Q-out -> Inverter [{}]: {} var (ratio: {:.1f}%, maxQ={} var)",
                                info.inverter.id(), allocatedPower, ratio * 100, info.maxReactivePower);
                    } catch (Exception e) {
                        LOG.error("Error applying Q-out to inverter [{}]: {}", info.inverter.id(), e.getMessage());
                    }
                }
            }

            this.lastAppliedReactivePower = totalPowerVar;
        }
    }
    
    /**
     * Get the list of discovered meters.
     */
    public List<ElectricityMeter> getMeters() {
        return new ArrayList<>(this.meters);
    }
    
    /**
     * Get the list of discovered PV inverters.
     */
    public List<ManagedSymmetricPvInverter> getInverters() {
        return new ArrayList<>(this.inverters);
    }
}
