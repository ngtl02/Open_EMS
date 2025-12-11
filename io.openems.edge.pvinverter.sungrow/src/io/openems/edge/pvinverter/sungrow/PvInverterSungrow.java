package io.openems.edge.pvinverter.sungrow;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;

public interface PvInverterSungrow extends ManagedSymmetricPvInverter, ElectricityMeter, OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		// --- Năng lượng theo chu kỳ ---
		ACTIVE_PRODUCTION_ENERGY_TOTAL(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.KILOWATT_HOURS)
				.persistencePriority(PersistencePriority.MEDIUM)),
		ACTIVE_PRODUCTION_ENERGY_CURRENTMONTH(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.KILOWATT_HOURS)
				.persistencePriority(PersistencePriority.MEDIUM)),
		ACTIVE_PRODUCTION_ENERGY_LASTMONTH(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.KILOWATT_HOURS)
				.persistencePriority(PersistencePriority.LOW)),
		ACTIVE_PRODUCTION_ENERGY_DAILY(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.KILOWATT_HOURS)
				.persistencePriority(PersistencePriority.HIGH)), // Giám sát hàng ngày
		ACTIVE_PRODUCTION_ENERGY_YESTERDAY(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.KILOWATT_HOURS)
				.persistencePriority(PersistencePriority.LOW)),
		ACTIVE_PRODUCTION_ENERGY_YEAR(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.KILOWATT_HOURS)
				.persistencePriority(PersistencePriority.MEDIUM)),
		ACTIVE_PRODUCTION_ENERGY_LASTYEAR(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.KILOWATT_HOURS)
				.persistencePriority(PersistencePriority.LOW)),

		// --- Công suất ---
		APPARENT_POWER_INVERTER(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.VOLT_AMPERE)
				.persistencePriority(PersistencePriority.HIGH)),

		// --- DC inputs ---
		DC1_VOLTAGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.VOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		DC2_VOLTAGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.VOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		DC3_VOLTAGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.VOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		DC4_VOLTAGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.VOLT)
				.persistencePriority(PersistencePriority.HIGH)),

		DC1_AMPERE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.AMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		DC2_AMPERE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.AMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		DC3_AMPERE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.AMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		DC4_AMPERE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.AMPERE)
				.persistencePriority(PersistencePriority.HIGH)),

		DC_TOTAL_POWER(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.WATT)
				.persistencePriority(PersistencePriority.HIGH)),

		// --- AC 3-phase ---
		VOLTAGE_A(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.VOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		VOLTAGE_B(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.VOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		VOLTAGE_C(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.VOLT)
				.persistencePriority(PersistencePriority.HIGH)),

		CURRENT_A(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.AMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		CURRENT_B(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.AMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		CURRENT_C(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.AMPERE)
				.persistencePriority(PersistencePriority.HIGH)),

		// --- Nhiệt độ ---
		TEMPERATURE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.DEGREE_CELSIUS)
				.persistencePriority(PersistencePriority.HIGH)),
		// --- Trạng thái hoạt động ---
		MODES(Doc.of(OpenemsType.INTEGER)
				.accessMode(AccessMode.READ_ONLY)
				.persistencePriority(PersistencePriority.MEDIUM)),
		PF(Doc.of(OpenemsType.FLOAT)
				.accessMode(AccessMode.READ_ONLY)
				.persistencePriority(PersistencePriority.HIGH)),
		// Register 5019: Power factor setting (-1000 to -800, 800 to 1000, scale 0.001)
		POWER_FACTOR_SETTING(Doc.of(OpenemsType.INTEGER)
				.accessMode(AccessMode.READ_WRITE)
				.persistencePriority(PersistencePriority.HIGH)),

		// Power Control Registers for Sungrow
		// Register 5007: 0xAA=Enable, 0x55=Disable
		P_LIMITATION_SWITCH(Doc.of(OpenemsType.INTEGER)
				.accessMode(AccessMode.READ_WRITE)
				.persistencePriority(PersistencePriority.HIGH)),
		// Register 5008: 0-1100 (0.1% scale)
		P_LIMITATION_SETTING(Doc.of(OpenemsType.INTEGER)
				.accessMode(AccessMode.READ_WRITE)
				.unit(Unit.PERCENT)
				.persistencePriority(PersistencePriority.HIGH)),
		// Register 5036: 0x55=OFF, 0xA1=PF valid, 0xA2=Q% valid
		Q_ADJUSTMENT_SWITCH(Doc.of(OpenemsType.INTEGER)
				.accessMode(AccessMode.READ_WRITE)
				.persistencePriority(PersistencePriority.HIGH)),
		// Register 5037: 0-1000 or 0 to -1000 (0.1% scale)
		Q_PERCENTAGE_SETTING(Doc.of(OpenemsType.INTEGER)
				.accessMode(AccessMode.READ_WRITE)
				.unit(Unit.PERCENT)
				.persistencePriority(PersistencePriority.HIGH)),
		// Register 5039: Power in 0.1kW
		P_LIMITATION_KW(Doc.of(OpenemsType.INTEGER)
				.accessMode(AccessMode.READ_WRITE)
				.unit(Unit.KILOWATT)
				.persistencePriority(PersistencePriority.HIGH)),
		// Register 5040: Reactive power in 0.1kvar
		Q_ADJUSTMENT_KVAR(Doc.of(OpenemsType.INTEGER)
				.accessMode(AccessMode.READ_WRITE)
				.unit(Unit.KILOVOLT_AMPERE_REACTIVE)
				.persistencePriority(PersistencePriority.HIGH)),

		// Operating modes for SMA-style control (legacy)
		P_OPERATING_MODE(Doc.of(OpenemsType.INTEGER)
				.accessMode(AccessMode.READ_WRITE)
				.persistencePriority(PersistencePriority.HIGH)),
		Q_OPERATING_MODE(Doc.of(OpenemsType.INTEGER)
				.accessMode(AccessMode.READ_WRITE)
				.persistencePriority(PersistencePriority.HIGH)),

		// Status flag
		PV_LIMIT_FAILED(Doc.of(OpenemsType.BOOLEAN)
				.accessMode(AccessMode.READ_ONLY)
				.persistencePriority(PersistencePriority.HIGH)),
				;

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
