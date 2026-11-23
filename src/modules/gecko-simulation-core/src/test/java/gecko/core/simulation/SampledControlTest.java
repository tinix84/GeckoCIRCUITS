package gecko.core.simulation;

import gecko.core.allg.SolverSettingsCore;
import gecko.core.allg.SolverType;
import gecko.core.circuit.circuitcomponents.CircuitTypCore;
import gecko.core.circuit.netlist.CircuitNetlist;
import gecko.core.io.CircuitModel;
import gecko.core.preparation.ExecutionOptions;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import static gecko.core.simulation.ControlReviewRegressionTest.component;
import static org.junit.jupiter.api.Assertions.*;

/** Optional global CONTROL sample period: zero-order hold between samples at the first step with t >= k*Ts. */
class SampledControlTest {
    /** Integrator of a constant one: its output advances by the elapsed time between executions. */
    private static CircuitModel rampModel() {
        var model = new CircuitModel();
        model.addControlComponent(component(3, "one", new double[]{1}, new String[0], "one"));
        model.addControlComponent(component(3, "zero", new double[]{0}, new String[0], "zero"));
        model.addControlComponent(component(64, "int", new double[]{1, 0, -1e9, 1e9},
                new String[]{"one", "zero"}, "ramp"));
        model.addControlComponent(component(5, "scope", new double[0], new String[]{"ramp"}));
        return model;
    }

    private static SolverSettingsCore settings(double dt, double duration, double samplePeriod) {
        return new SolverSettingsCore(SolverType.SOLVER_BE, dt, duration).withControlSamplePeriod(samplePeriod);
    }

    private static float[] run(CircuitModel model, SolverSettingsCore settings) {
        try (var session = SimulationSession.fromModel(model, settings, ExecutionOptions.defaults())) {
            session.run();
            return session.observe(0, 10000).values()[0];
        }
    }

    @Test
    void anIntegratorOutputIsAStaircaseWithOneRiseEveryHundredSteps() {
        float[] ramp = run(rampModel(), settings(1e-6, 1e-3, 100e-6));
        assertEquals(1000, ramp.length);
        // Trapezoid from a zero previous input: 0.5*Ts at the t=0 sample, then exactly Ts per sample.
        for (int step = 0; step < ramp.length; step++) {
            double expected = 100e-6 * (Math.floorDiv(step, 100) + 0.5);
            assertEquals(expected, ramp[step], 1e-9, "step " + step);
            if (step % 100 != 0) assertEquals(ramp[step - 1], ramp[step], "held at step " + step);
        }
        assertTrue(ramp[100] > ramp[99]);
    }

    @Test
    void anUnsetAndAZeroPeriodRunTheSameStepByStepPath() {
        float[] unset = run(rampModel(), new SolverSettingsCore(SolverType.SOLVER_BE, 1e-6, 1e-4));
        float[] zero = run(rampModel(), settings(1e-6, 1e-4, 0));
        assertArrayEquals(unset, zero);
        assertEquals(1e-6 * 100.5, unset[100], 1e-9);
    }

    @Test
    void aNonIntegralRatioSamplesAtTheFirstStepAtOrAfterEachMultipleWithoutDrift() {
        // dt = 3 us, Ts = 10 us: whole-microsecond arithmetic gives an exact reference schedule.
        float[] ramp = run(rampModel(), settings(3e-6, 3e-4, 10e-6));
        long lastSampleMicros = 0;
        long next = 0;
        for (int step = 0; step < ramp.length; step++) {
            long micros = 3L * step;
            if (micros >= next * 10) {
                lastSampleMicros = micros;
                next = micros / 10 + 1;
            }
            // Trapezoid: 0.5*Ts at the t=0 sample plus the elapsed time since then.
            assertEquals(5e-6 + lastSampleMicros * 1e-6, ramp[step], 1e-9, "step " + step);
        }
    }

    @Test
    void aPeriodBelowTheStepRunsEveryStep() {
        float[] ramp = run(rampModel(), settings(1e-6, 1e-5, 0.25e-6));
        for (int step = 1; step < ramp.length; step++) assertEquals(1e-6, ramp[step] - ramp[step - 1], 1e-9);
    }

