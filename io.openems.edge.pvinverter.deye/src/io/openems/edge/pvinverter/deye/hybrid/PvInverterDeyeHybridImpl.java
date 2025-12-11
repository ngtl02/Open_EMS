package io.openems.edge.pvinverter.deye.hybrid;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_1;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_2;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_1;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_2;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_3;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.propertytypes.EventTopics;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;

import io.openems.common.channel.AccessMode;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.types.MeterType;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.SignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.element.WordOrder;
import io.openems.edge.bridge.modbus.api.task.FC16WriteRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.common.modbusslave.ModbusSlaveTable;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;
import io.openems.edge.timedata.api.Timedata;
import io.openems.edge.timedata.api.TimedataProvider;
import io.openems.edge.timedata.api.utils.CalculateEnergyFromPower;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "PV-Inverter.Deye.Hybrid", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE, //
		property = { //
				"type=PRODUCTION" //
		})
@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_EXECUTE_WRITE, //
		EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE //
})
public class PvInverterDeyeHybridImpl extends AbstractOpenemsModbusComponent
		implements PvInverterDeyeHybrid, ManagedSymmetricPvInverter, ElectricityMeter, ModbusComponent,
		OpenemsComponent,
		EventHandler, ModbusSlave, TimedataProvider {

	private final SetPvLimitHandler setPvLimitHandler = new SetPvLimitHandler(this,
			ManagedSymmetricPvInverter.ChannelId.ACTIVE_POWER_LIMIT);
	private final SetReactivePowerLimitHandler setReactivePowerLimitHandler = new SetReactivePowerLimitHandler(this,
			ManagedSymmetricPvInverter.ChannelId.REACTIVE_POWER_LIMIT);
	private final CalculateEnergyFromPower calculateProductionEnergy = new CalculateEnergyFromPower(this,
			ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY);

	@Reference
	private ConfigurationAdmin cm;

	@Reference(policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.OPTIONAL)
	private volatile Timedata timedata = null;

	@Override
	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	protected Config config;

	public PvInverterDeyeHybridImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				ManagedSymmetricPvInverter.ChannelId.values(), //
				PvInverterDeyeHybrid.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsException {
		if (super.activate(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId(), this.cm,
				"Modbus", config.modbus_id())) {
			return;
		}
		this.config = config;
		this._setMaxApparentPower(config.maxActivePower());

		// Stop if component is disabled
		if (!config.enabled()) {
			return;
		}
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		return new ModbusProtocol(this,
				new FC3ReadRegistersTask(501, Priority.LOW,
						m(PvInverterDeyeHybrid.ChannelId.ACTIVE_PRODUCTION_ENERGY_DAILY,
								new SignedWordElement(501), SCALE_FACTOR_2),
						new DummyRegisterElement(502, 503),
						m(PvInverterDeyeHybrid.ChannelId.ACTIVE_PRODUCTION_ENERGY_TOTAL,
								new SignedDoublewordElement(504).wordOrder(WordOrder.LSWMSW), SCALE_FACTOR_2)),
				new FC3ReadRegistersTask(540, Priority.HIGH,
						m(PvInverterDeyeHybrid.ChannelId.TEMPERATURE,
								new SignedWordElement(540), SCALE_FACTOR_MINUS_1)),
				new FC3ReadRegistersTask(598, Priority.HIGH,
						m(ElectricityMeter.ChannelId.VOLTAGE_L1,
								new UnsignedWordElement(598), SCALE_FACTOR_2),
						m(ElectricityMeter.ChannelId.VOLTAGE_L2,
								new UnsignedWordElement(599), SCALE_FACTOR_2),
						m(ElectricityMeter.ChannelId.VOLTAGE_L3,
								new UnsignedWordElement(600), SCALE_FACTOR_2),
						new DummyRegisterElement(601, 609),
						m(ElectricityMeter.ChannelId.CURRENT_L1,
								new UnsignedWordElement(610), SCALE_FACTOR_1),
						m(ElectricityMeter.ChannelId.CURRENT_L2,
								new UnsignedWordElement(611), SCALE_FACTOR_1),
						m(ElectricityMeter.ChannelId.CURRENT_L3,
								new UnsignedWordElement(612), SCALE_FACTOR_1)),
				new FC3ReadRegistersTask(621, Priority.HIGH,
						m(PvInverterDeyeHybrid.ChannelId.PF,
								new UnsignedWordElement(621), SCALE_FACTOR_3), //
						new DummyRegisterElement(622, 635),
						m(ElectricityMeter.ChannelId.ACTIVE_POWER,
								new SignedWordElement(636)),
						m(ElectricityMeter.ChannelId.APPARENT_POWER,
								new SignedWordElement(637)),
						m(ElectricityMeter.ChannelId.FREQUENCY,
								new UnsignedWordElement(638), SCALE_FACTOR_1)),
				new FC3ReadRegistersTask(676, Priority.HIGH,
						m(PvInverterDeyeHybrid.ChannelId.DC1_VOLTAGE,
								new UnsignedWordElement(676), SCALE_FACTOR_2),
						m(PvInverterDeyeHybrid.ChannelId.DC1_AMPERE,
								new UnsignedWordElement(677), SCALE_FACTOR_2),
						m(PvInverterDeyeHybrid.ChannelId.DC2_VOLTAGE,
								new UnsignedWordElement(678), SCALE_FACTOR_2),
						m(PvInverterDeyeHybrid.ChannelId.DC2_AMPERE,
								new UnsignedWordElement(679), SCALE_FACTOR_2),
						m(PvInverterDeyeHybrid.ChannelId.DC3_VOLTAGE,
								new UnsignedWordElement(680), SCALE_FACTOR_2),
						m(PvInverterDeyeHybrid.ChannelId.DC3_AMPERE,
								new UnsignedWordElement(681), SCALE_FACTOR_2),
						m(PvInverterDeyeHybrid.ChannelId.DC4_VOLTAGE,
								new UnsignedWordElement(682), SCALE_FACTOR_2),
						m(PvInverterDeyeHybrid.ChannelId.DC4_AMPERE,
								new UnsignedWordElement(683), SCALE_FACTOR_2)),

				// =====================================================================
				// Battery Section
				// =====================================================================
				new FC3ReadRegistersTask(210, Priority.HIGH,
						// m(PvInverterDeyeHybrid.ChannelId.BATTERY_SOH,
						// new UnsignedWordElement(33140)),
						// new DummyRegisterElement(33141, 33141),
						m(PvInverterDeyeHybrid.ChannelId.BATTERY_VOLTAGE_CHARGE,
								new UnsignedWordElement(210), SCALE_FACTOR_1),
						m(PvInverterDeyeHybrid.ChannelId.BATTERY_VOLTAGE_DISCHARGE,
								new UnsignedWordElement(211), SCALE_FACTOR_1),
						m(PvInverterDeyeHybrid.ChannelId.BATTERY_CURRENTLIMIT_CHARGE,
								new UnsignedWordElement(212), SCALE_FACTOR_3),
						m(PvInverterDeyeHybrid.ChannelId.BATTERY_CURRENTLIMIT_DISCHARGE,
								new UnsignedWordElement(213), SCALE_FACTOR_3),
						m(PvInverterDeyeHybrid.ChannelId.BATTERY_SOC,
								new UnsignedWordElement(214)),
						m(PvInverterDeyeHybrid.ChannelId.BATTERY_VOLTAGE,
								new SignedWordElement(215), SCALE_FACTOR_1),
						m(PvInverterDeyeHybrid.ChannelId.BATTERY_CURRENT,
								new SignedWordElement(216), SCALE_FACTOR_3),
						m(PvInverterDeyeHybrid.ChannelId.BATTERY_TEMPERATURE,
								new UnsignedWordElement(217), SCALE_FACTOR_1)),
				new FC16WriteRegistersTask(108, //
						m(ManagedSymmetricPvInverter.ChannelId.MAX_CHARGE_CURRENT, new UnsignedWordElement(108)),
						m(ManagedSymmetricPvInverter.ChannelId.MAX_DISCHARGE_CURRENT, new UnsignedWordElement(109)),
						new DummyRegisterElement(110, 113),
						m(ManagedSymmetricPvInverter.ChannelId.MAX_CHARGE_SOC, new UnsignedWordElement(114)),
						m(ManagedSymmetricPvInverter.ChannelId.MAX_DISCHARGE_SOC, new UnsignedWordElement(115))),
				new FC16WriteRegistersTask(1104,
						m(ManagedSymmetricPvInverter.ChannelId.REMOTE_CONTROL, new UnsignedWordElement(1104)),
						new DummyRegisterElement(1105, 1105),
						m(ManagedSymmetricPvInverter.ChannelId.REMOTE_CONTROL_P, new UnsignedWordElement(1106)),
						new DummyRegisterElement(1107, 1110),
						m(ManagedSymmetricPvInverter.ChannelId.ACTIVE_POWER_LIMIT_PERCENT,
								new UnsignedWordElement(1111)),
						new DummyRegisterElement(1112, 1116),
						m(ManagedSymmetricPvInverter.ChannelId.REMOTE_CONTROL_Q, new UnsignedWordElement(1117)),
						m(ManagedSymmetricPvInverter.ChannelId.REACTIVE_POWER_LIMIT_PERCENT,
								new UnsignedWordElement(1118))));
	}

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}
		switch (event.getTopic()) {
			case EdgeEventConstants.TOPIC_CYCLE_EXECUTE_WRITE:
				try {
					this.setPvLimitHandler.run();
					this.setReactivePowerLimitHandler.run();

					this.channel(PvInverterDeyeHybrid.ChannelId.PV_LIMIT_FAILED).setNextValue(false);
				} catch (OpenemsNamedException e) {
					this.channel(PvInverterDeyeHybrid.ChannelId.PV_LIMIT_FAILED).setNextValue(true);
				}
				break;
			case EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE:
				Integer activePower = this.getActivePower().get();
				this.calculateProductionEnergy.update(activePower);
				break;
		}
	}

	@Override
	public MeterType getMeterType() {
		return MeterType.PRODUCTION;
	}

	@Override
	public String debugLog() {
		return "L:" + this.getActivePower().asString();
	}

	@Override
	protected void logInfo(Logger log, String message) {
		super.logInfo(log, message);
	}

	@Override
	public ModbusSlaveTable getModbusSlaveTable(AccessMode accessMode) {
		return new ModbusSlaveTable(//
				OpenemsComponent.getModbusSlaveNatureTable(accessMode), //
				ElectricityMeter.getModbusSlaveNatureTable(accessMode), //
				ManagedSymmetricPvInverter.getModbusSlaveNatureTable(accessMode));
	}

	@Override
	public Timedata getTimedata() {
		return this.timedata;
	}

}
