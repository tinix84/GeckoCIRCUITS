package gecko.core.simulation;

import gecko.core.io.CircuitFileParser;
import java.io.BufferedReader;
import java.io.StringReader;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

    private static float sample(String text) throws Exception {
        var model = new CircuitFileParser().parse(new BufferedReader(new StringReader(text)), "control-geometry.ipes");
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
