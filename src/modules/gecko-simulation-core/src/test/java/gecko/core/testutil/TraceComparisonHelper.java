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
package gecko.core.testutil;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * Helper for loading and comparing per-step simulation trace data.
 * Used for golden-reference comparison between the proven SimulationsKern
 * and the headless HeadlessSimulationEngine.
 */
public final class TraceComparisonHelper {

    private TraceComparisonHelper() {
    }

    /**
     * One time step of simulation trace data.
     */
    public record StepData(
            double time,
            double[] nodeVoltages,
            double[] currents,
            Map<String, Double> switchR,
            Map<String, Double> diodeR,
            Map<String, Double> gateSignals
    ) {
    }

    /**
     * Result of comparing two traces step-by-step.
     */
    public record ComparisonResult(
            boolean match,
            int divergentStep,
            String divergentVariable,
            double expectedValue,
            double actualValue,
            double relativeDifference
    ) {
        public static ComparisonResult ok() {
            return new ComparisonResult(true, -1, null, 0, 0, 0);
        }

        public static ComparisonResult mismatch(int step, String variable,
                                                  double expected, double actual) {
            double relDiff = Math.abs(expected) > 1e-15
                    ? Math.abs(actual - expected) / Math.abs(expected)
                    : Math.abs(actual - expected);
            return new ComparisonResult(false, step, variable, expected, actual, relDiff);
        }
    }

    /**
     * Loads a trace CSV (optionally gzip-compressed) from a classpath resource.
     *
     * <p>Expected CSV format — first row is header:
     * <pre>
     * time,p[0],p[1],...,i[0],i[1],...,R_sw_S.1,R_d_D.1,gate_S.1,...
     * 0.000000,0.0,10.0,...,0.001,...
     * </pre>
     *
     * @param resourcePath classpath resource path (e.g., "/traces/buck_simple_trace.csv.gz")
     * @return list of step data records in time order
     * @throws IOException if the resource cannot be read
     */
    public static List<StepData> loadTrace(String resourcePath) throws IOException {
        InputStream raw = TraceComparisonHelper.class.getResourceAsStream(resourcePath);
        if (raw == null) {
            throw new FileNotFoundException("Trace resource not found: " + resourcePath);
        }

        InputStream in = resourcePath.endsWith(".gz") ? new GZIPInputStream(raw) : raw;
        List<StepData> steps = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return steps;
            }
            String[] headers = headerLine.split(",");

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] values = line.split(",");
                steps.add(parseRow(headers, values));
            }
        }
        return steps;
    }

    /**
     * Compares two traces step-by-step, returning the first divergence.
     *
     * @param golden             expected (reference) trace
     * @param actual             simulated trace
     * @param relativeTolerance  maximum allowed relative difference (e.g., 0.01 for 1%)
     * @return comparison result (match=true or first divergence details)
     */
    public static ComparisonResult compare(List<StepData> golden, List<StepData> actual,
                                            double relativeTolerance) {
        int minLen = Math.min(golden.size(), actual.size());
        if (minLen == 0) {
            return ComparisonResult.ok();
        }

        for (int step = 0; step < minLen; step++) {
            StepData g = golden.get(step);
            StepData a = actual.get(step);

            // Compare node voltages
            int minV = Math.min(g.nodeVoltages.length, a.nodeVoltages.length);
            for (int i = 0; i < minV; i++) {
                if (!withinTolerance(g.nodeVoltages[i], a.nodeVoltages[i], relativeTolerance)) {
                    return ComparisonResult.mismatch(step, "p[" + i + "]",
                            g.nodeVoltages[i], a.nodeVoltages[i]);
                }
            }

            // Compare currents
            int minI = Math.min(g.currents.length, a.currents.length);
            for (int i = 0; i < minI; i++) {
                if (!withinTolerance(g.currents[i], a.currents[i], relativeTolerance)) {
                    return ComparisonResult.mismatch(step, "i[" + i + "]",
                            g.currents[i], a.currents[i]);
                }
            }

            // Compare switch resistances
            for (Map.Entry<String, Double> entry : g.switchR.entrySet()) {
                Double actualVal = a.switchR.get(entry.getKey());
                if (actualVal != null && !withinTolerance(entry.getValue(), actualVal, relativeTolerance)) {
                    return ComparisonResult.mismatch(step, "R_sw_" + entry.getKey(),
                            entry.getValue(), actualVal);
                }
            }

            // Compare diode resistances
            for (Map.Entry<String, Double> entry : g.diodeR.entrySet()) {
                Double actualVal = a.diodeR.get(entry.getKey());
                if (actualVal != null && !withinTolerance(entry.getValue(), actualVal, relativeTolerance)) {
                    return ComparisonResult.mismatch(step, "R_d_" + entry.getKey(),
                            entry.getValue(), actualVal);
                }
            }
        }

        return ComparisonResult.ok();
    }

    private static boolean withinTolerance(double expected, double actual, double relTol) {
        double absDiff = Math.abs(expected - actual);
        double absExpected = Math.abs(expected);
        if (absExpected < 1e-15) {
            // Near zero: use absolute tolerance
            return absDiff < relTol;
        }
        return (absDiff / absExpected) <= relTol;
    }

    private static StepData parseRow(String[] headers, String[] values) {
        double time = 0;
        List<Double> nodeVList = new ArrayList<>();
        List<Double> currentList = new ArrayList<>();
        Map<String, Double> switchR = new LinkedHashMap<>();
        Map<String, Double> diodeR = new LinkedHashMap<>();
        Map<String, Double> gateSignals = new LinkedHashMap<>();

        for (int col = 0; col < Math.min(headers.length, values.length); col++) {
            String h = headers[col].trim();
            double v = parseDouble(values[col].trim());

            if ("time".equals(h)) {
                time = v;
            } else if (h.startsWith("p[")) {
                nodeVList.add(v);
            } else if (h.startsWith("i[")) {
                currentList.add(v);
            } else if (h.startsWith("R_sw_")) {
                switchR.put(h.substring(5), v);
            } else if (h.startsWith("R_d_")) {
                diodeR.put(h.substring(4), v);
            } else if (h.startsWith("gate_")) {
                gateSignals.put(h.substring(5), v);
            }
        }

        return new StepData(time,
                nodeVList.stream().mapToDouble(Double::doubleValue).toArray(),
                currentList.stream().mapToDouble(Double::doubleValue).toArray(),
                switchR, diodeR, gateSignals);
    }

    private static double parseDouble(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
