package io.openems.edge.pvinverter.sungrow;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_1;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_2;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_3;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_2;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_3;

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
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.SignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.WordOrder;
import io.openems.edge.bridge.modbus.api.task.FC16WriteRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC4ReadInputRegistersTask;
import io.openems.edge.common.channel.Doc;
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
		name = "io.openems.edge.pvinverter.sungrow", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE, //
		property = { //
				"type=PRODUCTION" //
		})
@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_EXECUTE_WRITE, //
		EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE //
})
public class PvInverterSungrowImpl extends AbstractOpenemsModbusComponent
		implements PvInverterSungrow, ManagedSymmetricPvInverter, ElectricityMeter, ModbusComponent, OpenemsComponent,
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

	private Config config = null;

	public PvInverterSungrowImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				ManagedSymmetricPvInverter.ChannelId.values(), //
				PvInverterSungrow.ChannelId.values() //
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

	/**
	 * Gets the Config object.
	 * 
	 * @return the Config
	 */
	public Config getConfig() {
		return this.config;
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		return new ModbusProtocol(this,
				// Read Input Registers (FC4)
				new FC4ReadInputRegistersTask(5003, Priority.LOW,
						m(PvInverterSungrow.ChannelId.ACTIVE_PRODUCTION_ENERGY_DAILY,
								new UnsignedWordElement(5003), SCALE_FACTOR_2),
						m(PvInverterSungrow.ChannelId.ACTIVE_PRODUCTION_ENERGY_TOTAL,
								new SignedDoublewordElement(5004), SCALE_FACTOR_3),
						new DummyRegisterElement(5006, 5007),
						m(PvInverterSungrow.ChannelId.TEMPERATURE,
								new SignedDoublewordElement(5008), SCALE_FACTOR_1),
						new DummyRegisterElement(5009, 5010),
						m(PvInverterSungrow.ChannelId.DC1_AMPERE,
								new UnsignedWordElement(5011), SCALE_FACTOR_2),
						m(PvInverterSungrow.ChannelId.DC1_VOLTAGE,
								new UnsignedWordElement(5012), SCALE_FACTOR_2),
						m(PvInverterSungrow.ChannelId.DC2_AMPERE,
								new UnsignedWordElement(5013), SCALE_FACTOR_2),
						m(PvInverterSungrow.ChannelId.DC2_VOLTAGE,
								new UnsignedWordElement(5014), SCALE_FACTOR_2),
						m(PvInverterSungrow.ChannelId.DC3_AMPERE,
								new UnsignedWordElement(5015), SCALE_FACTOR_2),
						m(PvInverterSungrow.ChannelId.DC3_VOLTAGE,
								new UnsignedWordElement(5016), SCALE_FACTOR_2),
						m(PvInverterSungrow.ChannelId.DC_TOTAL_POWER,
								new UnsignedDoublewordElement(5017)),
						m(ElectricityMeter.ChannelId.VOLTAGE_L1,
								new UnsignedWordElement(5019), SCALE_FACTOR_2),
						m(ElectricityMeter.ChannelId.VOLTAGE_L2,
								new UnsignedWordElement(5020), SCALE_FACTOR_2),
						m(ElectricityMeter.ChannelId.VOLTAGE_L3,
								new UnsignedWordElement(5021), SCALE_FACTOR_2),
						m(ElectricityMeter.ChannelId.CURRENT_L1,
								new UnsignedWordElement(5022), SCALE_FACTOR_2),
						m(ElectricityMeter.ChannelId.CURRENT_L2,
								new UnsignedWordElement(5023), SCALE_FACTOR_2),
						m(ElectricityMeter.ChannelId.CURRENT_L3,
								new UnsignedWordElement(5024), SCALE_FACTOR_2),
						new DummyRegisterElement(5025, 5030),
						m(ElectricityMeter.ChannelId.ACTIVE_POWER,
								new UnsignedDoublewordElement(5031).wordOrder(WordOrder.MSWLSW)),
						m(ElectricityMeter.ChannelId.REACTIVE_POWER,
								new SignedDoublewordElement(5033).wordOrder(WordOrder.MSWLSW)),
						m(PvInverterSungrow.ChannelId.PF,
								new SignedWordElement(5035), SCALE_FACTOR_2),
						m(ElectricityMeter.ChannelId.FREQUENCY,
								new SignedDoublewordElement(5036).wordOrder(WordOrder.MSWLSW), SCALE_FACTOR_2)),
				new FC4ReadInputRegistersTask(5115, Priority.HIGH,
						m(PvInverterSungrow.ChannelId.DC4_AMPERE,
								new UnsignedWordElement(5115), SCALE_FACTOR_2),
						m(PvInverterSungrow.ChannelId.DC4_VOLTAGE,
								new UnsignedWordElement(5116), SCALE_FACTOR_2)),

				// =====================================================================
				// Sungrow Power Control Registers (Holding Registers - FC3/FC16)
				// =====================================================================
				// Active Power Control: 5007=switch, 5008=% setting
				new FC16WriteRegistersTask(5007,
						m(PvInverterSungrow.ChannelId.P_LIMITATION_SWITCH, new UnsignedWordElement(5007)),
						m(PvInverterSungrow.ChannelId.P_LIMITATION_SETTING, new UnsignedWordElement(5008))),
				// Power factor setting at 5019, skip reserved 5009-5018
				new FC16WriteRegistersTask(5019,
						m(PvInverterSungrow.ChannelId.POWER_FACTOR_SETTING, new SignedWordElement(5019))),
				// Reactive Power Control: 5036=switch, 5037=%, 5038=reserved, 5039=kW,
				// 5040=kvar
				new FC16WriteRegistersTask(5036,
						m(PvInverterSungrow.ChannelId.Q_ADJUSTMENT_SWITCH, new UnsignedWordElement(5036)),
						m(PvInverterSungrow.ChannelId.Q_PERCENTAGE_SETTING, new SignedWordElement(5037)),
						new DummyRegisterElement(5038),
						m(PvInverterSungrow.ChannelId.P_LIMITATION_KW, new UnsignedWordElement(5039)),
						m(PvInverterSungrow.ChannelId.Q_ADJUSTMENT_KVAR, new SignedWordElement(5040))));
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

					this.channel(PvInverterSungrow.ChannelId.PV_LIMIT_FAILED).setNextValue(false);
				} catch (OpenemsNamedException e) {
					this.channel(PvInverterSungrow.ChannelId.PV_LIMIT_FAILED).setNextValue(true);
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
