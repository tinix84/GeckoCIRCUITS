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
package gecko.core.simulation;

import gecko.core.allg.SolverSettingsCore;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Detects the steady state of a periodically driven power-electronics circuit.
 *
 * <h2>Algorithm</h2>
 * <p>The analyser runs a single transient simulation for up to
 * {@code maxPeriods × period} seconds.  After every switching period it
 * evaluates a convergence criterion:
 * <pre>
 *   error = max over all signals of
 *               |value(t=k·T, end) − value(t=(k-1)·T, end)|
 *               ─────────────────────────────────────────────
 *               max(|start value|, |end value|, EPSILON)
 * </pre>
 * When {@code error < tolerance} the circuit is considered to have reached
 * steady state and the waveforms from the last complete period are returned.</p>
 *
 * <p>This is a <em>time-domain simulation</em> approach (sometimes called the
 * "time-marching" steady-state method), consistent with the approach described
 * in the SIMBA technical resources referenced in the feature request.</p>
 *
 * <h2>Usage example</h2>
 * <pre>{@code
 * SteadyStateAnalyzer analyzer = new SteadyStateAnalyzer();
 * SimulationConfig config = SimulationConfig.builder()
 *     .stepWidth(1e-7)
 *     .simulationDuration(1e-4)    // will be overridden internally
 *     .build();
 *
 * SteadyStateResult result = analyzer.analyze(config,
 *         1e-4,   // switching period [s]
 *         200,    // max periods to simulate
 *         1e-3);  // convergence tolerance (0.1%)
 *
 * if (result.isConverged()) {
 *     double[] time    = result.getTimeArray();
 *     double[] vOut    = result.getSignalData().get("V_out");
 * }
 * }</pre>
 */
public class SteadyStateAnalyzer {

    /** Minimum denominator used in relative-error calculation to avoid division by zero. */
    private static final double EPSILON = 1e-10;

    /** Default maximum number of periods before giving up. */
    public static final int DEFAULT_MAX_PERIODS = 200;

    /** Default convergence tolerance (0.1 %). */
    public static final double DEFAULT_TOLERANCE = 1e-3;

    private final HeadlessSimulationEngine engine;

    /** Creates a new analyser backed by a fresh {@link HeadlessSimulationEngine}. */
    public SteadyStateAnalyzer() {
        this.engine = new HeadlessSimulationEngine();
    }

    /**
     * Runs a steady-state analysis with default parameters
     * ({@value #DEFAULT_MAX_PERIODS} periods, tolerance {@value #DEFAULT_TOLERANCE}).
     *
     * @param baseConfig simulation configuration (stepWidth and circuit are required;
     *                   simulationDuration is ignored – it is computed from period × maxPeriods)
     * @param period     switching / excitation period in seconds (must be &gt; 0)
     * @return steady-state result
     */
    public SteadyStateResult analyze(SimulationConfig baseConfig, double period) {
        return analyze(baseConfig, period, DEFAULT_MAX_PERIODS, DEFAULT_TOLERANCE);
    }

