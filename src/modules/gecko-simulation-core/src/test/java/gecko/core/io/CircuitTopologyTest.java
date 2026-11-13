package gecko.core.io;

import java.io.BufferedReader;
import java.io.StringReader;
import gecko.core.preparation.CircuitPreparation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CircuitTopologyTest {
    @Test
    void capturedWiresJoinDifferentLocalLabelsWithoutJoiningOtherSheets() throws Exception {
        String text = subcircuit(101, 0, 1) + subcircuit(202, 0, 1)
                + block(3, "source", 1, 101, "", "/left")
                + block(5, "localScope", 2, 101, "/right", "")
                + block(5, "otherScope", 3, 202, "/right", "") + """
                verbindungCONTROL
                <Verbindung>
                x[] 12 13 14 15 16 17 18
                y[] 10 10 10 10 10 10 10
                parentSheetIdentifier 101
                enabledShorted 1
                <\\Verbindung>
                """;
        var model = new CircuitFileParser().parse(new BufferedReader(new StringReader(text)), "wires.ipes");
        model.getControlComponents().get(0).getTerminalData().add(
                new CircuitModel.TerminalData("CONTROL", 101, CircuitTopology.Side.OUTPUT, 0, 12, 10, true));
        model.getControlComponents().get(1).getTerminalData().add(
                new CircuitModel.TerminalData("CONTROL", 101, CircuitTopology.Side.INPUT, 0, 18, 10, true));
        model.getControlComponents().get(2).getTerminalData().add(
                new CircuitModel.TerminalData("CONTROL", 202, CircuitTopology.Side.INPUT, 0, 18, 10, true));
        var captured = new CircuitPreparation().capture(model).toModel();
        var graph = CircuitTopology.resolve(captured);
        var source = graph.group(captured.getControlComponents().get(0), CircuitTopology.Side.OUTPUT, 0);
        assertEquals(source, graph.group(captured.getControlComponents().get(1), CircuitTopology.Side.INPUT, 0));
        assertNotEquals(source, graph.group(captured.getControlComponents().get(2), CircuitTopology.Side.INPUT, 0));
    }

    @Test
    void globalMarkersJoinByTheirComponentNameAcrossDifferentLocalLabels() throws Exception {
        String text = subcircuit(101, 0, 1) + subcircuit(202, 0, 1)
                + block(3, "source", 1, 101, "", "/localLeft")
                + block(81, "sharedGlobal", 2, 101, "/localLeft", "")
                + block(81, "sharedGlobal", 3, 202, "/localRight", "")
                + block(5, "scope", 4, 202, "/localRight", "");
        var model = new CircuitFileParser().parse(new BufferedReader(new StringReader(text)), "globals.ipes");
        var graph = CircuitTopology.resolve(model);
        var group = graph.group(model.getControlComponents().getFirst(), CircuitTopology.Side.OUTPUT, 0);
        assertEquals(group, graph.group(model.getControlComponents().getLast(), CircuitTopology.Side.INPUT, 0));
        assertEquals(1, group.producerTerminals().size());
    }

    @Test
    void subcircuitMarkerConnectsItsTwoSheetsWithoutAddingASignalProducer() throws Exception {
        String text = subcircuit(101, 0, 1)
                + block(3, "source", 1, 0, "", "/outside")
                + block(78, "boundary", 2, 101, "/inside", "/outside")
                + block(5, "scope", 3, 101, "/inside", "");
        var model = new CircuitFileParser().parse(new BufferedReader(new StringReader(text)), "boundary.ipes");
        var graph = CircuitTopology.resolve(model);
        var source = model.getControlComponents().get(0);
        var marker = model.getControlComponents().get(1);
        var scope = model.getControlComponents().get(2);
        var group = graph.group(source, CircuitTopology.Side.OUTPUT, 0);
        assertEquals(group, graph.group(marker, CircuitTopology.Side.INPUT, 0));
        assertEquals(group, graph.group(marker, CircuitTopology.Side.OUTPUT, 0));
        assertEquals(group, graph.group(scope, CircuitTopology.Side.INPUT, 0));
        assertEquals(1, group.producerTerminals().size());
    }

    @Test
    void disabledAncestorRemovesEveryNestedExecutionComponentAndItsSignalGroups() throws Exception {
        String text = """
                sp
                <ElementSPECIAL>
                typ 27
                idStringDialog disabled
                uniqueObjectIdentifier 101
                enabledShorted 0
                <\\ElementSPECIAL>
                sp
                <ElementSPECIAL>
                typ 27
                idStringDialog nested
                uniqueObjectIdentifier 202
                parentSheetIdentifier 101
                enabledShorted 1
                <\\ElementSPECIAL>
                """ + block(3, "hidden", 1, 202, "", "/out") + block(3, "visible", 2, 0, "", "/out");
        var model = new CircuitFileParser().parse(new BufferedReader(new StringReader(text)), "disabled.ipes");
        var topology = CircuitTopology.resolve(model);
        var hidden = model.getControlComponents().getFirst();
        var visible = model.getControlComponents().getLast();
        assertFalse(topology.activeComponents().contains(topology.identity(hidden)));
        assertNull(topology.group(hidden, CircuitTopology.Side.OUTPUT, 0));
        assertNotNull(topology.group(visible, CircuitTopology.Side.OUTPUT, 0));
        assertEquals(2, model.getControlComponents().size(), "Topology resolution must preserve the inspection description");
    }

    @Test
    void localAliasesStayWithinTheirSheetAndBlankScopePortsKeepTheirIdentity() throws Exception {
        String text = block(3, "left", 1, 101, "", "/bus")
                + block(3, "right", 2, 202, "", "/bus")
                + block(5, "scope", 3, 101, "/bus/NIX_NIX_NIX/bus", "");
        var model = new CircuitFileParser().parse(new BufferedReader(new StringReader(text)), "sheets.ipes");
        var topology = CircuitTopology.resolve(model);
        var left = model.getControlComponents().get(0);
        var right = model.getControlComponents().get(1);
        var scope = model.getControlComponents().get(2);
        var leftGroup = topology.group(left, CircuitTopology.Side.OUTPUT, 0);
        assertEquals(leftGroup, topology.group(scope, CircuitTopology.Side.INPUT, 0));
        assertEquals(leftGroup, topology.group(scope, CircuitTopology.Side.INPUT, 2));
        assertNotEquals(leftGroup, topology.group(right, CircuitTopology.Side.OUTPUT, 0));
        assertNotEquals(leftGroup, topology.group(scope, CircuitTopology.Side.INPUT, 1));
        assertEquals(1, leftGroup.producerTerminals().size());
        assertEquals(3, leftGroup.terminals().size());
    }

    private static String block(int type, String name, long id, long sheet, String inputs, String outputs) {
        return "c\n<ElementCONTROL>\ntyp " + type + "\nidStringDialog " + name
                + "\nuniqueObjectIdentifier " + id + "\nparentSheetIdentifier " + sheet
                + "\nparameter[] 2\nlabelAnfangsKnoten[] " + inputs
                + "\nlabelEndKnoten[] " + outputs + "\n<\\ElementCONTROL>\n";
    }

    private static String subcircuit(long id, long parent, int enabled) {
        return "sp\n<ElementSPECIAL>\ntyp 27\nidStringDialog sub" + id + "\nuniqueObjectIdentifier " + id
                + "\nparentSheetIdentifier " + parent + "\nenabledShorted " + enabled + "\n<\\ElementSPECIAL>\n";
    }
}
