package io.openems.edge.controller.api.modbus.evn;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ghgande.j2mod.modbus.procimg.DigitalIn;
import com.ghgande.j2mod.modbus.procimg.DigitalOut;
import com.ghgande.j2mod.modbus.procimg.FIFO;
import com.ghgande.j2mod.modbus.procimg.File;
import com.ghgande.j2mod.modbus.procimg.IllegalAddressException;
import com.ghgande.j2mod.modbus.procimg.InputRegister;
import com.ghgande.j2mod.modbus.procimg.ProcessImage;
import com.ghgande.j2mod.modbus.procimg.Register;
import com.ghgande.j2mod.modbus.procimg.SimpleDigitalIn;
import com.ghgande.j2mod.modbus.procimg.SimpleDigitalOut;
import com.ghgande.j2mod.modbus.procimg.SimpleInputRegister;
import com.ghgande.j2mod.modbus.procimg.SimpleRegister;

import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;

/**
 * Custom Process Image for EVN Modbus TCP.
 * Implements ProcessImage interface to support both READ and WRITE operations.
 * 
 * <p>
 * Uses auto-discovered meters and PV inverters from ComponentManager.
 * 
 * <p>
 * Key mechanism for FC06/FC16 write support:
 * <ul>
 * <li>Holding Registers (address 0-199) are stored as SimpleRegister objects</li>
 * <li>When j2mod receives FC06/FC16, it calls getRegister(addr) to get the Register object</li>
 * <li>Then j2mod calls register.setValue(value) DIRECTLY on the returned object</li>
 * <li>Since we return the SAME Register object, the value is updated in our array</li>
 * </ul>
 * 
 * <p>
 * Register Map:
 * <ul>
 * <li>1-2: Grid Active Power (kW) - _sum/GridActivePower</li>
 * <li>3-4: Total Production Power (kW) - _sum/ProductionActivePower</li>
 * <li>5-6: Production Energy (kWh) - _sum/ProductionActiveEnergy</li>
 * <li>7-8: Grid Reactive Power (kVar) - _sum/EssReactivePower</li>
 * <li>9-10: Voltage L1 (V) - first meter's VoltageL1</li>
 * <li>11: P-out Enable (write)</li>
 * <li>12: Q-out Enable (write)</li>
 * <li>13-14: P-out Setpoint % (write)</li>
 * <li>15-16: P-out Setpoint kW (write)</li>
 * <li>17-18: Q-out Setpoint % (write)</li>
 * <li>19-20: Q-out Setpoint kvar (write)</li>
 * <li>21-22: Frequency (Hz)</li>
 * <li>23-24: Power Factor</li>
 * <li>25+: PV Inverter data (4 registers per inverter)</li>
 * </ul>
 */
public class EvnProcessImage implements ProcessImage {

    private static final Logger LOG = LoggerFactory.getLogger(EvnProcessImage.class);
    private static final int MAX_REGISTER_ADDRESS = 500;
    
    // Register addresses for inverters start at 25
    private static final int INVERTER_START_ADDRESS = 25;
    private static final int REGISTERS_PER_INVERTER = 4;

    private final ComponentManager componentManager;
    private final List<ElectricityMeter> meters;
    private final List<ManagedSymmetricPvInverter> inverters;

    /**
     * Holding Registers array - these are the registers that EVN can write to.
     * j2mod calls getRegister(addr) and then calls setValue() on the returned object.
     * Because we return the SAME object from this array, the value gets updated.
     */
    private final SimpleRegister[] holdingRegisters;

