package gecko.core.simulation.application;

import gecko.core.testutil.TraceComparisonHelper.PhysicalSignal;
import org.junit.jupiter.api.Test;
import java.io.StringReader;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Ensures malformed reference data fails before a simulation executes. */
class ReleaseTraceFixtureValidationTest {
    private static final List<ReleaseTraceFixture.Column> SIGNALS = List.of(
            new ReleaseTraceFixture.Column(new PhysicalSignal("I:R.1@1", "A", false),
                    "component_current", "R.1@1", List.of(), List.of()));

    @Test void missingOrUnknownChannelsAreRejected() {
        for (String header : List.of("time", "time,I:unknown", "time,I:R.1@1,I:extra")) {
            assertThrows(AssertionError.class, () -> ReleaseTraceFixture.readRows(
                    new StringReader(header + "\n0,1\n"), SIGNALS));
        }
    }

    @Test void malformedTruncatedAndNonfiniteRecordsAreRejected() {
        assertThrows(NumberFormatException.class, () -> ReleaseTraceFixture.readRows(
                new StringReader("time,I:R.1@1\n0,broken\n"), SIGNALS));
        for (String row : List.of("0", "0,NaN", "0,Infinity")) {
            assertThrows(AssertionError.class, () -> ReleaseTraceFixture.readRows(
                    new StringReader("time,I:R.1@1\n" + row + "\n"), SIGNALS));
        }
    }

    @Test void duplicateTimesAndEmptyTracesAreRejected() {
        assertThrows(AssertionError.class, () -> ReleaseTraceFixture.readRows(
                new StringReader("time,I:R.1@1\n0,1\n0,1\n"), SIGNALS));
        assertThrows(AssertionError.class, () -> ReleaseTraceFixture.readRows(
                new StringReader("time,I:R.1@1\n"), SIGNALS));
    }
}
