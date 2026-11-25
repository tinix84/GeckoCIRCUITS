package gecko.geckocircuits.control;

import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReglerProbeTest {
    @Test
    void usesOneOutputForEachReference() {
        var probe = new ReglerProbe();
        assertEquals(0, probe.YOUT.size());
        probe.setReferenceText("L.1.i, C.1.v, D.1.state");
        assertEquals(3, probe.YOUT.size());
        assertThrows(IllegalArgumentException.class, () -> probe.setReferenceText("D.1.loss"));
        assertArrayEquals(new String[]{"L.1.i", "C.1.v", "D.1.state"}, probe.getOutputNames());
    }

    @Test
    void remapsNamesAndRetainsLabelsForCopiedTargets() {
        var probe = new ReglerProbe();
        probe.setReferenceText("L.1.i, C.1.v, D.1.state");
        probe.YOUT.get(1).getLabelObject().setLabelWithoutUndo("capacitorVoltage");
        probe.renameTarget("L.1", "Input.L");
        assertArrayEquals(new String[]{"Input.L.i", "C.1.v", "D.1.state"}, probe.getOutputNames());
        probe.remapTargets(Map.of("D.1", "D.2"), true);
        assertArrayEquals(new String[]{"Input.L.i", "C.1.v", "D.2.state"}, probe.getOutputNames());
        probe.remapTargets(Map.of("C.1", "C.2"), false);
        assertArrayEquals(new String[]{"C.2.v"}, probe.getOutputNames());
        assertEquals(1, probe.YOUT.size());
        assertEquals("capacitorVoltage", probe.YOUT.getFirst().getLabelObject().getLabelString());
    }
}
