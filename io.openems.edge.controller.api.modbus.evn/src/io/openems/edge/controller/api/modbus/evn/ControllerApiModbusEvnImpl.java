package io.openems.edge.controller.api.modbus.evn;

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
 * This controller receives P-out and Q-out setpoints from EVN via Modbus TCP
 * and exposes them as channels for other controllers to read.
 * 
 * <p>
 * Features:
 * <ul>
 * <li>Auto-discovers all meters (meterX) and PV inverters (pvInverterX)</li>
 * <li>Provides monitoring data via Modbus TCP (FC03/04 - Read)</li>
 * <li>Receives P/Q commands from EVN via Modbus TCP (FC06/16 - Write)</li>
 * <li>Exposes setpoints as channels for other controllers to read</li>
 * </ul>
 * 
 * <p>
 * <b>Important:</b> This controller does NOT directly control inverters.
 * It only exposes EVN setpoints as channels. A separate controller
 * (e.g., GridPowerController) should read these channels and implement
 * the actual grid power control logic.
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

    // Meters for monitoring (auto-discovered)
    private List<ElectricityMeter> meters = new ArrayList<>();

    // PV Inverters for monitoring (auto-discovered)
    private List<ManagedSymmetricPvInverter> inverters = new ArrayList<>();

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
            LOG.info("Setpoint channels available: POutEnabled, POutSetpointWatt, POutSetpointPercent, "
                    + "QOutEnabled, QOutSetpointVar, QOutSetpointPercent");

        } catch (ModbusException e) {
            LOG.error("Failed to start EVN Modbus server: {}", e.getMessage());
        }
    }

    /**
     * Auto-discover all meters and PV inverters from ComponentManager.
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

        // Remove components that no longer exist
        this.meters.removeIf(m -> {
            try {
                this.componentManager.getComponent(m.id());
                return false;
            } catch (OpenemsNamedException e) {
                LOG.info("Meter [{}] removed", m.id());
                return true;
            }
        });

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

        // ==================== Update Setpoint Channels ====================
        // Read setpoints from EVN process image and expose via channels
        
        // P-out control
        boolean pOutEnabled = this.evnProcessImage.isPOutEnabled();
        this._setPOutEnabled(pOutEnabled);
        
        float pPercent = this.evnProcessImage.getPOutSetpointPercent();
        this._setPOutSetpointPercent(pPercent);
        
        float pKw = this.evnProcessImage.getPOutSetpointKw();
        int pWatt = (int) (pKw * 1000); // kW -> W
        this._setPOutSetpointWatt(pWatt);

        // Q-out control
        boolean qOutEnabled = this.evnProcessImage.isQOutEnabled();
        this._setQOutEnabled(qOutEnabled);
        
        float qPercent = this.evnProcessImage.getQOutSetpointPercent();
        this._setQOutSetpointPercent(qPercent);
        
        float qKvar = this.evnProcessImage.getQOutSetpointKvar();
        int qVar = (int) (qKvar * 1000); // kvar -> var
        this._setQOutSetpointVar(qVar);

        // Debug logging every 10 seconds
        if (System.currentTimeMillis() % 10000 < 1000) {
            if (pOutEnabled || qOutEnabled) {
                LOG.info("EVN Setpoints - P-out: {} ({}W / {}%), Q-out: {} ({}var / {}%)",
                        pOutEnabled ? "ENABLED" : "disabled", pWatt, pPercent,
                        qOutEnabled ? "ENABLED" : "disabled", qVar, qPercent);
            }
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
