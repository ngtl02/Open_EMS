package io.openems.edge.pvinverter.solis.hybrid;

import java.time.LocalDateTime;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.function.ThrowingRunnable;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;
import io.openems.edge.pvinverter.solis.hybrid.PvInverterSolisHybrid.ChannelId;

/**
 * SetPvLimitHandler for Solis Hybrid inverter.
 * 
 * Input: Reads power limit from ACTIVE_POWER_LIMIT channel (in Watts).
 * Output: Writes scaled value to ACTIVE_POWER_LIMIT channel (Modbus register).
 * 
 * Solis Hybrid uses 1 unit = 10W for the power limit register.
 */
public class SetPvLimitHandler implements ThrowingRunnable<OpenemsNamedException> {

	private final Logger log = LoggerFactory.getLogger(SetPvLimitHandler.class);
	private final PvInverterSolisHybridImpl parent;
	private final ManagedSymmetricPvInverter.ChannelId channelId;
	private Integer lastPLimitPerc = null;
	private LocalDateTime lastPLimitPercTime = LocalDateTime.MIN;

	public SetPvLimitHandler(PvInverterSolisHybridImpl parent, ManagedSymmetricPvInverter.ChannelId activePowerLimit) {
		this.parent = parent;
		this.channelId = activePowerLimit;
	}

	@Override
	public void run() throws OpenemsNamedException {
		// ONLY read from VALUE channel (W) - this is the input from EVN controller
		// Solis Hybrid's ACTIVE_POWER_LIMIT is the INPUT channel
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
			// Apply scale factor: Solis uses 1 = 10W
			int scaledValue = power / 10;

			// Value needs to be set
			this.parent.logInfo(this.log,
					"Apply P limit: " + power + " W (" + pLimitPerc + " %) -> register: " + scaledValue);
			IntegerWriteChannel pRemoteCtrl = this.parent.channel(ManagedSymmetricPvInverter.ChannelId.REMOTE_CONTROL);
			pRemoteCtrl.setNextWriteValue(1); // 1 = ON with system grid connection point
			IntegerWriteChannel activePowerLimitCh = this.parent
					.channel(ManagedSymmetricPvInverter.ChannelId.ACTIVE_POWER_LIMIT);
			activePowerLimitCh.setNextWriteValue(scaledValue); // Scaled: 1 = 10W

			IntegerWriteChannel watchDogTagCh = this.parent.channel(ChannelId.WATCH_DOG_TAG);
			watchDogTagCh.setNextWriteValue((int) System.currentTimeMillis());

			this.lastPLimitPerc = pLimitPerc;
			this.lastPLimitPercTime = LocalDateTime.now();
		}
	}

}
