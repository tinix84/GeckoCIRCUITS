package gecko.geckocircuits.circuit;

import gecko.geckocircuits.circuit.circuitcomponents.CircuitTyp;
import gecko.geckocircuits.control.ControlTyp;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ComponentHelpTest {
    private static final String INDEX = "https://tinix84.github.io/GeckoCIRCUITS/components/";

    @ParameterizedTest
    @CsvSource({
        "LK_R, resistor",
        "LK_L, inductor",
        "LK_C, capacitor",
        "LK_U, voltage-source",
        "LK_I, current-source",
        "LK_D, diode",
        "LK_S, ideal-switch",
        "LK_MOSFET, mosfet",
        "LK_IGBT, igbt",
        "LK_THYR, thyristor",
        "LK_TRANS, transformer"
    })
    void resolvesEachDocumentedCircuitComponentPage(CircuitTyp type, String slug) {
        assertEquals(URI.create(INDEX + slug + "/"), ComponentHelp.urlFor(type));
    }

    @Test
    void resolvesThePiControllerPage() {
        assertEquals(URI.create(INDEX + "pi-controller/"), ComponentHelp.urlFor(ControlTyp.C_PI));
    }

    @Test
    void undocumentedComponentOpensIndex() {
        assertEquals(URI.create(INDEX), ComponentHelp.urlFor(ControlTyp.C_GAIN));
    }
}
