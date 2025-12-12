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
 * <li>Provides monitoring data via Modbus TCP (FC03/04 - Read)</li>
 * <li>Receives P/Q commands from EVN via Modbus TCP (FC06/16 - Write)</li>
 * <li>Applies P/Q limits to configured PV Inverters with proportional
 * distribution</li>
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

    @Reference
    private ComponentManager componentManager;

    private Config config;
    private ModbusSlave slave;
    private EvnProcessImage evnProcessImage;

    // PV Inverters to control
    private List<ManagedSymmetricPvInverter> inverters = new ArrayList<>();

    // Meters for monitoring
    private List<ElectricityMeter> meters = new ArrayList<>();

    // Last applied values for change detection
    private float lastAppliedActivePower = Float.NaN;
    private float lastAppliedReactivePower = Float.NaN;

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

        // Load PV Inverters
        this.loadInverters(config.pvInverter_ids());

        // Load Meters
        this.loadMeters(config.meter_ids());

        try {
            // Create custom EVN process image
            this.evnProcessImage = new EvnProcessImage(this.componentManager);

            // Create Modbus TCP slave
            this.slave = ModbusSlaveFactory.createTCPSlave(config.port(), config.maxConcurrentConnections());

            // Add EVN process image
            this.slave.addProcessImage(UNIT_ID, this.evnProcessImage);

            // Open the slave
            this.slave.open();

            LOG.info("EVN Modbus TCP Server started on port {}, Unit ID {}", config.port(), UNIT_ID);
            LOG.info("Controlling {} PV Inverters, Monitoring {} Meters", this.inverters.size(), this.meters.size());
            LOG.info("Redistribute on fault: {}", config.redistributeOnFault());

        } catch (ModbusException e) {
            LOG.error("Failed to start EVN Modbus server: {}", e.getMessage());
        }
    }

    /**
     * Load PV Inverter references.
     */
    private void loadInverters(String[] inverterIds) {
        this.inverters.clear();
        for (String id : inverterIds) {
            try {
                OpenemsComponent comp = this.componentManager.getComponent(id);
                if (comp instanceof ManagedSymmetricPvInverter inv) {
                    this.inverters.add(inv);
                    LOG.info("Added PV Inverter [{}] for EVN control", id);
                } else {
                    LOG.warn("Component [{}] is not a ManagedSymmetricPvInverter", id);
                }
            } catch (OpenemsNamedException e) {
                LOG.warn("PV Inverter [{}] not found: {}", id, e.getMessage());
            }
        }
    }

    /**
     * Load Meter references.
     */
    private void loadMeters(String[] meterIds) {
        this.meters.clear();
        for (String id : meterIds) {
            try {
                OpenemsComponent comp = this.componentManager.getComponent(id);
                if (comp instanceof ElectricityMeter meter) {
                    this.meters.add(meter);
                    LOG.info("Added Meter [{}] for EVN monitoring", id);
                } else {
                    LOG.warn("Component [{}] is not an ElectricityMeter", id);
                }
            } catch (OpenemsNamedException e) {
                LOG.warn("Meter [{}] not found: {}", id, e.getMessage());
            }
        }
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

        // Process P-out control if enabled
        if (this.evnProcessImage.isPOutEnabled()) {
            applyPOutControl();
        }

        // Process Q-out control if enabled
        if (this.evnProcessImage.isQOutEnabled()) {
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
     * Check if an inverter is active (enabled and no faults).
     */
    private boolean isInverterActive(ManagedSymmetricPvInverter inv) {
        return inv.isEnabled() && !inv.hasFaults();
    }

    /**
     * Get list of active inverters with their max power values.
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
            return;
        }

        if (usePercentMode) {
            // === PERCENTAGE MODE ===
            // Only apply if value changed
            if (!Float.isNaN(this.lastAppliedActivePower)
                    && Math.abs(pPercent - this.lastAppliedActivePower) < 0.1f) {
                return; // Value unchanged, skip
            }

            // Apply percentage directly to each inverter based on their maxActivePower
            LOG.info("EVN P-out: Applying {}% limit to {} inverters", pPercent, activeInverters.size());

            for (InverterInfo info : activeInverters) {
                int limitPercent = (int) pPercent; // Use percentage directly
                try {
                    info.inverter.setActivePowerLimitPercent(limitPercent);
                    int limitWatts = (int) (info.maxActivePower * pPercent / 100.0f);
                    LOG.info("EVN P-out -> Inverter [{}]: {}% => {} W (maxP={} W)",
                            info.inverter.id(), pPercent, limitWatts, info.maxActivePower);
                } catch (Exception e) {
                    LOG.error("Error applying P-out to inverter [{}]: {}", info.inverter.id(), e.getMessage());
                }
            }
            this.lastAppliedActivePower = pPercent; // Store percentage for change detection

        } else {
            // === ABSOLUTE kW MODE ===
            float totalPowerW = pKw * 1000; // kW -> W

            // Only apply if value changed
            if (!Float.isNaN(this.lastAppliedActivePower)
                    && Math.abs(totalPowerW - this.lastAppliedActivePower) <= 1) {
                return;
            }

            // Calculate total max active power
            int totalMaxPower = activeInverters.stream()
                    .mapToInt(i -> i.maxActivePower)
                    .sum();

            if (totalMaxPower <= 0) {
                // Fallback: equal distribution if no max power info
                int perInverter = (int) (totalPowerW / activeInverters.size());
                LOG.info("EVN P-out: No max power info, using equal distribution: {} W each", perInverter);

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
        // Note: Q can be negative so check if percent was explicitly set
        boolean usePercentMode = qPercent != 0;

        // Get active inverters
        List<InverterInfo> activeInverters = getActiveInvertersWithInfo();
        if (activeInverters.isEmpty()) {
            LOG.warn("EVN Q-out: No active inverters available");
            return;
        }

        if (usePercentMode) {
            // === PERCENTAGE MODE ===
            // Only apply if value changed
            if (!Float.isNaN(this.lastAppliedReactivePower)
                    && Math.abs(qPercent - this.lastAppliedReactivePower) < 0.1f) {
                return; // Value unchanged, skip
            }

            // Apply percentage directly to each inverter based on their maxReactivePower
            LOG.info("EVN Q-out: Applying {}% limit to {} inverters", qPercent, activeInverters.size());

            for (InverterInfo info : activeInverters) {
                int limitPercent = (int) qPercent; // Use percentage directly
                try {
                    info.inverter.setReactivePowerLimitPercent(limitPercent);
                    int limitVar = (int) (info.maxReactivePower * qPercent / 100.0f);
                    LOG.info("EVN Q-out -> Inverter [{}]: {}% => {} var (maxQ={} var)",
                            info.inverter.id(), qPercent, limitVar, info.maxReactivePower);
                } catch (Exception e) {
                    LOG.error("Error applying Q-out to inverter [{}]: {}", info.inverter.id(), e.getMessage());
                }
            }
            this.lastAppliedReactivePower = qPercent; // Store percentage for change detection

        } else {
            // === ABSOLUTE kvar MODE ===
            float totalPowerVar = qKvar * 1000; // kvar -> var

            // Only apply if value changed
            if (!Float.isNaN(this.lastAppliedReactivePower)
                    && Math.abs(totalPowerVar - this.lastAppliedReactivePower) <= 1) {
                return;
            }

            // Calculate total max reactive power
            int totalMaxPower = activeInverters.stream()
                    .mapToInt(i -> i.maxReactivePower)
                    .sum();

            if (totalMaxPower <= 0) {
                // Fallback: equal distribution if no max power info
                int perInverter = (int) (totalPowerVar / activeInverters.size());
                LOG.info("EVN Q-out: No max power info, using equal distribution: {} var each", perInverter);

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
}
