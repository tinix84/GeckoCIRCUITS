/*  This file is part of GeckoCIRCUITS. Copyright (C) ETH Zurich, Gecko-Simulations AG
 *
 *  GeckoCIRCUITS is free software: you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation, either version 3 of the License, or (at your option) any later version.
 *
 *  GeckoCIRCUITS is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR
 *  PURPOSE.  See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  GeckoCIRCUITS.  If not, see <http://www.gnu.org/licenses/>.
 */
package gecko.core.io.ltspice;

import gecko.core.io.CircuitModel;

import java.util.*;

/**
 * Converts a parsed {@link LtspiceCircuit} into a GeckoCIRCUITS {@link CircuitModel}.
 *
 * <h3>Coordinate mapping</h3>
 * LTspice uses pixel units (typically multiples of 16). GeckoCIRCUITS uses a grid
 * where 1 unit = 16 pixels (set by {@code dpix=16}).  All coordinates are divided
 * by {@value #GRID_PIXELS} when converting.
 *
 * <h3>Component type mapping</h3>
 * <ul>
 *   <li>res  → type 1 (resistor)</li>
 *   <li>ind / ind2 → type 2 (inductor)</li>
 *   <li>cap  → type 3 (capacitor)</li>
 *   <li>voltage → type 4 (voltage source)</li>
 *   <li>current → type 5 (current source)</li>
 *   <li>diode   → type 6 (diode)</li>
 *   <li>sw      → type 7 (ideal switch)</li>
 * </ul>
 *
 * <h3>Connectivity</h3>
 * Wire endpoints and FLAGs are used to build a Union-Find net graph.
 * Component terminals are matched to the nearest wire endpoint within
 * {@value #MATCH_RADIUS_PX} pixels.  Unresolved terminals receive
 * auto-generated names and a warning is recorded.
 *
 * <h3>Limitations</h3>
 * Multi-pin components (transistors, op-amps, etc.) are placed with placeholder
 * net names and a warning is added.  Simulation parameters (e.g. .tran) are not
 * parsed from LTspice SPICE directives in this version; default values are used.
 */
public class AscToIpesConverter {

    /** Pixels per GeckoCIRCUITS grid unit. */
    static final int GRID_PIXELS = 16;

