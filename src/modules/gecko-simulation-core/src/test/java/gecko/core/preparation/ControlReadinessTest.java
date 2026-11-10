package gecko.core.preparation;

import gecko.core.io.CircuitModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static gecko.core.preparation.CircuitPreparationTest.component;
import static gecko.core.preparation.CircuitPreparationTest.resistorCircuit;
import static gecko.core.preparation.CircuitReadinessTest.prepare;
import static org.junit.jupiter.api.Assertions.*;

class ControlReadinessTest {
    private static CircuitModel.ComponentData control(int type, String name, double[] parameters,
                                                      String[] inputs, String[] outputs, String... references) {
        var result = component(type, name, parameters, "", "");
        result.setTerminalXLabels(inputs);
        result.setTerminalYLabels(outputs);
        result.setParameterStrings(references);
        return result;
    }

    private static void diagnostic(CircuitModel model, String code, String name) {
        var result = assertInstanceOf(PreparationResult.Inspectable.class, prepare(model));
        assertFalse(result.report().readyForHeadless());
        assertTrue(result.report().diagnostics().stream().anyMatch(d -> d.code().equals(code)
                && "CONTROL".equals(d.domain()) && name.equals(d.component())), result.report().toString());
    }

    @Test
    void unknownControlTypeCannotBeDroppedFromAnExecutableCircuit() {
        var model = resistorCircuit();
        model.addControlComponent(control(777, "unknown", new double[]{1}, new String[0], new String[]{"out"}));
        diagnostic(model, "UNSUPPORTED_CONTROL_COMPONENT", "unknown");
        assertEquals(ReadinessReport.Outcome.UNSUPPORTED, prepare(model).report().outcome());
    }

    @ParameterizedTest
    @CsvSource({"999"})
    void unsupportedSignalSubtypeCannotFallBackToRectangle(int subtype) {
        var model = resistorCircuit();
        model.addControlComponent(control(4, "signal", new double[]{subtype, 1, 10, 0, 0, 0.5},
                new String[0], new String[]{"out"}));
        diagnostic(model, "UNSUPPORTED_CONTROL_SUBTYPE", "signal");
    }

    @Test
    void rawShortSignalLayoutCannotPassAfterNormalizationExtendsItsArray() {
        var model = resistorCircuit();
        var signal = control(4, "short", new double[]{400, 1, 10, 0, 0}, new String[0], new String[]{"out"});
        signal.setParsedInput(true);
        signal.setImportedParameterCount(5);
        model.addControlComponent(signal);
        diagnostic(model, "INVALID_CONTROL_LAYOUT", "short");
    }

    @Test
    void missingAndAmbiguousInputSignalsHaveStructuredReferenceDiagnostics() {
        var missing = resistorCircuit();
        missing.addControlComponent(control(7, "gain", new double[]{2}, new String[]{"missing"}, new String[]{"out"}));
        var missingReady = assertInstanceOf(PreparationResult.Ready.class, prepare(missing));
        assertTrue(missingReady.report().diagnostics().stream().anyMatch(d -> d.code().equals("MISSING_CONTROL_INPUT")
                && d.severity() == Diagnostic.Severity.WARNING && "gain".equals(d.component())));
        var ambiguous = resistorCircuit();
        ambiguous.addControlComponent(control(3, "one", new double[]{1}, new String[0], new String[]{"shared"}));
        ambiguous.addControlComponent(control(3, "two", new double[]{2}, new String[0], new String[]{"shared"}));
        ambiguous.addControlComponent(control(5, "scope", new double[0], new String[]{"shared"}, new String[0]));
        diagnostic(ambiguous, "AMBIGUOUS_CONTROL_SIGNAL", "scope");
    }

    @Test
    void unsupportedProducerPortCannotBeUsedAsARealSignal() {
        var model = resistorCircuit();
        model.addControlComponent(control(3, "constant", new double[]{1}, new String[0], new String[]{"first", "extra"}));
        model.addControlComponent(control(5, "scope", new double[0], new String[]{"extra"}, new String[0]));
        diagnostic(model, "INVALID_CONTROL_PORT", "constant");
    }

    @ParameterizedTest
    @CsvSource({"2,missing,MISSING_REFERENCE", "6,R.1,INVALID_GATE_REFERENCE", "1,bus,INVALID_PROBE_REFERENCE",
                "1,99,INVALID_PROBE_REFERENCE"})
    void missingAndInvalidElectricalReferencesCannotUseFactoryDefaults(int type, String reference, String code) {
        var model = resistorCircuit();
        model.addControlComponent(control(3, "constant", new double[]{1}, new String[0], new String[]{"drive"}));
        model.addControlComponent(control(type, "reference", new double[0],
                type == 6 ? new String[]{"drive"} : new String[0], type == 6 ? new String[0] : new String[]{"probe"},
                reference, "0"));
        diagnostic(model, code, "reference");
    }

    @Test
    void duplicateElectricalNamesAreAmbiguousProbeReferences() {
        var model = resistorCircuit();
        model.addCircuitComponent(component(1, "R.1", new double[]{20}, "1", "0"));
        model.addControlComponent(control(2, "probe", new double[0], new String[0], new String[]{"amps"}, "R.1"));
        diagnostic(model, "AMBIGUOUS_REFERENCE", "probe");
    }

