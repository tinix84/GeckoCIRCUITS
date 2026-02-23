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
 * Parser for SPICE netlist (.cir) files.
 *
 * <p>Supports the following SPICE element types:</p>
 * <ul>
 *   <li>R – Resistor: {@code R<name> <n+> <n-> <value>}</li>
 *   <li>L – Inductor: {@code L<name> <n+> <n-> <value>}</li>
 *   <li>C – Capacitor: {@code C<name> <n+> <n-> <value>}</li>
 *   <li>V – Voltage source: {@code V<name> <n+> <n-> [DC <value>] [AC <amp> [<phase>]]}</li>
 *   <li>I – Current source: {@code I<name> <n+> <n-> [DC <value>] [AC <amp> [<phase>]]}</li>
 *   <li>D – Diode: {@code D<name> <n+> <n-> <model>}</li>
 * </ul>
 *
 * <p>Simulation control statements supported:</p>
 * <ul>
 *   <li>{@code .tran <step> <stop>} – defines transient simulation parameters</li>
 * </ul>
 *
 * <p>Comments (lines starting with {@code *} or {@code ;}) and continuation
 * lines (starting with {@code +}) are handled.</p>
 *
 * <p>Usage example:</p>
 * <pre>{@code
 * SpiceNetlistParser parser = new SpiceNetlistParser();
 * SpiceNetlist netlist = parser.parse("* RC circuit\nV1 1 0 DC 12\nR1 1 2 1k\nC1 2 0 100u\n.end");
 * }</pre>
 */
public class SpiceNetlistParser {

    /**
     * Parses a SPICE netlist string and returns the resulting {@link SpiceNetlist}.
     *
     * @param content the full text of the SPICE .cir file
     * @return parsed netlist
     * @throws SpiceParseException if the netlist contains unrecoverable syntax errors
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
     * Parses a single element line (R, L, C, V, I, D, …).
     */
    private void parseElementLine(String line, SpiceNetlist netlist) {
        String[] tokens = line.split("\\s+");
        if (tokens.length < 4) {
            return; // Not enough tokens for any element
        }

        char typeChar = Character.toUpperCase(tokens[0].charAt(0));
        String name = tokens[0];

        switch (typeChar) {
            case 'R' -> parsePassive(name, tokens, SpiceComponent.Type.R, netlist);
            case 'L' -> parsePassive(name, tokens, SpiceComponent.Type.L, netlist);
            case 'C' -> parsePassive(name, tokens, SpiceComponent.Type.C, netlist);
            case 'V' -> parseSource(name, tokens, SpiceComponent.Type.V, netlist);
            case 'I' -> parseSource(name, tokens, SpiceComponent.Type.I, netlist);
            case 'D' -> parseDiode(name, tokens, netlist);
            default -> { /* unsupported element type – silently ignored */ }
        }
    }

    /**
     * Parses a passive element (R, L, C):
     * {@code <name> <n+> <n-> <value>}
     */
    private void parsePassive(String name, String[] tokens, SpiceComponent.Type type,
                               SpiceNetlist netlist) {
        if (tokens.length < 4) {
            return;
        }
        String n1 = tokens[1];
        String n2 = tokens[2];
        double value = parseValue(tokens[3]);
        netlist.addComponent(new SpiceComponent(type, name, n1, n2, value,
                SpiceComponent.SourceMode.DC, value, 0.0));
    }

    /**
     * Parses a source element (V, I):
     * {@code <name> <n+> <n-> [DC <dcValue>] [AC <amplitude> [<phase>]]}
     *
     * <p>If neither DC nor AC keyword is present but a numeric value follows the
     * nodes, that value is treated as the DC value.</p>
     */
    private void parseSource(String name, String[] tokens, SpiceComponent.Type type,
                              SpiceNetlist netlist) {
        if (tokens.length < 3) {
            return;
        }
        String n1 = tokens[1];
        String n2 = tokens[2];

        double dcValue = 0.0;
        double acAmplitude = 0.0;
        double acPhase = 0.0;
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
                    acPhase = parseValue(tokens[i + 1]);
                    i++;
                }
            } else if (isNumeric(tok)) {
                // Bare value treated as DC
                dcValue = parseValue(tok);
            }
            i++;
        }

        double primaryValue = (mode == SpiceComponent.SourceMode.AC) ? acAmplitude : dcValue;
        netlist.addComponent(new SpiceComponent(type, name, n1, n2, primaryValue,
                mode, dcValue, acAmplitude));
    }

    /**
     * Parses a diode element (D):
     * {@code <name> <anode> <cathode> [<model>]}
     *
     * <p>Default diode parameters (Vf = 0.6 V) are used unless a model statement
     * provides a forward-voltage override.</p>
     */
    private void parseDiode(String name, String[] tokens, SpiceNetlist netlist) {
        if (tokens.length < 4) {
            return;
        }
        String anode = tokens[1];
        String cathode = tokens[2];
        // Use default diode forward voltage; model name stored in 'name' field for info
        netlist.addComponent(new SpiceComponent(SpiceComponent.Type.D, name, anode, cathode,
                0.6, SpiceComponent.SourceMode.DC, 0.6, 0.0));
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
