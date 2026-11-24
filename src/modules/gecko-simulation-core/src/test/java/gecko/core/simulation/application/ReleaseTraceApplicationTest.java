package gecko.core.simulation.application;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.ArrayList;
import gecko.core.circuit.netlist.NetlistBuilder;
import gecko.core.io.CircuitFileParser;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Verifies all contract circuits against direct release captures without sample alignment changes. */
@Tag("application")
class ReleaseTraceApplicationTest {
    static List<ReleaseTraceFixture.Case> circuits() throws Exception { return ReleaseTraceFixture.cases(); }

    @ParameterizedTest(name = "{0}")
    @MethodSource("circuits")
    void releaseParity(ReleaseTraceFixture.Case circuit) throws Exception {
        ReleaseTraceFixture.compare(circuit, -1, -1);
    }

    @Test
    void fivePercentReferenceMutationFailsTheMatchingSimulation() throws Exception {
        var circuit = circuits().stream().filter(item -> item.id().equals("buck_simple")).findFirst().orElseThrow();
        int column = -1;
        for (int index = 0; index < circuit.columns().size(); index++) {
            var signal = circuit.columns().get(index).signal();
            if (!signal.discrete() && Math.abs(circuit.rows().getFirst().values()[index])
                    > 1000 * circuit.absoluteTolerances().get(signal.unit())) {
                column = index;
                break;
            }
        }
        assertTrue(column >= 0, "A nonzero analog mutation target is required.");
        int target = column;
        AssertionError failure = assertThrows(AssertionError.class, () -> ReleaseTraceFixture.compare(circuit, 0, target));
        assertTrue(failure.getMessage().contains(circuit.columns().get(target).signal().name()), failure::getMessage);
        assertTrue(failure.getMessage().contains("divergentStep=0"), failure::getMessage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"gate_command", "conduction_state"})
    void aDiscreteFlipFailsTheMatchingSimulation(String kind) throws Exception {
        var circuit = buck();
        int column = -1;
        for (int index = 0; index < circuit.columns().size(); index++) {
            if (circuit.columns().get(index).kind().equals(kind)) { column = index; break; }
        }
        assertTrue(column >= 0);
        var rows = new ArrayList<>(circuit.rows());
        double[] values = rows.getFirst().values().clone();
        values[column] = 1 - values[column];
        rows.set(0, new ReleaseTraceFixture.Row(rows.getFirst().time(), values));
        AssertionError failure = assertThrows(AssertionError.class,
                () -> ReleaseTraceFixture.compare(withRows(circuit, rows), -1, -1));
        assertTrue(failure.getMessage().contains(circuit.columns().get(column).signal().name()), failure::getMessage);
        assertTrue(failure.getMessage().contains("limit=0.0"), failure::getMessage);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aShiftedOrMissingStepFailsAtItsNativePosition(boolean missing) throws Exception {
        var circuit = buck();
        var rows = new ArrayList<>(circuit.rows());
        if (missing) rows.remove(5);
        else rows.set(5, new ReleaseTraceFixture.Row(rows.get(5).time() + 2e-12, rows.get(5).values()));
        AssertionError failure = assertThrows(AssertionError.class,
                () -> ReleaseTraceFixture.compare(withRows(circuit, rows), -1, -1));
        assertTrue(failure.getMessage().contains("time [s]"), failure::getMessage);
        assertTrue(failure.getMessage().contains("divergentStep=5"), failure::getMessage);
        assertTrue(failure.getMessage().contains("limit=1.0E-12"), failure::getMessage);
        assertTrue(failure.getMessage().contains("expectedValue="), failure::getMessage);
        assertTrue(failure.getMessage().contains("actualValue="), failure::getMessage);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingOrExtraEndpointReportsCountsAndNativeTime(boolean extraReference) throws Exception {
        var circuit = buck();
        var rows = new ArrayList<>(circuit.rows());
        if (extraReference) rows.add(new ReleaseTraceFixture.Row(rows.getLast().time() + 1e-6, rows.getLast().values()));
        else rows.removeLast();
        AssertionError failure = assertThrows(AssertionError.class,
                () -> ReleaseTraceFixture.compare(withRows(circuit, rows), -1, -1));
        for (String required : List.of("divergentStep=", "nativeTime=", "[steps] limit=0", "expected=", "actual=")) {
            assertTrue(failure.getMessage().contains(required), failure::getMessage);
        }
    }

    @Test
    void missingAndDuplicateResistanceBindingsFailTheCoreMapping() throws Exception {
        var circuit = buck();
        var input = ReleaseTraceApplicationTest.class.getResource(circuit.input());
        var model = new CircuitFileParser().parse(java.nio.file.Path.of(input.toURI()).toString());
        var netlist = NetlistBuilder.buildFromCircuitModel(model);
        var columns = new ArrayList<>(circuit.columns());
        var resistance = columns.stream().filter(column -> column.kind().equals("device_resistance")).findFirst().orElseThrow();
        columns.remove(resistance);
        AssertionError missing = assertThrows(AssertionError.class, () -> new ReleaseTraceFixture.Mapping(columns, model, netlist));
        assertTrue(missing.getMessage().contains("resistance"), missing::getMessage);
        columns.add(resistance);
        columns.add(resistance);
        AssertionError duplicate = assertThrows(AssertionError.class, () -> new ReleaseTraceFixture.Mapping(columns, model, netlist));
        assertTrue(duplicate.getMessage().contains("resistance"), duplicate::getMessage);
    }

    private static ReleaseTraceFixture.Case buck() throws Exception {
        return circuits().stream().filter(item -> item.id().equals("buck_simple")).findFirst().orElseThrow();
    }

    private static ReleaseTraceFixture.Case withRows(ReleaseTraceFixture.Case circuit, List<ReleaseTraceFixture.Row> rows) {
        return new ReleaseTraceFixture.Case(circuit.id(), circuit.input(), circuit.trace(), circuit.columns(), rows,
                circuit.relativeTolerance(), circuit.clockTolerance(), circuit.absoluteTolerances());
    }
}
