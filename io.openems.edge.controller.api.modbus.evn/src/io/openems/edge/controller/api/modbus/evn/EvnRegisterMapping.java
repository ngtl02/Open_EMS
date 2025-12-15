package io.openems.edge.controller.api.modbus.evn;

import java.util.ArrayList;
import java.util.List;

/**
 * EVN Register Mapping defines the fixed Modbus register addresses for EVN
 * monitoring system.
 * 
 * <p>
 * Channel names based on:
 * - Sum.java: _sum/GridActivePower, _sum/ProductionActivePower, _sum/EssSoc,
 * etc.
 * - ElectricityMeter.java: meter0/ActivePower, meter0/VoltageL1,
 * meter0/CurrentL1, meter0/Frequency, etc.
 * </p>
 * 
 * <p>
 * All float values use IEEE-754 encoding (2 registers per value).
 * </p>
 */
public class EvnRegisterMapping {

    // Inverter addresses follow formula: 25 + 4*(i-1) where i is 1-based
    public static final int INVERTER_START_ADDRESS = 25;
    public static final int REGISTERS_PER_INVERTER = 4;
    public static final int MAX_INVERTERS = 10;

    // Default component IDs - change these in Config if needed
    public static final String DEFAULT_METER_ID = "meter0";
    public static final String DEFAULT_PVINVERTER_PREFIX = "pvInverter";

    private final int address;
    private final String description;
    private final String channelAddress;
    private final boolean isFloat;
    private final float scaleFactor;

    private EvnRegisterMapping(int address, String description, String channelAddress, boolean isFloat,
            float scaleFactor) {
        this.address = address;
        this.description = description;
        this.channelAddress = channelAddress;
        this.isFloat = isFloat;
        this.scaleFactor = scaleFactor;
    }

    // Convenience constructor with default scale factor of 1.0
    private EvnRegisterMapping(int address, String description, String channelAddress, boolean isFloat) {
        this(address, description, channelAddress, isFloat, 1.0f);
    }

    // ==================== MONITORING REGISTERS (T04 - Input Registers)
    // ====================

    // Power Monitoring - addresses 1-8 (from _sum)
    // Sum.ChannelId: GRID_ACTIVE_POWER, PRODUCTION_ACTIVE_POWER, etc.
    // Scale factor 0.001f converts: W→kW, var→kVar, Wh→kWh
    public static final EvnRegisterMapping GRID_ACTIVE_POWER = new EvnRegisterMapping(1,
            "P-out: Grid Active Power (kW)",
            "_sum/GridActivePower", true, 0.001f);
    public static final EvnRegisterMapping TOTAL_PRODUCTION_POWER = new EvnRegisterMapping(3,
            "Pinv-out: Total Production Power (kW)", "_sum/ProductionActivePower", true, 0.001f);
    public static final EvnRegisterMapping PRODUCTION_ENERGY = new EvnRegisterMapping(5,
            "Ainv: Total Production Energy (kWh)", "_sum/ProductionActiveEnergy", true, 0.001f);
    public static final EvnRegisterMapping GRID_REACTIVE_POWER = new EvnRegisterMapping(7,
            "Q-out: Grid Reactive Power (kVar)", DEFAULT_METER_ID + "/ReactivePower", true, 0.001f);

    // Voltage Monitoring - addresses 9-14 (from meter)
    // ElectricityMeter.ChannelId: VOLTAGE_L1, VOLTAGE_L2, VOLTAGE_L3 (unit: mV)
    // Scale factor 0.001f converts: mV→V
    public static final EvnRegisterMapping VOLTAGE_L1 = new EvnRegisterMapping(9, "Ua: Phase Voltage L1 (V)",
            DEFAULT_METER_ID + "/VoltageL1", true, 0.001f);
    public static final EvnRegisterMapping VOLTAGE_L2 = new EvnRegisterMapping(11, "Ub: Phase Voltage L2 (V)",
            DEFAULT_METER_ID + "/VoltageL2", true, 0.001f);
    public static final EvnRegisterMapping VOLTAGE_L3 = new EvnRegisterMapping(13, "Uc: Phase Voltage L3 (V)",
            DEFAULT_METER_ID + "/VoltageL3", true, 0.001f);

    // Current Monitoring - addresses 15-20 (from meter)
    // ElectricityMeter.ChannelId: CURRENT_L1, CURRENT_L2, CURRENT_L3 (unit: mA)
    // Scale factor 0.001f converts: mA→A
    public static final EvnRegisterMapping CURRENT_L1 = new EvnRegisterMapping(15, "Ia: Phase Current L1 (A)",
            DEFAULT_METER_ID + "/CurrentL1", true, 0.001f);
    public static final EvnRegisterMapping CURRENT_L2 = new EvnRegisterMapping(17, "Ib: Phase Current L2 (A)",
            DEFAULT_METER_ID + "/CurrentL2", true, 0.001f);
    public static final EvnRegisterMapping CURRENT_L3 = new EvnRegisterMapping(19, "Ic: Phase Current L3 (A)",
            DEFAULT_METER_ID + "/CurrentL3", true, 0.001f);

    // Frequency - address 21-22 (from meter)
    // ElectricityMeter.ChannelId: FREQUENCY (unit: Hz, NOT mHz as in some
    // implementations)
    // Scale factor 0.001f converts: mHz→Hz
    public static final EvnRegisterMapping FREQUENCY = new EvnRegisterMapping(21, "f: Grid Frequency (Hz)",
            DEFAULT_METER_ID + "/Frequency", true, 0.001f);

