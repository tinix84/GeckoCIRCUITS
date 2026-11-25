package gecko.core.simulation;

import gecko.core.circuit.circuitcomponents.CircuitTypCore;
import gecko.core.circuit.netlist.CircuitNetlist;
import java.util.Arrays;
import java.util.List;

/** A scalar Probe output, stored as a component name followed by a quantity. */
public record ProbeReference(String component, Quantity quantity) {
    public static final int TYPE = 91;

    public enum Quantity {
        CURRENT("i"), VOLTAGE("v"), STATE("state");

        private final String suffix;

        Quantity(String suffix) {
            this.suffix = suffix;
        }

        public boolean supports(CircuitTypCore type) {
            return switch (type) {
                case LK_R, LK_L, LK_C, LK_I, LK_S, LK_IGBT, LK_THYR -> this != STATE;
                case LK_U -> this == VOLTAGE;
                case LK_D -> true;
                default -> false;
            };
        }
    }

    public static List<ProbeReference> parseText(String text) {
        return text.isBlank() ? List.of() : parse(text.split(",", -1));
    }

    public static List<ProbeReference> parse(String[] entries) {
        return Arrays.stream(entries).map(ProbeReference::parseEntry).toList();
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
