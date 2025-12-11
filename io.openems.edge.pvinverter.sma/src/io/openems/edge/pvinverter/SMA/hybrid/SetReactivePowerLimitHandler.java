package io.openems.edge.pvinverter.SMA.hybrid;

import java.time.LocalDateTime;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.function.ThrowingRunnable;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.pvinverter.SMA.hybrid.PvInverterSMAHybrid.ChannelId;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;

/**
 * Handler for applying Reactive Power Limit (Q) to SMA Hybrid inverter.
 * SMA uses registers 40200=mode, 40202=VAr, 40204=%.
 */
public class SetReactivePowerLimitHandler implements ThrowingRunnable<OpenemsNamedException> {

    private static final int Q_OPERATING_MODE_DIRECT = 1070; // Q direct specification
    private static final int Q_OPERATING_MODE_KVAR = 1071; // Q const in kvar

    private final Logger log = LoggerFactory.getLogger(SetReactivePowerLimitHandler.class);
    private final PvInverterSMAHybridImpl parent;
    private Integer lastQLimit = null;
    private LocalDateTime lastQLimitTime = LocalDateTime.MIN;

    public SetReactivePowerLimitHandler(PvInverterSMAHybridImpl parent) {
        this.parent = parent;
    }

    @Override
    public void run() throws OpenemsNamedException {
        // Check PERCENT channel first (priority)
        IntegerWriteChannel percentChannel = this.parent
                .channel(ManagedSymmetricPvInverter.ChannelId.REACTIVE_POWER_LIMIT_PERCENT);
        var percentOpt = percentChannel.getNextWriteValueAndReset();

        // Check VALUE channel (var)
        IntegerWriteChannel valueChannel = this.parent
                .channel(ManagedSymmetricPvInverter.ChannelId.REACTIVE_POWER_LIMIT);
        var valueOpt = valueChannel.getNextWriteValueAndReset();

        int qLimit;
        int operatingMode;
        boolean usePercent;

        if (percentOpt.isPresent()) {
            // EVN sent PERCENT - use percent mode (via direct spec with calculated var)
            int percent = percentOpt.get();

            // keep percentage in range [0, 100]
            if (percent > 100) {
                percent = 100;
            }
            if (percent < 0) {
                percent = 0;
            }
            qLimit = percent;
            operatingMode = Q_OPERATING_MODE_DIRECT;
            usePercent = true;
        } else if (valueOpt.isPresent()) {
            // EVN sent VALUE (var) - use kvar mode
            qLimit = valueOpt.get();
            operatingMode = Q_OPERATING_MODE_KVAR;
            usePercent = false;
        } else {
            return; // No command to apply
        }

        if (!Objects.equals(this.lastQLimit, qLimit) || this.lastQLimitTime
                .isBefore(LocalDateTime.now().minusSeconds(150))) {

            this.parent.logInfo(this.log, "Apply Q limit: " + qLimit + (usePercent ? " %" : " var")
                    + " (mode=" + operatingMode + ")");

            // Set operating mode first (40200)
            IntegerWriteChannel qModeChannel = this.parent.channel(ChannelId.Q_OPERATING_MODE);
            qModeChannel.setNextWriteValue(operatingMode);

            // Write setpoint to appropriate channel
            if (usePercent) {
                IntegerWriteChannel qPercentCh = this.parent
                        .channel(ManagedSymmetricPvInverter.ChannelId.REACTIVE_POWER_LIMIT_PERCENT);
                qPercentCh.setNextWriteValue(qLimit);
            } else {
                IntegerWriteChannel qValueCh = this.parent
                        .channel(ManagedSymmetricPvInverter.ChannelId.REACTIVE_POWER_LIMIT);
                qValueCh.setNextWriteValue(qLimit);
            }

            this.lastQLimit = qLimit;
            this.lastQLimitTime = LocalDateTime.now();
        }
    }
}
