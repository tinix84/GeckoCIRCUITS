package gecko.core.testutil;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Negative controls for physical-unit limits, switching state, and native step alignment. */
class PhysicalTraceComparisonTest {
    private static final Map<String, Double> FLOORS = Map.of("V", 6e-11, "A", 6e-11, "ohm", 4e-9);

    private static TraceComparisonHelper.ComparisonResult analog(String unit, double expected, double actual) {
        return TraceComparisonHelper.comparePhysicalStep(7, 1e-6, 1e-6,
                List.of(new TraceComparisonHelper.PhysicalSignal("physical", unit, false)),
                new double[]{expected}, new double[]{actual}, .01, FLOORS, 1e-12);
    }

    @Test void zeroUsesItsPhysicalUnitFloor() {
        assertTrue(analog("V", 0, 6e-11).match());
        assertFalse(analog("V", 0, 6.1e-11).match());
        assertTrue(analog("ohm", 0, 4e-9).match());
        assertFalse(analog("A", 0, 4e-9).match());
    }

    @Test void nonzeroValuesKeepOnePercentRelativeLimit() {
        assertTrue(analog("A", 100, 101).match());
        var mutation = analog("A", 100, 105);
        assertFalse(mutation.match());
        assertEquals(7, mutation.divergentStep());
        assertTrue(mutation.divergentVariable().contains("[A]"));
    }

    @Test void aBinaryFlipAndAnAlmostBinaryValueBothFail() {
        var signal = List.of(new TraceComparisonHelper.PhysicalSignal("command:S.1", "1", true));
        for (double observed : new double[]{0, .999999999999}) {
            assertFalse(TraceComparisonHelper.comparePhysicalStep(0, 0, 0, signal,
                    new double[]{1}, new double[]{observed}, .01, FLOORS, 1e-12).match());
        }
    }

    @Test void aShiftedStepFailsBeforeValuesAreCompared() {
        var result = TraceComparisonHelper.comparePhysicalStep(2, 2e-6, 3e-6,
                List.of(new TraceComparisonHelper.PhysicalSignal("V:node", "V", false)),
                new double[]{48}, new double[]{48}, .01, FLOORS, 1e-12);
        assertFalse(result.match());
        assertEquals("time [s]", result.divergentVariable());
    }

    @Test void aMissingSignalCannotPass() {
        assertFalse(TraceComparisonHelper.comparePhysicalStep(0, 0, 0,
                List.of(new TraceComparisonHelper.PhysicalSignal("I:source", "A", false)),
                new double[]{1}, new double[0], .01, FLOORS, 1e-12).match());
    }

    @Test void nonfiniteSamplesCannotPass() {
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertFalse(analog("V", 0, invalid).match());
            assertFalse(analog("V", invalid, 0).match());
        }
    }
}
