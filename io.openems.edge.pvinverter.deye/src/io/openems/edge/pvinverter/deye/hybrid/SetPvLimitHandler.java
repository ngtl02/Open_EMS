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
	private int remote_ctrl = 0;
	private Integer lastPLimitPerc = null;
	private LocalDateTime lastPLimitPercTime = LocalDateTime.MIN;

	public SetPvLimitHandler(PvInverterDeyeHybridImpl parent, ManagedSymmetricPvInverter.ChannelId activePowerLimit) {
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
			// EVN sent PERCENT directly - use it as-is
			pLimitPerc = percentOpt.get();
			power = (int) (this.parent.config.maxActivePower() * pLimitPerc / 100.0);

			// keep percentage in range [0, 100]
			if (pLimitPerc > 100) {
				pLimitPerc = 100;
			}
			if (pLimitPerc < 0) {
				pLimitPerc = 0;
			}
		} else if (valueOpt.isPresent()) {
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
		} else {
			// No command - reset to 100%
			power = this.parent.config.maxActivePower();
			pLimitPerc = 100;
		}

		if (!Objects.equals(this.lastPLimitPerc, pLimitPerc) || this.lastPLimitPercTime
				.isBefore(LocalDateTime.now().minusSeconds(150 /* watchdog timeout is 300 */))) {
			// Value needs to be set
			this.parent.logInfo(this.log, "Apply new limit: " + power + " W (" + pLimitPerc + " %)");
			IntegerWriteChannel pRemoteCtrl = this.parent.channel(ManagedSymmetricPvInverter.ChannelId.REMOTE_CONTROL);
			pRemoteCtrl.setNextWriteValue(0);

			IntegerWriteChannel pRemoteCtrlP = this.parent
					.channel(ManagedSymmetricPvInverter.ChannelId.REMOTE_CONTROL_P);
			pRemoteCtrlP.setNextWriteValue(0);

			IntegerWriteChannel activePowerLimitCh = this.parent
					.channel(ManagedSymmetricPvInverter.ChannelId.ACTIVE_POWER_LIMIT_PERCENT);
			activePowerLimitCh.setNextWriteValue(pLimitPerc * 10); // Deye uses 0.1% per unit (gain=10)

			IntegerWriteChannel watchDogTagCh = this.parent.channel(ChannelId.WATCH_DOG_TAG);
			watchDogTagCh.setNextWriteValue((int) System.currentTimeMillis());

			this.lastPLimitPerc = pLimitPerc;
			this.lastPLimitPercTime = LocalDateTime.now();
		}
	}

}
