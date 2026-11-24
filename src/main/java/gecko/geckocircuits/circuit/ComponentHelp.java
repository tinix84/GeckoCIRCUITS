package gecko.geckocircuits.circuit;

import gecko.geckocircuits.allg.AbstractComponentTyp;
import java.net.URI;
import java.util.Map;

public final class ComponentHelp {
    private static final String COMPONENTS_URL = "https://tinix84.github.io/GeckoCIRCUITS/components/";
    private static final Map<String, String> PAGES = Map.ofEntries(
            Map.entry("R", "resistor"),
            Map.entry("L", "inductor"),
            Map.entry("C", "capacitor"),
            Map.entry("U", "voltage-source"),
            Map.entry("I", "current-source"),
            Map.entry("D", "diode"),
            Map.entry("S", "ideal-switch"),
            Map.entry("MOSFET", "mosfet"),
            Map.entry("IGBT", "igbt"),
            Map.entry("THYR", "thyristor"),
            Map.entry("Trans", "transformer"),
            Map.entry("PI", "pi-controller"));

    private ComponentHelp() {
    }

    public static URI urlFor(AbstractComponentTyp type) {
        String slug = PAGES.get(type.getTypeInfo()._fixedIDString);
        return URI.create(slug == null ? COMPONENTS_URL : COMPONENTS_URL + slug + "/");
    }
}
