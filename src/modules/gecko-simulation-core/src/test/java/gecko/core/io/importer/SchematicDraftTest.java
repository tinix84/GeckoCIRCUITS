package gecko.core.io.importer;

import gecko.core.io.importer.SchematicDraft.Kind;
import gecko.core.io.importer.SchematicDraft.Part;
import gecko.core.io.importer.SchematicDraft.Terminal;
import gecko.core.io.importer.SchematicDraft.Wire;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchematicDraftTest {
    @Test
    void rejectsAnElbowThatWouldConnectTwoDifferentNodes() {
        var parts = List.of(
                new Part(Kind.VOLTAGE_SOURCE, "V1", 5, 12, 5),
                new Part(Kind.RESISTOR, "R2", 12, 6, 1_000),
                new Part(Kind.CAPACITOR, "C3", 12, 12, 1e-6),
                new Part(Kind.GROUND, "GND4", 9, 18, 0));
        var wires = List.of(
                new Wire(new Terminal("V1", 0), new Terminal("R2", 0)),
                new Wire(new Terminal("R2", 1), new Terminal("C3", 0)),
                new Wire(new Terminal("V1", 1), new Terminal("GND4", 0)),
                new Wire(new Terminal("C3", 1), new Terminal("GND4", 0)));

        var failure = assertThrows(IllegalArgumentException.class, () ->
                SchematicDraft.build(parts, wires, 0.005, 1e-6));

        assertTrue(failure.getMessage().contains("share a grid point"));
    }
}
