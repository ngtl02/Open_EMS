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
 * Implements ProcessImage interface directly (like MyProcessImage).
 * Supports both READ (monitoring) and WRITE (control commands) operations.
 */
public class EvnProcessImage implements ProcessImage {

    private static final Logger LOG = LoggerFactory.getLogger(EvnProcessImage.class);
    private static final int DEFAULT_INVERTER_COUNT = 10;

    private final ComponentManager componentManager;
    private final int inverterCount;
    private final java.util.List<EvnRegisterMapping> mappings;

    // ==================== EVN WRITE COMMAND STATE ====================
    // These are set by EVN via Modbus write operations (FC06/FC16)
    // According to EVN specification

    /** P-out Enable: 0=Disabled, 1=Enabled (Address 11) */
    private volatile int pOutEnabled = 0;

    /** P-out Setpoint in % (Address 13-14) */
    private volatile float pOutSetpointPercent = 0f;

    /** P-out Setpoint in kW (Address 15-16) */
    private volatile float pOutSetpointKw = 0f;

    /** Q-out Enable: 0=Disabled, 1=Enabled (Address 12) */
    private volatile int qOutEnabled = 0;

    /** Q-out Setpoint in % (Address 17-18) */
    private volatile float qOutSetpointPercent = 0f;

    /** Q-out Setpoint in kvar (Address 19-20) */
    private volatile float qOutSetpointKvar = 0f;

    /** Temporary storage for float high word during 2-register writes */
    private volatile int tempHighWord = 0;
    private volatile int lastWriteAddress = -1;

    public EvnProcessImage(ComponentManager componentManager) {
        this(componentManager, DEFAULT_INVERTER_COUNT);
    }

