package io.openems.edge.controller.api.modbus.evn;

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

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;

/**
 * Custom Process Image for EVN Modbus TCP.
 * Implements ProcessImage interface to support both READ and WRITE operations.
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
 * FC03: Read Holding Registers - returns control register values (EVN writes here)
 * FC04: Read Input Registers - returns monitoring data from OpenEMS channels
 * FC06: Write Single Register - j2mod writes to our holdingRegisters array
 * FC16: Write Multiple Registers - j2mod writes to our holdingRegisters array
 */
public class EvnProcessImage implements ProcessImage {

    private static final Logger LOG = LoggerFactory.getLogger(EvnProcessImage.class);
    private static final int DEFAULT_INVERTER_COUNT = 10;
    private static final int MAX_REGISTER_ADDRESS = 200;

    private final ComponentManager componentManager;
    private final int inverterCount;
    private final java.util.List<EvnRegisterMapping> mappings;

    /**
     * Holding Registers array - these are the registers that EVN can write to.
     * j2mod calls getRegister(addr) and then calls setValue() on the returned object.
     * Because we return the SAME object from this array, the value gets updated.
     */
    private final SimpleRegister[] holdingRegisters;

    public EvnProcessImage(ComponentManager componentManager) {
        this(componentManager, DEFAULT_INVERTER_COUNT);
    }