    @Test
    void aSplitSessionKeepsTheSampleClock() {
        var settings = settings(1e-6, 1e-4, 2.5e-6);
        float[] whole = run(rampModel(), settings);
        for (int split : new int[]{1, 2, 3, 37}) {
            try (var session = SimulationSession.fromModel(rampModel(), settings, ExecutionOptions.defaults())) {
                for (int index = 0; index < split; index++) session.step();
                session.run();
                assertArrayEquals(whole, session.observe(0, 10000).values()[0], "split at " + split);
            }
        }
    }

    @Test
    void anExtendedSegmentOnAFinerGridKeepsSamplingOnTheAbsoluteClock() {
        try (var session = SimulationSession.fromModel(rampModel(), settings(2e-6, 1e-5, 5e-6),
                ExecutionOptions.defaults())) {
            session.run();
            session.extend(3e-5, 1e-6);
            session.run();
            var observed = session.observe(0, 10000);
            double previous = Double.NaN;
            long next = 0;
            for (int index = 0; index < observed.times().length; index++) {
                long nanos = Math.round(observed.times()[index] * 1e9);
                boolean due = nanos >= next * 5000;
                if (due) next = nanos / 5000 + 1;
                float value = observed.values()[0][index];
                if (!due) assertEquals(previous, value, "held at " + nanos + " ns");
                else if (index > 0) assertNotEquals(previous, value, "sampled at " + nanos + " ns");
                previous = value;
            }
        }
    }