    /**
     * Constructs EvnProcessImage with auto-discovered components.
     * 
     * @param componentManager ComponentManager for reading channel values
     * @param meters           List of discovered meters
     * @param inverters        List of discovered PV inverters
     */
    public EvnProcessImage(ComponentManager componentManager, 
                           List<ElectricityMeter> meters,
                           List<ManagedSymmetricPvInverter> inverters) {
        this.componentManager = componentManager;
        this.meters = new ArrayList<>(meters);
        this.inverters = new ArrayList<>(inverters);

        // Initialize holding registers for control commands
        this.holdingRegisters = new SimpleRegister[MAX_REGISTER_ADDRESS];
        for (int i = 0; i < MAX_REGISTER_ADDRESS; i++) {
            this.holdingRegisters[i] = new SimpleRegister(0);
        }

        LOG.info("EVN ProcessImage initialized with {} meters, {} inverters",
                this.meters.size(), this.inverters.size());
        
        // Log discovered components
        for (int i = 0; i < this.meters.size(); i++) {
            LOG.info("  Meter[{}]: {} (alias: {})", i, this.meters.get(i).id(), this.meters.get(i).alias());
        }
        for (int i = 0; i < this.inverters.size(); i++) {
            int addr = INVERTER_START_ADDRESS + (i * REGISTERS_PER_INVERTER);
            LOG.info("  Inverter[{}]: {} (alias: {}) -> Registers {}-{}", 
                    i, this.inverters.get(i).id(), this.inverters.get(i).alias(), addr, addr + 3);
        }
        
        LOG.info("Control registers: P-out Enable={}, Q-out Enable={}, P-out%={}-{}, P-out kW={}-{}, Q-out%={}-{}, Q-out kvar={}-{}",
                EvnWriteRegisters.P_OUT_ENABLE_ADDRESS,
                EvnWriteRegisters.Q_OUT_ENABLE_ADDRESS,
                EvnWriteRegisters.P_OUT_SETPOINT_PERCENT_ADDRESS,
                EvnWriteRegisters.P_OUT_SETPOINT_PERCENT_ADDRESS + 1,
                EvnWriteRegisters.P_OUT_SETPOINT_KW_ADDRESS,
                EvnWriteRegisters.P_OUT_SETPOINT_KW_ADDRESS + 1,
                EvnWriteRegisters.Q_OUT_SETPOINT_PERCENT_ADDRESS,
                EvnWriteRegisters.Q_OUT_SETPOINT_PERCENT_ADDRESS + 1,
                EvnWriteRegisters.Q_OUT_SETPOINT_KVAR_ADDRESS,
                EvnWriteRegisters.Q_OUT_SETPOINT_KVAR_ADDRESS + 1);
    }

    /**
     * Reads a float value from a channel.
     */
    private float readChannelFloat(OpenemsComponent component, String channelId) {
        try {
            Channel<?> channel = component.channel(channelId);
            if (channel == null) {
                return 0f;
            }

            var value = channel.value();
            if (!value.isDefined()) {
                return 0f;
            }

            Object obj = value.get();
            if (obj instanceof Number) {
                return ((Number) obj).floatValue();
            }
            return 0f;
        } catch (IllegalArgumentException e) {
            return 0f;
        }
    }

