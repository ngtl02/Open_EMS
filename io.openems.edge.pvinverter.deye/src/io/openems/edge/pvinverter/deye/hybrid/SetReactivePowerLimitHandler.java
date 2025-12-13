package io.openems.edge.pvinverter.deye.hybrid;

import java.time.LocalDateTime;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.function.ThrowingRunnable;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;
import io.openems.edge.pvinverter.deye.hybrid.PvInverterDeyeHybrid.ChannelId;

/**
 * Handler for setting Reactive Power Limit on Deye inverter.
 * 
 * Input: Reads from REACTIVE_POWER_LIMIT channel (in var).
 * Output: Writes scaled percentage to REACTIVE_POWER_LIMIT_PERCENT channel (Modbus register 1118).
 * 
 * IMPORTANT: Do NOT read from REACTIVE_POWER_LIMIT_PERCENT as input because it's mapped
 * to the same Modbus register we write to, which would cause a feedback loop.
 */
public class SetReactivePowerLimitHandler implements ThrowingRunnable<OpenemsNamedException> {

    private final Logger log = LoggerFactory.getLogger(SetReactivePowerLimitHandler.class);
    private final PvInverterDeyeHybridImpl parent;
    private final ManagedSymmetricPvInverter.ChannelId channelId;

    private Integer lastQLimitPerc = null;
    private LocalDateTime lastQLimitPercTime = LocalDateTime.MIN;

    public SetReactivePowerLimitHandler(PvInverterDeyeHybridImpl parent,
            ManagedSymmetricPvInverter.ChannelId reactivePowerLimit) {
        this.parent = parent;
        this.channelId = reactivePowerLimit;
    }

    @Override
    public void run() throws OpenemsNamedException {
        // ONLY read from VALUE channel (var) - this is the input from EVN controller
        // DO NOT read from REACTIVE_POWER_LIMIT_PERCENT as it's mapped to Modbus register
        // and would cause feedback loop
        IntegerWriteChannel valueChannel = this.parent.channel(this.channelId);
        var valueOpt = valueChannel.getNextWriteValueAndReset();

        if (!valueOpt.isPresent()) {
            // No command from EVN - do nothing, let the last value persist
            return;
        }

        // EVN sent VALUE (var) - calculate percentage
        int power = valueOpt.get();
        int qLimitPerc = (int) ((double) power / (double) this.parent.config.maxActivePower() * 100.0);

        // keep percentage in range [0, 100]
        if (qLimitPerc > 100) {
            qLimitPerc = 100;
        }
        if (qLimitPerc < 0) {
            qLimitPerc = 0;
        }

        if (!Objects.equals(this.lastQLimitPerc, qLimitPerc) || this.lastQLimitPercTime
                .isBefore(LocalDateTime.now().minusSeconds(150 /* watchdog timeout is 300 */))) {

            // Deye uses 0.1% per unit (gain=10), so multiply by 10
            int scaledValue = qLimitPerc * 10;

            // Value needs to be set
            this.parent.logInfo(this.log,
                    "Apply Q limit: " + power + " var (" + qLimitPerc + " %) -> register 1118: " + scaledValue);

            IntegerWriteChannel qRemoteCtrl = this.parent.channel(ManagedSymmetricPvInverter.ChannelId.REMOTE_CONTROL);
            qRemoteCtrl.setNextWriteValue(0);

            IntegerWriteChannel qRemoteCtrlQ = this.parent
                    .channel(ManagedSymmetricPvInverter.ChannelId.REMOTE_CONTROL_Q);
            qRemoteCtrlQ.setNextWriteValue(0);

            // Write scaled value to Modbus register 1118 via REACTIVE_POWER_LIMIT_PERCENT
            // This is OUTPUT only - we never read from this channel
            IntegerWriteChannel modbusChannel = this.parent
                    .channel(ManagedSymmetricPvInverter.ChannelId.REACTIVE_POWER_LIMIT_PERCENT);
            modbusChannel.setNextWriteValue(scaledValue);

            IntegerWriteChannel watchDogTagCh = this.parent.channel(ChannelId.WATCH_DOG_TAG);
            watchDogTagCh.setNextWriteValue((int) System.currentTimeMillis());

            this.lastQLimitPerc = qLimitPerc;
            this.lastQLimitPercTime = LocalDateTime.now();
        }
    }
}
