package gecko.core.simulation;

import gecko.core.io.CircuitFileParser;
import gecko.core.io.CircuitModel;
import gecko.core.io.CircuitTopology;
import java.io.BufferedReader;
import java.io.StringReader;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SavedControlGeometryTest {
    @ParameterizedTest
    @CsvSource({"503,14,10", "501,6,10", "504,10,14", "502,10,6"})
    void unlabelledAbuttingPortsExecuteInEachSavedDirection(int direction, int x, int y) throws Exception {
        String text = block(3, "source", 10, 10, direction, "", "NIX_NIX_NIX", "2")
                + block(7, "gain", x, y, direction, "NIX_NIX_NIX", "result", "3")
                + block(5, "scope", 30, 30, 503, "result", "", "");
        assertEquals(6, sample(text), 1e-6);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void savedAnonymousProbeWireConnectsToTheSelectedOutput(int port) throws Exception {
        int wireY = 10 - port;
        var model = parse(block(91, "PROBE.1", 10, 10, 503, "", "NIX_NIX_NIX/NIX_NIX_NIX", "")
                        .replace("parameterString[] /", "parameterString[] /L.1.i/C.1.v")
                + block(7, "GAIN.1", 20, wireY, 503, "NIX_NIX_NIX", "result", "3")
                + """
                verbindungCONTROL
                <Verbindung>
                x[] 12 18
                y[] %d %d
                label NIX_NIX_NIX
                parentSheetIdentifier 0
                enabledShorted 1
                <\\Verbindung>
                """.formatted(wireY, wireY));
        var topology = CircuitTopology.resolve(model);
        var probe = model.getControlComponents().get(0);
        var gain = model.getControlComponents().get(1);
        var input = topology.group(gain, CircuitTopology.Side.INPUT, 0);

        assertNotNull(input);
        assertEquals(input, topology.group(probe, CircuitTopology.Side.OUTPUT, port));
        assertNotEquals(input, topology.group(probe, CircuitTopology.Side.OUTPUT, 1 - port));
    }

    @Test
    void anExpandedSignalUsesItsSavedAmplitudeTerminalPosition() throws Exception {
        String text = block(3, "source", 5, 12, 503, "", "NIX_NIX_NIX", "2")
                + block(4, "wave", 8, 10, 503, "NIX_NIX_NIX/frequency/offset/phase/duty", "result",
                        "402 1 50 0 0 .5 1")
                + block(3, "frequency", 30, 10, 503, "", "frequency", "1")
                + block(3, "phase", 30, 12, 503, "", "phase", "270")
                + block(5, "scope", 40, 10, 503, "result", "", "");
        assertEquals(2, sample(text), 1e-6);
    }

    @Test
    void aDisabledAnonymousBlockCannotJoinActiveNamedSignals() throws Exception {
        String active = block(3, "first", 10, 10, 503, "", "first", "2")
                + block(3, "second", 10, 10, 503, "", "second", "4")
                + block(5, "scope", 30, 30, 503, "first", "", "");
        String disabled = block(3, "disabled", 10, 10, 503, "", "NIX_NIX_NIX", "6")
                .replace("parameter[]", "enabledShorted 0\nparameter[]");
        assertEquals(2, sample(active), 1e-6);
        assertEquals(2, sample(active + disabled), 1e-6);
    }

    @Test
    void unusedXorPaddingCannotJoinActiveNamedSignals() throws Exception {
        String text = block(3, "first", 6, 12, 503, "", "first", "2")
                + block(3, "second", 6, 12, 503, "", "second", "4")
                + block(21, "xor", 10, 10, 503, "first/second/NIX_NIX_NIX", "logic", "")
                + block(5, "scope", 30, 30, 503, "first", "", "");
        assertEquals(2, sample(text), 1e-6);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anExplicitAnonymousTerminalConnectsOnlyWhenItsGeometryIsActive(boolean geometric) throws Exception {
        var model = parse(block(3, "source", 10, 10, 503, "", "drive", "2")
                + block(7, "gain", 30, 30, 503, "NIX_NIX_NIX", "result", "3")
                + block(5, "scope", 40, 40, 503, "result", "", ""));
        model.getControlComponents().get(1).getTerminalData().add(new CircuitModel.TerminalData(
                "CONTROL", 0, CircuitTopology.Side.INPUT, 0, 12, 10, geometric));
        assertEquals(geometric ? 6 : 0, sample(model), 1e-6);
    }

    private static float sample(String text) throws Exception {
        return sample(parse(text));
    }

    private static CircuitModel parse(String text) throws Exception {
        return new CircuitFileParser().parse(new BufferedReader(new StringReader(text)), "control-geometry.ipes");
    }

    private static float sample(CircuitModel model) {
        var build = new ControlCalculatorFactory().buildCircuit(model, 1e-6);
        build.getControlNetlist().initializeAtSimulationStart(1e-6);
        build.getControlNetlist().executeTimeStep(1e-6, 0);
        return ScopeDataExtractor.fromScopes(build.getMapping().getScopeInfos()).extractCurrentValues()[0];
    }

    private static String block(int type, String name, int x, int y, int direction,
            String inputs, String outputs, String parameters) {
        return "c\n<ElementCONTROL>\ntyp " + type + "\nidStringDialog " + name
                + "\nx " + x + "\ny " + y + "\norientierung " + direction
                + "\nparameter[] " + parameters + "\nparameterString[] /\n"
                + "labelAnfangsKnoten[] " + (inputs.isEmpty() ? "" : "/" + inputs)
                + "\nlabelEndKnoten[] " + (outputs.isEmpty() ? "" : "/" + outputs)
                + "\n<" + (char) 92 + "ElementCONTROL>\n";
    }
}
