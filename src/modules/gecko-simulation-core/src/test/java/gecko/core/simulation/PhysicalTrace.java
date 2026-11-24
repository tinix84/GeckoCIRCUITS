package gecko.core.simulation;

import gecko.core.circuit.netlist.INetList;
import gecko.core.testutil.TraceEquivalence;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The complete electrical trace of one run as primitive vectors per step, split into physical unit blocks. */
final class PhysicalTrace {
    final List<Double> times = new ArrayList<>();
    final List<double[]> potentials = new ArrayList<>();
    final List<double[]> sourceCurrents = new ArrayList<>();
    final List<double[]> componentCurrents = new ArrayList<>();
    final List<double[]> switchStates = new ArrayList<>();

    /** Records one step. {@code volts} holds the node potentials and then any source branch currents. */
    void add(double time, double[] volts, double[] currents, INetList netlist) {
        int nodes = netlist.getNodeMax() + 1;
        times.add(time);
        potentials.add(Arrays.copyOfRange(volts, 0, nodes));
        sourceCurrents.add(Arrays.copyOfRange(volts, nodes, volts.length));
        componentCurrents.add(currents.clone());
        switchStates.add(switchStates(netlist));
    }

    /** Conduction resistance of every switching element and limit state of every limited source, exactly as stored. */
    static double[] switchStates(INetList netlist) {
        double[] states = new double[netlist.getElementCount()];
        int count = 0;
        for (int element = 0; element < states.length; element++) {
            double[] parameters = netlist.getParameter(element);
            switch (netlist.getType(element)) {
                case LK_D, LK_THYR, LK_IGBT, LK_S, LK_MOSFET -> states[count++] = parameters[0];
                case LK_U -> { if (parameters.length > 14) states[count++] = parameters[14]; }
                default -> { }
            }
        }
        return Arrays.copyOf(states, count);
    }

    /** @return how many steps changed any switch or limit state */
    int transitions() {
        int transitions = 0;
        for (int i = 1; i < switchStates.size(); i++) {
            if (!Arrays.equals(switchStates.get(i - 1), switchStates.get(i))) transitions++;
        }
        return transitions;
    }

    /**
     * Requires the same grid and step count, the same switch and limit states at every step, and the potentials and
     * the currents each within the tolerance as complete-trace max norms.
     */
    static void assertEquivalent(PhysicalTrace expected, PhysicalTrace actual, String label, double tolerance) {
        assertEquivalent(expected, actual, label, tolerance, 0);
    }

    /**
     * As {@link #assertEquivalent(PhysicalTrace, PhysicalTrace, String, double)}, but a current block also passes when
     * its largest absolute difference is at most {@code currentAbsoluteAmperes}. Only a test whose first values are a
     * DC seed with rounding noise from the 1e15 reference pin may use it, and it must say so; it is not a relative bound.
     */
    static void assertEquivalent(PhysicalTrace expected, PhysicalTrace actual, String label, double tolerance,
            double currentAbsoluteAmperes) {
        assertEquals(expected.times.size(), actual.times.size(), label + " step count");
        assertFalse(expected.times.isEmpty(), label);
        assertEquals(expected.times, actual.times, label + " electrical time grid");
        for (int i = 0; i < expected.times.size(); i++) {
            assertArrayEquals(expected.switchStates.get(i), actual.switchStates.get(i),
                    label + " switch and limit states must agree exactly, step " + i);
        }
        TraceEquivalence.assertWithin(label + " node potentials [V]", TraceEquivalence.norm(expected.potentials,
                actual.potentials, 0, Integer.MAX_VALUE), tolerance);
        assertCurrents(label + " source branch currents [A]", TraceEquivalence.norm(
                expected.sourceCurrents, actual.sourceCurrents, 0, Integer.MAX_VALUE), tolerance, currentAbsoluteAmperes);
        assertCurrents(label + " component currents [A]", TraceEquivalence.norm(
                expected.componentCurrents, actual.componentCurrents, 0, Integer.MAX_VALUE), tolerance, currentAbsoluteAmperes);
    }

    private static void assertCurrents(String label, TraceEquivalence.Norm norm, double tolerance, double absolute) {
        if (norm.difference() > absolute) TraceEquivalence.assertWithin(label, norm, tolerance);
    }
}
