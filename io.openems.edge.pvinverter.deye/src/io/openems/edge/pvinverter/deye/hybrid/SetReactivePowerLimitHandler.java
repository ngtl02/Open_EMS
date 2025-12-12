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
 * Converts absolute var values to percentage and writes to Modbus register.
 *
 * Note: Reads from REACTIVE_POWER_LIMIT (var) or REACTIVE_POWER_LIMIT_PERCENT,
 * then writes scaled value to Modbus register 1118.
 */
public class SetReactivePowerLimitHandler implements ThrowingRunnable<OpenemsNamedException> {

    private final Logger log = LoggerFactory.getLogger(SetReactivePowerLimitHandler.class);
    private final PvInverterDeyeHybridImpl parent;
    private final ManagedSymmetricPvInverter.ChannelId channelId;

    private Integer lastQLimitPerc = null;
    private Integer lastWrittenScaledValue = null; // Track what we wrote to detect feedback
    private LocalDateTime lastQLimitPercTime = LocalDateTime.MIN;

    public SetReactivePowerLimitHandler(PvInverterDeyeHybridImpl parent,
            ManagedSymmetricPvInverter.ChannelId reactivePowerLimit) {
        this.parent = parent;
        this.channelId = reactivePowerLimit;
    }

    @Override
    public void run() throws OpenemsNamedException {
        // Check VALUE channel (var) first - this is the primary input from EVN
        IntegerWriteChannel valueChannel = this.parent.channel(this.channelId);
        var valueOpt = valueChannel.getNextWriteValueAndReset();

        // Check PERCENT channel - alternative input from EVN
        IntegerWriteChannel percentInputChannel = this.parent
                .channel(ManagedSymmetricPvInverter.ChannelId.REACTIVE_POWER_LIMIT_PERCENT);
        var percentOpt = percentInputChannel.getNextWriteValueAndReset();

        int qLimitPerc;
        int power;

        if (valueOpt.isPresent()) {
            // EVN sent VALUE (var) - calculate percentage
            power = valueOpt.get();
            qLimitPerc = (int) ((double) power / (double) this.parent.config.maxActivePower() * 100.0);

            // keep percentage in range [0, 100]
            if (qLimitPerc > 100) {
                qLimitPerc = 100;
            }
            if (qLimitPerc < 0) {
                qLimitPerc = 0;
            }
        } else if (percentOpt.isPresent()) {
            int receivedPercent = percentOpt.get();

            // IMPORTANT: Check if this is our own feedback (value we wrote previously)
            // We wrote scaled value (qLimitPerc * 10), so if received ==
            // lastWrittenScaledValue,
            // it's feedback from our previous write, not a new command from EVN
            if (this.lastWrittenScaledValue != null && receivedPercent == this.lastWrittenScaledValue) {
                // This is feedback from our own write, ignore it
                return;
            }

            // EVN sent PERCENT directly - use it as-is
            qLimitPerc = receivedPercent;
            power = (int) (this.parent.config.maxActivePower() * qLimitPerc / 100.0);

            // keep percentage in range [0, 100]
            if (qLimitPerc > 100) {
                qLimitPerc = 100;
            }
            if (qLimitPerc < 0) {
                qLimitPerc = 0;
            }
        } else {
            // No command - do nothing, let the last value persist
            return;
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

            // Write scaled value to Modbus register 1118
            IntegerWriteChannel modbusChannel = this.parent
                    .channel(ManagedSymmetricPvInverter.ChannelId.REACTIVE_POWER_LIMIT_PERCENT);
            modbusChannel.setNextWriteValue(scaledValue);

            IntegerWriteChannel watchDogTagCh = this.parent.channel(ChannelId.WATCH_DOG_TAG);
            watchDogTagCh.setNextWriteValue((int) System.currentTimeMillis());

            this.lastQLimitPerc = qLimitPerc;
            this.lastWrittenScaledValue = scaledValue; // Remember what we wrote to detect feedback
            this.lastQLimitPercTime = LocalDateTime.now();
        }
    }
}