    public EvnProcessImage(ComponentManager componentManager, int inverterCount) {
        this.componentManager = componentManager;
        this.inverterCount = inverterCount;
        this.mappings = EvnRegisterMapping.getAllMappings(inverterCount);
        LOG.info("EVN ProcessImage initialized with {} mappings", mappings.size());
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
     * Returns the scaled float value if this is a mapped address, or 0 for
     * unmapped.
     * Scale factor converts internal units (mV, mA, W) to EVN units (V, A, kW).
     */
    private float getFloatValueForAddress(int address) {
        // Find mapping for this address (could be high or low word)
        for (EvnRegisterMapping mapping : this.mappings) {
            int baseAddr = mapping.getAddress();
            if (address == baseAddr || address == baseAddr + 1) {
                float rawValue = readChannelValue(mapping.getComponentId(), mapping.getChannelId());
                // Apply scale factor: e.g., 0.001 converts W->kW, mV->V, mA->A
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
     * Creates registers for a float value.
     * Returns 2 registers [highWord, lowWord].
     */
    private Register[] createFloatRegisters(float value) {
        int intBits = Float.floatToIntBits(value);
        byte[] bytes = new byte[4];
        bytes[0] = (byte) ((intBits >> 24) & 0xFF);
        bytes[1] = (byte) ((intBits >> 16) & 0xFF);
        bytes[2] = (byte) ((intBits >> 8) & 0xFF);
        bytes[3] = (byte) (intBits & 0xFF);

        return new Register[] {
                new SimpleInputRegister(bytes[0], bytes[1]), // High word
                new SimpleInputRegister(bytes[2], bytes[3]) // Low word
        };
    }

    @Override
    public synchronized InputRegister[] getInputRegisterRange(int offset, int count) throws IllegalAddressException {
        LOG.info("EVN getInputRegisterRange({}, {})", offset, count);

        Register[] registers = getRegisterRange(offset, count);
        InputRegister[] result = new InputRegister[registers.length];
        for (int i = 0; i < registers.length; i++) {
            result[i] = registers[i];
        }
        return result;
    }

    @Override
    public synchronized Register[] getRegisterRange(int offset, int count) throws IllegalAddressException {
        LOG.info("EVN getRegisterRange({}, {})", offset, count);

        Register[] result = new Register[count];

        for (int i = 0; i < count; i++) {
            int address = offset + i;
            int baseAddr = getBaseAddress(address);

            if (baseAddr >= 0) {
                // This is a mapped float register
                float value = getFloatValueForAddress(address);
                Register[] floatRegs = createFloatRegisters(value);

                if (address == baseAddr) {
                    // High word
                    result[i] = floatRegs[0];
                    LOG.info("  Reg[{}] = 0x{} (high word, float={})",
                            address, String.format("%04X", floatRegs[0].getValue()), value);
                } else {
                    // Low word
                    result[i] = floatRegs[1];
                    LOG.info("  Reg[{}] = 0x{} (low word)",
                            address, String.format("%04X", floatRegs[1].getValue()));
                }
            } else {
                // Unmapped - return 0
                result[i] = new SimpleInputRegister(0);
            }
        }

        return result;
    }

    @Override
    public synchronized Register getRegister(int ref) throws IllegalAddressException {
        LOG.debug("EVN getRegister({})", ref);

        int baseAddr = getBaseAddress(ref);
        if (baseAddr >= 0) {
            float value = getFloatValueForAddress(ref);
            Register[] floatRegs = createFloatRegisters(value);
            return (ref == baseAddr) ? floatRegs[0] : floatRegs[1];
        }

        return new SimpleInputRegister(0);
    }

    @Override
    public synchronized InputRegister getInputRegister(int ref) throws IllegalAddressException {
        return (InputRegister) getRegister(ref);
    }

    // ==================== WRITE OPERATIONS (FC06/FC16) ====================

    /**
     * Set single register value (FC06).
     * Note: ProcessImage interface doesn't define this, we add it for write
     * support.
     */
    public synchronized void setRegister(int ref, Register reg) throws IllegalAddressException {
        int value = reg.getValue();
        handleWriteRegister(ref, value);
    }

    /**
     * Set multiple registers (FC16).
     * Note: ProcessImage interface doesn't define this, we add it for write
     * support.
     */
    public synchronized void setRegisterRange(int ref, Register[] regs) throws IllegalAddressException {
        LOG.info("EVN setRegisterRange({}, count={})", ref, regs.length);
        for (int i = 0; i < regs.length; i++) {
            handleWriteRegister(ref + i, regs[i].getValue());
        }
    }

    /**
     * Handle write to a single register address.
     * Based on EVN specification for control registers.
     */
    private void handleWriteRegister(int address, int value) {
        LOG.info("EVN WRITE: address={}, value={} (0x{})", address, value, String.format("%04X", value));

        switch (address) {
            // ========== P-out Control ==========
            case EvnWriteRegisters.P_OUT_ENABLE_ADDRESS: // 11
                this.pOutEnabled = value;
                LOG.info("EVN SET P-out Enable = {} ({})", value, value == 0 ? "Disabled" : "Enabled");
                break;

            case EvnWriteRegisters.P_OUT_SETPOINT_PERCENT_ADDRESS: // 13 - High word
                this.tempHighWord = value;
                this.lastWriteAddress = address;
                break;

            case EvnWriteRegisters.P_OUT_SETPOINT_PERCENT_ADDRESS + 1: // 14 - Low word
                if (this.lastWriteAddress == EvnWriteRegisters.P_OUT_SETPOINT_PERCENT_ADDRESS) {
                    this.pOutSetpointPercent = combineToFloat(this.tempHighWord, value);
                    LOG.info("EVN SET P-out Setpoint = {} %", this.pOutSetpointPercent);
                }
                this.lastWriteAddress = -1;
                break;

            case EvnWriteRegisters.P_OUT_SETPOINT_KW_ADDRESS: // 15 - High word
                this.tempHighWord = value;
                this.lastWriteAddress = address;
                break;

            case EvnWriteRegisters.P_OUT_SETPOINT_KW_ADDRESS + 1: // 16 - Low word
                if (this.lastWriteAddress == EvnWriteRegisters.P_OUT_SETPOINT_KW_ADDRESS) {
                    this.pOutSetpointKw = combineToFloat(this.tempHighWord, value);
                    LOG.info("EVN SET P-out Setpoint = {} kW", this.pOutSetpointKw);
                }
                this.lastWriteAddress = -1;
                break;

            // ========== Q-out Control ==========
            case EvnWriteRegisters.Q_OUT_ENABLE_ADDRESS: // 12
                this.qOutEnabled = value;
                LOG.info("EVN SET Q-out Enable = {} ({})", value, value == 0 ? "Disabled" : "Enabled");
                break;

            case EvnWriteRegisters.Q_OUT_SETPOINT_PERCENT_ADDRESS: // 17 - High word
                this.tempHighWord = value;
                this.lastWriteAddress = address;
                break;

            case EvnWriteRegisters.Q_OUT_SETPOINT_PERCENT_ADDRESS + 1: // 18 - Low word
                if (this.lastWriteAddress == EvnWriteRegisters.Q_OUT_SETPOINT_PERCENT_ADDRESS) {
                    this.qOutSetpointPercent = combineToFloat(this.tempHighWord, value);
                    LOG.info("EVN SET Q-out Setpoint = {} %", this.qOutSetpointPercent);
                }
                this.lastWriteAddress = -1;
                break;

            case EvnWriteRegisters.Q_OUT_SETPOINT_KVAR_ADDRESS: // 19 - High word
                this.tempHighWord = value;
                this.lastWriteAddress = address;
                break;

            case EvnWriteRegisters.Q_OUT_SETPOINT_KVAR_ADDRESS + 1: // 20 - Low word
                if (this.lastWriteAddress == EvnWriteRegisters.Q_OUT_SETPOINT_KVAR_ADDRESS) {
                    this.qOutSetpointKvar = combineToFloat(this.tempHighWord, value);
                    LOG.info("EVN SET Q-out Setpoint = {} kvar", this.qOutSetpointKvar);
                }
                this.lastWriteAddress = -1;
                break;

            default:
                LOG.warn("EVN WRITE to unmapped address: {}", address);
        }
    }

    /**
     * Combine high and low word to IEEE-754 float.
     */
    private float combineToFloat(int highWord, int lowWord) {
        int intBits = (highWord << 16) | (lowWord & 0xFFFF);
        return Float.intBitsToFloat(intBits);
    }

    // ==================== GETTERS FOR EVN COMMANDS ====================

    /** Is P-out control enabled by EVN? */
    public boolean isPOutEnabled() {
        return this.pOutEnabled != 0;
    }

    /** Get P-out Setpoint in % (0-100). */
    public float getPOutSetpointPercent() {
        return this.pOutSetpointPercent;
    }

    /** Get P-out Setpoint in kW. */
    public float getPOutSetpointKw() {
        return this.pOutSetpointKw;
    }

    /** Is Q-out control enabled by EVN? */
    public boolean isQOutEnabled() {
        return this.qOutEnabled != 0;
    }

    /** Get Q-out Setpoint in % (-100 to +100). */
    public float getQOutSetpointPercent() {
        return this.qOutSetpointPercent;
    }

    /** Get Q-out Setpoint in kvar. */
    public float getQOutSetpointKvar() {
        return this.qOutSetpointKvar;
    }

    // ==================== Register counts ====================

    @Override
    public int getInputRegisterCount() {
        return 200; // Extended for future use
    }

    @Override
    public int getRegisterCount() {
        return 200; // Extended for write registers
    }

    // ==================== Not implemented methods ====================

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
