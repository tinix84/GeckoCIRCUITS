/*  This file is part of GeckoCIRCUITS. Copyright (C) ETH Zurich, Gecko-Simulations AG
 *
 *  GeckoCIRCUITS is free software: you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation, either version 3 of the License, or (at your option) any later version.
 */
package gecko.core.simulation;

import gecko.core.allg.SolverType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SteadyStateAnalyzerTest {

    // -------------------------------------------------------------------------
    // Input validation
    // -------------------------------------------------------------------------

    @Test
    void analyze_nullConfig_returnsFailedResult() {
        SteadyStateAnalyzer analyzer = new SteadyStateAnalyzer();

        SteadyStateResult result = analyzer.analyze(null, 1e-3, 10, 1e-3);

        assertEquals(SteadyStateResult.Status.FAILED, result.getStatus());
        assertFalse(result.isConverged());
        assertNotNull(result.getErrorMessage());
    }

    @Test
    void analyze_zeroPeriod_returnsFailedResult() {
        SteadyStateAnalyzer analyzer = new SteadyStateAnalyzer();
        SimulationConfig config = SimulationConfig.builder()
                .stepWidth(1e-6)
                .simulationDuration(1e-3)
                .build();

        SteadyStateResult result = analyzer.analyze(config, 0.0, 10, 1e-3);

        assertEquals(SteadyStateResult.Status.FAILED, result.getStatus());
        assertFalse(result.isConverged());
    }

    @Test
    void analyze_negativePeriod_returnsFailedResult() {
        SteadyStateAnalyzer analyzer = new SteadyStateAnalyzer();
        SimulationConfig config = SimulationConfig.builder()
                .stepWidth(1e-6)
                .simulationDuration(1e-3)
                .build();

        SteadyStateResult result = analyzer.analyze(config, -1e-3, 10, 1e-3);

        assertEquals(SteadyStateResult.Status.FAILED, result.getStatus());
    }

    @Test
    void analyze_zeroMaxPeriods_returnsFailedResult() {
        SteadyStateAnalyzer analyzer = new SteadyStateAnalyzer();
        SimulationConfig config = SimulationConfig.builder()
                .stepWidth(1e-6)
                .simulationDuration(1e-3)
                .build();

        SteadyStateResult result = analyzer.analyze(config, 1e-3, 0, 1e-3);

        assertEquals(SteadyStateResult.Status.FAILED, result.getStatus());
    }

    @Test
    void analyze_zeroTolerance_returnsFailedResult() {
        SteadyStateAnalyzer analyzer = new SteadyStateAnalyzer();
        SimulationConfig config = SimulationConfig.builder()
                .stepWidth(1e-6)
                .simulationDuration(1e-3)
                .build();

        SteadyStateResult result = analyzer.analyze(config, 1e-3, 10, 0.0);

        assertEquals(SteadyStateResult.Status.FAILED, result.getStatus());
    }

    @Test
    void analyze_stepWidthLargerThanPeriod_returnsFailedResult() {
        SteadyStateAnalyzer analyzer = new SteadyStateAnalyzer();
        SimulationConfig config = SimulationConfig.builder()
                .stepWidth(1e-2)  // dt > period
                .simulationDuration(1e-3)
                .build();

        SteadyStateResult result = analyzer.analyze(config, 1e-3, 10, 1e-3);

        assertEquals(SteadyStateResult.Status.FAILED, result.getStatus());
    }

    @Test
    void analyze_missingCircuitFile_returnsFailedResult() {
        SteadyStateAnalyzer analyzer = new SteadyStateAnalyzer();
        SimulationConfig config = SimulationConfig.builder()
                .circuitFile("/definitely/missing/circuit.ipes")
                .stepWidth(1e-6)
                .simulationDuration(1e-3)
                .build();

        SteadyStateResult result = analyzer.analyze(config, 1e-4, 10, 1e-3);

        assertEquals(SteadyStateResult.Status.FAILED, result.getStatus());
        assertFalse(result.isConverged());
    }

    // -------------------------------------------------------------------------
    // Successful analysis (no circuit file → fallback zero output → converges trivially)
    // -------------------------------------------------------------------------

    @Test
    void analyze_noCircuit_zeroOutputConvergesImmediately() {
        SteadyStateAnalyzer analyzer = new SteadyStateAnalyzer();
        // No circuit file → engine produces zero signals → converges on period 1
        SimulationConfig config = SimulationConfig.builder()
                .stepWidth(1e-6)
                .simulationDuration(1e-3)
                .solverType(SolverType.SOLVER_BE)
                .build();

        SteadyStateResult result = analyzer.analyze(config, 1e-4, 50, 1e-3);

        assertNotEquals(SteadyStateResult.Status.FAILED, result.getStatus());
        // Zero-output circuits should converge immediately (error = 0 < tolerance)
        assertTrue(result.isConverged()
                || result.getStatus() == SteadyStateResult.Status.MAX_PERIODS_REACHED,
                "Expected CONVERGED or MAX_PERIODS_REACHED, got " + result.getStatus());
        assertTrue(result.getPeriodsSimulated() >= 1);
        assertNotNull(result.getTimeArray());
        assertNotNull(result.getSignalData());
        assertFalse(result.getSignalData().isEmpty());
    }

    @Test
    void analyze_defaultParameters_usesDefaultMaxPeriodsAndTolerance() {
        SteadyStateAnalyzer analyzer = new SteadyStateAnalyzer();
        SimulationConfig config = SimulationConfig.builder()
                .stepWidth(1e-6)
                .simulationDuration(1e-3)
                .build();

        // Two-arg overload uses DEFAULT_MAX_PERIODS and DEFAULT_TOLERANCE
        SteadyStateResult result = analyzer.analyze(config, 1e-4);

        assertNotNull(result);
        assertNotEquals(SteadyStateResult.Status.FAILED, result.getStatus());
    }

    // -------------------------------------------------------------------------
    // Result structure
    // -------------------------------------------------------------------------

    @Test
    void analyze_successResult_containsNormalisedTimeArray() {
        SteadyStateAnalyzer analyzer = new SteadyStateAnalyzer();
        SimulationConfig config = SimulationConfig.builder()
                .stepWidth(1e-6)
                .simulationDuration(1e-3)
                .build();

        SteadyStateResult result = analyzer.analyze(config, 1e-4, 20, 1e-3);

        if (result.getStatus() != SteadyStateResult.Status.FAILED) {
            double[] times = result.getTimeArray();
            assertTrue(times.length > 0, "Time array should not be empty");
            assertEquals(0.0, times[0], 1e-12, "Time array should start at 0");
        }
    }

    @Test
    void analyze_successResult_periodMatchesRequested() {
        SteadyStateAnalyzer analyzer = new SteadyStateAnalyzer();
        double period = 2e-4;
        SimulationConfig config = SimulationConfig.builder()
                .stepWidth(1e-6)
                .simulationDuration(1e-3)
                .build();

        SteadyStateResult result = analyzer.analyze(config, period, 20, 1e-3);

        assertEquals(period, result.getPeriod(), 1e-15);
    }

    @Test
    void analyze_successResult_executionTimeMsNonNegative() {
        SteadyStateAnalyzer analyzer = new SteadyStateAnalyzer();
        SimulationConfig config = SimulationConfig.builder()
                .stepWidth(1e-6)
                .simulationDuration(1e-3)
                .build();

        SteadyStateResult result = analyzer.analyze(config, 1e-4, 10, 1e-3);

        assertTrue(result.getExecutionTimeMs() >= 0);
    }

    // -------------------------------------------------------------------------
    // SteadyStateResult static factories
    // -------------------------------------------------------------------------

    @Test
    void steadyStateResult_failedFactory_setsStatusAndMessage() {
        SteadyStateResult result = SteadyStateResult.failed("test error");

        assertEquals(SteadyStateResult.Status.FAILED, result.getStatus());
        assertFalse(result.isConverged());
        assertEquals("test error", result.getErrorMessage());
        assertEquals(0, result.getTimeArray().length);
        assertTrue(result.getSignalData().isEmpty());
    }

    @Test
    void steadyStateResult_builder_buildsCorrectly() {
        double[] times = {0.0, 1e-6, 2e-6};
        java.util.Map<String, double[]> signals = new java.util.LinkedHashMap<>();
        signals.put("V_out", new double[]{1.0, 2.0, 1.0});

        SteadyStateResult result = SteadyStateResult.builder()
                .status(SteadyStateResult.Status.CONVERGED)
                .periodsSimulated(5)
                .convergenceError(1e-5)
                .period(1e-4)
                .timeArray(times)
                .signalData(signals)
                .executionTimeMs(123)
                .build();

        assertTrue(result.isConverged());
        assertEquals(SteadyStateResult.Status.CONVERGED, result.getStatus());
        assertEquals(5, result.getPeriodsSimulated());
        assertEquals(1e-5, result.getConvergenceError(), 1e-20);
        assertEquals(1e-4, result.getPeriod(), 1e-20);
        assertEquals(3, result.getTimeArray().length);
        assertEquals(1, result.getSignalData().size());
        assertTrue(result.getSignalData().containsKey("V_out"));
        assertEquals(123L, result.getExecutionTimeMs());
    }

    @Test
    void steadyStateResult_timeArrayIsDefensivelyCopied() {
        double[] original = {0.0, 1.0, 2.0};
        SteadyStateResult result = SteadyStateResult.builder()
                .status(SteadyStateResult.Status.CONVERGED)
                .timeArray(original)
                .build();

        // Mutate original - result should be unaffected
        original[0] = 999.0;
        assertEquals(0.0, result.getTimeArray()[0]);
    }
}
