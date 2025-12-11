package io.openems.edge.pvinverter.solis.ongrid;

import org.osgi.service.event.EventHandler;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.Level;
import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;

public interface PvInverterSolisOnGrid
		extends ManagedSymmetricPvInverter, ElectricityMeter, OpenemsComponent, EventHandler, ModbusSlave {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
	    // --- Năng lượng theo chu kỳ ---
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

	    DC_TOTAL(Doc.of(OpenemsType.FLOAT)
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
		// PV
		P_LIMIT_TYPE(Doc.of(PLimitType.values()) //
				.accessMode(AccessMode.WRITE_ONLY)), //
		P_LIMIT_PERC(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_WRITE) //
				.unit(Unit.PERCENT)),
		P_LIMIT(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.KILOWATT)),
		WATCH_DOG_TAG(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_WRITE)), //
		STATUS(Doc.of(Status.values())),

		PV_LIMIT_FAILED(Doc.of(Level.FAULT) //
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
