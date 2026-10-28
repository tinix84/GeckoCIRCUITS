/*  This file is part of GeckoCIRCUITS. Copyright (C) ETH Zurich, Gecko-Simulations AG
 *
 *  GeckoCIRCUITS is free software: you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation, either version 3 of the License, or (at your option) any later version.
 *
 *  GeckoCIRCUITS is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR
 *  PURPOSE.  See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  GeckoCIRCUITS.  If not, see <http://www.gnu.org/licenses/>.
 */
package gecko.core.simulation.application;

import gecko.core.circuit.circuitcomponents.CircuitTypCore;
import gecko.core.circuit.netlist.CircuitNetlist;
import gecko.core.simulation.HeadlessSimulationEngine;
import gecko.core.simulation.SimulationConfig;
import gecko.core.simulation.SimulationResult;
import gecko.core.testutil.TraceComparisonHelper;
import gecko.core.testutil.TraceComparisonHelper.ComparisonResult;
import gecko.core.testutil.TraceComparisonHelper.StepData;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Application-level golden trace comparison for the boost_simple circuit.
 */
@Tag("application")
public class BoostSimpleApplicationTest {

    private static final String GOLDEN_TRACE = "/traces/boost_simple_trace.csv.gz";
    private static final String CIRCUIT_FILE_RESOURCE = "/ipes/boost_simple.ipes";
    private static final double RELATIVE_TOLERANCE = 0.01; // 1%

    @Test
    void goldenTraceComparison() throws Exception {
        List<StepData> goldenTrace = TraceComparisonHelper.loadTrace(GOLDEN_TRACE);
        assertFalse(goldenTrace.isEmpty(), "Golden trace is empty");
        System.out.println("Loaded golden trace: " + goldenTrace.size() + " steps");

        String circuitPath = extractCircuitFile();
        List<StepData> actualTrace = runSimulationWithTraceCapture(circuitPath);

        System.out.println("Headless trace: " + actualTrace.size() + " steps");

        ComparisonResult result = TraceComparisonHelper.compare(goldenTrace, actualTrace, RELATIVE_TOLERANCE);

        if (!result.match()) {
            StepData goldenStep = goldenTrace.get(result.divergentStep());
            StepData actualStep = actualTrace.get(result.divergentStep());

            StringBuilder sb = new StringBuilder();
            sb.append("GOLDEN TRACE MISMATCH at step ").append(result.divergentStep())
                    .append(" (t=").append(String.format("%.6e", goldenStep.time())).append("s)\n");
            sb.append("  Variable: ").append(result.divergentVariable()).append("\n");
            sb.append("  Expected: ").append(String.format("%.12e", result.expectedValue())).append("\n");
            sb.append("  Actual:   ").append(String.format("%.12e", result.actualValue())).append("\n");
            sb.append("  Rel diff: ").append(String.format("%.4f%%", result.relativeDifference() * 100)).append("\n");

            sb.append("\n  Golden p[]: ").append(Arrays.toString(goldenStep.nodeVoltages()));
            sb.append("\n  Actual p[]: ").append(Arrays.toString(actualStep.nodeVoltages()));
            sb.append("\n  Golden i[]: ").append(Arrays.toString(goldenStep.currents()));
            sb.append("\n  Actual i[]: ").append(Arrays.toString(actualStep.currents()));

            fail(sb.toString());
        }

        System.out.println("PASS: boost_simple golden trace matches (" + goldenTrace.size() + " steps compared)");
    }

    private List<StepData> runSimulationWithTraceCapture(String circuitPath) {
        HeadlessSimulationEngine engine = new HeadlessSimulationEngine();
        List<StepData> trace = new ArrayList<>();

        engine.setStepTraceListener((time, nodeVoltages, currents, netlist) -> {
            double[] pCopy = Arrays.copyOf(nodeVoltages, nodeVoltages.length);
            double[] iCopy = Arrays.copyOf(currents, currents.length);

            Map<Integer, String> indexToName = new HashMap<>();
            for (Map.Entry<String, Integer> e : netlist.getComponentNameToIndex().entrySet()) {
                indexToName.put(e.getValue(), e.getKey());
            }

            Map<String, Double> switchR = new LinkedHashMap<>();
            Map<String, Double> diodeR = new LinkedHashMap<>();
            Map<String, Double> gateSignals = new LinkedHashMap<>();

            for (int i = 0; i < netlist.getElementCount(); i++) {
                CircuitTypCore type = netlist.getType(i);
                double[] params = netlist.getParameter(i);
                String name = indexToName.getOrDefault(i, "elem" + i);

                if (type == CircuitTypCore.LK_S || type == CircuitTypCore.LK_MOSFET) {
                    switchR.put(name, params[0]);
                    gateSignals.put(name, params.length > 8 ? params[8] : 0.0);
                } else if (type == CircuitTypCore.LK_D || type == CircuitTypCore.LK_THYR
                        || type == CircuitTypCore.LK_IGBT) {
                    diodeR.put(name, params[0]);
                }
            }

            trace.add(new StepData(time, pCopy, iCopy, switchR, diodeR, gateSignals));
        });

        SimulationConfig config = SimulationConfig.builder()
                .circuitFile(circuitPath)
                .build();

        SimulationResult result = engine.runSimulation(config);
        assertTrue(result.isSuccess(), "Simulation failed: " + result.getErrorMessage());

        return trace;
    }

    private String extractCircuitFile() throws IOException {
        java.io.InputStream is = getClass().getResourceAsStream(CIRCUIT_FILE_RESOURCE);
        if (is == null) {
            fail("Circuit file resource not found: " + CIRCUIT_FILE_RESOURCE
                    + " (copy boost_simple.ipes to src/test/resources/ipes/)");
            return null;
        }
        java.nio.file.Path temp = java.nio.file.Files.createTempFile("boost_simple_", ".ipes");
        temp.toFile().deleteOnExit();
        java.nio.file.Files.copy(is, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        is.close();
        return temp.toAbsolutePath().toString();
    }
}
