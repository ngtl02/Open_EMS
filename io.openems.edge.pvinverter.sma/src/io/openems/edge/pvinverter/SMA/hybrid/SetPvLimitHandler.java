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

public class SetPvLimitHandler implements ThrowingRunnable<OpenemsNamedException> {

	private static final int P_OPERATING_MODE_WATT = 1077; // Active power limitation P in W
	private static final int P_OPERATING_MODE_PERCENT = 1078; // Act. power lim. as % of Pmax

	private final Logger log = LoggerFactory.getLogger(SetPvLimitHandler.class);
	private final PvInverterSMAHybridImpl parent;
	private final ManagedSymmetricPvInverter.ChannelId channelId;
	private Integer lastPLimit = null;
	private LocalDateTime lastPLimitTime = LocalDateTime.MIN;

	public SetPvLimitHandler(PvInverterSMAHybridImpl parent, ManagedSymmetricPvInverter.ChannelId activePowerLimit) {
		this.parent = parent;
		this.channelId = activePowerLimit;
	}

	@Override
	public void run() throws OpenemsNamedException {
		// Check PERCENT channel first (priority)
		IntegerWriteChannel percentChannel = this.parent
				.channel(ManagedSymmetricPvInverter.ChannelId.ACTIVE_POWER_LIMIT_PERCENT);
		var percentOpt = percentChannel.getNextWriteValueAndReset();

		// Check VALUE channel (W)
		IntegerWriteChannel valueChannel = this.parent.channel(this.channelId);
		var valueOpt = valueChannel.getNextWriteValueAndReset();

		int pLimit;
		int operatingMode;
		boolean usePercent;

		if (percentOpt.isPresent()) {
			// EVN sent PERCENT - use percent mode
			pLimit = percentOpt.get();
			operatingMode = P_OPERATING_MODE_PERCENT;
			usePercent = true;

			// keep percentage in range [0, 100]
			if (pLimit > 100) {
				pLimit = 100;
			}
			if (pLimit < 0) {
				pLimit = 0;
			}
		} else if (valueOpt.isPresent()) {
			// EVN sent VALUE (W) - use watt mode
			pLimit = valueOpt.get();
			operatingMode = P_OPERATING_MODE_WATT;
			usePercent = false;
		} else {
			// No command - do nothing, let the last value persist
			return;
		}

		if (!Objects.equals(this.lastPLimit, pLimit) || this.lastPLimitTime
				.isBefore(LocalDateTime.now().minusSeconds(150 /* watchdog timeout is 300 */))) {
			// Value needs to be set
			this.parent.logInfo(this.log, "Apply P limit: " + pLimit + (usePercent ? " %" : " W")
					+ " (mode=" + operatingMode + ")");

			// Set operating mode first (40210)
			IntegerWriteChannel pModeChannel = this.parent.channel(ChannelId.P_OPERATING_MODE);
			pModeChannel.setNextWriteValue(operatingMode);

			// Write setpoint to appropriate channel
			if (usePercent) {
				IntegerWriteChannel pPercentCh = this.parent
						.channel(ManagedSymmetricPvInverter.ChannelId.ACTIVE_POWER_LIMIT_PERCENT);
				pPercentCh.setNextWriteValue(pLimit);
			} else {
				IntegerWriteChannel pValueCh = this.parent
						.channel(ManagedSymmetricPvInverter.ChannelId.ACTIVE_POWER_LIMIT);
				pValueCh.setNextWriteValue(pLimit);
			}

			IntegerWriteChannel watchDogTagCh = this.parent.channel(ChannelId.WATCH_DOG_TAG);
			watchDogTagCh.setNextWriteValue((int) System.currentTimeMillis());

			this.lastPLimit = pLimit;
			this.lastPLimitTime = LocalDateTime.now();
		}
	}

}
