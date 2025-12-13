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
 * SetPvLimitHandler for Deye Hybrid inverter.
 * 
 * Input: Reads power limit from ACTIVE_POWER_LIMIT channel (in Watts).
 * Output: Writes scaled percentage to ACTIVE_POWER_LIMIT_PERCENT channel (Modbus register 1111).
 * 
 * IMPORTANT: Do NOT read from ACTIVE_POWER_LIMIT_PERCENT as input because it's mapped
 * to the same Modbus register we write to, which would cause a feedback loop.
 */
public class SetPvLimitHandler implements ThrowingRunnable<OpenemsNamedException> {

	private final Logger log = LoggerFactory.getLogger(SetPvLimitHandler.class);
	private final PvInverterDeyeHybridImpl parent;
	private final ManagedSymmetricPvInverter.ChannelId channelId;
	private Integer lastPLimitPerc = null;
	private LocalDateTime lastPLimitPercTime = LocalDateTime.MIN;

	public SetPvLimitHandler(PvInverterDeyeHybridImpl parent, ManagedSymmetricPvInverter.ChannelId activePowerLimit) {
		this.parent = parent;
		this.channelId = activePowerLimit;
	}

	@Override
	public void run() throws OpenemsNamedException {
		// ONLY read from VALUE channel (W) - this is the input from EVN controller
		// DO NOT read from ACTIVE_POWER_LIMIT_PERCENT as it's mapped to Modbus register
		// and would cause feedback loop
		IntegerWriteChannel valueChannel = this.parent.channel(this.channelId);
		var valueOpt = valueChannel.getNextWriteValueAndReset();

		if (!valueOpt.isPresent()) {
			// No command from EVN - do nothing, let the last value persist
			return;
		}

		// EVN sent VALUE (W) - calculate percentage
		int power = valueOpt.get();
		int pLimitPerc = (int) ((double) power / (double) this.parent.config.maxActivePower() * 100.0);

		// keep percentage in range [0, 100]
		if (pLimitPerc > 100) {
			pLimitPerc = 100;
		}
		if (pLimitPerc < 0) {
			pLimitPerc = 0;
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

			// Write scaled value to Modbus register 1111 via ACTIVE_POWER_LIMIT_PERCENT
			// This is OUTPUT only - we never read from this channel
			IntegerWriteChannel activePowerLimitCh = this.parent
					.channel(ManagedSymmetricPvInverter.ChannelId.ACTIVE_POWER_LIMIT_PERCENT);
			activePowerLimitCh.setNextWriteValue(scaledValue);

			IntegerWriteChannel watchDogTagCh = this.parent.channel(ChannelId.WATCH_DOG_TAG);
			watchDogTagCh.setNextWriteValue((int) System.currentTimeMillis());

			this.lastPLimitPerc = pLimitPerc;
			this.lastPLimitPercTime = LocalDateTime.now();
		}
	}
}
