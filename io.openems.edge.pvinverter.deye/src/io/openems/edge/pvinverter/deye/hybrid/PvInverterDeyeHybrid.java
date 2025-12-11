package io.openems.edge.pvinverter.deye.hybrid;

import org.osgi.service.event.EventHandler;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.Level;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;

public interface PvInverterDeyeHybrid
		extends ManagedSymmetricPvInverter, ElectricityMeter, OpenemsComponent, EventHandler, ModbusSlave {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		ACTIVE_PRODUCTION_ENERGY_TOTAL(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.KILOWATT_HOURS)),
		ACTIVE_PRODUCTION_ENERGY_CURRENTMONTH(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.KILOWATT_HOURS)),
		ACTIVE_PRODUCTION_ENERGY_LASTMONTH(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.KILOWATT_HOURS)),
		ACTIVE_PRODUCTION_ENERGY_DAILY(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.KILOWATT_HOURS)),
		ACTIVE_PRODUCTION_ENERGY_YESTERDAY(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.KILOWATT_HOURS)),
		ACTIVE_PRODUCTION_ENERGY_YEAR(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.KILOWATT_HOURS)),
		ACTIVE_PRODUCTION_ENERGY_LASTYEAR(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.KILOWATT_HOURS)),
		APPARENT_POWER_INVERTER(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.VOLT_AMPERE)),
		DC1_VOLTAGE(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.MILLIVOLT)),
		DC2_VOLTAGE(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.MILLIVOLT)),
		DC3_VOLTAGE(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.MILLIVOLT)),
		DC4_VOLTAGE(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.MILLIVOLT)),
		DC1_AMPERE(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.MILLIAMPERE)),
		DC2_AMPERE(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.MILLIAMPERE)),
		DC3_AMPERE(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.MILLIAMPERE)),
		DC4_AMPERE(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.MILLIAMPERE)),
		DC_TOTAL(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.WATT)),
		TEMPERATURE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.DEGREE_CELSIUS)),
		BATTERY_TEMPERATURE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.DEGREE_CELSIUS)),
		BATTERY_TEMPERATURE2(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.DEGREE_CELSIUS)),
		BATTERY_VOLTAGE_INVERTER(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.VOLT)),
		BATTERY_CURRENT_INVERTER(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.AMPERE)),
		BATTERY_SOC(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.PERCENT)),
		BATTERY_SOH(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.PERCENT)),
		BATTERY_VOLTAGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.VOLT)),
		BATTERY_CURRENT(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.VOLT)),
		BATTERY_CURRENTLIMIT_CHARGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.AMPERE)),
		BATTERY_CURRENTLIMIT_DISCHARGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.AMPERE)),
		BATTERY_VOLTAGE_CHARGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.VOLT)),
		BATTERY_VOLTAGE_DISCHARGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.VOLT)),
		BATTERY_POWER(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.WATT)),
		BATTERY_SOC2(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.PERCENT)),
		BATTERY_SOH2(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.PERCENT)),
		BATTERY_VOLTAGE2(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.VOLT)),
		BATTERY_CURRENT2(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.VOLT)),
		BATTERY_CURRENTLIMIT_CHARGE2(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.VOLT)),
		BATTERY_CURRENTLIMIT_DISCHARGE2(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.AMPERE)),
		BATTERY_CAPACITY(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.AMPERE_HOURS)),
		BATTERY_POWER2(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.WATT)),
		BATTERY_ENERGYCHARGE_TOTAL(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.KILOWATT_HOURS)),
		BATTERY_ENERGYCHARGE_TODAY(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.VOLT)),
		BATTERY_ENERGYDISCHARGE_TOTAL(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.KILOWATT_HOURS)),
		BATTERY_ENERGYDISCHARGE_TODAY(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.VOLT)),
		MODES(Doc.of(OpenemsType.INTEGER)
				.accessMode(AccessMode.READ_ONLY)),
		PF(Doc.of(OpenemsType.FLOAT)
				.accessMode(AccessMode.READ_ONLY)),
		// PV
		P_LIMIT_TYPE(Doc.of(PLimitType.values())//
				.accessMode(AccessMode.WRITE_ONLY)), //
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