    /**
     * Maximum distance in pixels between a component terminal position and a
     * wire endpoint for them to be considered connected.
     */
    static final int MATCH_RADIUS_PX = 64;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Result object returned by {@link #convert(LtspiceCircuit)}.
     */
    public static class ConversionResult {
        private final CircuitModel model;
        private final List<String> warnings;

        ConversionResult(CircuitModel model, List<String> warnings) {
            this.model = model;
            this.warnings = Collections.unmodifiableList(warnings);
        }

        /** The converted {@link CircuitModel}. */
        public CircuitModel getModel() { return model; }

        /** Human-readable warnings produced during conversion. */
        public List<String> getWarnings() { return warnings; }
    }

    /**
     * Converts an {@link LtspiceCircuit} into a {@link CircuitModel}.
     *
     * @param circuit the parsed LTspice schematic
     * @return conversion result containing the model and any warnings
     */
    public ConversionResult convert(LtspiceCircuit circuit) {
        List<String> warnings = new ArrayList<>();
        CircuitModel model = new CircuitModel();

        // Build wire connectivity
        WireGraph graph = new WireGraph(circuit.getWires(), circuit.getFlags());

        // Convert components
        int unresolved = 0;
        for (LtspiceComponent comp : circuit.getComponents()) {
            int geckoType = mapSymbolType(comp.getSymbolType());
            if (geckoType < 0) {
                warnings.add("Unsupported component type '" + comp.getSymbolType()
                        + "' for instance '" + comp.getInstName() + "' – skipped.");
                continue;
            }

            // Grid positions
            int gx = comp.getX() / GRID_PIXELS;
            int gy = comp.getY() / GRID_PIXELS;

            // Determine terminal net names from wire connectivity
            int[][] pinOffsets = getPinOffsets(comp.getSymbolType(), comp.getOrientation());
            String[] terminals = resolveTerminals(comp, pinOffsets, graph, warnings);
            if (terminals[0].startsWith("unconnected_") || terminals[1].startsWith("unconnected_")) {
                unresolved++;
            }

            // Parse primary value
            double primaryValue = parseValue(comp.getValue(), warnings, comp.getInstName());

            // Build parameters
            double[] params = buildParameters(geckoType, primaryValue, comp.getValue());

            // Create component data
            CircuitModel.ComponentData cd = new CircuitModel.ComponentData(
                    geckoType,
                    comp.getInstName() != null ? comp.getInstName() : comp.getSymbolType().toUpperCase() + "?",
                    gx, gy,
                    mapOrientation(comp.getOrientation())
            );
            for (int i = 0; i < params.length; i++) {
                cd.setParameter("param" + i, params[i]);
            }
            if (params.length > 0) {
                cd.setParameter(resolveParameterKey(geckoType), params[0]);
            }
            cd.setTerminalXLabels(new String[]{terminals[0]});
            cd.setTerminalYLabels(new String[]{terminals[1]});

            model.addCircuitComponent(cd);
        }

        if (unresolved > 0) {
            warnings.add(unresolved + " component terminal(s) could not be resolved to a net. "
                    + "Check wire connections in the generated .ipes file.");
        }

        // Set sensible simulation defaults (LTspice .tran not parsed in this version)
        model.setSimulationDuration(0.001);   // 1 ms default
        model.setTimeStep(1e-7);              // 100 ns default
        model.setPreSimulationTimeStep(1e-7);

        warnings.add("Simulation parameters (duration, time-step) were set to defaults. "
                + "Adjust them in GeckoCIRCUITS before running a simulation.");

        return new ConversionResult(model, warnings);
    }

    // -------------------------------------------------------------------------
    // Component type mapping
    // -------------------------------------------------------------------------

    /**
     * Maps an LTspice symbol type to a GeckoCIRCUITS component type number.
     * Returns -1 for unsupported types.
     */
    static int mapSymbolType(String symbolType) {
        if (symbolType == null) return -1;
        return switch (symbolType.toLowerCase()) {
            case "res"              -> 1;   // LK_R
            case "ind", "ind2"      -> 2;   // LK_L
            case "cap"              -> 3;   // LK_C
            case "voltage"          -> 4;   // LK_U (voltage source)
            case "current"          -> 5;   // LK_I (current source)
            case "diode"            -> 6;   // LK_D
            case "sw"               -> 7;   // LK_S (ideal switch)
            default                 -> -1;  // unsupported
        };
    }

    /**
     * Returns the semantic parameter key for the primary value of a component type.
     * Mirrors the key used in {@link gecko.core.io.CircuitFileParser}.
     */
    private static String resolveParameterKey(int type) {
        return switch (type) {
            case 1 -> "resistance";
            case 2 -> "inductance";
            case 3 -> "capacitance";
            case 4, 5 -> "amplitude";
            case 6 -> "forwardVoltage";
            case 7 -> "resistance";
            default -> "value";
        };
    }

    // -------------------------------------------------------------------------
    // Pin offset tables
    // -------------------------------------------------------------------------

    /**
     * Returns the two standard pin offsets (dx, dy) relative to the symbol origin
     * for the given component type and orientation.
     *
     * <p>Pin offsets are derived from LTspice XVII standard library .asy files.
     * For R0 (vertical, top→bottom):
     * <ul>
     *   <li>res, cap, ind, sw: pin A at (0, -48), pin B at (0, +48)</li>
     *   <li>voltage, current: positive at (0, -112), negative at (0, +112)</li>
     *   <li>diode: anode at (0, 0), cathode at (0, -96)</li>
     * </ul>
     *
     * @return int[2][2] where [0] = {dx1, dy1} and [1] = {dx2, dy2}
     */
    static int[][] getPinOffsets(String symbolType, String orientation) {
        // R0 base offsets
        int[][] base = getR0PinOffsets(symbolType);
        return rotatePins(base, orientation);
    }

    private static int[][] getR0PinOffsets(String symbolType) {
        if (symbolType == null) symbolType = "res";
        return switch (symbolType.toLowerCase()) {
            case "voltage", "current" -> new int[][]{{0, -112}, {0, 112}};
            case "diode"              -> new int[][]{{0, 0}, {0, -96}};
            default                   -> new int[][]{{0, -48}, {0, 48}}; // res, cap, ind, sw
        };
    }

    /**
     * Rotates pin offsets according to the LTspice orientation string.
     * R0=0°, R90=90°CW, R180=180°, R270=270°CW.
     * Mirror variants (M0, M90, ...) mirror horizontally then rotate.
     */
    static int[][] rotatePins(int[][] pins, String orientation) {
        if (orientation == null) return pins;
        return switch (orientation.toUpperCase()) {
            case "R0"   -> pins;
            case "R90"  -> rotateCW(pins);
            case "R180" -> rotate180(pins);
            case "R270" -> rotateCCW(pins);
            case "M0"   -> mirrorX(pins);
            case "M90"  -> rotateCW(mirrorX(pins));
            case "M180" -> rotate180(mirrorX(pins));
            case "M270" -> rotateCCW(mirrorX(pins));
            default     -> pins;
        };
    }

    private static int[][] rotateCW(int[][] p) {
        return new int[][]{{-p[0][1], p[0][0]}, {-p[1][1], p[1][0]}};
    }

    private static int[][] rotateCCW(int[][] p) {
        return new int[][]{{p[0][1], -p[0][0]}, {p[1][1], -p[1][0]}};
    }

    private static int[][] rotate180(int[][] p) {
        return new int[][]{{-p[0][0], -p[0][1]}, {-p[1][0], -p[1][1]}};
    }

    private static int[][] mirrorX(int[][] p) {
        return new int[][]{{-p[0][0], p[0][1]}, {-p[1][0], p[1][1]}};
    }

    // -------------------------------------------------------------------------
    // Orientation mapping
    // -------------------------------------------------------------------------

    /**
     * Maps an LTspice orientation string to a GeckoCIRCUITS {@code orientierung} integer.
     * GeckoCIRCUITS uses 0 = standard, matching LTspice R0.
     * Exact mapping for other rotations is approximate and may require manual adjustment.
     */
    static int mapOrientation(String orientation) {
        if (orientation == null) return 0;
        return switch (orientation.toUpperCase()) {
            case "R0"   -> 0;
            case "R90"  -> 1;
            case "R180" -> 2;
            case "R270" -> 3;
            case "M0"   -> 4;
            case "M90"  -> 5;
            case "M180" -> 6;
            case "M270" -> 7;
            default     -> 0;
        };
    }

    // -------------------------------------------------------------------------
    // Value parsing
    // -------------------------------------------------------------------------

    /**
     * Parses an LTspice value string to a double.
     * Handles SI suffixes (k/K, M/Meg, g/G, m, u/µ, n, p, f)
     * and simple SPICE expressions like {@code SINE(0 amplitude frequency)}.
     *
     * @param value    the value string (may be null)
     * @param warnings list to append warning messages to
     * @param instName instance name for warnings
     * @return parsed value, or 0.0 if unparseable
     */
    static double parseValue(String value, List<String> warnings, String instName) {
        if (value == null || value.isBlank()) {
            return 0.0;
        }

        String v = value.trim();

        // Handle SPICE transient source expressions: SINE(offset ampl freq ...)
        // Extract amplitude (second argument) for use as the primary value
        if (v.toUpperCase().startsWith("SINE(")) {
            return parseSineAmplitude(v, warnings, instName);
        }
        if (v.toUpperCase().startsWith("PULSE(")) {
            return parsePulseAmplitude(v, warnings, instName);
        }
        if (v.toUpperCase().startsWith("PWL(")) {
            warnings.add("PWL source value for '" + instName + "' is not supported; using 0.");
            return 0.0;
        }

        return parseNumericWithSuffix(v, warnings, instName);
    }

    private static double parseSineAmplitude(String v, List<String> warnings, String instName) {
        // SINE(offset amplitude [frequency [td [theta [phi [ncycles]]]]])
        try {
            String inner = v.substring(v.indexOf('(') + 1, v.lastIndexOf(')'));
            String[] parts = inner.trim().split("\\s+");
            if (parts.length >= 2) {
                return parseNumericWithSuffix(parts[1], warnings, instName);
            }
        } catch (Exception ignored) { /* fall through */ }
        warnings.add("Could not parse SINE expression for '" + instName + "'; using 0.");
        return 0.0;
    }

    private static double parsePulseAmplitude(String v, List<String> warnings, String instName) {
        // PULSE(Vlo Vhi [Tdelay [Trise [Tfall [Ton [Tperiod [Ncycles]]]]]])
        try {
            String inner = v.substring(v.indexOf('(') + 1, v.lastIndexOf(')'));
            String[] parts = inner.trim().split("\\s+");
            if (parts.length >= 2) {
                return parseNumericWithSuffix(parts[1], warnings, instName);
            }
        } catch (Exception ignored) { /* fall through */ }
        warnings.add("Could not parse PULSE expression for '" + instName + "'; using 0.");
        return 0.0;
    }

    /**
     * Parses a numeric value with optional SI suffix.
     */
    static double parseNumericWithSuffix(String v, List<String> warnings, String instName) {
        if (v == null || v.isBlank()) return 0.0;

        // Try plain double first
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException ignored) { /* continue */ }

        // Find where the number ends and the suffix begins
        int suffixStart = 0;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (Character.isDigit(c) || c == '.' || c == '-' || c == '+' || c == 'e' || c == 'E') {
                suffixStart = i + 1;
            } else {
                break;
            }
        }

