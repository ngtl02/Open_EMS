package io.openems.edge.meter.edmi.mk6e;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.INVERT_IF_TRUE;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_3;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_2;
import static io.openems.edge.bridge.modbus.api.element.WordOrder.MSWLSW;

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
import org.osgi.service.metatype.annotations.Designate;

import io.openems.common.channel.AccessMode;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.types.MeterType;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.FloatDoublewordElement;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.modbusslave.ModbusSlaveNatureTable;
import io.openems.edge.common.modbusslave.ModbusSlaveTable;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.meter.api.ElectricityMeter;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Meter.EDMI.MK6E", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class MeterEDMIMK6EImpl extends AbstractOpenemsModbusComponent
		implements ElectricityMeter, MeterEDMIMK6E, ModbusComponent, OpenemsComponent {

	@Reference
	private ConfigurationAdmin cm;

	@Override
	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	private Config config;

	/** Invert power values. */
	private boolean invert = false;

	public MeterEDMIMK6EImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				MeterEDMIMK6E.ChannelId.values() //
		);
		// Note: Do NOT call calculateSumCurrentFromPhases or
		// calculateAverageVoltageFromPhases
		// in the constructor - channels are not yet properly initialized at this point
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsException {
		this.config = config;
		this.invert = config.invert();
		if (super.activate(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId(), this.cm,
				"Modbus", config.modbus_id())) {
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
		var modbusProtocol = new ModbusProtocol(this,
				new FC3ReadRegistersTask(0x2395, Priority.HIGH,
						// ====== Voltage ======
						m(ElectricityMeter.ChannelId.VOLTAGE_L1,
								new FloatDoublewordElement(0x2395)
										.wordOrder(MSWLSW)),
						m(ElectricityMeter.ChannelId.VOLTAGE_L2,
								new FloatDoublewordElement(0x2397)
										.wordOrder(MSWLSW)),
						m(ElectricityMeter.ChannelId.VOLTAGE_L3,
								new FloatDoublewordElement(0x2399)
										.wordOrder(MSWLSW)),

						// ====== Current ======
						m(ElectricityMeter.ChannelId.CURRENT_L1,
								new FloatDoublewordElement(0x239B)
										.wordOrder(MSWLSW)),
						m(ElectricityMeter.ChannelId.CURRENT_L2,
								new FloatDoublewordElement(0x239D)
										.wordOrder(MSWLSW)),
						m(ElectricityMeter.ChannelId.CURRENT_L3,
								new FloatDoublewordElement(0x239F)
										.wordOrder(MSWLSW)),

						// ====== Active Power ======
						m(ElectricityMeter.ChannelId.ACTIVE_POWER,
								new FloatDoublewordElement(0x23A1)
										.wordOrder(MSWLSW),
								INVERT_IF_TRUE(this.invert)),
						m(ElectricityMeter.ChannelId.REACTIVE_POWER,
								new FloatDoublewordElement(0x23A3)
										.wordOrder(MSWLSW),
								INVERT_IF_TRUE(this.invert)),
						m(ElectricityMeter.ChannelId.FREQUENCY,
								new FloatDoublewordElement(0x23A5)
										.wordOrder(MSWLSW)),
						new DummyRegisterElement(0x23A7, 0x23C2),
						// ====== Active Power per Phase ======
						m(ElectricityMeter.ChannelId.ACTIVE_POWER_L1,
								new FloatDoublewordElement(0x23C3)
										.wordOrder(MSWLSW),
								INVERT_IF_TRUE(this.invert)),
						m(ElectricityMeter.ChannelId.ACTIVE_POWER_L2,
								new FloatDoublewordElement(0x23C5)
										.wordOrder(MSWLSW),
								INVERT_IF_TRUE(this.invert)),
						m(ElectricityMeter.ChannelId.ACTIVE_POWER_L3,
								new FloatDoublewordElement(0x23C7)
										.wordOrder(MSWLSW),
								INVERT_IF_TRUE(this.invert)),

						// ====== Reactive Power per Phase ======
						m(ElectricityMeter.ChannelId.REACTIVE_POWER_L1,
								new FloatDoublewordElement(0x23C9)
										.wordOrder(MSWLSW),
								INVERT_IF_TRUE(this.invert)),
						m(ElectricityMeter.ChannelId.REACTIVE_POWER_L2,
								new FloatDoublewordElement(0x23CB)
										.wordOrder(MSWLSW),
								INVERT_IF_TRUE(this.invert)),
						m(ElectricityMeter.ChannelId.APPARENT_POWER,
								new FloatDoublewordElement(0x23CD)
										.wordOrder(MSWLSW)),
						m(ElectricityMeter.ChannelId.REACTIVE_POWER_L3,
								new FloatDoublewordElement(0x23CF)
										.wordOrder(MSWLSW),
								INVERT_IF_TRUE(this.invert))));
		return modbusProtocol;
	}

	@Override
	public String debugLog() {
		return "L:" + this.getActivePower().asString();
	}

	@Override
	public MeterType getMeterType() {
		return this.config.meterType();
	}

	@Override
	public ModbusSlaveTable getModbusSlaveTable(AccessMode accessMode) {
		return new ModbusSlaveTable(//
				OpenemsComponent.getModbusSlaveNatureTable(accessMode), //
				ElectricityMeter.getModbusSlaveNatureTable(accessMode), //
				ModbusSlaveNatureTable.of(MeterEDMIMK6E.class, accessMode, 100) //
						.build());
	}
}
