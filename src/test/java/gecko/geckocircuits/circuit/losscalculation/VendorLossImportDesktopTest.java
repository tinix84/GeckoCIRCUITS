package gecko.geckocircuits.circuit.losscalculation;

import gecko.GeckoExternal;
import gecko.GeckoSim;
import gecko.core.allg.GeckoFile;
import gecko.core.circuit.losscalculation.LossCalculationDetail;
import gecko.core.circuit.losscalculation.LossCalculationSplittable;
import gecko.geckocircuits.allg.MainWindow;
import gecko.geckocircuits.circuit.circuitcomponents.AbstractSwitch;
import gecko.geckocircuits.circuit.NetListContainer;
import gecko.geckocircuits.circuit.SimulationsKern;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

@Tag("regression")
class VendorLossImportDesktopTest {
    private static final Path BUCK = Path.of("src/test/resources/ipes/education/buck_simple.ipes");
    private static final double TIME_STEP = 1e-6;

    @TempDir Path directory;

    @Test
    void importsVendorCsvOnTheBuckSwitchAndReopensItsEmbeddedLossModel() throws Exception {
        Path csv = directory.resolve("STIPQ5M60T-HZ.CSV");
        try (var input = getClass().getResourceAsStream("/devices/STIPQ5M60T-HZ.csv")) {
            assertNotNull(input);
            Files.copy(input, csv);
        }
        boolean previousTestingMode = GeckoSim._isTestingMode;
        GeckoSim._isTestingMode = true;
        try {
            GeckoSim.main(new String[0]);
            GeckoExternal.openFile(BUCK.toAbsolutePath().toString());
            switchLossProperties().getDetailedLosses().importVendorLosses(csv);
            MainWindow._se.registerChangeWithNetlistUpdate();
            Path saved = directory.resolve("buck-with-losses.ipes");
            GeckoExternal.saveFileAs(saved.toString());
            assertTrue(Files.isRegularFile(saved));

            GeckoExternal.openFile(BUCK.toAbsolutePath().toString());
            GeckoExternal.openFile(saved.toString());
            var properties = switchLossProperties();
            var detailed = properties.getDetailedLosses();
            assertEquals(LossCalculationDetail.DETAILED, properties.getLossType());
            assertEquals(GeckoFile.StorageType.INTERNAL, detailed.lossFile.getStorageType());
            assertEquals("STIPQ5M60T-HZ.scl", detailed.lossFile.getName());
            assertEquals(300, detailed.getCopyOfSchaltverlusteMesskurvenArray().getFirst()._uBlock.getDoubleValue());

            var kernel = new SimulationsKern();
            var netlist = NetListContainer.fabricStartSimulation(MainWindow._se, kernel);
            kernel.initSimulation(TIME_STEP, 0, 0, 0.001, -1, true, netlist, false);
            try {
                kernel.initialisiereCONTROLatSimulationStart(TIME_STEP);
                var powerSwitch = Arrays.stream(netlist._nlLK.eLKneu)
                        .filter(element -> element.getStringID().equals("S.1")).findFirst().orElseThrow();
                var calculator = properties.lossCalculatorFabric();
                var loss = assertInstanceOf(LossCalculationSplittable.class, calculator);
                boolean measured = false;
                for (int step = 0; step < 500; step++) {
                    kernel.simulateOneStep();
                    double current = powerSwitch._currentInAmps;
                    if (current > 0.5 && current < 2.5) {
                        calculator.calcLosses(current, 25.0, TIME_STEP);
                        // The first conduction segment goes from 0 to 2 V at 2.5 A.
                        double expected = 0.8 * current * current;
                        assertEquals(expected, loss.getConductionLoss(), 0.02 * expected);
                        measured = true;
                        break;
                    }
                }
                assertTrue(measured, "The simulated switch must conduct in the vendor curve's first segment.");
            } finally {
                kernel.endSim();
            }
        } finally {
            GeckoSim._isTestingMode = previousTestingMode;
        }
    }

    private static LossProperties switchLossProperties() {
        var component = MainWindow._se.getBlockInterfaceComponents().stream()
                .filter(block -> block.getStringID().equals("S.1")).findFirst().orElseThrow();
        return assertInstanceOf(AbstractSwitch.class, component).getVerlustBerechnung();
    }
}
