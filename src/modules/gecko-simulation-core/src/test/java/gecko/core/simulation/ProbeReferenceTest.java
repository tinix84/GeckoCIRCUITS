package gecko.core.simulation;

import gecko.core.io.CircuitFileParser;
import gecko.core.io.CircuitModel;
import gecko.core.preparation.CircuitPreparation;
import gecko.core.preparation.ExecutionOptions;
import gecko.core.preparation.PreparationRequest;
import gecko.core.preparation.PreparationResult;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProbeReferenceTest {
    @Test
    void parsesOrderedReferencesAndReportsInvalidSuffixes() {
        assertEquals(List.of("L.1.i", "C.1.v", "D.1.state"),
                ProbeReference.parseText(" L.1.i, C.1.v , D.1.state ").stream().map(Object::toString).toList());
        assertTrue(ProbeReference.parseText(" ").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> ProbeReference.parseText("L.1.loss"));
        assertThrows(IllegalArgumentException.class, () -> ProbeReference.parseText("D.1.i,"));
    }

    @Test
    void rejectsAnUnresolvedTargetBeforeSimulation() throws Exception {
        var model = buckWithProbe("missing.i", "C.1.v", "D.1.state");
        var rejected = prepare(model);
        assertInstanceOf(PreparationResult.Inspectable.class, rejected);
        assertTrue(rejected.report().diagnostics().stream()
                .anyMatch(item -> item.code().equals("INVALID_PROBE_REFERENCE") && item.message().contains("missing.i")));
    }

    @Test
    void transfersRawBuckSignalsWithMeterPolarityAndCorrectDiodeState() throws Exception {
        var model = buckWithProbe("L.1.i", "C.1.v", "D.1.state");
        var ready = assertInstanceOf(PreparationResult.Ready.class, prepare(model));
        var verifier = new DomainCoupler();
        var engine = new HeadlessSimulationEngine();
        int[] diodeStates = {0};
        var mapping = new ControlCalculatorFactory().buildCircuit(model, model.getTimeStep()).getMapping();
        verifier.configureFromMapping(mapping);
        var probe = mapping.componentProbes().getFirst().calculator()._outputSignal;
        var current = mapping.getAmpProbeMappings().stream()
                .filter(meter -> meter.getComponentName().equals("L.1")).findFirst().orElseThrow();
        var voltage = mapping.getVoltProbeMappings().stream()
                .filter(meter -> meter.getName().equals("VOLT.3")).findFirst().orElseThrow();
        engine.setStepTraceListener((time, voltages, currents, netlist) -> {
            verifier.transferLkToControlProbes(netlist);
            assertEquals(current.getCalculator()._outputSignal[0][0], probe[0][0], 1e-12);
            assertEquals(voltage.getCalculator()._outputSignal[0][0], probe[1][0], 1e-12);
            int diode = netlist.getElementIndexByName("D.1");
            if (currents[diode] > 0.1) {
                assertEquals(1.0, probe[2][0]);
                diodeStates[0] |= 1;
            }
            if (voltages[netlist.getNodeX(diode)] - voltages[netlist.getNodeY(diode)] < -0.1) {
                assertEquals(0.0, probe[2][0]);
                diodeStates[0] |= 2;
            }
        });
        var result = engine.runSimulation(ready.execution(), ExecutionOptions.defaults(), () -> false);
        assertEquals(SimulationResult.Status.SUCCESS, result.getStatus());
        assertEquals(3, diodeStates[0], "The trace must include forward conduction and reverse blocking.");
    }

    private static CircuitModel buckWithProbe(String... references) throws Exception {
        var model = new CircuitFileParser().parse("src/test/resources/ipes/buck_simple.ipes");
        model.setSimulationDuration(model.getTimeStep() * 1800);
        var probe = new CircuitModel.ComponentData(ProbeReference.TYPE, "PROBE.1", 35, 26, 503);
        probe.setParameterStrings(references);
        probe.setTerminalYLabels(new String[] {"probeI", "probeV", "probeState"});
        model.getControlComponents().add(probe);
        return model;
    }

    private static PreparationResult prepare(CircuitModel model) {
        var preparation = new CircuitPreparation();
        return preparation.prepare(preparation.capture(model), PreparationRequest.defaults());
    }
}
