package gecko.core.simulation;

import gecko.core.circuit.circuitcomponents.CircuitTypCore;
import gecko.core.circuit.netlist.CircuitNetlist;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

/** A scalar Probe output, stored as a component name followed by a quantity. */
public record ProbeReference(String component, Quantity quantity) {
    public static final int TYPE = 91;
    private static final Pattern TEXT_ESCAPE = Pattern.compile("\\\\([\\\\,])");
    // A valid plain reference always contains a dot. The encoded form never contains one.
    private static final String SAVED_PREFIX = "~";

    public enum Quantity {
        CURRENT("i"), VOLTAGE("v"), STATE("state");

        private final String suffix;

        Quantity(String suffix) {
            this.suffix = suffix;
        }

        /** Returns whether this quantity is available for the given electrical component type. */
        public boolean supports(CircuitTypCore type) {
            return switch (type) {
                case LK_R, LK_L, LK_C, LK_I, LK_S, LK_IGBT, LK_THYR -> this != STATE;
                case LK_U -> this == VOLTAGE;
                case LK_D -> true;
                default -> false;
            };
        }
    }

    /** Parses ordered editor entries. Escape a comma or backslash in a component name with a backslash. */
    public static List<ProbeReference> parseText(String text) {
        return text.isBlank() ? List.of() : Arrays.stream(splitTextEntries(text))
                .map(entry -> TEXT_ESCAPE.matcher(entry).replaceAll("$1")).map(ProbeReference::parseEntry).toList();
    }

    /** Splits the editor text at unescaped commas and retains the text of each entry. */
    public static String[] splitTextEntries(String text) {
        var entries = new ArrayList<String>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (character == '\\' && i + 1 < text.length()
                    && (text.charAt(i + 1) == '\\' || text.charAt(i + 1) == ',')) {
                i++;
            } else if (character == ',') {
                entries.add(text.substring(start, i));
                start = i + 1;
            }
        }
        entries.add(text.substring(start));
        return entries.toArray(String[]::new);
    }

    /** Escapes a reference or component name for display in the editor. */
    public static String escapeText(String value) {
        return value.replace("\\", "\\\\").replace(",", "\\,");
    }

    /** Parses an ordered array of saved references. */
    public static List<ProbeReference> parse(String[] entries) {
        return Arrays.stream(entries).map(ProbeReference::decodeSavedEntry).map(ProbeReference::parseEntry).toList();
    }

    /** Encodes a reference with a slash so it fits in one entry of a saved string array. */
    public static String encodeSavedEntry(String reference) {
        return reference.contains("/") ? SAVED_PREFIX
                + Base64.getUrlEncoder().withoutPadding().encodeToString(reference.getBytes(StandardCharsets.UTF_8))
                : reference;
    }

    /** Restores the text of a saved reference. Invalid encoded entries remain available for repair. */
    public static String decodeSavedEntry(String entry) {
        if (entry.startsWith(SAVED_PREFIX) && entry.indexOf('.') < 0) {
            try {
                String decoded = new String(Base64.getUrlDecoder().decode(entry.substring(SAVED_PREFIX.length())),
                        StandardCharsets.UTF_8);
                return encodeSavedEntry(decoded).equals(entry) ? decoded : entry;
            } catch (IllegalArgumentException error) {
                return entry;
            }
        }
        return entry;
    }

    private static ProbeReference parseEntry(String text) {
        String entry = text.strip();
        int separator = entry.lastIndexOf('.');
        if (separator > 0) {
            String name = entry.substring(0, separator).strip();
            String suffix = entry.substring(separator + 1);
            for (Quantity quantity : Quantity.values()) {
                if (!name.isEmpty() && quantity.suffix.equals(suffix)) {
                    return new ProbeReference(name, quantity);
                }
            }
        }
        throw new IllegalArgumentException("Enter a Probe reference such as L.1.i or D.1.state: " + entry);
    }

    /** Reports the conduction state of a piecewise-linear diode. */
    public static double diodeState(double[] electricalParameters) {
        return electricalParameters[0] == electricalParameters[3] ? 0.0 : 1.0;
    }

    /** Returns the electrical element index or reports an invalid or unsupported target. */
    public int resolve(CircuitNetlist netlist) {
        int element = netlist == null ? -1 : netlist.getElementIndexByName(component);
        if (element < 0 || !quantity.supports(netlist.getType(element))) {
            throw new IllegalArgumentException("Select a supported electrical component for Probe reference " + this + ".");
        }
        return element;
    }

    @Override
    public String toString() {
        return component + "." + quantity.suffix;
    }
}