    /**
     * Runs a steady-state analysis.
     *
     * @param baseConfig  simulation configuration (stepWidth and circuit required)
     * @param period      switching period in seconds (must be &gt; 0)
     * @param maxPeriods  maximum number of periods to simulate (must be &ge; 1)
     * @param tolerance   convergence tolerance; convergence is declared when the
     *                    maximum relative change across all signals is below this value
     * @return steady-state result
     */
    public SteadyStateResult analyze(
            SimulationConfig baseConfig,
            double period,
            int maxPeriods,
            double tolerance) {

        // --- Input validation --------------------------------------------------
        if (baseConfig == null) {
            return SteadyStateResult.failed("Simulation configuration is required");
        }
        if (!Double.isFinite(period) || period <= 0) {
            return SteadyStateResult.failed("Period must be a finite value > 0");
        }
        if (maxPeriods < 1) {
            return SteadyStateResult.failed("maxPeriods must be >= 1");
        }
        if (!Double.isFinite(tolerance) || tolerance <= 0) {
            return SteadyStateResult.failed("Tolerance must be a finite value > 0");
        }

        long wallStart = System.currentTimeMillis();

        // --- Build a config that covers all requested periods -------------------
        SolverSettingsCore settings = baseConfig.getSolverSettings();
        double dt = settings.getStepWidth();

        if (!Double.isFinite(dt) || dt <= 0) {
            return SteadyStateResult.failed("Step width must be a finite value > 0");
        }
        if (dt >= period) {
            return SteadyStateResult.failed("Step width must be smaller than the period");
        }

        double totalDuration = period * maxPeriods;
        SimulationConfig runConfig = SimulationConfig.builder()
                .solverSettings(settings)
                .simulationDuration(totalDuration)
                .circuitFile(baseConfig.getCircuitFilePath())
                .withParameters(baseConfig.getParameterOverrides())
                .enableDataLogging(true)
                .dataLoggingInterval(1)
                .build();

        // --- Run the underlying transient simulation ----------------------------
        SimulationResult transientResult = engine.runSimulation(runConfig);
        if (!transientResult.isSuccess()) {
            return SteadyStateResult.failed(
                    "Transient simulation failed: " + transientResult.getErrorMessage());
        }

        // --- Gather time / signal arrays ----------------------------------------
        double[] allTimes = transientResult.getTimeArray();
        String[] signalNames = transientResult.getSignalNames();
        int nSignals = signalNames.length;

        if (allTimes.length == 0 || nSignals == 0) {
            return SteadyStateResult.failed("Transient simulation produced no output data");
        }

        // Approximate number of samples per period
        int stepsPerPeriod = Math.max(1, (int) Math.round(period / dt));

        // Pre-fetch all signal arrays once to avoid repeated extraction
        float[][] allData = new float[nSignals][];
        for (int s = 0; s < nSignals; s++) {
            allData[s] = transientResult.getSignalData(s);
        }

        // --- Scan period boundaries for convergence ----------------------------
        int convergedPeriod = -1;
        double convergenceError = Double.MAX_VALUE;

        for (int p = 1; p <= maxPeriods; p++) {
            // Index of the sample at the boundary between period p-1 and period p
            int prevBoundaryIdx = (p - 1) * stepsPerPeriod;
            int currBoundaryIdx = p * stepsPerPeriod;

            if (currBoundaryIdx >= allTimes.length) {
                // Not enough data for this period
                break;
            }

            double maxRelError = 0.0;
            for (int s = 0; s < nSignals; s++) {
                float[] data = allData[s];
                if (data == null
                        || prevBoundaryIdx >= data.length
                        || currBoundaryIdx >= data.length) {
                    continue;
                }
                double startVal = data[prevBoundaryIdx];
                double endVal   = data[currBoundaryIdx];
                double scale    = Math.max(Math.abs(startVal),
                                  Math.max(Math.abs(endVal), EPSILON));
                double relError = Math.abs(endVal - startVal) / scale;
                if (relError > maxRelError) {
                    maxRelError = relError;
                }
            }

            convergenceError = maxRelError;

            if (maxRelError < tolerance) {
                convergedPeriod = p;
                break;
            }
        }

        // --- Extract the last period's waveforms --------------------------------
        int lastPeriod = (convergedPeriod > 0) ? convergedPeriod : maxPeriods;
        int startIdx   = Math.max(0, (lastPeriod - 1) * stepsPerPeriod);
        int endIdx     = Math.min(allTimes.length - 1, lastPeriod * stepsPerPeriod);

        int periodSamples = endIdx - startIdx + 1;
        double[] periodTime = new double[periodSamples];
        double t0 = allTimes[startIdx];
        for (int i = 0; i < periodSamples; i++) {
            periodTime[i] = allTimes[startIdx + i] - t0;
        }

        Map<String, double[]> periodSignals = new LinkedHashMap<>();
        for (int s = 0; s < nSignals; s++) {
            float[] data = allData[s];
            double[] slice = new double[periodSamples];
            for (int i = 0; i < periodSamples; i++) {
                if (data != null && (startIdx + i) < data.length) {
                    slice[i] = data[startIdx + i];
                }
            }
            periodSignals.put(signalNames[s], slice);
        }

        // --- Build and return result --------------------------------------------
        long wallMs = System.currentTimeMillis() - wallStart;

        SteadyStateResult.Status status = (convergedPeriod > 0)
                ? SteadyStateResult.Status.CONVERGED
                : SteadyStateResult.Status.MAX_PERIODS_REACHED;

        return SteadyStateResult.builder()
                .status(status)
                .periodsSimulated(lastPeriod)
                .convergenceError(convergenceError)
                .period(period)
                .timeArray(periodTime)
                .signalData(periodSignals)
                .executionTimeMs(wallMs)
                .build();
    }
}