        String numberPart = v.substring(0, suffixStart);
        String suffix = v.substring(suffixStart).trim();

        double number;
        try {
            number = Double.parseDouble(numberPart.isEmpty() ? "1" : numberPart);
        } catch (NumberFormatException e) {
            if (warnings != null) {
                warnings.add("Cannot parse value '" + v + "' for '" + instName + "'; using 0.");
            }
            return 0.0;
        }

        double multiplier = switch (suffix) {
            case "T"                    -> 1e12;
            case "G", "g"               -> 1e9;
            case "Meg", "MEG", "meg"    -> 1e6;
            case "M"                    -> 1e6;  // LTspice 'M' (upper) = Mega
            case "K", "k"               -> 1e3;
            case "m"                    -> 1e-3; // milli (lower-case)
            case "u", "µ", "U"          -> 1e-6;
            case "n", "N"               -> 1e-9;
            case "p", "P"               -> 1e-12;
            case "f", "F"               -> 1e-15;
            default                     -> 1.0;
        };

        return number * multiplier;
    }

    // -------------------------------------------------------------------------
    // Parameter array building
    // -------------------------------------------------------------------------

    /**
     * Builds a minimal parameter array for a GeckoCIRCUITS component.
     * The first element is the primary value; remaining elements are zero.
     */
    static double[] buildParameters(int geckoType, double primaryValue, String rawValue) {
        return switch (geckoType) {
            case 1 -> new double[]{primaryValue, 0.0, 0.0};       // R
            case 2 -> new double[]{primaryValue, 0.0, 0.0};       // L
            case 3 -> new double[]{primaryValue, 0.0, 0.0};       // C
            case 4, 5 -> buildSourceParameters(primaryValue, rawValue);
            case 6 -> new double[]{0.6, 1e-9, 0.0};               // diode: Vf, Is, ...
            case 7 -> new double[]{0.01, 0.0};                     // switch on-resistance
            default -> new double[]{primaryValue};
        };
    }

    private static double[] buildSourceParameters(double amplitude, String rawValue) {
        // Try to extract frequency from SINE(offset ampl freq ...)
        double frequency = 0.0;
        if (rawValue != null && rawValue.toUpperCase().startsWith("SINE(")) {
            try {
                String inner = rawValue.substring(rawValue.indexOf('(') + 1, rawValue.lastIndexOf(')'));
                String[] parts = inner.trim().split("\\s+");
                if (parts.length >= 3) {
                    frequency = parseNumericWithSuffix(parts[2], null, "");
                }
            } catch (Exception ignored) { /* use 0 */ }
        }
        // params: amplitude, offset, frequency, phase, ...
        return new double[]{amplitude, 0.0, frequency, 0.0, 0.0};
    }

    // -------------------------------------------------------------------------
    // Wire connectivity (Union-Find)
    // -------------------------------------------------------------------------

    /**
     * Resolves the two terminal net names for a component using wire connectivity.
     *
     * @return String[2] where [0]=startNode label, [1]=endNode label
     */
    private String[] resolveTerminals(LtspiceComponent comp, int[][] pinOffsets,
                                      WireGraph graph, List<String> warnings) {
        int ox = comp.getX();
        int oy = comp.getY();

        // Absolute pixel positions of the two pins
        int pin0x = ox + pinOffsets[0][0];
        int pin0y = oy + pinOffsets[0][1];
        int pin1x = ox + pinOffsets[1][0];
        int pin1y = oy + pinOffsets[1][1];

        String net0 = graph.findNetAt(pin0x, pin0y, MATCH_RADIUS_PX);
        String net1 = graph.findNetAt(pin1x, pin1y, MATCH_RADIUS_PX);

        if (net0 == null) {
            net0 = graph.autoNetName();
            warnings.add("Terminal A of '" + comp.getInstName() + "' at ("
                    + pin0x + "," + pin0y + ") not connected to any wire; assigned '" + net0 + "'.");
        }
        if (net1 == null) {
            net1 = graph.autoNetName();
            warnings.add("Terminal B of '" + comp.getInstName() + "' at ("
                    + pin1x + "," + pin1y + ") not connected to any wire; assigned '" + net1 + "'.");
        }

        return new String[]{net0, net1};
    }

    // -------------------------------------------------------------------------
    // WireGraph – Union-Find based net connectivity
    // -------------------------------------------------------------------------

    /**
     * Builds a net connectivity graph from LTspice wires and flags using
     * Union-Find, then provides point-based net lookup.
     */
    static final class WireGraph {

        /** Map from canonical point key to root representative. */
        private final Map<String, String> parent = new HashMap<>();

        /** Map from net root to user-visible net name (from FLAGS). */
        private final Map<String, String> netNames = new HashMap<>();

        private int autoCounter = 0;

        WireGraph(List<LtspiceWire> wires, List<LtspiceFlag> flags) {
            // Union all endpoints of each wire
            for (LtspiceWire wire : wires) {
                String k1 = key(wire.getX1(), wire.getY1());
                String k2 = key(wire.getX2(), wire.getY2());
                ensure(k1);
                ensure(k2);
                union(k1, k2);
            }

            // Register FLAG net names
            for (LtspiceFlag flag : flags) {
                String k = key(flag.getX(), flag.getY());
                ensure(k);
                String root = find(k);
                // Prefer named flag over ground; first flag wins for a given root
                if (!netNames.containsKey(root) || "gnd".equals(netNames.get(root))) {
                    String name = flag.isGround() ? "gnd" : flag.getNetName();
                    netNames.put(root, sanitizeNetName(name));
                }
            }
        }

        /**
         * Finds the net name for the wire endpoint closest to (px, py) within the
         * given radius.  Returns null if no wire endpoint is within range.
         */
        String findNetAt(int px, int py, int radiusPx) {
            String closest = null;
            long minDist2 = (long) radiusPx * radiusPx + 1;

            for (String k : parent.keySet()) {
                int[] coords = decodeKey(k);
                long dx = coords[0] - px;
                long dy = coords[1] - py;
                long d2 = dx * dx + dy * dy;
                if (d2 < minDist2) {
                    minDist2 = d2;
                    closest = k;
                }
            }

            if (closest == null) return null;
            String root = find(closest);
            return netNames.getOrDefault(root, "net_" + root.replace(",", "_"));
        }

        /** Generates a unique auto net name for unresolved terminals. */
        String autoNetName() {
            return "unconnected_" + (++autoCounter);
        }

        // ----- Union-Find helpers -----

        private void ensure(String k) {
            parent.putIfAbsent(k, k);
        }

        private String find(String x) {
            if (!parent.get(x).equals(x)) {
                parent.put(x, find(parent.get(x)));
            }
            return parent.get(x);
        }

        private void union(String x, String y) {
            String rx = find(x), ry = find(y);
            if (!rx.equals(ry)) {
                parent.put(rx, ry);
                // Propagate name: keep the named one if possible
                String nameX = netNames.get(rx);
                String nameY = netNames.get(ry);
                if (nameX != null && nameY == null) {
                    netNames.put(ry, nameX);
                } else if (nameY != null) {
                    // keep nameY on ry (already there)
                }
            }
        }

        private static String key(int x, int y) {
            return x + "," + y;
        }

        private static int[] decodeKey(String k) {
            int comma = k.indexOf(',');
            return new int[]{
                Integer.parseInt(k.substring(0, comma)),
                Integer.parseInt(k.substring(comma + 1))
            };
        }

        private static String sanitizeNetName(String name) {
            if (name == null || name.isBlank()) return "net";
            // Replace characters invalid in GeckoCIRCUITS labels
            return name.replaceAll("[^A-Za-z0-9_.]", "_");
        }
    }
}