    // Power Factor - address 23-24
    // Note: ElectricityMeter doesn't have a direct PowerFactor channel
    // Use meter's ActivePower and ApparentPower to calculate, or use a specific
    // meter channel
    // For now, using ActivePower as placeholder - adjust per your meter
    // implementation
    public static final EvnRegisterMapping POWER_FACTOR = new EvnRegisterMapping(23, "Cos Phi: Power Factor",
            DEFAULT_METER_ID + "/Pf", true);

    // ==================== INVERTER REGISTERS (Dynamic, starting from address 25)
    // ====================
    // Address formula: 25 + 4*(i-1) for inverter i (1-based index)
    // Each inverter has 2 measurements:
    // - Active Power at address 25 + 4*(i-1)
    // - Production Energy at address 27 + 4*(i-1)

    /**
     * Creates register mapping for PV Inverter i Active Power.
     * Address: 25 + 4*i for 0-based index
     * 
     * @param inverterIndex 0-based index (0 = pvInverter0 @ address 25)
     */
    public static EvnRegisterMapping createInverterActivePower(int inverterIndex) {
        int address = INVERTER_START_ADDRESS + (inverterIndex * REGISTERS_PER_INVERTER);
        return new EvnRegisterMapping(
                address,
                "PV Inverter " + (inverterIndex + 1) + " Active Power (kW)",
                DEFAULT_PVINVERTER_PREFIX + inverterIndex + "/ActivePower",
                true,
                0.001f); // W -> kW
    }

    /**
     * Creates register mapping for PV Inverter i Production Energy.
     * Address: 27 + 4*i for 0-based index
     * 
     * @param inverterIndex 0-based index (0 = pvInverter0 @ address 27)
     */
    public static EvnRegisterMapping createInverterProductionEnergy(int inverterIndex) {
        int address = INVERTER_START_ADDRESS + (inverterIndex * REGISTERS_PER_INVERTER) + 2;
        return new EvnRegisterMapping(
                address,
                "PV Inverter " + (inverterIndex + 1) + " Production Energy (kWh)",
                DEFAULT_PVINVERTER_PREFIX + inverterIndex + "/ActiveProductionEnergy",
                true,
                0.001f); // Wh -> kWh
    }

    // ==================== Helper Methods ====================

    /**
     * Gets all static monitoring register mappings (addresses 1-24).
     */
    public static List<EvnRegisterMapping> getStaticMappings() {
        List<EvnRegisterMapping> mappings = new ArrayList<>();
        // Power monitoring from _sum
        mappings.add(GRID_ACTIVE_POWER); // 1-2: _sum/GridActivePower
        mappings.add(TOTAL_PRODUCTION_POWER); // 3-4: _sum/ProductionActivePower
        mappings.add(PRODUCTION_ENERGY); // 5-6: _sum/ProductionActiveEnergy
        mappings.add(GRID_REACTIVE_POWER); // 7-8: _sum/EssReactivePower
        // Voltage from meter
        mappings.add(VOLTAGE_L1); // 9-10: meter0/VoltageL1
        mappings.add(VOLTAGE_L2); // 11-12: meter0/VoltageL2
        mappings.add(VOLTAGE_L3); // 13-14: meter0/VoltageL3
        // Current from meter
        mappings.add(CURRENT_L1); // 15-16: meter0/CurrentL1
        mappings.add(CURRENT_L2); // 17-18: meter0/CurrentL2
        mappings.add(CURRENT_L3); // 19-20: meter0/CurrentL3
        // Frequency & Power Factor from meter
        mappings.add(FREQUENCY); // 21-22: meter0/Frequency
        mappings.add(POWER_FACTOR); // 23-24: meter0/Pf (as proxy for PF)
        return mappings;
    }

    /**
     * Gets all register mappings including dynamic inverter mappings.
     * 
     * @param inverterCount number of inverters (0 to MAX_INVERTERS)
     */
    public static List<EvnRegisterMapping> getAllMappings(int inverterCount) {
        List<EvnRegisterMapping> mappings = getStaticMappings();

        // Add dynamic inverter mappings starting from address 25
        int count = Math.min(inverterCount, MAX_INVERTERS);
        for (int i = 0; i < count; i++) {
            mappings.add(createInverterActivePower(i)); // 25, 29, 33, ...
            mappings.add(createInverterProductionEnergy(i)); // 27, 31, 35, ...
        }

        return mappings;
    }

    /**
     * Default: 2 inverters for backward compatibility.
     */
    public static List<EvnRegisterMapping> values() {
        return getAllMappings(2);
    }

    // ==================== Getters ====================

    public int getAddress() {
        return this.address;
    }

    public String getDescription() {
        return this.description;
    }

    public String getChannelAddress() {
        return this.channelAddress;
    }

    public boolean isFloat() {
        return this.isFloat;
    }

    public int getRegisterCount() {
        return this.isFloat ? 2 : 1;
    }

    public float getScaleFactor() {
        return this.scaleFactor;
    }

    public String getComponentId() {
        if (this.channelAddress.contains("/")) {
            return this.channelAddress.split("/")[0];
        }
        return this.channelAddress;
    }

    public String getChannelId() {
        if (this.channelAddress.contains("/")) {
            String[] parts = this.channelAddress.split("/");
            if (parts.length > 1) {
                return parts[1];
            }
        }
        return this.channelAddress;
    }

    @Override
    public String toString() {
        return "Reg" + address + ": " + description + " -> " + channelAddress;
    }
}
