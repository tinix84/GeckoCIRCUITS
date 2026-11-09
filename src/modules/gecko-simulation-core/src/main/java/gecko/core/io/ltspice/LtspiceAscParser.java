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

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser for LTspice .asc schematic files.
 *
 * <p>The LTspice .asc format is a plain-text format with directives such as:
 * <ul>
 *   <li>{@code Version N} – file version number</li>
 *   <li>{@code SHEET W H} – sheet dimensions in pixels</li>
 *   <li>{@code WIRE x1 y1 x2 y2} – wire segment</li>
 *   <li>{@code SYMBOL type x y orientation} – component placement</li>
 *   <li>{@code SYMATTR attribute value} – component attribute (follows SYMBOL)</li>
 *   <li>{@code FLAG x y netname} – net label (name "0" = ground)</li>
 * </ul>
 *
 * <p>Usage example:
 * <pre>{@code
 * LtspiceAscParser parser = new LtspiceAscParser();
 * LtspiceCircuit circuit = parser.parse(new File("circuit.asc"));
 * List<LtspiceComponent> components = circuit.getComponents();
 * }</pre>
 */
public class LtspiceAscParser {

    /**
     * Parses an LTspice .asc file.
     *
     * @param file the .asc file to parse
     * @return parsed circuit model
     * @throws IOException if the file cannot be read
     * @throws AscParseException if the .asc format is invalid
     */
    public LtspiceCircuit parse(File file) throws IOException, AscParseException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            return parse(reader, file.getName());
        }
    }

    /**
     * Parses LTspice .asc content from an InputStream.
     *
     * @param inputStream the stream containing .asc content
     * @param sourceName  name used in error messages (e.g. filename)
     * @return parsed circuit model
     * @throws IOException       if the stream cannot be read
     * @throws AscParseException if the .asc format is invalid
     */
    public LtspiceCircuit parse(InputStream inputStream, String sourceName)
            throws IOException, AscParseException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            return parse(reader, sourceName);
        }
    }

    /**
     * Parses LTspice .asc content from a BufferedReader.
     *
     * @param reader     the reader providing .asc content
     * @param sourceName name used in error messages
     * @return parsed circuit model
     * @throws IOException       if reading fails
     * @throws AscParseException if the .asc format is invalid
     */
    public LtspiceCircuit parse(BufferedReader reader, String sourceName)
            throws IOException, AscParseException {
        List<String> lines = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null) {
            lines.add(line);
        }
        return parseLines(lines, sourceName);
    }

    // -------------------------------------------------------------------------

    private LtspiceCircuit parseLines(List<String> lines, String sourceName)
            throws AscParseException {
        LtspiceCircuit circuit = new LtspiceCircuit();
        circuit.setSourceName(sourceName);

        LtspiceComponent currentComponent = null;

        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            // LTspice .asc files sometimes use Windows line endings – strip \r
            String trimmed = raw.stripTrailing();
            if (trimmed.isEmpty()) {
                continue;
            }

            // Split on whitespace, preserving tokens
            String[] tokens = trimmed.split("\\s+", -1);
            if (tokens.length == 0) {
                continue;
            }

            String directive = tokens[0].toUpperCase();

            switch (directive) {
                case "VERSION" -> {
                    if (tokens.length >= 2) {
                        try {
                            circuit.setVersion(Integer.parseInt(tokens[1]));
                        } catch (NumberFormatException ignored) { /* keep 0 */ }
                    }
                    currentComponent = null;
                }

                case "SHEET" -> {
                    // Format: SHEET <sheetNumber> <width> <height>
                    if (tokens.length >= 4) {
                        try {
                            circuit.setSheetWidth(Integer.parseInt(tokens[2]));
                            circuit.setSheetHeight(Integer.parseInt(tokens[3]));
                        } catch (NumberFormatException ignored) { /* keep 0 */ }
                    }
                    currentComponent = null;
                }

                case "WIRE" -> {
                    if (tokens.length >= 5) {
                        try {
                            int x1 = Integer.parseInt(tokens[1]);
                            int y1 = Integer.parseInt(tokens[2]);
                            int x2 = Integer.parseInt(tokens[3]);
                            int y2 = Integer.parseInt(tokens[4]);
                            circuit.addWire(new LtspiceWire(x1, y1, x2, y2));
                        } catch (NumberFormatException e) {
                            throw new AscParseException("Invalid WIRE at line " + (i + 1)
                                    + ": " + trimmed, e);
                        }
                    }
                    currentComponent = null;
                }

                case "FLAG" -> {
                    if (tokens.length >= 4) {
                        try {
                            int x = Integer.parseInt(tokens[1]);
                            int y = Integer.parseInt(tokens[2]);
                            // Net name may contain spaces in theory, join remaining tokens
                            String netName = joinFrom(tokens, 3);
                            circuit.addFlag(new LtspiceFlag(x, y, netName));
                        } catch (NumberFormatException e) {
                            throw new AscParseException("Invalid FLAG at line " + (i + 1)
                                    + ": " + trimmed, e);
                        }
                    }
                    currentComponent = null;
                }

                case "SYMBOL" -> {
                    if (tokens.length >= 5) {
                        try {
                            // SYMBOL <type> <x> <y> <orientation>
                            String type = tokens[1];
                            int x = Integer.parseInt(tokens[2]);
                            int y = Integer.parseInt(tokens[3]);
                            String orient = tokens[4];

                            currentComponent = new LtspiceComponent();
                            currentComponent.setSymbolType(normalizeSymbolType(type));
                            currentComponent.setX(x);
                            currentComponent.setY(y);
                            currentComponent.setOrientation(orient);
                            circuit.addComponent(currentComponent);
                        } catch (NumberFormatException e) {
                            throw new AscParseException("Invalid SYMBOL at line " + (i + 1)
                                    + ": " + trimmed, e);
                        }
                    }
                }

                case "SYMATTR" -> {
                    // SYMATTR attribute value (applies to the most-recently-seen SYMBOL)
                    if (currentComponent != null && tokens.length >= 3) {
                        String attr = tokens[1];
                        String value = joinFrom(tokens, 2);
                        switch (attr) {
                            case "InstName"   -> currentComponent.setInstName(value);
                            case "Value"      -> currentComponent.setValue(value);
                            case "Value2"     -> currentComponent.setValue2(value);
                            case "SpiceModel" -> currentComponent.setSpiceModel(value);
                            // Other attributes (Prefix, Description, etc.) are ignored
                            default -> { /* skip */ }
                        }
                    }
                    // Note: do NOT reset currentComponent – multiple SYMATTRs follow one SYMBOL
                }

                case "TEXT", "LINE", "RECTANGLE", "CIRCLE", "ARC", "IOPIN" -> {
                    // Graphical / annotation elements – not relevant for circuit topology
                    currentComponent = null;
                }

                default -> {
                    // Unknown or unsupported directive – skip silently
                    if (!directive.startsWith(";") && !directive.startsWith("*")) {
                        currentComponent = null;
                    }
                }
            }
        }

        if (circuit.getComponents().isEmpty() && circuit.getWires().isEmpty()) {
            throw new AscParseException("No circuit elements found in " + sourceName
                    + ". Verify the file is a valid LTspice .asc schematic.");
        }

        return circuit;
    }

    /**
     * Normalises a symbol type path such as "res", "voltage",
     * or a path like "C:\\LTspice\\lib\\sym\\Misc\\res" to a simple lowercase type.
     */
    private static String normalizeSymbolType(String raw) {
        // Strip path separators and take the last segment
        String name = raw.replace('\\', '/');
        int lastSlash = name.lastIndexOf('/');
        if (lastSlash >= 0) {
            name = name.substring(lastSlash + 1);
        }
        return name.toLowerCase();
    }

    /** Joins tokens from startIndex to the end with spaces. */
    private static String joinFrom(String[] tokens, int startIndex) {
        if (startIndex >= tokens.length) {
            return "";
        }
        if (startIndex == tokens.length - 1) {
            return tokens[startIndex];
        }
        StringBuilder sb = new StringBuilder();
        for (int i = startIndex; i < tokens.length; i++) {
            if (i > startIndex) sb.append(' ');
            sb.append(tokens[i]);
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------

    /**
     * Exception thrown when .asc parsing fails.
     */
    public static class AscParseException extends Exception {
        public AscParseException(String message) {
            super(message);
        }

        public AscParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