    /**
     * Reads an integer value from a channel.
     */
    private int readChannelInt(OpenemsComponent component, String channelId) {
        try {
            Channel<?> channel = component.channel(channelId);
            if (channel == null) {
                return 0;
            }

            var value = channel.value();
            if (!value.isDefined()) {
                return 0;
            }

            Object obj = value.get();
            if (obj instanceof Number) {
                return ((Number) obj).intValue();
            }
            return 0;
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    /**
     * Gets the first meter or null.
     */
    private ElectricityMeter getFirstMeter() {
        return this.meters.isEmpty() ? null : this.meters.get(0);
    }

    /**
     * Reads monitoring data for a specific register address.
     * Returns scaled float value in EVN units (kW, V, A, Hz).
     */
    private float readMonitoringValue(int address) {
        ElectricityMeter meter = getFirstMeter();
        
        // Static monitoring registers (1-24)
        switch (address) {
            case 1: case 2: // Grid Active Power (kW) - _sum
                try {
                    OpenemsComponent sum = this.componentManager.getComponent("_sum");
                    return readChannelFloat(sum, "GridActivePower") * 0.001f; // W -> kW
                } catch (Exception e) {
                    return 0f;
                }
                
            case 3: case 4: // Total Production Power (kW) - _sum
                try {
                    OpenemsComponent sum = this.componentManager.getComponent("_sum");
                    return readChannelFloat(sum, "ProductionActivePower") * 0.001f; // W -> kW
                } catch (Exception e) {
                    return 0f;
                }
                
            case 5: case 6: // Production Energy (kWh) - _sum
                try {
                    OpenemsComponent sum = this.componentManager.getComponent("_sum");
                    return readChannelFloat(sum, "ProductionActiveEnergy") * 0.001f; // Wh -> kWh
                } catch (Exception e) {
                    return 0f;
                }
                
            case 7: case 8: // Grid Reactive Power (kVar) - _sum
                try {
                    OpenemsComponent sum = this.componentManager.getComponent("_sum");
                    return readChannelFloat(sum, "GridReactivePower") * 0.001f; // var -> kVar
                } catch (Exception e) {
                    return 0f;
                }
                
            case 9: case 10: // Voltage L1 (V) - first meter
                if (meter != null) {
                    return readChannelFloat(meter, "VoltageL1") * 0.001f; // mV -> V
                }
                return 0f;
                
            case 21: case 22: // Frequency (Hz) - first meter
                if (meter != null) {
                    return readChannelFloat(meter, "Frequency") * 0.001f; // mHz -> Hz
                }
                return 0f;
                
            case 23: case 24: // Power Factor - first meter
                if (meter != null) {
                    // Some meters expose Pf in different scales
                    float pf = readChannelFloat(meter, "Pf");
                    if (Math.abs(pf) > 1.0f) {
                        pf = pf * 0.001f; // Scale if needed
                    }
                    return pf;
                }
                return 0f;
                
            default:
                // Check if this is an inverter register
                if (address >= INVERTER_START_ADDRESS) {
                    return readInverterValue(address);
                }
                return 0f;
        }
    }

    /**
     * Reads PV inverter data for a specific register address.
     */
    private float readInverterValue(int address) {
        int relativeAddr = address - INVERTER_START_ADDRESS;
        int inverterIndex = relativeAddr / REGISTERS_PER_INVERTER;
        int registerOffset = relativeAddr % REGISTERS_PER_INVERTER;
        
        if (inverterIndex < 0 || inverterIndex >= this.inverters.size()) {
            return 0f;
        }
        
        ManagedSymmetricPvInverter inv = this.inverters.get(inverterIndex);
        
        // Each inverter has: ActivePower (2 regs), ProductionEnergy (2 regs)
        switch (registerOffset) {
            case 0: case 1: // Active Power (kW)
                return readChannelFloat(inv, "ActivePower") * 0.001f; // W -> kW
            case 2: case 3: // Production Energy (kWh)
                return readChannelFloat(inv, "ActiveProductionEnergy") * 0.001f; // Wh -> kWh
            default:
                return 0f;
        }
    }

    /**
     * Gets the base address for a float register pair.
     */
    private int getFloatBaseAddress(int address) {
        // Static registers: 1-2, 3-4, 5-6, 7-8, 9-10, 21-22, 23-24
        if (address >= 1 && address <= 10) {
            return ((address - 1) / 2) * 2 + 1;
        }
        if (address >= 21 && address <= 24) {
            return ((address - 21) / 2) * 2 + 21;
        }
        // Inverter registers start at 25
        if (address >= INVERTER_START_ADDRESS) {
            int relativeAddr = address - INVERTER_START_ADDRESS;
            int baseOffset = (relativeAddr / 2) * 2;
            return INVERTER_START_ADDRESS + baseOffset;
        }
        return -1;
    }

    /**
     * Creates words for a float value [highWord, lowWord].
     */
    private int[] createFloatWords(float value) {
        int intBits = Float.floatToIntBits(value);
        int highWord = (intBits >> 16) & 0xFFFF;
        int lowWord = intBits & 0xFFFF;
        return new int[] { highWord, lowWord };
    }

    // ==================== FC04 - Read Input Registers (Monitoring Data) ====================

    @Override
    public synchronized InputRegister[] getInputRegisterRange(int offset, int count) throws IllegalAddressException {
        LOG.debug("EVN FC4 getInputRegisterRange({}, {}) - Monitoring Data", offset, count);

        InputRegister[] result = new InputRegister[count];

        for (int i = 0; i < count; i++) {
            int address = offset + i;
            int baseAddr = getFloatBaseAddress(address);

            if (baseAddr >= 0) {
                float value = readMonitoringValue(baseAddr);
                int[] words = createFloatWords(value);

                if (address == baseAddr) {
                    result[i] = new SimpleInputRegister(words[0]);
                } else {
                    result[i] = new SimpleInputRegister(words[1]);
                }
            } else {
                result[i] = new SimpleInputRegister(0);
            }
        }

        return result;
    }

    @Override
    public synchronized InputRegister getInputRegister(int ref) throws IllegalAddressException {
        LOG.debug("EVN FC4 getInputRegister({}) - Monitoring Data", ref);

        int baseAddr = getFloatBaseAddress(ref);
        if (baseAddr >= 0) {
            float value = readMonitoringValue(baseAddr);
            int[] words = createFloatWords(value);
            return new SimpleInputRegister(ref == baseAddr ? words[0] : words[1]);
        }

        return new SimpleInputRegister(0);
    }

    @Override
    public int getInputRegisterCount() {
        return MAX_REGISTER_ADDRESS;
    }

    // ==================== FC03 - Read Holding Registers ====================
    // ==================== FC06/FC16 - Write Holding Registers ====================

    @Override
    public synchronized Register getRegister(int ref) throws IllegalAddressException {
        if (ref < 0 || ref >= MAX_REGISTER_ADDRESS) {
            throw new IllegalAddressException("Address " + ref + " out of range");
        }
        return this.holdingRegisters[ref];
    }

    @Override
    public synchronized Register[] getRegisterRange(int offset, int count) throws IllegalAddressException {
        if (offset < 0 || offset + count > MAX_REGISTER_ADDRESS) {
            throw new IllegalAddressException("Address range " + offset + "-" + (offset + count - 1) + " out of range");
        }

        Register[] result = new Register[count];
        for (int i = 0; i < count; i++) {
            result[i] = this.holdingRegisters[offset + i];
        }
        return result;
    }

    @Override
    public int getRegisterCount() {
        return MAX_REGISTER_ADDRESS;
    }

    // ==================== GETTERS FOR EVN COMMANDS ====================

    private int getHoldingRegisterValue(int address) {
        if (address < 0 || address >= MAX_REGISTER_ADDRESS) {
            return 0;
        }
        return this.holdingRegisters[address].getValue();
    }

    private float getHoldingRegisterFloat(int baseAddress) {
        if (baseAddress < 0 || baseAddress + 1 >= MAX_REGISTER_ADDRESS) {
            return 0f;
        }
        int highWord = this.holdingRegisters[baseAddress].getValue();
        int lowWord = this.holdingRegisters[baseAddress + 1].getValue();
        int intBits = (highWord << 16) | (lowWord & 0xFFFF);
        return Float.intBitsToFloat(intBits);
    }

    public boolean isPOutEnabled() {
        return getHoldingRegisterValue(EvnWriteRegisters.P_OUT_ENABLE_ADDRESS) != 0;
    }

    public float getPOutSetpointPercent() {
        return getHoldingRegisterFloat(EvnWriteRegisters.P_OUT_SETPOINT_PERCENT_ADDRESS);
    }

    public float getPOutSetpointKw() {
        return getHoldingRegisterFloat(EvnWriteRegisters.P_OUT_SETPOINT_KW_ADDRESS);
    }

    public boolean isQOutEnabled() {
        return getHoldingRegisterValue(EvnWriteRegisters.Q_OUT_ENABLE_ADDRESS) != 0;
    }

    public float getQOutSetpointPercent() {
        return getHoldingRegisterFloat(EvnWriteRegisters.Q_OUT_SETPOINT_PERCENT_ADDRESS);
    }

    public float getQOutSetpointKvar() {
        return getHoldingRegisterFloat(EvnWriteRegisters.Q_OUT_SETPOINT_KVAR_ADDRESS);
    }

    // ==================== Debug logging ====================

    public void logControlRegisterValues() {
        LOG.info("=== EVN Control Register Values ===");
        LOG.info("P-out Enable (Reg {}): {}",
                EvnWriteRegisters.P_OUT_ENABLE_ADDRESS,
                isPOutEnabled() ? "ENABLED" : "DISABLED");
        LOG.info("P-out % (Reg {}-{}): {} %",
                EvnWriteRegisters.P_OUT_SETPOINT_PERCENT_ADDRESS,
                EvnWriteRegisters.P_OUT_SETPOINT_PERCENT_ADDRESS + 1,
                getPOutSetpointPercent());
        LOG.info("P-out kW (Reg {}-{}): {} kW",
                EvnWriteRegisters.P_OUT_SETPOINT_KW_ADDRESS,
                EvnWriteRegisters.P_OUT_SETPOINT_KW_ADDRESS + 1,
                getPOutSetpointKw());
        LOG.info("Q-out Enable (Reg {}): {}",
                EvnWriteRegisters.Q_OUT_ENABLE_ADDRESS,
                isQOutEnabled() ? "ENABLED" : "DISABLED");
        LOG.info("Q-out % (Reg {}-{}): {} %",
                EvnWriteRegisters.Q_OUT_SETPOINT_PERCENT_ADDRESS,
                EvnWriteRegisters.Q_OUT_SETPOINT_PERCENT_ADDRESS + 1,
                getQOutSetpointPercent());
        LOG.info("Q-out kvar (Reg {}-{}): {} kvar",
                EvnWriteRegisters.Q_OUT_SETPOINT_KVAR_ADDRESS,
                EvnWriteRegisters.Q_OUT_SETPOINT_KVAR_ADDRESS + 1,
                getQOutSetpointKvar());
        LOG.info("===================================");
    }

    // ==================== Not implemented ====================

    @Override
    public DigitalOut[] getDigitalOutRange(int offset, int count) {
        DigitalOut[] result = new DigitalOut[count];
        for (int i = 0; i < count; i++) {
            result[i] = new SimpleDigitalOut(false);
        }
        return result;
    }

    @Override
    public DigitalOut getDigitalOut(int ref) {
        return new SimpleDigitalOut(false);
    }

    @Override
    public int getDigitalOutCount() {
        return 0;
    }

    @Override
    public DigitalIn[] getDigitalInRange(int offset, int count) {
        DigitalIn[] result = new DigitalIn[count];
        for (int i = 0; i < count; i++) {
            result[i] = new SimpleDigitalIn(false);
        }
        return result;
    }

    @Override
    public DigitalIn getDigitalIn(int ref) {
        return new SimpleDigitalIn(false);
    }

    @Override
    public int getDigitalInCount() {
        return 0;
    }

    @Override
    public File getFile(int ref) {
        return null;
    }

    @Override
    public File getFileByNumber(int ref) {
        return null;
    }

    @Override
    public int getFileCount() {
        return 0;
    }

    @Override
    public FIFO getFIFO(int ref) {
        return null;
    }

    @Override
    public FIFO getFIFOByAddress(int ref) {
        return null;
    }

    @Override
    public int getFIFOCount() {
        return 0;
    }
}
