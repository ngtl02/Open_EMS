package io.openems.edge.meter.elecnova.PD194Z;

import static io.openems.edge.common.event.EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE;

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

import io.openems.common.exceptions.OpenemsException;
import io.openems.common.types.MeterType;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.FloatDoublewordElement;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.timedata.api.Timedata;
import io.openems.edge.timedata.api.TimedataProvider;
import io.openems.edge.timedata.api.utils.CalculateEnergyFromPower;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Meter.Elecnova.PD194Z.E14", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
@EventTopics({ //
		TOPIC_CYCLE_BEFORE_PROCESS_IMAGE, //
})
public class MeterElecnovaPD194ZImpl extends AbstractOpenemsModbusComponent
		implements MeterElecnovaPD194Z, ElectricityMeter, ModbusComponent, OpenemsComponent, TimedataProvider,
		EventHandler {

	private CalculateEnergyFromPower calculateProductionEnergy;
	private CalculateEnergyFromPower calculateConsumptionEnergy;

	private MeterType meterType = MeterType.PRODUCTION;

	@Reference
	private ConfigurationAdmin cm;

	@Reference
	private ComponentManager componentManager;

	@Reference(policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.OPTIONAL)
	private volatile Timedata timedata;

	@Override
	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	public MeterElecnovaPD194ZImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				MeterElecnovaPD194Z.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsException {
		this.meterType = config.type();

		this.calculateProductionEnergy = new CalculateEnergyFromPower(this,
				ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY, this.componentManager.getClock());
		this.calculateConsumptionEnergy = new CalculateEnergyFromPower(this,
				ElectricityMeter.ChannelId.ACTIVE_CONSUMPTION_ENERGY, this.componentManager.getClock());

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
	public MeterType getMeterType() {
		return this.meterType;
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		return new ModbusProtocol(this, //
				// Voltages
				new FC3ReadRegistersTask(0x0006, Priority.HIGH, //
						m(ElectricityMeter.ChannelId.VOLTAGE_L1, new FloatDoublewordElement(0x0006)),
						m(ElectricityMeter.ChannelId.VOLTAGE_L2, new FloatDoublewordElement(0x0008)),
						m(ElectricityMeter.ChannelId.VOLTAGE_L3, new FloatDoublewordElement(0x000A))),
				// Frequency
				new FC3ReadRegistersTask(0x0012, Priority.LOW, //
						m(ElectricityMeter.ChannelId.FREQUENCY, new FloatDoublewordElement(0x0012))),

				// ====== Data Line 1 ======
				new FC3ReadRegistersTask(0x0014, Priority.HIGH, //
						m(MeterElecnovaPD194Z.ChannelId.L1_IA, new FloatDoublewordElement(0x0014)),
						m(MeterElecnovaPD194Z.ChannelId.L1_IB, new FloatDoublewordElement(0x0016)),
						m(MeterElecnovaPD194Z.ChannelId.L1_IC, new FloatDoublewordElement(0x0018)),
						m(MeterElecnovaPD194Z.ChannelId.L1_I, new FloatDoublewordElement(0x001A)),
						m(MeterElecnovaPD194Z.ChannelId.L1_PA, new FloatDoublewordElement(0x001C)),
						m(MeterElecnovaPD194Z.ChannelId.L1_PB, new FloatDoublewordElement(0x001E)),
						m(MeterElecnovaPD194Z.ChannelId.L1_PC, new FloatDoublewordElement(0x0020)),
						m(MeterElecnovaPD194Z.ChannelId.L1_P, new FloatDoublewordElement(0x0022)),
						m(MeterElecnovaPD194Z.ChannelId.L1_QA, new FloatDoublewordElement(0x0024)),
						m(MeterElecnovaPD194Z.ChannelId.L1_QB, new FloatDoublewordElement(0x0026)),
						m(MeterElecnovaPD194Z.ChannelId.L1_QC, new FloatDoublewordElement(0x0028)),
						m(MeterElecnovaPD194Z.ChannelId.L1_Q, new FloatDoublewordElement(0x002A)),
						m(MeterElecnovaPD194Z.ChannelId.L1_SA, new FloatDoublewordElement(0x002C)),
						m(MeterElecnovaPD194Z.ChannelId.L1_SB, new FloatDoublewordElement(0x002E)),
						m(MeterElecnovaPD194Z.ChannelId.L1_SC, new FloatDoublewordElement(0x0030)),
						m(MeterElecnovaPD194Z.ChannelId.L1_S, new FloatDoublewordElement(0x0032)),
						m(MeterElecnovaPD194Z.ChannelId.L1_PFA, new FloatDoublewordElement(0x0034)),
						m(MeterElecnovaPD194Z.ChannelId.L1_PFB, new FloatDoublewordElement(0x0036)),
						m(MeterElecnovaPD194Z.ChannelId.L1_PFC, new FloatDoublewordElement(0x0038)),
						m(MeterElecnovaPD194Z.ChannelId.L1_PF, new FloatDoublewordElement(0x003A))),

				// ====== Data Line 2 ======
				new FC3ReadRegistersTask(0x005C, Priority.HIGH, //
						m(MeterElecnovaPD194Z.ChannelId.L2_IA, new FloatDoublewordElement(0x005C)),
						m(MeterElecnovaPD194Z.ChannelId.L2_IB, new FloatDoublewordElement(0x005E)),
						m(MeterElecnovaPD194Z.ChannelId.L2_IC, new FloatDoublewordElement(0x0060)),
						m(MeterElecnovaPD194Z.ChannelId.L2_I, new FloatDoublewordElement(0x0062)),
						m(MeterElecnovaPD194Z.ChannelId.L2_PA, new FloatDoublewordElement(0x0064)),
						m(MeterElecnovaPD194Z.ChannelId.L2_PB, new FloatDoublewordElement(0x0066)),
						m(MeterElecnovaPD194Z.ChannelId.L2_PC, new FloatDoublewordElement(0x0068)),
						m(MeterElecnovaPD194Z.ChannelId.L2_P, new FloatDoublewordElement(0x006A)),
						m(MeterElecnovaPD194Z.ChannelId.L2_QA, new FloatDoublewordElement(0x006C)),
						m(MeterElecnovaPD194Z.ChannelId.L2_QB, new FloatDoublewordElement(0x006E)),
						m(MeterElecnovaPD194Z.ChannelId.L2_QC, new FloatDoublewordElement(0x0070)),
						m(MeterElecnovaPD194Z.ChannelId.L2_Q, new FloatDoublewordElement(0x0072)),
						m(MeterElecnovaPD194Z.ChannelId.L2_SA, new FloatDoublewordElement(0x0074)),
						m(MeterElecnovaPD194Z.ChannelId.L2_SB, new FloatDoublewordElement(0x0076)),
						m(MeterElecnovaPD194Z.ChannelId.L2_SC, new FloatDoublewordElement(0x0078)),
						m(MeterElecnovaPD194Z.ChannelId.L2_S, new FloatDoublewordElement(0x007A)),
						m(MeterElecnovaPD194Z.ChannelId.L2_PFA, new FloatDoublewordElement(0x007C)),
						m(MeterElecnovaPD194Z.ChannelId.L2_PFB, new FloatDoublewordElement(0x007E)),
						m(MeterElecnovaPD194Z.ChannelId.L2_PFC, new FloatDoublewordElement(0x0080)),
						m(MeterElecnovaPD194Z.ChannelId.L2_PF, new FloatDoublewordElement(0x0082))),

				// ====== Data Line 3 ======
				new FC3ReadRegistersTask(0x00A4, Priority.HIGH, //
						m(MeterElecnovaPD194Z.ChannelId.L3_IA, new FloatDoublewordElement(0x00A4)),
						m(MeterElecnovaPD194Z.ChannelId.L3_IB, new FloatDoublewordElement(0x00A6)),
						m(MeterElecnovaPD194Z.ChannelId.L3_IC, new FloatDoublewordElement(0x00A8)),
						m(MeterElecnovaPD194Z.ChannelId.L3_I, new FloatDoublewordElement(0x00AA)),
						m(MeterElecnovaPD194Z.ChannelId.L3_PA, new FloatDoublewordElement(0x00AC)),
						m(MeterElecnovaPD194Z.ChannelId.L3_PB, new FloatDoublewordElement(0x00AE)),
						m(MeterElecnovaPD194Z.ChannelId.L3_PC, new FloatDoublewordElement(0x00B0)),
						m(MeterElecnovaPD194Z.ChannelId.L3_P, new FloatDoublewordElement(0x00B2)),
						m(MeterElecnovaPD194Z.ChannelId.L3_QA, new FloatDoublewordElement(0x00B4)),
						m(MeterElecnovaPD194Z.ChannelId.L3_QB, new FloatDoublewordElement(0x00B6)),
						m(MeterElecnovaPD194Z.ChannelId.L3_QC, new FloatDoublewordElement(0x00B8)),
						m(MeterElecnovaPD194Z.ChannelId.L3_Q, new FloatDoublewordElement(0x00BA)),
						m(MeterElecnovaPD194Z.ChannelId.L3_SA, new FloatDoublewordElement(0x00BC)),
						m(MeterElecnovaPD194Z.ChannelId.L3_SB, new FloatDoublewordElement(0x00BE)),
						m(MeterElecnovaPD194Z.ChannelId.L3_SC, new FloatDoublewordElement(0x00C0)),
						m(MeterElecnovaPD194Z.ChannelId.L3_S, new FloatDoublewordElement(0x00C2)),
						m(MeterElecnovaPD194Z.ChannelId.L3_PFA, new FloatDoublewordElement(0x00C4)),
						m(MeterElecnovaPD194Z.ChannelId.L3_PFB, new FloatDoublewordElement(0x00C6)),
						m(MeterElecnovaPD194Z.ChannelId.L3_PFC, new FloatDoublewordElement(0x00C8)),
						m(MeterElecnovaPD194Z.ChannelId.L3_PF, new FloatDoublewordElement(0x00CA))),

				// ====== Data Line 4 ======
				new FC3ReadRegistersTask(0x00EC, Priority.HIGH, //
						m(MeterElecnovaPD194Z.ChannelId.L4_IA, new FloatDoublewordElement(0x00EC)),
						m(MeterElecnovaPD194Z.ChannelId.L4_IB, new FloatDoublewordElement(0x00EE)),
						m(MeterElecnovaPD194Z.ChannelId.L4_IC, new FloatDoublewordElement(0x00F0)),
						m(MeterElecnovaPD194Z.ChannelId.L4_I, new FloatDoublewordElement(0x00F2)),
						m(MeterElecnovaPD194Z.ChannelId.L4_PA, new FloatDoublewordElement(0x00F4)),
						m(MeterElecnovaPD194Z.ChannelId.L4_PB, new FloatDoublewordElement(0x00F6)),
						m(MeterElecnovaPD194Z.ChannelId.L4_PC, new FloatDoublewordElement(0x00F8)),
						m(MeterElecnovaPD194Z.ChannelId.L4_P, new FloatDoublewordElement(0x00FA)),
						m(MeterElecnovaPD194Z.ChannelId.L4_QA, new FloatDoublewordElement(0x00FC)),
						m(MeterElecnovaPD194Z.ChannelId.L4_QB, new FloatDoublewordElement(0x00FE)),
						m(MeterElecnovaPD194Z.ChannelId.L4_QC, new FloatDoublewordElement(0x0100)),
						m(MeterElecnovaPD194Z.ChannelId.L4_Q, new FloatDoublewordElement(0x0102)),
						m(MeterElecnovaPD194Z.ChannelId.L4_SA, new FloatDoublewordElement(0x0104)),
						m(MeterElecnovaPD194Z.ChannelId.L4_SB, new FloatDoublewordElement(0x0106)),
						m(MeterElecnovaPD194Z.ChannelId.L4_SC, new FloatDoublewordElement(0x0108)),
						m(MeterElecnovaPD194Z.ChannelId.L4_S, new FloatDoublewordElement(0x010A)),
						m(MeterElecnovaPD194Z.ChannelId.L4_PFA, new FloatDoublewordElement(0x010C)),
						m(MeterElecnovaPD194Z.ChannelId.L4_PFB, new FloatDoublewordElement(0x010E)),
						m(MeterElecnovaPD194Z.ChannelId.L4_PFC, new FloatDoublewordElement(0x0110)),
						m(MeterElecnovaPD194Z.ChannelId.L4_PF, new FloatDoublewordElement(0x0112))));
	}

	/**
	 * Calculate the Energy values from ActivePower.
	 */
	private void calculateEnergy() {
		// Calculate Energy
		final Integer activePower = this.getActivePower().get();
		if (activePower == null) {
			this.calculateProductionEnergy.update(null);
			this.calculateConsumptionEnergy.update(null);
		} else if (activePower >= 0) {
			// Convert from kW to W for energy calculation
			this.calculateProductionEnergy.update(activePower * 1000);
			this.calculateConsumptionEnergy.update(0);
		} else {
			this.calculateProductionEnergy.update(0);
			// Convert from kW to W for energy calculation
			this.calculateConsumptionEnergy.update(-activePower * 1000);
		}
	}

	@Override
	public String debugLog() {
		return "L:" + this.getActivePower().asString();
	}

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}
		switch (event.getTopic()) {
			case TOPIC_CYCLE_BEFORE_PROCESS_IMAGE -> this.calculateEnergy();
		}
	}

	@Override
	public Timedata getTimedata() {
		return this.timedata;
	}
}
