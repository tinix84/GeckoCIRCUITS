package gecko.geckocircuits.allg;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeckoFileChooserTest {
    @Test
    void recognisesTheSelectedFileSuffixRegardlessOfCase() {
        assertTrue(GeckoFileChooser.hasEnding("device.CSV", ".csv"));
        assertTrue(GeckoFileChooser.hasEnding("device.CsV", ".csv"));
        assertFalse(GeckoFileChooser.hasEnding("device.csv.txt", ".csv"));
        assertFalse(GeckoFileChooser.hasEnding("csv", ".csv"));
    }
}
