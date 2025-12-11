package io.openems.edge.pvinverter.solis.ongrid;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_2;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_3;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_1;
//import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_3;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_2;

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
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.element.WordOrder;
import io.openems.edge.bridge.modbus.api.task.FC6WriteRegisterTask;
import io.openems.edge.bridge.modbus.api.task.FC16WriteRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC4ReadInputRegistersTask;
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
		name = "PV-Inverter.Solis.OnGrid", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE, //
		property = { //
				"type=PRODUCTION" //
		})
@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_EXECUTE_WRITE, //
		EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE //
})
public class PvInverterSolisOnGridImpl extends AbstractOpenemsModbusComponent
		implements PvInverterSolisOnGrid, ManagedSymmetricPvInverter, ElectricityMeter, ModbusComponent,
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

	public PvInverterSolisOnGridImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				ManagedSymmetricPvInverter.ChannelId.values(), //
				PvInverterSolisOnGrid.ChannelId.values() //
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
				// =====================================================================
				// PV Input Section
				// =====================================================================
				new FC4ReadInputRegistersTask(3004, Priority.LOW,
						m(ElectricityMeter.ChannelId.ACTIVE_POWER,
								new UnsignedDoublewordElement(3004).wordOrder(WordOrder.MSWLSW)),
						m(PvInverterSolisOnGrid.ChannelId.DC_TOTAL,
								new UnsignedDoublewordElement(3006).wordOrder(WordOrder.MSWLSW)),
						m(ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY,
								new UnsignedDoublewordElement(3008).wordOrder(WordOrder.MSWLSW), SCALE_FACTOR_3),
						m(PvInverterSolisOnGrid.ChannelId.ACTIVE_PRODUCTION_ENERGY_CURRENTMONTH,
								new UnsignedDoublewordElement(3010).wordOrder(WordOrder.MSWLSW), SCALE_FACTOR_3),
						m(PvInverterSolisOnGrid.ChannelId.ACTIVE_PRODUCTION_ENERGY_LASTMONTH,
								new UnsignedDoublewordElement(3012).wordOrder(WordOrder.MSWLSW), SCALE_FACTOR_3),
						m(PvInverterSolisOnGrid.ChannelId.ACTIVE_PRODUCTION_ENERGY_DAILY,
								new UnsignedWordElement(3014), SCALE_FACTOR_2),
						m(PvInverterSolisOnGrid.ChannelId.ACTIVE_PRODUCTION_ENERGY_YESTERDAY,
								new UnsignedWordElement(3015), SCALE_FACTOR_2)

				),

				new FC4ReadInputRegistersTask(3021, Priority.HIGH,
						m(PvInverterSolisOnGrid.ChannelId.DC1_VOLTAGE,
								new UnsignedWordElement(3021), SCALE_FACTOR_MINUS_1),
						m(PvInverterSolisOnGrid.ChannelId.DC1_AMPERE,
								new UnsignedWordElement(3022), SCALE_FACTOR_MINUS_1),
						m(PvInverterSolisOnGrid.ChannelId.DC2_VOLTAGE,
								new UnsignedWordElement(3023), SCALE_FACTOR_MINUS_1),
						m(PvInverterSolisOnGrid.ChannelId.DC2_AMPERE,
								new UnsignedWordElement(3024), SCALE_FACTOR_MINUS_1),
						m(PvInverterSolisOnGrid.ChannelId.DC3_VOLTAGE,
								new UnsignedWordElement(3025), SCALE_FACTOR_MINUS_1),
						m(PvInverterSolisOnGrid.ChannelId.DC3_AMPERE,
								new UnsignedWordElement(3026), SCALE_FACTOR_MINUS_1),
						m(PvInverterSolisOnGrid.ChannelId.DC4_VOLTAGE,
								new UnsignedWordElement(3027), SCALE_FACTOR_MINUS_1),
						m(PvInverterSolisOnGrid.ChannelId.DC4_AMPERE,
								new UnsignedWordElement(3028), SCALE_FACTOR_MINUS_1),
						new DummyRegisterElement(3029, 3032),

						// =================================================================
						// AC Side (Grid / Output)
						// =================================================================
						m(ElectricityMeter.ChannelId.CURRENT_L1,
								new UnsignedWordElement(3033), SCALE_FACTOR_MINUS_1),
						m(ElectricityMeter.ChannelId.CURRENT_L2,
								new UnsignedWordElement(3034), SCALE_FACTOR_MINUS_1),
						m(ElectricityMeter.ChannelId.CURRENT_L3,
								new UnsignedWordElement(3035), SCALE_FACTOR_MINUS_1),
						m(ElectricityMeter.ChannelId.VOLTAGE_L1,
								new UnsignedWordElement(3036), SCALE_FACTOR_MINUS_1),
						m(ElectricityMeter.ChannelId.VOLTAGE_L2,
								new UnsignedWordElement(3037), SCALE_FACTOR_MINUS_1),
						m(ElectricityMeter.ChannelId.VOLTAGE_L3,
								new UnsignedWordElement(3038), SCALE_FACTOR_MINUS_1),
						new DummyRegisterElement(3039, 3040),
						m(PvInverterSolisOnGrid.ChannelId.TEMPERATURE,
								new SignedDoublewordElement(3041), SCALE_FACTOR_MINUS_1),
						m(ElectricityMeter.ChannelId.FREQUENCY,
								new SignedDoublewordElement(3042), SCALE_FACTOR_MINUS_2),
						new DummyRegisterElement(3043, 3054),
						m(ElectricityMeter.ChannelId.REACTIVE_POWER,
								new SignedDoublewordElement(3055).wordOrder(WordOrder.MSWLSW)),
						m(ElectricityMeter.ChannelId.APPARENT_POWER,
								new SignedDoublewordElement(3057).wordOrder(WordOrder.MSWLSW))),
				new FC6WriteRegisterTask(3070, //
						m(ManagedSymmetricPvInverter.ChannelId.REMOTE_CONTROL, new UnsignedWordElement(3070))),
				new FC6WriteRegisterTask(3071, //
						m(ManagedSymmetricPvInverter.ChannelId.REMOTE_CONTROL_Q, new UnsignedWordElement(3071))),
				new FC16WriteRegistersTask(3081,
						m(ManagedSymmetricPvInverter.ChannelId.ACTIVE_POWER_LIMIT, new SignedWordElement(3081)),
						new DummyRegisterElement(3082, 3082),
						m(ManagedSymmetricPvInverter.ChannelId.REACTIVE_POWER_LIMIT, new SignedWordElement(3083))));
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

					this.channel(PvInverterSolisOnGrid.ChannelId.PV_LIMIT_FAILED).setNextValue(false);
				} catch (OpenemsNamedException e) {
					this.channel(PvInverterSolisOnGrid.ChannelId.PV_LIMIT_FAILED).setNextValue(true);
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
