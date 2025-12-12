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

public class SetPvLimitHandler implements ThrowingRunnable<OpenemsNamedException> {

	private final Logger log = LoggerFactory.getLogger(SetPvLimitHandler.class);
	private final PvInverterDeyeHybridImpl parent;
	private final ManagedSymmetricPvInverter.ChannelId channelId;
	private Integer lastPLimitPerc = null;
	private Integer lastWrittenScaledValue = null; // Track what we wrote to detect feedback
	private LocalDateTime lastPLimitPercTime = LocalDateTime.MIN;

	public SetPvLimitHandler(PvInverterDeyeHybridImpl parent, ManagedSymmetricPvInverter.ChannelId activePowerLimit) {
		this.parent = parent;
		this.channelId = activePowerLimit;
	}

	@Override
	public void run() throws OpenemsNamedException {
		// Check VALUE channel (W) first - this is the primary input from EVN
		IntegerWriteChannel valueChannel = this.parent.channel(this.channelId);
		var valueOpt = valueChannel.getNextWriteValueAndReset();

		// Check PERCENT channel - alternative input from EVN
		IntegerWriteChannel percentChannel = this.parent
				.channel(ManagedSymmetricPvInverter.ChannelId.ACTIVE_POWER_LIMIT_PERCENT);
		var percentOpt = percentChannel.getNextWriteValueAndReset();

		int pLimitPerc;
		int power;

		if (valueOpt.isPresent()) {
			// EVN sent VALUE (W) - calculate percentage
			power = valueOpt.get();
			pLimitPerc = (int) ((double) power / (double) this.parent.config.maxActivePower() * 100.0);

			// keep percentage in range [0, 100]
			if (pLimitPerc > 100) {
				pLimitPerc = 100;
			}
			if (pLimitPerc < 0) {
				pLimitPerc = 0;
			}
		} else if (percentOpt.isPresent()) {
			int receivedPercent = percentOpt.get();

			// IMPORTANT: Check if this is our own feedback (value we wrote previously)
			// We wrote scaled value (pLimitPerc * 10), so if received ==
			// lastWrittenScaledValue,
			// it's feedback from our previous write, not a new command from EVN
			if (this.lastWrittenScaledValue != null && receivedPercent == this.lastWrittenScaledValue) {
				// This is feedback from our own write, ignore it
				return;
			}

			// EVN sent PERCENT directly - use it as-is
			pLimitPerc = receivedPercent;
			power = (int) (this.parent.config.maxActivePower() * pLimitPerc / 100.0);

			// keep percentage in range [0, 100]
			if (pLimitPerc > 100) {
				pLimitPerc = 100;
			}
			if (pLimitPerc < 0) {
				pLimitPerc = 0;
			}
		} else {
			// No command - do nothing, let the last value persist
			return;
		}

		if (!Objects.equals(this.lastPLimitPerc, pLimitPerc) || this.lastPLimitPercTime
				.isBefore(LocalDateTime.now().minusSeconds(150 /* watchdog timeout is 300 */))) {

			// Deye uses 0.1% per unit (gain=10), so multiply by 10
			int scaledValue = pLimitPerc * 10;

			// Value needs to be set
			this.parent.logInfo(this.log,
					"Apply P limit: " + power + " W (" + pLimitPerc + " %) -> register 1111: " + scaledValue);

			IntegerWriteChannel pRemoteCtrl = this.parent.channel(ManagedSymmetricPvInverter.ChannelId.REMOTE_CONTROL);
			pRemoteCtrl.setNextWriteValue(0);

			IntegerWriteChannel pRemoteCtrlP = this.parent
					.channel(ManagedSymmetricPvInverter.ChannelId.REMOTE_CONTROL_P);
			pRemoteCtrlP.setNextWriteValue(0);

			// Write scaled value to Modbus register 1111
			IntegerWriteChannel activePowerLimitCh = this.parent
					.channel(ManagedSymmetricPvInverter.ChannelId.ACTIVE_POWER_LIMIT_PERCENT);
			activePowerLimitCh.setNextWriteValue(scaledValue);

			IntegerWriteChannel watchDogTagCh = this.parent.channel(ChannelId.WATCH_DOG_TAG);
			watchDogTagCh.setNextWriteValue((int) System.currentTimeMillis());

			this.lastPLimitPerc = pLimitPerc;
			this.lastWrittenScaledValue = scaledValue; // Remember what we wrote to detect feedback
			this.lastPLimitPercTime = LocalDateTime.now();
		}
	}
}
