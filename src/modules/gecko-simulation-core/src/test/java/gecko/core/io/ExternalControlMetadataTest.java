package gecko.core.io;

import java.io.BufferedReader;
import java.io.StringReader;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExternalControlMetadataTest {
    @Test
    void retainsToExternalWidthWhenSavedInputLabelsAreAbsent() throws Exception {
        String text = "c\n<ElementCONTROL>\ntyp 22\nidStringDialog ToEXT.3\ntorder 2\n"
                + "anzXIN 3\nknotenNrXIN[] 0 1 2\n<"
                + (char) 92 + "ElementCONTROL>\n";
        var model = parse(text);
        var component = model.getControlComponents().getFirst();
        assertEquals(0, component.getTerminalXLabels().length);
        assertEquals(3, component.getParameters().get("externalWidth"));
        var descriptor = gecko.core.simulation.ControlBlockDescriptor.find(22);
        assertEquals(3, descriptor.inputs(component));
        assertEquals(3, descriptor.createCalculator(component, model)._inputSignal.length);
    }

    @Test
    void retainsSavedTerminalWidthAndExternalOrder() throws Exception {
        String text = "c\n<ElementCONTROL>\ntyp 23\nidStringDialog from\ntn 2\ntorder 7\n"
                + "labelAnfangsKnoten[] /\nlabelEndKnoten[] /a/b\n<" + (char) 92 + "ElementCONTROL>\n";
        var component = parse(text).getControlComponents().getFirst();
        assertEquals(2, component.getParameters().get("externalWidth"));
        assertEquals(7, component.getParameters().get("externalOrder"));
    }

    private static CircuitModel parse(String text) throws Exception {
        return new CircuitFileParser().parse(new BufferedReader(new StringReader(text)), "external.ipes");
    }
}
