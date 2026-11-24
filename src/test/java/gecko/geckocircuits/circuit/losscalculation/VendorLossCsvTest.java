package gecko.geckocircuits.circuit.losscalculation;

import gecko.core.allg.GeckoFile;
import gecko.core.circuit.losscalculation.LossCalculationDetail;
import gecko.core.circuit.losscalculation.LossCalculationSplittable;
import gecko.core.circuit.losscalculation.LossFileAccessor;
import gecko.core.simulation.HeadlessSimulationEngine;
import gecko.core.simulation.SimulationConfig;
import gecko.geckocircuits.circuit.circuitcomponents.IGBT;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VendorLossCsvTest {
    private static final String HEADER = "curve,tj_c,current_a,v_drop_v,blocking_v,e_on_j,e_off_j\n";
    private static final String DATA = HEADER
            + "switching,25,5,,400,0.001,0.002\n"
            + "conduction,25,5,0.5,,,\n"
            + "conduction,25,0,0,,,\n"
            + "switching,25,0,,400,0,0\n";

    @Test
    void existingReaderPreservesTheCurvePoints() throws Exception {
        var device = new IGBT();
        byte[] lossFile = VendorLossCsv.toDetailedLossFile(new StringReader(DATA));
        var detailed = readDetailedLosses(device, lossFile);

        var conduction = detailed.getCopyOfLeitverlusteMesskurvenArray().getFirst();
        assertEquals(25.0, conduction.tj.getDoubleValue());
        assertArrayEquals(new double[] {0, 0.5}, conduction.getCurveData()[0]);
        assertArrayEquals(new double[] {0, 5}, conduction.getCurveData()[1]);

        var switching = detailed.getCopyOfSchaltverlusteMesskurvenArray().getFirst();
        assertEquals(25.0, switching.tj.getDoubleValue());
        assertEquals(400.0, switching._uBlock.getDoubleValue());
        assertArrayEquals(new double[] {0, 5}, switching.getCurveData()[0]);
        assertArrayEquals(new double[] {0, 0.001}, switching.getCurveData()[1]);
        assertArrayEquals(new double[] {0, 0.002}, switching.getCurveData()[2]);
    }

    @Test
    void libraryHasThreeReadableDetailedLossModels() throws Exception {
        for (String part : List.of("STIPQ5M60T-HZ", "C3M0065090D", "IKW40N65H5")) {
            var detailed = readDetailedLosses(new IGBT(), libraryModel(part));
            assertFalse(detailed.getCopyOfLeitverlusteMesskurvenArray().isEmpty(), part);
            assertFalse(detailed.getCopyOfSchaltverlusteMesskurvenArray().isEmpty(), part);
        }
    }

    @Test
    void rejectsDuplicateSamplesAndInvalidBlockingVoltage() {
        assertThrows(IllegalArgumentException.class, () -> VendorLossCsv.toDetailedLossFile(
                new StringReader(DATA + "conduction,25,5,0.7,,,\n")));
        assertThrows(IllegalArgumentException.class, () -> VendorLossCsv.toDetailedLossFile(
                new StringReader(DATA.replace(",400,", ",0,"))));
    }

    @Test
    void importedSiliconCurveMatchesHandCalculationAtBuckOnState() throws Exception {
        Path buckFile = Path.of("src/test/resources/ipes/education/buck_simple.ipes");
        var result = new HeadlessSimulationEngine().runSimulation(SimulationConfig.builder()
                .circuitFile(buckFile.toString()).stepWidth(1e-6).simulationDuration(0.001).build());
        assertTrue(result.isSuccess(), result.getErrorMessage());
        var names = Arrays.asList(result.getSignalNames());
        int inductorIndex = names.indexOf("iL");
        int gateIndex = names.indexOf("gate");
        assertTrue(inductorIndex >= 0 && gateIndex >= 0, names.toString());
        float[] current = result.getSignalData(inductorIndex);
        float[] gate = result.getSignalData(gateIndex);
        int sample = -1;
        for (int i = 1; i < gate.length; i++) {
            if (gate[i] > 0.5 && gate[i - 1] > 0.5 && current[i] > 0.5 && current[i] < 2.5) {
                sample = i;
                break;
            }
        }
        assertTrue(sample >= 0, "The buck must have an on-state sample in the 0–2.5 A conduction segment.");

        var device = new IGBT();
        var losses = readDetailedLosses(device, libraryModel("STIPQ5M60T-HZ"));
        device.getVerlustBerechnung().setLossType(LossCalculationDetail.DETAILED);
        var calculator = (LossCalculationSplittable) losses.lossCalculatorFabric();
        double onCurrent = current[sample];
        // In the buck on-state, switch current equals inductor current.
        ((AbstractLossCalculatorSwitch) calculator).calcLosses(onCurrent, 25.0, 1e-6);
        double expected = 0.8 * onCurrent * onCurrent;
        assertEquals(expected, calculator.getConductionLoss(), 0.02 * expected);
    }

    private static byte[] libraryModel(String part) throws Exception {
        try (var input = VendorLossCsvTest.class.getResourceAsStream("/devices/" + part + ".csv")) {
            assertNotNull(input, part);
            return VendorLossCsv.toDetailedLossFile(new InputStreamReader(input, StandardCharsets.UTF_8));
        }
    }

    private static VerlustBerechnungDetailed readDetailedLosses(IGBT device, byte[] file) throws Exception {
        LossFileAccessor files = new LossFileAccessor() {
            @Override public GeckoFile getFile(long hash) throws FileNotFoundException {
                throw new FileNotFoundException();
            }

            @Override public List<GeckoFile> getFilesByExtension(String extension) { return List.of(); }
            @Override public void maintain(GeckoFile file) {}
            @Override public void addFile(GeckoFile file) {}
            @Override public String getOpenFileName() { return "Untitled"; }
        };
        var detailed = new VerlustBerechnungDetailed(device, device.getVerlustBerechnung(), files);
        assertTrue(detailed.leseDetailVerlusteVonDatei(new GeckoFile(new File("vendor.scl"), "Untitled", file)));
        return detailed;
    }
}
