package io.openems.edge.pvinverter.huawei.hybrid;

import org.osgi.service.event.EventHandler;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.Level;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.common.channel.PersistencePriority;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;

public interface PvInverterHuaweiHybrid
		extends ManagedSymmetricPvInverter, ElectricityMeter, OpenemsComponent, EventHandler, ModbusSlave {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		PV1_VOLTAGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIVOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		PV2_VOLTAGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIVOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		PV3_VOLTAGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIVOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		PV4_VOLTAGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIVOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		PV5_VOLTAGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIVOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		PV6_VOLTAGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIVOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		PV7_VOLTAGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIVOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		PV8_VOLTAGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIVOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		PV9_VOLTAGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIVOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		PV10_VOLTAGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIVOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		PV11_VOLTAGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIVOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		PV12_VOLTAGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIVOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		PV13_VOLTAGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIVOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		PV14_VOLTAGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIVOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		PV15_VOLTAGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIVOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		PV16_VOLTAGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIVOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		PV17_VOLTAGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIVOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		PV18_VOLTAGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIVOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		PV19_VOLTAGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIVOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		PV20_VOLTAGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIVOLT)
				.persistencePriority(PersistencePriority.HIGH)),

		PV1_AMPERE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIAMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		PV2_AMPERE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIAMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		PV3_AMPERE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIAMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		PV4_AMPERE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIAMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		PV5_AMPERE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIAMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		PV6_AMPERE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIAMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		PV7_AMPERE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIAMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		PV8_AMPERE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIAMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		PV9_AMPERE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIAMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		PV10_AMPERE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIAMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		PV11_AMPERE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIAMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		PV12_AMPERE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIAMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		PV13_AMPERE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIAMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		PV14_AMPERE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIAMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		PV15_AMPERE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIAMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		PV16_AMPERE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIAMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		PV17_AMPERE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIAMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		PV18_AMPERE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIAMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		PV19_AMPERE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIAMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		PV20_AMPERE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIAMPERE)
				.persistencePriority(PersistencePriority.HIGH)),

		PEAK_ACTIVE_POWER(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.KILOWATT)
				.persistencePriority(PersistencePriority.HIGH)),
		PF(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.PERCENT)
				.persistencePriority(PersistencePriority.HIGH)),
		TEMPERATURE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.DEGREE_CELSIUS)
				.persistencePriority(PersistencePriority.HIGH)),
		POWER_FACTOR(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.PERCENT)
				.persistencePriority(PersistencePriority.MEDIUM)),
		DC_TOTAL(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.WATT)
				.persistencePriority(PersistencePriority.HIGH)),

		P_LIMIT_TYPE(Doc.of(PLimitType.values())//
				.accessMode(AccessMode.WRITE_ONLY)), //
		P_LIMIT_PERC(Doc.of(OpenemsType.INTEGER)//
				.accessMode(AccessMode.READ_WRITE)//
				.unit(Unit.PERCENT)),
		P_LIMIT(Doc.of(OpenemsType.INTEGER)//
				.unit(Unit.KILOWATT)),
		WATCH_DOG_TAG(Doc.of(OpenemsType.INTEGER)//
				.accessMode(AccessMode.READ_WRITE)), //
		STATUS(Doc.of(Status.values())),

		PV_LIMIT_FAILED(Doc.of(Level.FAULT)//
				.text("PV-Limit failed"));

		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}
}
