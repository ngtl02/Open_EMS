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
        // Check PERCENT channel first (priority)
        IntegerWriteChannel percentChannel = this.parent
                .channel(ManagedSymmetricPvInverter.ChannelId.REACTIVE_POWER_LIMIT_PERCENT);
        var percentOpt = percentChannel.getNextWriteValueAndReset();

        // Check VALUE channel (var)
        IntegerWriteChannel valueChannel = this.parent.channel(this.channelId);
        var valueOpt = valueChannel.getNextWriteValueAndReset();

        int qLimitPerc;
        int power;

        if (percentOpt.isPresent()) {
            // EVN sent PERCENT directly - use it as-is
            qLimitPerc = percentOpt.get();
            power = (int) (this.parent.config.maxActivePower() * qLimitPerc / 100.0);

            // keep percentage in range [0, 100]
            if (qLimitPerc > 100) {
                qLimitPerc = 100;
            }
            if (qLimitPerc < 0) {
                qLimitPerc = 0;
            }
        } else if (valueOpt.isPresent()) {
            // EVN sent VALUE (var) - calculate percentage
            power = valueOpt.get();
            // Calculate percentage based on max reactive power (same as max active power)
            qLimitPerc = (int) ((double) power / (double) this.parent.config.maxActivePower() * 100.0);

            // keep percentage in range [0, 100]
            if (qLimitPerc > 100) {
                qLimitPerc = 100;
            }
            if (qLimitPerc < 0) {
                qLimitPerc = 0;
            }
        } else {
            // No command - reset to 100%
            power = this.parent.config.maxActivePower();
            qLimitPerc = 100;
        }

        if (!Objects.equals(this.lastQLimitPerc, qLimitPerc) || this.lastQLimitPercTime
                .isBefore(LocalDateTime.now().minusSeconds(150 /* watchdog timeout is 300 */))) {
            // Value needs to be set
            this.parent.logInfo(this.log, "Apply new reactive power limit: " + power + " var (" + qLimitPerc + " %)");

            IntegerWriteChannel qRemoteCtrl = this.parent.channel(ManagedSymmetricPvInverter.ChannelId.REMOTE_CONTROL);
            qRemoteCtrl.setNextWriteValue(0);

            IntegerWriteChannel qRemoteCtrlQ = this.parent
                    .channel(ManagedSymmetricPvInverter.ChannelId.REMOTE_CONTROL_Q);
            qRemoteCtrlQ.setNextWriteValue(0);

            IntegerWriteChannel reactivePowerLimitCh = this.parent
                    .channel(ManagedSymmetricPvInverter.ChannelId.REACTIVE_POWER_LIMIT_PERCENT);
            reactivePowerLimitCh.setNextWriteValue(qLimitPerc * 10); // Deye uses 0.1% per unit (gain=10)

            IntegerWriteChannel watchDogTagCh = this.parent.channel(ChannelId.WATCH_DOG_TAG);
            watchDogTagCh.setNextWriteValue((int) System.currentTimeMillis());

            this.lastQLimitPerc = qLimitPerc;
            this.lastQLimitPercTime = LocalDateTime.now();
        }
    }

}
