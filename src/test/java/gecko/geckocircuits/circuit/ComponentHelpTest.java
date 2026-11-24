package gecko.geckocircuits.circuit;

import gecko.geckocircuits.circuit.circuitcomponents.CircuitTyp;
import gecko.geckocircuits.control.ControlTyp;
import java.net.URI;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ComponentHelpTest {
    private static final String INDEX = "https://tinix84.github.io/GeckoCIRCUITS/components/";

    @Test
    void resolvesCircuitComponentAndControlBlockPages() {
        assertEquals(URI.create(INDEX + "resistor/"), ComponentHelp.urlFor(CircuitTyp.LK_R));
        assertEquals(URI.create(INDEX + "pi-controller/"), ComponentHelp.urlFor(ControlTyp.C_PI));
        assertEquals(URI.create(INDEX + "transformer/"), ComponentHelp.urlFor(CircuitTyp.LK_TRANS));
    }

    @Test
    void undocumentedComponentOpensIndex() {
        assertEquals(URI.create(INDEX), ComponentHelp.urlFor(ControlTyp.C_GAIN));
    }
}
