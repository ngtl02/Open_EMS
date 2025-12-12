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
		// Check PERCENT channel first (priority)
		IntegerWriteChannel percentChannel = this.parent
				.channel(ManagedSymmetricPvInverter.ChannelId.ACTIVE_POWER_LIMIT_PERCENT);
		var percentOpt = percentChannel.getNextWriteValueAndReset();

		// Check VALUE channel (W)
		IntegerWriteChannel valueChannel = this.parent.channel(this.channelId);
		var valueOpt = valueChannel.getNextWriteValueAndReset();

		int pLimitPerc;
		int power;

		if (percentOpt.isPresent()) {
			// EVN sent PERCENT - calculate value (W) from percent
			pLimitPerc = percentOpt.get();

			// keep percentage in range [0, 100]
			if (pLimitPerc > 100) {
				pLimitPerc = 100;
			}
			if (pLimitPerc < 0) {
				pLimitPerc = 0;
			}
			power = (int) (this.parent.config.maxActivePower() * pLimitPerc / 100.0);
		} else if (valueOpt.isPresent()) {
			// EVN sent VALUE (W) - use it directly and calculate percent
			power = valueOpt.get();
			pLimitPerc = (int) ((double) power / (double) this.parent.config.maxActivePower() * 100.0);

			// keep percentage in range [0, 100]
			if (pLimitPerc > 100) {
				pLimitPerc = 100;
			}
			if (pLimitPerc < 0) {
				pLimitPerc = 0;
			}
		} else {
			// No command - do nothing, let the last value persist
			// DON'T reset to 100% here! It would cause a self-feedback loop
			// because the written value gets read back as new input on next cycle
			return;
		}

		if (!Objects.equals(this.lastPLimitPerc, pLimitPerc) || this.lastPLimitPercTime
				.isBefore(LocalDateTime.now().minusSeconds(150 /* watchdog timeout is 300 */))) {
			// Value needs to be set
			this.parent.logInfo(this.log, "Apply new limit: " + power + " W (" + pLimitPerc + " %)");

			// Enable power limitation (register 3070): 0xAA = ON
			IntegerWriteChannel pRemoteCtrl = this.parent.channel(ManagedSymmetricPvInverter.ChannelId.REMOTE_CONTROL);
			pRemoteCtrl.setNextWriteValue(0xAA);

			// Write percentage to register 3052: 1% = 100, so 100% = 10000
			IntegerWriteChannel activePowerLimitPercCh = this.parent
					.channel(ManagedSymmetricPvInverter.ChannelId.ACTIVE_POWER_LIMIT_PERCENT);
			activePowerLimitPercCh.setNextWriteValue(pLimitPerc * 100); // Solis uses gain=100

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
