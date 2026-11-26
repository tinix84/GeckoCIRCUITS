package gecko.core.preparation;

import gecko.core.io.CircuitFileParser;
import gecko.core.io.CircuitFileWriter;
import gecko.core.io.CircuitModel;
import gecko.core.simulation.HeadlessSimulationEngine;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SourcePreparationTest {
    static String element(String domain, int type, String name, String parameters, String inputs, String outputs, String extra) {
        return (domain.equals("LK") ? "e" : "c") + "\n<Element" + domain + ">\ntyp " + type
                + "\nidStringDialog " + name + "\nx 0\ny 0\norientierung 503\nparameterString[] /\nparameter[] " + parameters
                + "\nlabelAnfangsKnoten[] " + inputs + "\nlabelEndKnoten[] " + outputs + "\n" + extra
                + "\n<" + (char) 92 + "Element" + domain + ">\n";
    }
    static CircuitModel parse(String components) throws Exception {
        return new CircuitFileParser().parse(new BufferedReader(new StringReader("dt 0.125\ntDURATION 0.25\n" + components)), "uploaded.ipes");
    }
    static List<Double> sourceValues(PreparedCircuit circuit, int type) {
        List<Double> values = new ArrayList<>();
        var engine = new HeadlessSimulationEngine();
        engine.setStepTraceListener((time, volts, amps, netlist) -> {
            int index = netlist.getElementIndexByName("source");
            values.add(type == 4 ? volts[netlist.getNodeX(index)] : amps[index]);
        });
        var result = engine.runSimulation(circuit, ExecutionOptions.defaults(), () -> false);
        assertTrue(result.isSuccess(), result.getErrorMessage());
        return values;
    }
    static Stream<Arguments> savedSources() {
        return Stream.of(4, 5).flatMap(type -> Stream.of(
                Arguments.of(type, "401 6", "", Map.of(), List.of(6.0, 6.0, 6.0)),
                Arguments.of(type, "0 6", "", Map.of(), List.of(6.0, 6.0, 6.0)),
                Arguments.of(type, "402 6 1 2 1.5707963267948966", "", Map.of(), List.of(-4.0, -2.242640687119285, 2.0)),
                Arguments.of(type, "1 6 1 2 1.5707963267948966", "", Map.of(), List.of(-4.0, -2.242640687119285, 2.0)),
                Arguments.of(type, "1 6 1 2 1.5707963267948966 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 9",
                        "amplitude 0\nphaseDegrees 0", Map.of(), List.of(2.0, 2.0, 2.0)),
                Arguments.of(type, "1 6 1 2 1.5707963267948966", "amplitude 8\nfrequency 0\noffset -2\nphaseDegrees 90",
                        Map.of(), List.of(-10.0, -10.0, -10.0)),
                Arguments.of(type, "401 6", "", Map.of("source.sourceType", 1.0, "source.dcValue", 9.0,
                        "source.amplitude", 4.0, "source.frequency", 0.0, "source.offset", 2.0, "source.phaseDegrees", 90.0), List.of(-2.0, -2.0, -2.0))));
    }
    @ParameterizedTest
    @MethodSource("savedSources")
    void savedSourceLayoutsAndOverridesProduceTheConfiguredWaveform(int type, String parameters, String tokens,
            Map<String, Double> overrides, List<Double> expected) throws Exception {
        var model = parse(element("LK", type, "source", parameters, "/1", "/0", tokens)
                + element("LK", 1, "R.1", "10", "/1", "/0", ""));
        var preparation = new CircuitPreparation();
        var result = assertInstanceOf(PreparationResult.Ready.class, preparation.prepare(preparation.capture(model),
                new PreparationRequest(SolverOverrides.none(), overrides)));
        var actual = sourceValues(result.execution(), type);
        assertEquals(expected.size(), actual.size());
        for (int index = 0; index < expected.size(); index++) assertEquals(expected.get(index), actual.get(index), 1e-10);
        var repeated = assertInstanceOf(PreparationResult.Ready.class,
                preparation.prepare(result.circuit(), PreparationRequest.defaults()));
        assertEquals(actual, sourceValues(repeated.execution(), type));
    }

    @org.junit.jupiter.api.Test
    void savedOptimizerBindingsResolveAfterNamedValuesAndOverridesPersist() throws Exception {
        for (String binding : new String[]{"unchanged", "replacement", "cleared"}) {
            String tokens = "nameOpt[] /resistorValue\nresistance 30";
            if (!binding.equals("unchanged")) tokens += "\nresistance$ParameterName " + (binding.equals("replacement") ? "replacementValue" : "");
            var model = parse(element("LK", 4, "source", "401 6", "/1", "/0", "")
                    + element("LK", 1, "R.1", "10", "/1", "/0", tokens));
            model.getOptimizerParameters().put("resistorValue", 20.0);
            model.getOptimizerParameters().put("replacementValue", 40.0);
            var preparation = new CircuitPreparation();
            var base = preparation.capture(model);
            var result = assertInstanceOf(PreparationResult.Ready.class, preparation.prepare(base, PreparationRequest.defaults()));
            double expected = binding.equals("unchanged") ? 0.3 : binding.equals("replacement") ? 0.15 : 0.2;
            for (double current : CircuitPreparationTest.currents(result.execution())) assertEquals(expected, current, 1e-10);
            var changed = assertInstanceOf(PreparationResult.Ready.class, preparation.prepare(result.circuit(),
                    new PreparationRequest(SolverOverrides.none(), Map.of("R.1.resistance", 60.0))));
            var repeated = assertInstanceOf(PreparationResult.Ready.class,
                    preparation.prepare(changed.circuit(), PreparationRequest.defaults()));
            for (double current : CircuitPreparationTest.currents(repeated.execution())) assertEquals(0.1, current, 1e-10);
        }
    }
    @org.junit.jupiter.api.Test
    void editingLegacyDcDoesNotChangeTheAmplitudeBindingOrPhaseOnReload() throws Exception {
        var model = parse(element("LK", 4, "source", "402 6 1 4 1.5707963267948966",
                "/1", "/0", "nameOpt[] /NIX_NIX_NIX/sourceValue")
                + element("LK", 1, "R.1", "10", "/1", "/0", ""));
        model.getOptimizerParameters().put("sourceValue", 20.0);
        var preparation = new CircuitPreparation();
        var edited = assertInstanceOf(PreparationResult.Ready.class, preparation.prepareForSave(
                preparation.capture(model), new PreparationRequest(SolverOverrides.none(), Map.of("source.dcValue", 9.0))));
        byte[] bytes = new CircuitFileWriter().write(edited.circuit().toModel());

        var reopened = new CircuitFileParser().parse(new ByteArrayInputStream(bytes), "reopened.ipes");
        reopened.getOptimizerParameters().put("sourceValue", 40.0);
        var prepared = assertInstanceOf(PreparationResult.Ready.class,
                preparation.prepare(preparation.capture(reopened), PreparationRequest.defaults()));
        var source = prepared.circuit().toModel().getCircuitComponents().getFirst().getParameters();
        assertEquals(9.0, source.get("dcValue"));
        assertEquals(40.0, source.get("amplitude"));
        assertEquals(90.0, source.get("phaseDegrees"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void savingPreservesControlSignalPhaseAndOffset(boolean named) throws Exception {
        String tokens = named ? "phase 60\nanteilDC 3\nanteilDC$ParameterName midpoint" : "";
        var model = parse(element("LK", 4, "source", "401 6", "/1", "/0", "amplitude 2\nphase 30")
                + element("LK", 1, "R.1", "10", "/1", "/0", "nameOpt[] null")
                + element("CONTROL", 4, "drive", "401 5 35 4 1.5707963267948966 0.5 0 0", "", "/drive", tokens));
        model.getOptimizerParameters().put("midpoint", 8.0);
        var preparation = new CircuitPreparation();
        var result = preparation.prepareForSave(preparation.capture(model),
                new PreparationRequest(SolverOverrides.none(), Map.of("R.1.resistance", 12.0, "drive.useExternal", 0.0)));
        var edited = assertInstanceOf(PreparationResult.Ready.class, result, () -> result.report().diagnostics().toString());
        byte[] bytes = new CircuitFileWriter().write(edited.circuit().toModel());
        var reopened = new CircuitFileParser().parse(new ByteArrayInputStream(bytes), "control.ipes");
        reopened.getOptimizerParameters().put("midpoint", 11.0);
        var prepared = assertInstanceOf(PreparationResult.Ready.class,
                preparation.prepare(preparation.capture(reopened), PreparationRequest.defaults()));
        var drive = prepared.circuit().toModel().getControlComponents().getFirst();
        assertEquals(named ? 60.0 : 90.0, ((Number) drive.getParameters().get("phaseDegrees")).doubleValue(), 1e-12);
        assertEquals(named ? 11.0 : 4.0, drive.getParameters().get("offset"));
        assertEquals("false", drive.getImportTokens().get("useExternal"));
    }

    @org.junit.jupiter.api.Test
    void savedNamedEditDetachesItsBindingAndRejectsInvalidChanges() throws Exception {
        var model = parse(element("LK", 4, "source", "401 6", "/1", "/0", "")
                + element("LK", 1, "R.1", "10", "/1", "/0",
                        "nameOpt[] resistorValue\nresistance 30\nresistance$ParameterName resistorValue"));
        model.getOptimizerParameters().put("resistorValue", 20.0);
        var preparation = new CircuitPreparation();
        var base = preparation.capture(model);
        assertInstanceOf(PreparationResult.Rejected.class, preparation.prepareForSave(base,
                new PreparationRequest(SolverOverrides.none(), Map.of("R.1.resistance", -1.0))));
        var edited = assertInstanceOf(PreparationResult.Ready.class, preparation.prepareForSave(base,
                new PreparationRequest(SolverOverrides.none(), Map.of("R.1.resistance", 60.0))));
        byte[] bytes = new CircuitFileWriter().write(edited.circuit().toModel());
        var reopened = new CircuitFileParser().parse(new ByteArrayInputStream(bytes), "named.ipes");
        var prepared = assertInstanceOf(PreparationResult.Ready.class,
                preparation.prepare(preparation.capture(reopened), PreparationRequest.defaults()));
        assertEquals(60.0, prepared.circuit().toModel().getCircuitComponents().get(1).getParameters().get("resistance"));
        assertEquals(20.0, reopened.getOptimizerParameters().get("resistorValue"));
    }

    @org.junit.jupiter.api.Test
    void savedNullOptimizerArrayMeansNoBinding() throws Exception {
        var model = parse(element("LK", 4, "source", "401 6", "/1", "/0", "nameOpt[] null")
                + element("LK", 1, "R.1", "10", "/1", "/0", "nameOpt[] null"));
        var preparation = new CircuitPreparation();
        var ready = assertInstanceOf(PreparationResult.Ready.class,
                preparation.prepare(preparation.capture(model), PreparationRequest.defaults()));
        for (double current : CircuitPreparationTest.currents(ready.execution())) assertEquals(0.6, current, 1e-10);
    }
    @org.junit.jupiter.api.Test
    void delimitedOptimizerNameNullRemainsAnOpaqueBindingName() throws Exception {
        var model = parse(element("LK", 4, "source", "401 6", "/1", "/0", "")
                + element("LK", 1, "R.1", "10", "/1", "/0", "nameOpt[] /null"));
        model.getOptimizerParameters().put("null", 20.0);
        var preparation = new CircuitPreparation();
        var ready = assertInstanceOf(PreparationResult.Ready.class,
                preparation.prepare(preparation.capture(model), PreparationRequest.defaults()));
        for (double current : CircuitPreparationTest.currents(ready.execution())) assertEquals(0.3, current, 1e-10);
    }
}
