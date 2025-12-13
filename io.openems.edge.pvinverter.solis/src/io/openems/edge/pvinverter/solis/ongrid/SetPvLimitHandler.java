package io.openems.edge.pvinverter.solis.ongrid;

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
 * SetPvLimitHandler for Solis OnGrid inverter.
 * 
 * Input: Reads power limit from ACTIVE_POWER_LIMIT channel (in Watts).
 * Output: Writes to registers 3070 (enable), 3081 (power limit).
 * 
 * IMPORTANT: Do NOT read from ACTIVE_POWER_LIMIT_PERCENT as input because
 * writing to the same channel we read from would cause a feedback loop.
 */
public class SetPvLimitHandler implements ThrowingRunnable<OpenemsNamedException> {

	private final Logger log = LoggerFactory.getLogger(SetPvLimitHandler.class);
	private final PvInverterSolisOnGridImpl parent;
	private final ManagedSymmetricPvInverter.ChannelId channelId;

	private Integer lastPLimitPerc = null;
	private LocalDateTime lastPLimitPercTime = LocalDateTime.MIN;

	public SetPvLimitHandler(PvInverterSolisOnGridImpl parent, ManagedSymmetricPvInverter.ChannelId activePowerLimit) {
		this.parent = parent;
		this.channelId = activePowerLimit;
	}

	@Override
	public void run() throws OpenemsNamedException {
		// ONLY read from VALUE channel (W) - this is the input from EVN controller
		// DO NOT read from percentage channel as it could cause feedback loop
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
			// Value needs to be set
			this.parent.logInfo(this.log, "Apply new limit: " + power + " W (" + pLimitPerc + " %)");

			// Enable power limitation (register 3070): 0xAA = ON
			IntegerWriteChannel pRemoteCtrl = this.parent.channel(ManagedSymmetricPvInverter.ChannelId.REMOTE_CONTROL);
			pRemoteCtrl.setNextWriteValue(0xAA);

			// Write value to register 3081: 1 unit = 10W
			IntegerWriteChannel activePowerLimitCh = this.parent
					.channel(ManagedSymmetricPvInverter.ChannelId.ACTIVE_POWER_LIMIT);
			activePowerLimitCh.setNextWriteValue(power / 10); // Solis uses gain=10 (1 unit = 10W)

			IntegerWriteChannel watchDogTagCh = this.parent.channel(ChannelId.WATCH_DOG_TAG);
			watchDogTagCh.setNextWriteValue((int) System.currentTimeMillis());

			this.lastPLimitPerc = pLimitPerc;
			this.lastPLimitPercTime = LocalDateTime.now();
		}
	}
}