    public EvnProcessImage(ComponentManager componentManager, int inverterCount) {
        this.componentManager = componentManager;
        this.inverterCount = inverterCount;
        this.mappings = EvnRegisterMapping.getAllMappings(inverterCount);

        // Initialize holding registers for control commands
        // These are SimpleRegister objects which have setValue() method
        this.holdingRegisters = new SimpleRegister[MAX_REGISTER_ADDRESS];
        for (int i = 0; i < MAX_REGISTER_ADDRESS; i++) {
            this.holdingRegisters[i] = new SimpleRegister(0);
        }

        LOG.info("EVN ProcessImage initialized with {} mappings, {} holding registers for write support",
                mappings.size(), MAX_REGISTER_ADDRESS);
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
     * Reads current float value from OpenEMS channel.
     */
    private float readChannelValue(String componentId, String channelId) {
        try {
            OpenemsComponent component = this.componentManager.getComponent(componentId);
            if (component == null) {
                return 0f;
            }

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

        } catch (OpenemsNamedException | IllegalArgumentException e) {
            return 0f;
        }
    }

    /**
     * Gets float value for a register address.
     */
    private float getFloatValueForAddress(int address) {
        for (EvnRegisterMapping mapping : this.mappings) {
            int baseAddr = mapping.getAddress();
            if (address == baseAddr || address == baseAddr + 1) {
                float rawValue = readChannelValue(mapping.getComponentId(), mapping.getChannelId());
                return rawValue * mapping.getScaleFactor();
            }
        }
        return 0f;
    }

    /**
     * Gets the base address for a register (start of the float).
     */
    private int getBaseAddress(int address) {
        for (EvnRegisterMapping mapping : this.mappings) {
            int baseAddr = mapping.getAddress();
            if (address == baseAddr || address == baseAddr + 1) {
                return baseAddr;
            }
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
            int baseAddr = getBaseAddress(address);

            if (baseAddr >= 0) {
                float value = getFloatValueForAddress(address);
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

        int baseAddr = getBaseAddress(ref);
        if (baseAddr >= 0) {
            float value = getFloatValueForAddress(ref);
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
    // 
    // CRITICAL: getRegister() must return the SAME SimpleRegister object from our array.
    // When j2mod receives FC06/FC16, it:
    //   1. Calls getRegister(address) to get the Register object
    //   2. Calls register.setValue(newValue) on that object
    // Since we return the SAME object from holdingRegisters[], the value is stored!

    @Override
    public synchronized Register getRegister(int ref) throws IllegalAddressException {
        if (ref < 0 || ref >= MAX_REGISTER_ADDRESS) {
            LOG.warn("EVN getRegister({}) - Address out of range", ref);
            throw new IllegalAddressException("Address " + ref + " out of range (0-" + (MAX_REGISTER_ADDRESS - 1) + ")");
        }

        // Return the SAME Register object - this is critical for FC06/FC16 to work!
        // j2mod will call setValue() on this object to store the written value.
        LOG.debug("EVN getRegister({}) - returning holding register (current value={})",
                ref, this.holdingRegisters[ref].getValue());
        return this.holdingRegisters[ref];
    }

    @Override
    public synchronized Register[] getRegisterRange(int offset, int count) throws IllegalAddressException {
        LOG.debug("EVN getRegisterRange({}, {}) - Holding Registers", offset, count);

        if (offset < 0 || offset + count > MAX_REGISTER_ADDRESS) {
            throw new IllegalAddressException("Address range " + offset + "-" + (offset + count - 1) + " out of range");
        }

        Register[] result = new Register[count];
        for (int i = 0; i < count; i++) {
            // Return the SAME Register objects from our array
            result[i] = this.holdingRegisters[offset + i];
        }
        return result;
    }

    @Override
    public int getRegisterCount() {
        return MAX_REGISTER_ADDRESS;
    }

    // ==================== GETTERS FOR EVN COMMANDS ====================
    // Read values that were written by EVN via FC06/FC16

    /**
     * Get value from a holding register.
     */
    private int getHoldingRegisterValue(int address) {
        if (address < 0 || address >= MAX_REGISTER_ADDRESS) {
            return 0;
        }
        return this.holdingRegisters[address].getValue();
    }

    /**
     * Get float value from 2 consecutive holding registers.
     */
    private float getHoldingRegisterFloat(int baseAddress) {
        if (baseAddress < 0 || baseAddress + 1 >= MAX_REGISTER_ADDRESS) {
            return 0f;
        }
        int highWord = this.holdingRegisters[baseAddress].getValue();
        int lowWord = this.holdingRegisters[baseAddress + 1].getValue();
        int intBits = (highWord << 16) | (lowWord & 0xFFFF);
        return Float.intBitsToFloat(intBits);
    }

    /** Is P-out control enabled by EVN? */
    public boolean isPOutEnabled() {
        int value = getHoldingRegisterValue(EvnWriteRegisters.P_OUT_ENABLE_ADDRESS);
        return value != 0;
    }

    /** Get P-out Setpoint in % (0-100). */
    public float getPOutSetpointPercent() {
        return getHoldingRegisterFloat(EvnWriteRegisters.P_OUT_SETPOINT_PERCENT_ADDRESS);
    }

    /** Get P-out Setpoint in kW. */
    public float getPOutSetpointKw() {
        return getHoldingRegisterFloat(EvnWriteRegisters.P_OUT_SETPOINT_KW_ADDRESS);
    }

    /** Is Q-out control enabled by EVN? */
    public boolean isQOutEnabled() {
        int value = getHoldingRegisterValue(EvnWriteRegisters.Q_OUT_ENABLE_ADDRESS);
        return value != 0;
    }

    /** Get Q-out Setpoint in % (-100 to +100). */
    public float getQOutSetpointPercent() {
        return getHoldingRegisterFloat(EvnWriteRegisters.Q_OUT_SETPOINT_PERCENT_ADDRESS);
    }

    /** Get Q-out Setpoint in kvar. */
    public float getQOutSetpointKvar() {
        return getHoldingRegisterFloat(EvnWriteRegisters.Q_OUT_SETPOINT_KVAR_ADDRESS);
    }

    // ==================== Debug logging ====================

    /**
     * Log all control register values for debugging.
     */
    public void logControlRegisterValues() {
        LOG.info("=== EVN Control Register Values (written by FC06/FC16) ===");
        LOG.info("P-out Enable (Reg {}): {} (raw=0x{})",
                EvnWriteRegisters.P_OUT_ENABLE_ADDRESS,
                isPOutEnabled() ? "ENABLED" : "DISABLED",
                String.format("%04X", getHoldingRegisterValue(EvnWriteRegisters.P_OUT_ENABLE_ADDRESS)));
        LOG.info("P-out % (Reg {}-{}): {} %",
                EvnWriteRegisters.P_OUT_SETPOINT_PERCENT_ADDRESS,
                EvnWriteRegisters.P_OUT_SETPOINT_PERCENT_ADDRESS + 1,
                getPOutSetpointPercent());
        LOG.info("P-out kW (Reg {}-{}): {} kW",
                EvnWriteRegisters.P_OUT_SETPOINT_KW_ADDRESS,
                EvnWriteRegisters.P_OUT_SETPOINT_KW_ADDRESS + 1,
                getPOutSetpointKw());
        LOG.info("Q-out Enable (Reg {}): {} (raw=0x{})",
                EvnWriteRegisters.Q_OUT_ENABLE_ADDRESS,
                isQOutEnabled() ? "ENABLED" : "DISABLED",
                String.format("%04X", getHoldingRegisterValue(EvnWriteRegisters.Q_OUT_ENABLE_ADDRESS)));
        LOG.info("Q-out % (Reg {}-{}): {} %",
                EvnWriteRegisters.Q_OUT_SETPOINT_PERCENT_ADDRESS,
                EvnWriteRegisters.Q_OUT_SETPOINT_PERCENT_ADDRESS + 1,
                getQOutSetpointPercent());
        LOG.info("Q-out kvar (Reg {}-{}): {} kvar",
                EvnWriteRegisters.Q_OUT_SETPOINT_KVAR_ADDRESS,
                EvnWriteRegisters.Q_OUT_SETPOINT_KVAR_ADDRESS + 1,
                getQOutSetpointKvar());
        LOG.info("=========================================================");
    }

    // ==================== Not implemented (coils, files, etc.) ====================

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
