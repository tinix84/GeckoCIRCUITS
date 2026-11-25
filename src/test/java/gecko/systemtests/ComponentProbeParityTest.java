package gecko.systemtests;

import gecko.GeckoExternal;
import gecko.GeckoSim;
import gecko.core.io.CircuitFileParser;
import gecko.core.preparation.CircuitPreparation;
import gecko.core.preparation.ExecutionOptions;
import gecko.core.preparation.PreparationRequest;
import gecko.core.preparation.PreparationResult;
import gecko.core.simulation.SimulationSession;
import gecko.geckocircuits.allg.MainWindow;
import gecko.geckocircuits.circuit.NetListContainer;
import gecko.geckocircuits.circuit.SimulationsKern;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Tag("regression")
class ComponentProbeParityTest {
    private static final double TOLERANCE = 1e-12;
    private static final Path BUCK = Path.of("src/test/resources/ipes/education/buck_simple.ipes");
    private static final int SAMPLE_COUNT = 1800;

    @Test
    void probeMatchesBuckMetersAndReportsBothDiodeStates() throws Exception {
        Path fixture = writeProbeBuck(true);
        var model = new CircuitFileParser().parse(fixture.toString());
        verifyCore(model);
        verifyLegacy(writeProbeBuck(false), model.getTimeStep());
    }

    private static void verifyCore(gecko.core.io.CircuitModel model) {
        model.setSimulationDuration(model.getTimeStep() * SAMPLE_COUNT);
        var preparation = new CircuitPreparation();
        var result = preparation.prepare(preparation.capture(model), PreparationRequest.defaults());
        var ready = assertInstanceOf(PreparationResult.Ready.class, result, () -> result.report().toString());
        int states = 0;
        try (var session = SimulationSession.open(ready.execution(), ExecutionOptions.defaults())) {
            for (int index = 0; index < SAMPLE_COUNT; index++) {
                float[] values = session.step().values();
                assertEquals(values[0], values[1], TOLERANCE, "core branch current");
                assertEquals(values[2], values[3], TOLERANCE, "core terminal voltage");
                states |= stateMask(values[4]);
            }
        }
        assertEquals(3, states, "core diode must block and conduct");
    }

    private static void verifyLegacy(Path fixture, double step) throws Exception {
        boolean testingMode = GeckoSim._isTestingMode;
        GeckoSim._isTestingMode = true;
        SimulationsKern kernel = new SimulationsKern();
        boolean initialized = false;
        try {
            GeckoSim.main(new String[0]);
            GeckoExternal.openFile(fixture.toAbsolutePath().toString());
            var netlist = NetListContainer.fabricStartSimulation(MainWindow._se, kernel);
            kernel.initSimulation(step, 0, 0, step * (SAMPLE_COUNT + 1), -1, true, netlist, false);
            initialized = true;
            kernel.initialisiereCONTROLatSimulationStart(step);
            var blocks = netlist._nlControl.getElementCONTROL();
            var calculators = netlist._nlControl._allUnSortedControlCalculators;
            int probe = findBlock(blocks, "PROBE.1");
            int amp = findBlock(blocks, "AMP.1");
            int volt = findBlock(blocks, "VOLT.3");
            var diode = Arrays.stream(netlist._nlLK.eLKneu)
                    .filter(element -> element.getStringID().equals("D.1")).findFirst().orElseThrow();
            int states = 0;
            for (int index = 0; index < SAMPLE_COUNT; index++) {
                kernel.simulateOneStep();
                var outputs = calculators[probe]._outputSignal;
                assertEquals(calculators[amp]._outputSignal[0][0], outputs[0][0], TOLERANCE, "legacy branch current");
                assertEquals(calculators[volt]._outputSignal[0][0], outputs[1][0], TOLERANCE, "legacy terminal voltage");
                states |= stateMask(outputs[2][0]);
                if (diode._currentInAmps > 0.1) {
                    assertEquals(1.0, outputs[2][0], "legacy forward conduction");
                } else if (diode._currentInAmps < -1e-6) {
                    assertEquals(0.0, outputs[2][0], "legacy reverse blocking");
                }
            }
            assertEquals(3, states, "legacy diode must block and conduct");
        } finally {
            if (initialized) {
                kernel.endSim();
            }
            GeckoSim._isTestingMode = testingMode;
        }
    }

    private static int stateMask(double state) {
        assertTrue(state == 0.0 || state == 1.0, "Expected a binary diode state");
        return state == 0.0 ? 1 : 2;
    }

    private static int findBlock(gecko.geckocircuits.control.RegelBlock[] blocks, String name) {
        for (int i = 0; i < blocks.length; i++) {
            if (name.equals(blocks[i].getStringID())) return i;
        }
        throw new AssertionError("Missing block " + name);
    }

    private static Path writeProbeBuck(boolean scopeProbe) throws Exception {
        String source;
        try (var gzip = new GZIPInputStream(Files.newInputStream(BUCK))) {
            source = new String(gzip.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
        String channels = "/u1/uS/u2/iL/iC/gate";
        assertTrue(source.contains("controlANZAHL 8") && source.contains("labelAnfangsKnoten[] " + channels));
        String fixture = source.replace("controlANZAHL 8", "controlANZAHL 9")
                .replace("thermANZAHL 0", """
                c (8)
                <ElementCONTROL>
                labelAnfangsKnoten[]\s
                labelEndKnoten[] /probeI/probeV/probeState
                enabledShorted 1
                typ 91
                uniqueObjectIdentifier 989123012
                x 35
                y 26
                parameter[]\s
                parameterString[] /L.1.i/C.1.v/D.1.state
                nameOpt[] null
                shiftLabelsIn[]\s
                shiftLabelsOut[] false false false
                orientierung 503
                idStringDialog PROBE.1
                <\\ElementCONTROL>
                """ + "thermANZAHL 0");
        if (scopeProbe) {
            fixture = fixture.replace(channels, "/iL/probeI/u2/probeV/probeState/gate");
        }
        Path file = Files.createTempFile(Path.of("target"), "buck-probe-", ".ipes");
        try (var gzip = new GZIPOutputStream(Files.newOutputStream(file))) {
            gzip.write(fixture.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }
}