    @Test
    void duplicateGateTargetsCannotUseTheLastGateAsAnImplicitWinner() {
        var model = resistorCircuit();
        model.addCircuitComponent(component(7, "switch", new double[]{100, 1, 100}, "1", "0"));
        model.addControlComponent(control(3, "constant", new double[]{1}, new String[0], new String[]{"drive"}));
        model.addControlComponent(control(6, "one", new double[0], new String[]{"drive"}, new String[0], "switch"));
        model.addControlComponent(control(6, "two", new double[0], new String[]{"drive"}, new String[0], "switch"));
        diagnostic(model, "AMBIGUOUS_GATE_REFERENCE", "two");
    }

    @Test
    void multipleScopesCannotSilentlyDiscardTheFirstScope() {
        var model = resistorCircuit();
        model.addControlComponent(control(3, "constant", new double[]{1}, new String[0], new String[]{"drive"}));
        model.addControlComponent(control(5, "first", new double[0], new String[]{"drive"}, new String[0]));
        model.addControlComponent(control(5, "second", new double[0], new String[]{"drive"}, new String[0]));
        var ready = assertInstanceOf(PreparationResult.Ready.class, prepare(model));
        var result = new gecko.core.simulation.HeadlessSimulationEngine()
                .runSimulation(ready.execution(), ExecutionOptions.defaults(), () -> false);
        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertEquals(1, result.getDataContainer().getRowLength());
        assertEquals(1, result.getDataContainer().getValue(0, 0));
        @SuppressWarnings("unchecked")
        var channels = (java.util.List<gecko.core.simulation.ScopeDataExtractor.ScopeChannel>) result.getMetadata().get("scopeChannels");
        assertEquals(java.util.List.of("first", "second"), channels.stream()
                .map(gecko.core.simulation.ScopeDataExtractor.ScopeChannel::scopeName).toList());
        assertEquals(java.util.List.of(0, 0), channels.stream()
                .map(gecko.core.simulation.ScopeDataExtractor.ScopeChannel::row).toList());
    }

    @Test
    void importedSignalRequiresItsCapturedResource() {
        var model = resistorCircuit();
        model.addControlComponent(control(4, "signal", new double[]{406, 1, 10, 0, 0, 0.5},
                new String[0], new String[]{"out"}));
        diagnostic(model, "INVALID_PARAMETER", "signal");
    }

    @Test
    void randomWalkIsAnExecutableSignalWithBoundedIndependentIncrements() {
        var model = resistorCircuit();
        model.setTimeStep(0.01);
        model.setSimulationDuration(1);
        model.addControlComponent(control(4, "signal", new double[]{405, 1, 10, 0, 0, 0.5},
                new String[0], new String[]{"out"}));
        model.addControlComponent(control(5, "scope", new double[0], new String[]{"out"}, new String[0]));
        var ready = assertInstanceOf(PreparationResult.Ready.class, prepare(model));
        var result = new gecko.core.simulation.HeadlessSimulationEngine()
                .runSimulation(ready.execution(), ExecutionOptions.defaults(), () -> false);
        assertTrue(result.isSuccess(), result.getErrorMessage());
        double previous = 0;
        for (float sample : result.getSignalData(0)) {
            assertTrue(Math.abs(sample - previous) <= 1.000001);
            previous = sample;
        }
    }

    @Test
    void unsupportedGateTargetDoesNotBecomeAnInvalidDesktopReference() {
        var model = resistorCircuit();
        model.addCircuitComponent(component(777, "unknown", new double[]{1}, "1", "0"));
        model.addControlComponent(control(3, "constant", new double[]{1}, new String[0], new String[]{"drive"}));
        model.addControlComponent(control(6, "gate", new double[0], new String[]{"drive"}, new String[0], "unknown"));
        assertEquals(ReadinessReport.Outcome.UNSUPPORTED, prepare(model).report().outcome());
    }
    @Test
    void completeProbeGateAndSignalReferencesPassWithoutChangingCapturedInputs() {
        var model = resistorCircuit();
        model.addCircuitComponent(component(7, "switch", new double[]{100, 1, 100}, "1", "0"));
        model.addControlComponent(control(3, "constant", new double[]{1}, new String[0], new String[]{"drive"}));
        model.addControlComponent(control(6, "gate", new double[0], new String[]{"drive"}, new String[0], "switch"));
        model.addControlComponent(control(1, "volts", new double[0], new String[0], new String[]{"voltage"}, "1", "0"));
        model.addControlComponent(control(2, "amps", new double[0], new String[0], new String[]{"current"}, "R.1"));
        model.addControlComponent(control(5, "scope", new double[0], new String[]{"voltage", "current"}, new String[0]));
        var result = assertInstanceOf(PreparationResult.Ready.class, prepare(model));
        assertEquals(result.report(), prepare(model).report());
        assertArrayEquals(new double[]{100, 1, 100}, model.getCircuitComponents().getLast().getNumericParameters());
    }
}
