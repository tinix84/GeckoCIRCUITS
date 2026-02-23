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
package gecko.core.io;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parser for SPICE and Gecko-dialect {@code .cir} netlist files.
 *
 * <p>Standard SPICE elements supported:</p>
 * <ul>
 *   <li>R – Resistor: {@code R<name> <n+> <n-> <value>}</li>
 *   <li>L – Inductor: {@code L<name> <n+> <n-> <value>}</li>
 *   <li>C – Capacitor: {@code C<name> <n+> <n-> <value>}</li>
 *   <li>V – Voltage source: {@code V<name> <n+> <n-> [DC <value>] [AC <amp> [<phase>]]}</li>
 *   <li>I – Current source: {@code I<name> <n+> <n-> [DC <value>] [AC <amp> [<phase>]]}</li>
 *   <li>D – Diode: {@code D<name> <n+> <n-> [vf[V]]}</li>
 * </ul>
 *
 * <p>Gecko-exclusive elements supported:</p>
 * <ul>
 *   <li>S      – Ideal switch:   {@code S<name> <n+> <n-> <gate_label> [ron[Ω]]}</li>
 *   <li>THYR   – Thyristor:      {@code THYR<name> <anode> <cathode> <gate_label> [vf] [ron]}</li>
 *   <li>IGBT   – IGBT:           {@code IGBT<name> <collector> <emitter> <gate_label> [vf] [ron]}</li>
 *   <li>MOSFET – MOSFET:         {@code MOSFET<name> <drain> <source> <gate_label> [vf] [ron]}</li>
 *   <li>BJT    – BJT:            {@code BJT<name> <collector> <emitter> <base> [NPN|PNP] [beta]}</li>
 *   <li>Lc     – Coupled inductor: {@code Lc<name> <n+> <n-> <inductance>}</li>
 *   <li>K      – Mutual coupling: {@code K<name> <Lc1> <Lc2> <k>}</li>
 *   <li>TRANS  – Transformer:    {@code TRANS<name> <n1+> <n1-> <n2+> <n2-> [ratio]}</li>
 *   <li>OPAMP  – Op-amp:         {@code OPAMP<name> <in+> <in-> <out> <ref>}</li>
 *   <li>LISN   – LISN:           {@code LISN<name> <a1> <a2> <a3> <b1> <b2> <b3>}</li>
 * </ul>
 *
 * <p>Simulation control statements supported:</p>
 * <ul>
 *   <li>{@code .tran <step> <stop>} – transient simulation parameters</li>
 * </ul>
 *
 * <p>Comments (lines starting with {@code *} or {@code ;}) and continuation
 * lines (starting with {@code +}) are handled.</p>
 *
 * <p>Usage example:</p>
 * <pre>{@code
 * SpiceNetlistParser parser = new SpiceNetlistParser();
 * // Standard SPICE:
 * SpiceNetlist netlist = parser.parse("* RC circuit\nV1 1 0 DC 12\nR1 1 2 1k\n.end");
 * // Gecko dialect:
 * SpiceNetlist netlist = parser.parse("* Buck\nV1 in 0 DC 400\nS1 in sw GATE.1 0.01\nL1 sw out 300u\n.end");
 * }</pre>
 *
 * @see GeckoElementDictionary
 */
public class SpiceNetlistParser {