    @Test
    void aSamplePeriodMustBeFiniteAndNotNegative() {
        for (double invalid : new double[]{-1e-6, Double.NaN, Double.POSITIVE_INFINITY}) {
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> SimulationSession.fromModel(rampModel(), settings(1e-6, 1e-4, invalid),
                            ExecutionOptions.defaults()));
            assertTrue(failure.getMessage().contains("sample period"), failure.getMessage());
        }
    }

    @Test
    void delayBlocksNeedAWholeNumberOfStepsPerSample() {
        var model = new CircuitModel();
        model.addControlComponent(component(58, "time", new double[0], new String[0], "clock"));
        model.addControlComponent(component(25, "delay", new double[]{1e-5}, new String[]{"clock"}, "delayed"));
        model.addControlComponent(component(5, "scope", new double[0], new String[]{"clock", "delayed"}));
        assertThrows(IllegalArgumentException.class,
                () -> SimulationSession.fromModel(model, settings(3e-6, 1e-4, 10e-6), ExecutionOptions.defaults()));
        try (var session = SimulationSession.fromModel(model, settings(1e-6, 1e-4, 10e-6),
                ExecutionOptions.defaults())) {
            session.run();
            assertEquals(1e-5f, session.observe(0, 100).values()[1][20], 1e-9f);
        }
    }

    @Test
    void configAndSettingsCopiesCarryThePeriod() {
        var config = SimulationConfig.builder().controlSamplePeriod(1e-4).build();
        assertEquals(1e-4, config.getSolverSettings().getControlSamplePeriod());
        assertEquals(1e-4, config.getSolverSettings().copy().getControlSamplePeriod());
        assertEquals(0, SimulationConfig.builder().build().getSolverSettings().getControlSamplePeriod());
        assertEquals(1e-4, SimulationConfig.builder().solverSettings(settings(1e-6, 1e-3, 1e-4)).build()
                .getSolverSettings().getControlSamplePeriod());
    }

    private static CircuitNetlist electricalGate() {
        var netlist = new CircuitNetlist();
        netlist.initNetlist(new CircuitTypCore[]{CircuitTypCore.LK_S}, new int[]{1}, new int[]{0}, new int[]{-1},
                new double[][]{new double[]{1e9, 0.01, 1e9, 0, 0, 0, 0, 0, 0}}, 2, 0, 1);
        netlist.setComponentNameToIndex(Map.of("S.1", 0));
        return netlist;
    }

    @Test
    void aProbeWiredStraightToAGateOnlyReachesTheSwitchAtSamples() {
        var model = new CircuitModel();
        var probe = component(1, "VOLT.1", new double[0], new String[0], "gate");
        probe.setParameterStrings(new String[]{"1", "0"});
        model.addControlComponent(probe);
        var gate = component(6, "GATE.1", new double[0], new String[]{"gate"});
        gate.setParameterStrings(new String[]{"S.1"});
        model.addControlComponent(gate);
        var netlist = electricalGate();
        var seen = new ArrayList<Double>();
        try (var runtime = ControlRuntime.fromDeferred(model, 1e-6, null, 3e-6)) {
            for (int step = 0; step < 7; step++) {
                netlist.storeResults(new double[]{0, step % 2 == 0 ? 1 : 0}, new double[]{0});
                runtime.advanceAfterElectricalStep(netlist, step * 1e-6);
                seen.add(netlist.getParameter(0)[8]);
            }
        }
        // Samples at steps 0, 3 and 6 see 1, 0 and 1. Other steps hold the previous gate.
        assertEquals(List.of(1.0, 1.0, 1.0, 0.0, 0.0, 0.0, 1.0), seen);
    }

    @Test
    void aProbeFedSourceKeepsItsValueBetweenSamples() {
        var model = new CircuitModel();
        var source = component(4, "U.2", new double[]{400, -0.02}, new String[]{"1"}, "0");
        source.setParameterStrings(new String[]{"v"});
        model.addCircuitComponent(source);
        var probe = component(1, "VOLT.1", new double[0], new String[0], "v");
        probe.setParameterStrings(new String[]{"1", "0"});
        model.addControlComponent(probe);
        var netlist = new CircuitNetlist();
        double[] parameters = new double[21];
        parameters[0] = 400;
        netlist.initNetlist(new CircuitTypCore[]{CircuitTypCore.LK_U}, new int[]{1}, new int[]{0}, new int[]{1},
                new double[][]{parameters}, 1, 1, 1);
        netlist.setComponentNameToIndex(Map.of("U.2", 0));
        var applied = new ArrayList<Double>();
        try (var runtime = ControlRuntime.fromDeferred(model, 1e-6, null, 3e-6)) {
            runtime.seedSources(netlist);
            for (int step = 0; step < 7; step++) {
                netlist.storeResults(new double[]{0, 5 + 2 * step}, new double[]{0});
                runtime.advanceAfterElectricalStep(netlist, step * 1e-6);
                applied.add(netlist.getParameter(0)[1]);
            }
        }
        // The source a solve sees is the sampled measurement from the step after which CONTROL last ran.
        assertEquals(List.of(0.0, 5.0, 5.0, 5.0, 11.0, 11.0, 11.0), applied);
    }

    @Test
    void aZeroPeriodOnBuckSimpleIsBitIdenticalToAnUnsetPeriod() throws Exception {
        String circuit = Path.of(Objects.requireNonNull(
                getClass().getResource("/ipes/buck_simple.ipes")).toURI()).toString();
        var unset = trace(SimulationConfig.builder().circuitFile(circuit).simulationDuration(2e-4).build());
        var zero = trace(SimulationConfig.builder().circuitFile(circuit).simulationDuration(2e-4)
                .controlSamplePeriod(0).build());
        assertEquals(unset.size(), zero.size());
        assertTrue(unset.size() > 100);
        for (int index = 0; index < unset.size(); index++) {
            assertArrayEquals(unset.get(index), zero.get(index), "trace row " + index);
        }
        var sampled = trace(SimulationConfig.builder().circuitFile(circuit).simulationDuration(2e-4)
                .controlSamplePeriod(50e-6).build());
        assertFalse(java.util.Arrays.deepEquals(unset.toArray(), sampled.toArray()),
                "a positive period must reach the engine and change the PWM");
    }

    /** Raw IEEE-754 bits of every node voltage and current, so equality is exact rather than tolerant. */
    private static List<long[]> trace(SimulationConfig config) {
        var rows = new ArrayList<long[]>();
        var engine = new HeadlessSimulationEngine();
        engine.setStepTraceListener((time, volts, currents, circuit) -> {
            long[] row = new long[1 + volts.length + currents.length];
            row[0] = Double.doubleToRawLongBits(time);
            for (int index = 0; index < volts.length; index++) row[1 + index] = Double.doubleToRawLongBits(volts[index]);
            for (int index = 0; index < currents.length; index++) {
                row[1 + volts.length + index] = Double.doubleToRawLongBits(currents[index]);
            }
            rows.add(row);
        });
        var result = engine.runSimulation(config);
        assertTrue(result.isSuccess(), result.getErrorMessage());
        return rows;
    }
}
