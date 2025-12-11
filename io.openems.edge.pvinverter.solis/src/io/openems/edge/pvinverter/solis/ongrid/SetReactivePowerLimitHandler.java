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
 */
public class SetReactivePowerLimitHandler implements ThrowingRunnable<OpenemsNamedException> {

    private static final int Q_SWITCH_OFF = 0x55;
    private static final int Q_SWITCH_ON = 0xA1;


    private final Logger log = LoggerFactory.getLogger(SetReactivePowerLimitHandler.class);
    private final PvInverterSolisOnGridImpl parent;
    private final ManagedSymmetricPvInverter.ChannelId channelId;

    private Integer lastQLimitPerc = null;
    private LocalDateTime lastQLimitPercTime = LocalDateTime.MIN;

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

        int qLimitPerc;  // 0-1000 scale (0.1%), can be negative
        int powerKvar;   // in 0.1kvar

        int maxActivePower = this.parent.getConfig().maxActivePower();

        if (percentOpt.isPresent()) {
            // EVN sent PERCENT directly (0-100 or negative) - convert to 0-1000 (0.1% scale)
            int inputPercent = percentOpt.get();
            qLimitPerc = inputPercent;  // Convert to 0.1% scale
            powerKvar = (int) (maxActivePower * inputPercent / 100.0 / 100.0);  // Convert var to 0.1kvar

            // keep percentage in range [-1000, 1000]
            if (qLimitPerc > 1000) {
                qLimitPerc = 1000;
            }
            if (qLimitPerc < -1000) {
                qLimitPerc = -1000;
            }
        } else if (valueOpt.isPresent()) {
            // EVN sent VALUE (var) - calculate percentage
            int powerVar = valueOpt.get();
            powerKvar = powerVar /10;  // Convert var to 0.1kvar
            qLimitPerc = (int) ((double) powerVar / (double) maxActivePower * 1000.0);  // 0.1% scale

            // keep percentage in range [-1000, 1000]
            if (qLimitPerc > 1000) {
                qLimitPerc = 1000;
            }
            if (qLimitPerc < -1000) {
                qLimitPerc = -1000;
            }
        } else {
            // No command - reset to 0% (no reactive power)
            qLimitPerc = 0;
            powerKvar = 0;
        }

        if (!Objects.equals(this.lastQLimitPerc, qLimitPerc) || this.lastQLimitPercTime
                .isBefore(LocalDateTime.now().minusSeconds(150 /* watchdog timeout is 300 */))) {
            // Value needs to be set
            this.parent.logInfo(this.log, "Apply new Q limit: " + (powerKvar * 100) + " var (" + (qLimitPerc / 10.0) + " %)");

            // Enable reactive power percentage mode (register 5036)
            IntegerWriteChannel qSwitch = this.parent.channel(PvInverterSolisOnGrid.ChannelId.Q_ADJUSTMENT_SWITCH);
            qSwitch.setNextWriteValue(Q_SWITCH_Q_PERCENT_VALID);

            // Set reactive power percentage (register 5037) - 0.1% scale
            IntegerWriteChannel qPercentSetting = this.parent.channel(PvInverterSolisOnGrid.ChannelId.Q_PERCENTAGE_SETTING);
            qPercentSetting.setNextWriteValue(qLimitPerc);

            // Set reactive power in kvar (register 5040) - 0.1kvar scale
            IntegerWriteChannel qKvar = this.parent.channel(PvInverterSolisOnGrid.ChannelId.Q_ADJUSTMENT_KVAR);
            qKvar.setNextWriteValue(powerKvar);

            this.lastQLimitPerc = qLimitPerc;
            this.lastQLimitPercTime = LocalDateTime.now();
        }
    }
}
