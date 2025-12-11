package io.openems.edge.pvinverter.solis.ongrid;

import java.time.LocalDateTime;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.function.ThrowingRunnable;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;

/**
 * Handler for applying Reactive Power Limit (Q) to Solis OnGrid inverter.
 * Uses register 3071 (REMOTE_CONTROL_Q) and register 3083 (REACTIVE_POWER_LIMIT).
 */
public class SetReactivePowerLimitHandler implements ThrowingRunnable<OpenemsNamedException> {

    private final Logger log = LoggerFactory.getLogger(SetReactivePowerLimitHandler.class);
    private final PvInverterSolisOnGridImpl parent;
    private final ManagedSymmetricPvInverter.ChannelId channelId;

    private Integer lastQLimitVar = null;
    private LocalDateTime lastQLimitTime = LocalDateTime.MIN;

    public SetReactivePowerLimitHandler(PvInverterSolisOnGridImpl parent,
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

        int reactivePowerVar;
        int qLimitPerc;

        if (percentOpt.isPresent()) {
            // EVN sent PERCENT - calculate value (var) from percent
            qLimitPerc = percentOpt.get();
            
            // keep percentage in range [0, 100]
            if (qLimitPerc > 100) {
                qLimitPerc = 100;
            }
            if (qLimitPerc < 0) {
                qLimitPerc = 0;
            }
            reactivePowerVar = (int) (this.parent.config.maxActivePower() * qLimitPerc / 100.0);
        } else if (valueOpt.isPresent()) {
            // EVN sent VALUE (var) - use it directly
            reactivePowerVar = valueOpt.get();
            qLimitPerc = (int) ((double) reactivePowerVar / (double) this.parent.config.maxActivePower() * 100.0);
        } else {
            return; // No command to apply
        }

        if (!Objects.equals(this.lastQLimitVar, reactivePowerVar) || this.lastQLimitTime
                .isBefore(LocalDateTime.now().minusSeconds(150))) {

            this.parent.logInfo(this.log, "Apply Q limit: " + reactivePowerVar + " var (" + qLimitPerc + " %)");

            // Enable reactive power control (register 3071): 0xA1 = Reactive setting effective
            IntegerWriteChannel remoteControlQ = this.parent
                    .channel(ManagedSymmetricPvInverter.ChannelId.REMOTE_CONTROL_Q);
            remoteControlQ.setNextWriteValue(0xA1);

            // Write reactive power limit value to register 3083: 1 unit = 10 var
            IntegerWriteChannel reactivePowerLimitCh = this.parent
                    .channel(ManagedSymmetricPvInverter.ChannelId.REACTIVE_POWER_LIMIT);
            reactivePowerLimitCh.setNextWriteValue(reactivePowerVar / 10); // Solis uses gain=10 (1 unit = 10 var)

            this.lastQLimitVar = reactivePowerVar;
            this.lastQLimitTime = LocalDateTime.now();
        }
    }
}