    /**
     * Parses a SPICE or Gecko-dialect netlist string.
     *
     * @param content the full text of the .cir file
     * @return parsed netlist
     * @throws SpiceParseException if the content is empty or fatally malformed
     */
    public SpiceNetlist parse(String content) throws SpiceParseException {
        if (content == null || content.isBlank()) {
            throw new SpiceParseException("Netlist content is empty");
        }

        String[] rawLines = content.split("\\r?\\n", -1);
        List<String> lines = joinContinuationLines(rawLines);

        SpiceNetlist netlist = new SpiceNetlist();

        // The first non-blank, non-comment line is the title
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                netlist.setTitle(trimmed);
                break;
            }
        }

        boolean firstLine = true;
        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.isEmpty()) {
                continue;
            }

            // Skip the title (first non-blank line in SPICE is always the title)
            if (firstLine) {
                firstLine = false;
                continue;
            }

            // Skip comments
            if (trimmed.startsWith("*") || trimmed.startsWith(";")) {
                continue;
            }

            // End of netlist
            if (trimmed.equalsIgnoreCase(".end")) {
                break;
            }

            // Control/simulator statements
            if (trimmed.startsWith(".")) {
                parseControlStatement(trimmed, netlist);
                continue;
            }

            // Element lines (first character determines type)
            parseElementLine(trimmed, netlist);
        }

        return netlist;
    }

    // ==================== Private helpers ====================

    /**
     * Joins continuation lines (lines starting with '+') to the preceding line.
     */
    private List<String> joinContinuationLines(String[] rawLines) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String raw : rawLines) {
            String trimmed = raw.trim();
            if (trimmed.startsWith("+")) {
                // Continuation: append to the previous line, removing the '+'
                current.append(' ').append(trimmed.substring(1).trim());
            } else {
                if (current.length() > 0) {
                    result.add(current.toString());
                }
                current = new StringBuilder(raw);
            }
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result;
    }

    /**
     * Parses a single element line using the {@link GeckoElementDictionary} for type lookup.
     * Gecko-exclusive keywords (e.g., IGBT, MOSFET, THYR) are matched first by longest prefix
     * before falling back to single-character SPICE matching.
     */
    private void parseElementLine(String line, SpiceNetlist netlist) {
        String[] tokens = line.split("\\s+");
        if (tokens.length < 2) {
            return;
        }

        String name = tokens[0];
        GeckoElementDictionary dict = GeckoElementDictionary.fromElementName(name);
        if (dict == null) {
            return; // unrecognized element type – silently ignored
        }

        switch (dict) {
            case R  -> parsePassive(name, tokens, GeckoElementDictionary.R, netlist);
            case L  -> parsePassive(name, tokens, GeckoElementDictionary.L, netlist);
            case C  -> parsePassive(name, tokens, GeckoElementDictionary.C, netlist);
            case V  -> parseSource(name, tokens, GeckoElementDictionary.V, netlist);
            case I  -> parseSource(name, tokens, GeckoElementDictionary.I, netlist);
            case D  -> parseDiode(name, tokens, netlist);
            case S  -> parseGatedSwitch(name, tokens, GeckoElementDictionary.S, 0.01, 0.0, netlist);
            case THYR  -> parseGatedSwitch(name, tokens, GeckoElementDictionary.THYR, 0.01, 0.6, netlist);
            case IGBT  -> parseGatedSwitch(name, tokens, GeckoElementDictionary.IGBT, 0.01, 0.6, netlist);
            case MOSFET -> parseGatedSwitch(name, tokens, GeckoElementDictionary.MOSFET, 0.01, 0.6, netlist);
            case BJT    -> parseBjt(name, tokens, netlist);
            case LC -> parsePassive(name, tokens, GeckoElementDictionary.LC, netlist);
            case K  -> parseMutualCoupling(name, tokens, netlist);
            case TRANS  -> parseTransformer(name, tokens, netlist);
            case OPAMP  -> parseOpamp(name, tokens, netlist);
            case LISN   -> parseLisn(name, tokens, netlist);
        }
    }

    /**
     * Parses a 2-terminal passive element (R, L, C, Lc, D value):
     * {@code <name> <n+> <n-> <value>}
     */
    private void parsePassive(String name, String[] tokens, GeckoElementDictionary type,
                               SpiceNetlist netlist) {
        if (tokens.length < 4) {
            return;
        }
        String n1 = tokens[1];
        String n2 = tokens[2];
        double value = parseValue(tokens[3]);
        netlist.addComponent(SpiceComponent.passive(type, name, n1, n2, value));
    }

    /**
     * Parses a source element (V, I):
     * {@code <name> <n+> <n-> [DC <dcValue>] [AC <amplitude> [<phase>]]}
     *
     * <p>If neither DC nor AC keyword is present but a numeric value follows the
     * nodes, that value is treated as the DC value.</p>
     */
    private void parseSource(String name, String[] tokens, GeckoElementDictionary type,
                              SpiceNetlist netlist) {
        if (tokens.length < 3) {
            return;
        }
        String n1 = tokens[1];
        String n2 = tokens[2];

        double dcValue = 0.0;
        double acAmplitude = 0.0;
        SpiceComponent.SourceMode mode = SpiceComponent.SourceMode.DC;

        int i = 3;
        while (i < tokens.length) {
            String tok = tokens[i].toUpperCase(Locale.ROOT);
            if (tok.equals("DC")) {
                mode = SpiceComponent.SourceMode.DC;
                if (i + 1 < tokens.length && isNumeric(tokens[i + 1])) {
                    dcValue = parseValue(tokens[i + 1]);
                    i++;
                }
            } else if (tok.equals("AC")) {
                mode = SpiceComponent.SourceMode.AC;
                if (i + 1 < tokens.length && isNumeric(tokens[i + 1])) {
                    acAmplitude = parseValue(tokens[i + 1]);
                    i++;
                }
                if (i + 1 < tokens.length && isNumeric(tokens[i + 1])) {
                    // phase (consumed but not stored - default 0)
                    i++;
                }
            } else if (isNumeric(tok)) {
                dcValue = parseValue(tok);
            }
            i++;
        }

        netlist.addComponent(SpiceComponent.source(type, name, n1, n2, mode, dcValue, acAmplitude));
    }

    /**
     * Parses a diode element (D):
     * {@code <name> <anode> <cathode> [<model> | <vf>]}
     *
     * <p>If the 4th token is numeric it is treated as vf; otherwise the default 0.6 V is used.</p>
     */
    private void parseDiode(String name, String[] tokens, SpiceNetlist netlist) {
        if (tokens.length < 4) {
            return;
        }
        String anode = tokens[1];
        String cathode = tokens[2];
        // Use as vf only if the token looks like a plain number (no letter-digit combos like "1N4148")
        double vf = isStrictlyNumeric(tokens[3]) ? parseValue(tokens[3]) : 0.6;
        netlist.addComponent(SpiceComponent.passive(GeckoElementDictionary.D, name, anode, cathode, vf));
    }

    /**
     * Parses a gate-controlled semiconductor switch (S, THYR, IGBT, MOSFET):
     * {@code <name> <n+> <n-> <gate_label> [vf] [ron]}
     * or for ideal switch S:
     * {@code S<name> <n+> <n-> <gate_label> [ron]}
     *
     * <p>The gate label is the name of the gate control block in the control circuit.</p>
     */
    private void parseGatedSwitch(String name, String[] tokens, GeckoElementDictionary type,
                                   double defaultRon, double defaultVf, SpiceNetlist netlist) {
        if (tokens.length < 4) {
            return;
        }
        String n1 = tokens[1];
        String n2 = tokens[2];
        String gate = tokens[3]; // gate control label

        double vf = defaultVf;
        double ron = defaultRon;

        // Optional parameters after gate label: [vf] [ron] or [ron] for ideal switch
        if (tokens.length >= 5 && isNumeric(tokens[4])) {
            if (type == GeckoElementDictionary.S) {
                ron = parseValue(tokens[4]); // ideal switch has only ron
            } else {
                vf = parseValue(tokens[4]);  // semiconductors: vf first
            }
        }
        if (tokens.length >= 6 && isNumeric(tokens[5])) {
            ron = parseValue(tokens[5]);
        }

        netlist.addComponent(SpiceComponent.gatedSwitch(type, name, n1, n2, gate, ron, vf));
    }

    /**
     * Parses a BJT (3-terminal):
     * {@code BJT<name> <collector> <emitter> <base> [NPN|PNP] [beta]}
     */
    private void parseBjt(String name, String[] tokens, SpiceNetlist netlist) {
        if (tokens.length < 5) {
            return;
        }
        String collector = tokens[1];
        String emitter   = tokens[2];
        String base      = tokens[3];
        boolean isNpn = true;
        double beta = 100.0;

        int i = 4;
        if (i < tokens.length) {
            String tok = tokens[i].toUpperCase(Locale.ROOT);
            if (tok.equals("PNP")) {
                isNpn = false;
                i++;
            } else if (tok.equals("NPN")) {
                i++;
            }
        }
        if (i < tokens.length && isNumeric(tokens[i])) {
            beta = parseValue(tokens[i]);
        }

        netlist.addComponent(SpiceComponent.bjt(name, collector, emitter, base, isNpn, beta));
    }

    /**
     * Parses a mutual inductance coupling element (K):
     * {@code K<name> <Lc1_name> <Lc2_name> <k>}
     */
    private void parseMutualCoupling(String name, String[] tokens, SpiceNetlist netlist) {
        if (tokens.length < 4) {
            return;
        }
        String lc1 = tokens[1];
        String lc2 = tokens[2];
        double k = parseValue(tokens[3]);
        netlist.addComponent(SpiceComponent.coupling(name, lc1, lc2, k));
    }

    /**
     * Parses an ideal transformer (4-terminal):
     * {@code TRANS<name> <n1+> <n1-> <n2+> <n2-> [ratio]}
     */
    private void parseTransformer(String name, String[] tokens, SpiceNetlist netlist) {
        if (tokens.length < 5) {
            return;
        }
        String n1p = tokens[1];
        String n1n = tokens[2];
        String n2p = tokens[3];
        String n2n = tokens[4];
        double ratio = (tokens.length >= 6 && isNumeric(tokens[5])) ? parseValue(tokens[5]) : 1.0;
        netlist.addComponent(SpiceComponent.transformer(name, n1p, n1n, n2p, n2n, ratio));
    }

    /**
     * Parses an operational amplifier (4-terminal):
     * {@code OPAMP<name> <in+> <in-> <out> <ref>}
     */
    private void parseOpamp(String name, String[] tokens, SpiceNetlist netlist) {
        if (tokens.length < 5) {
            return;
        }
        netlist.addComponent(SpiceComponent.opamp(name, tokens[1], tokens[2], tokens[3], tokens[4]));
    }

    /**
     * Parses a LISN (6-terminal):
     * {@code LISN<name> <a1> <a2> <a3> <b1> <b2> <b3>}
     */
    private void parseLisn(String name, String[] tokens, SpiceNetlist netlist) {
        if (tokens.length < 7) {
            return;
        }
        netlist.addComponent(SpiceComponent.lisn(name,
                List.of(tokens[1], tokens[2], tokens[3], tokens[4], tokens[5], tokens[6])));
    }

    /**
     * Parses a control/simulator statement (lines starting with '.').
     */
    private void parseControlStatement(String line, SpiceNetlist netlist) {
        String upper = line.toUpperCase(Locale.ROOT);
        String[] tokens = line.split("\\s+");

        if (upper.startsWith(".TRAN") && tokens.length >= 3) {
            // .tran <step> <stop> [<start>]
            double step = parseValue(tokens[1]);
            double stop = parseValue(tokens[2]);
            if (step > 0) {
                netlist.setTimeStep(step);
            }
            if (stop > 0) {
                netlist.setSimulationDuration(stop);
            }
        }
    }

    /**
     * Parses a SPICE value token including SI suffixes.
     *
     * <p>Supported suffixes (case-insensitive):</p>
     * <table>
     *   <tr><th>Suffix</th><th>Multiplier</th></tr>
     *   <tr><td>MEG</td><td>1e6</td></tr>
     *   <tr><td>G</td><td>1e9</td></tr>
     *   <tr><td>K</td><td>1e3</td></tr>
     *   <tr><td>M</td><td>1e-3</td></tr>
     *   <tr><td>U, µ</td><td>1e-6</td></tr>
     *   <tr><td>N</td><td>1e-9</td></tr>
     *   <tr><td>P</td><td>1e-12</td></tr>
     *   <tr><td>F</td><td>1e-15</td></tr>
     * </table>
     *
     * <p><b>Note:</b> {@code Double.parseDouble} treats trailing 'f'/'F' as a Java float
     * literal suffix and returns the numeric value without the SI femto multiplier.
     * This method detects the numeric–suffix boundary first to avoid that ambiguity.</p>
     *
     * @param token the value string to parse
     * @return the parsed double value
     */
    static double parseValue(String token) {
        if (token == null || token.isBlank()) {
            return 0.0;
        }

        String t = token.trim();

        // Find where the numeric part ends (before any SI suffix begins).
        // We do this BEFORE trying Double.parseDouble to avoid misinterpreting
        // Java float literal suffixes such as 'f'/'F' as the SI femto prefix.
        int numEnd = 0;
        while (numEnd < t.length()) {
            char c = t.charAt(numEnd);
            if (Character.isDigit(c) || c == '.' || c == '-' || c == '+') {
                numEnd++;
            } else if ((c == 'e' || c == 'E') && numEnd + 1 < t.length()) {
                // Allow scientific notation exponent (e.g. "1.5E-3")
                char next = t.charAt(numEnd + 1);
                if (Character.isDigit(next) || next == '+' || next == '-') {
                    numEnd++;
                } else {
                    break;
                }
            } else {
                break;
            }
        }

        if (numEnd == 0) {
            return 0.0;
        }

        double base;
        try {
            base = Double.parseDouble(t.substring(0, numEnd));
        } catch (NumberFormatException e) {
            return 0.0;
        }

        String suffix = t.substring(numEnd).toUpperCase(Locale.ROOT);

        // No suffix → return the numeric value directly
        if (suffix.isEmpty()) {
            return base;
        }

        double multiplier = switch (suffix) {
            case "MEG" -> 1e6;
            case "G" -> 1e9;
            case "K" -> 1e3;
            case "M" -> 1e-3;
            case "U", "µ" -> 1e-6;
            case "N" -> 1e-9;
            case "P" -> 1e-12;
            case "F" -> 1e-15;
            // Handle suffixes followed by unit letters (e.g., "1kΩ", "10mH", "100uF")
            default -> switch (suffix.charAt(0)) {
                case 'G' -> 1e9;
                case 'K' -> 1e3;
                case 'M' -> 1e-3;
                case 'U' -> 1e-6;
                case 'N' -> 1e-9;
                case 'P' -> 1e-12;
                case 'F' -> 1e-15;
                default -> 1.0;
            };
        };

        return base * multiplier;
    }

    /**
     * Returns true if the token can be parsed as a numeric value (with or without SI suffix).
     */
    private static boolean isNumeric(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String t = token.trim();
        // Check if first character starts a number
        char first = t.charAt(0);
        return Character.isDigit(first) || first == '-' || first == '+' || first == '.';
    }

    /**
     * Returns true if the token looks like a pure SI-value (digits, optional decimal, optional
     * SI suffix like k/m/u/n/p/f/MEG/G) and is NOT a part-number style string such as
     * "1N4148" (digit + letter-digit mix that could be a model name).
     *
     * <p>A strict numeric token must be entirely consumable by {@link #parseValue} without
     * leaving alphanumeric characters beyond a recognised SI suffix.</p>
     */
    private static boolean isStrictlyNumeric(String token) {
        if (!isNumeric(token)) {
            return false;
        }
        String t = token.trim().toUpperCase(Locale.ROOT);
        // Reject tokens where a letter appears between digits (e.g. "1N4148", "BC547")
        boolean seenDigit = false;
        boolean seenSuffix = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isDigit(c) || c == '.') {
                if (seenSuffix) {
                    // Digit after suffix → looks like part number (e.g. "1N4148")
                    return false;
                }
                seenDigit = true;
            } else if (c == '-' || c == '+') {
                // OK only at the start of the token or after E in scientific notation
                if (i > 0 && t.charAt(i - 1) != 'E') {
                    return false;
                }
            } else if (c == 'E' && seenDigit && i + 1 < t.length()
                    && (Character.isDigit(t.charAt(i + 1))
                    || t.charAt(i + 1) == '+' || t.charAt(i + 1) == '-')) {
                // Scientific notation exponent
            } else if (Character.isLetter(c)) {
                seenSuffix = true;
            }
        }
        return seenDigit;
    }

    /**
     * Exception thrown when a SPICE netlist cannot be parsed.
     */
    public static class SpiceParseException extends Exception {
        public SpiceParseException(String message) {
            super(message);
        }

        public SpiceParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
