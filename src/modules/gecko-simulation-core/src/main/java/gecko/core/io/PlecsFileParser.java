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

import gecko.core.allg.SolverType;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * GUI-free parser for PLECS {@code .plecs} circuit files.
 * Parses the PLECS XML format and converts it to a GeckoCIRCUITS {@link CircuitModel}.
 *
 * <p>PLECS files are XML documents (optionally gzip-compressed) containing circuit
 * components, connections, and simulation settings. This parser maps PLECS block types
 * to the corresponding GeckoCIRCUITS component type IDs.</p>
 *
 * <p>Supported component mappings:</p>
 * <ul>
 *   <li>Resistor → type 1 (resistance)</li>
 *   <li>Inductor → type 2 (inductance)</li>
 *   <li>Capacitor → type 3 (capacitance)</li>
 *   <li>Voltage Source variants → type 4 (amplitude)</li>
 *   <li>Current Source variants → type 5 (amplitude)</li>
 *   <li>Diode → type 6 (forwardVoltage)</li>
 *   <li>Switch/IGBT/MOSFET/Thyristor → type 7 (on-resistance)</li>
 * </ul>
 *
 * <p>Unsupported component types are recorded as conversion warnings.</p>
 *
 * <p>Usage example:</p>
 * <pre>{@code
 * PlecsFileParser parser = new PlecsFileParser();
 * PlecsFileParser.ParseResult result = parser.parse("path/to/circuit.plecs");
 * CircuitModel model = result.model();
 * List<String> warnings = result.warnings();
 * }</pre>
 */
public class PlecsFileParser {

    // ── Component type ID mapping ──────────────────────────────────────────────

    /** Maps lowercase PLECS block type names to GeckoCIRCUITS type IDs. */
    private static final Map<String, Integer> PLECS_TYPE_MAP = buildTypeMap();

    /** Maps GeckoCIRCUITS type ID to the primary parameter key used by CircuitModel. */
    private static final Map<Integer, String> GECKO_PARAM_KEY = Map.of(
            1, "resistance",
            2, "inductance",
            3, "capacitance",
            4, "amplitude",
            5, "amplitude",
            6, "forwardVoltage",
            7, "resistance"
    );

    /**
     * Maps lowercase PLECS parameter names to the primary parameter key
     * used to store the value in {@link CircuitModel.ComponentData}.
     */
    private static final Map<String, String> PLECS_PARAM_NAME_MAP = Map.ofEntries(
            Map.entry("r", "resistance"),
            Map.entry("l", "inductance"),
            Map.entry("c", "capacitance"),
            Map.entry("v", "amplitude"),
            Map.entry("vpeak", "amplitude"),
            Map.entry("vdc", "amplitude"),
            Map.entry("i", "amplitude"),
            Map.entry("ipeak", "amplitude"),
            Map.entry("idc", "amplitude"),
            Map.entry("vf", "forwardVoltage"),
            Map.entry("ron", "resistance"),
            Map.entry("rd", "resistance")
    );

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Parses a PLECS file from the given file path and converts it to a {@link CircuitModel}.
     *
     * @param filePath path to the {@code .plecs} file
     * @return parse result containing the circuit model and any conversion warnings
     * @throws IOException if the file cannot be read
     * @throws PlecsParseException if the XML structure is invalid
     */
    public ParseResult parse(String filePath) throws IOException, PlecsParseException {
        File file = new File(filePath);
        if (!file.exists()) {
            throw new FileNotFoundException("PLECS file not found: " + filePath);
        }

        try (InputStream fis = new FileInputStream(file)) {
            if (isGzipCompressed(file)) {
                try (GZIPInputStream gzis = new GZIPInputStream(fis)) {
                    return parseStream(gzis, filePath);
                }
            } else {
                return parseStream(fis, filePath);
            }
        }
    }

    /**
     * Parses a PLECS file from a byte array.
     *
     * @param bytes     raw bytes of the {@code .plecs} file (may be gzip-compressed)
     * @param sourceName name used in error messages
     * @return parse result containing the circuit model and any conversion warnings
     * @throws IOException if reading fails
     * @throws PlecsParseException if the XML structure is invalid
     */
    public ParseResult parse(byte[] bytes, String sourceName) throws IOException, PlecsParseException {
        if (bytes.length >= 2 && bytes[0] == (byte) 0x1f && bytes[1] == (byte) 0x8b) {
            try (GZIPInputStream gzis = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                return parseStream(gzis, sourceName);
            }
        } else {
            return parseStream(new ByteArrayInputStream(bytes), sourceName);
        }
    }

    /**
     * Parses a PLECS file from an {@link InputStream}.
     *
     * @param inputStream stream containing the {@code .plecs} XML content (uncompressed)
     * @param sourceName  name used in error messages
     * @return parse result containing the circuit model and any conversion warnings
     * @throws IOException if reading fails
     * @throws PlecsParseException if the XML structure is invalid
     */
    public ParseResult parseStream(InputStream inputStream, String sourceName)
            throws IOException, PlecsParseException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            // Disable external entity processing for security
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(inputStream);
            doc.getDocumentElement().normalize();

            return convertDocument(doc, sourceName);
        } catch (PlecsParseException e) {
            throw e;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new PlecsParseException("Failed to parse PLECS file: " + e.getMessage(), e);
        }
    }

    // ── Internal conversion logic ──────────────────────────────────────────────

    private ParseResult convertDocument(Document doc, String sourceName) throws PlecsParseException {
        Element root = doc.getDocumentElement();

        // Accept <PLECS> or <Flecs> as root (legacy format)
        String rootName = root.getNodeName();
        if (!"PLECS".equalsIgnoreCase(rootName) && !"Flecs".equalsIgnoreCase(rootName)) {
            throw new PlecsParseException(
                    "Expected <PLECS> root element, found <" + rootName + ">. "
                    + "Is this a valid .plecs file?");
        }

        CircuitModel model = new CircuitModel();
        model.setFilePath(sourceName);
        List<String> warnings = new ArrayList<>();

        // Parse simulation settings (may appear directly under root or under <Circuit>)
        parseSimulationSettings(doc, model);

        // Parse circuit blocks — look for <Circuit> or directly nested <Blocks>/<Components>
        NodeList circuitNodes = root.getElementsByTagName("Circuit");
        if (circuitNodes.getLength() > 0) {
            Element circuit = (Element) circuitNodes.item(0);
            parseCircuit(circuit, model, warnings);
        } else {
            // Try to parse blocks directly under root
            parseBlocks(root, model, warnings);
            parseLines(root, model);
        }

        // Provide default simulation parameters if not found
        if (model.getTimeStep() <= 0) {
            model.setTimeStep(1e-6);
            warnings.add("No time step found in PLECS file; defaulting to 1e-6 s.");
        }
        if (model.getSimulationDuration() <= 0) {
            model.setSimulationDuration(0.02);
            warnings.add("No stop time found in PLECS file; defaulting to 0.02 s.");
        }

        return new ParseResult(model, Collections.unmodifiableList(warnings));
    }

    private void parseSimulationSettings(Document doc, CircuitModel model) {
        // PLECS 4.x: <Simulation><StopTime> and <TimeStep>
        NodeList simNodes = doc.getElementsByTagName("Simulation");
        for (int i = 0; i < simNodes.getLength(); i++) {
            Element sim = (Element) simNodes.item(i);
            parseSimElement(sim, model);
        }

        // PLECS may also store settings in <SolverOpts>
        NodeList solverNodes = doc.getElementsByTagName("SolverOpts");
        for (int i = 0; i < solverNodes.getLength(); i++) {
            Element opts = (Element) solverNodes.item(i);
            String stopTime = getChildText(opts, "StopTime");
            if (stopTime != null) {
                model.setSimulationDuration(parseDouble(stopTime, model.getSimulationDuration()));
            }
            String dt = getChildText(opts, "MaxStep");
            if (dt != null) {
                model.setTimeStep(parseDouble(dt, model.getTimeStep()));
            }
        }
    }

    private void parseSimElement(Element sim, CircuitModel model) {
        String stopTime = getChildText(sim, "StopTime");
        if (stopTime != null) {
            model.setSimulationDuration(parseDouble(stopTime, model.getSimulationDuration()));
        }
        String dt = getChildText(sim, "TimeStep");
        if (dt != null) {
            model.setTimeStep(parseDouble(dt, model.getTimeStep()));
        }
        String solver = getChildText(sim, "Solver");
        if (solver != null) {
            model.setSolverType(mapSolverType(solver));
        }
    }

    private void parseCircuit(Element circuit, CircuitModel model, List<String> warnings) {
        parseSimElement(circuit, model);
        parseBlocks(circuit, model, warnings);
        parseLines(circuit, model);
    }

    private void parseBlocks(Element parent, CircuitModel model, List<String> warnings) {
        // Try <Blocks> container first, then <Components>
        NodeList containers = parent.getElementsByTagName("Blocks");
        if (containers.getLength() == 0) {
            containers = parent.getElementsByTagName("Components");
        }

        if (containers.getLength() > 0) {
            Element container = (Element) containers.item(0);
            parseBlockList(container, model, warnings);
        } else {
            // Blocks might be direct children
            parseBlockList(parent, model, warnings);
        }
    }

    private void parseBlockList(Element container, CircuitModel model, List<String> warnings) {
        NodeList blocks = container.getElementsByTagName("Block");
        Set<String> unmappedTypes = new LinkedHashSet<>();

        for (int i = 0; i < blocks.getLength(); i++) {
            Element block = (Element) blocks.item(i);

            // Skip nested blocks (subsystems) – only handle direct children
            if (!block.getParentNode().equals(container)) {
                continue;
            }

            String type = block.getAttribute("type").trim();
            String name = block.getAttribute("name").trim();
            if (name.isEmpty()) {
                name = block.getAttribute("Name").trim();
            }

            Integer geckoTypeId = PLECS_TYPE_MAP.get(type.toLowerCase(Locale.ROOT));
            if (geckoTypeId == null) {
                unmappedTypes.add(type);
                continue;  // skip unknown types
            }

            int[] position = parsePosition(block);
            int orientation = parseOrientation(block);

            CircuitModel.ComponentData comp = new CircuitModel.ComponentData(
                    geckoTypeId, name, position[0], position[1], orientation);

            // Extract parameters
            extractParameters(block, geckoTypeId, comp);

            model.addCircuitComponent(comp);
        }

        for (String unmapped : unmappedTypes) {
            warnings.add("Unsupported PLECS block type ignored: '" + unmapped + "'");
        }
    }

    private void parseLines(Element parent, CircuitModel model) {
        // PLECS connections are stored as <Lines>/<Line> elements
        NodeList lineContainers = parent.getElementsByTagName("Lines");
        for (int lc = 0; lc < lineContainers.getLength(); lc++) {
            Element linesEl = (Element) lineContainers.item(lc);
            NodeList lines = linesEl.getElementsByTagName("Line");
            for (int i = 0; i < lines.getLength(); i++) {
                Element line = (Element) lines.item(i);
                CircuitModel.ConnectionData conn = parseConnection(line);
                if (conn != null) {
                    model.addConnection(conn);
                }
            }
        }

        // PLECS may also use <Connections>/<Net> notation
        NodeList connectionContainers = parent.getElementsByTagName("Connections");
        for (int nc = 0; nc < connectionContainers.getLength(); nc++) {
            Element connsEl = (Element) connectionContainers.item(nc);
            NodeList nets = connsEl.getElementsByTagName("Net");
            for (int i = 0; i < nets.getLength(); i++) {
                Element net = (Element) nets.item(i);
                CircuitModel.ConnectionData conn = parseNetConnection(net);
                if (conn != null) {
                    model.addConnection(conn);
                }
            }
        }
    }

    private CircuitModel.ConnectionData parseConnection(Element line) {
        // A <Line> may contain <Segment> child elements with points
        NodeList segments = line.getElementsByTagName("Segment");
        if (segments.getLength() == 0) {
            return null;
        }

        List<int[]> points = new ArrayList<>();
        for (int i = 0; i < segments.getLength(); i++) {
            Element seg = (Element) segments.item(i);
            String text = seg.getTextContent().trim();
            int[] pt = parsePoint(text);
            if (pt != null) {
                points.add(pt);
            }
        }

        if (points.isEmpty()) {
            return null;
        }

        return new CircuitModel.ConnectionData("LK", points.toArray(new int[0][]));
    }

    private CircuitModel.ConnectionData parseNetConnection(Element net) {
        // A <Net> contains <Terminal block="..." port="..."> children
        // We record it as a type-LK connection with no specific points
        NodeList terminals = net.getElementsByTagName("Terminal");
        if (terminals.getLength() < 2) {
            return null;
        }
        // Store as a single zero-point connection (net data stored in labels)
        return new CircuitModel.ConnectionData("LK", new int[0][]);
    }

    // ── Parameter extraction ───────────────────────────────────────────────────

    private void extractParameters(Element block, int geckoTypeId, CircuitModel.ComponentData comp) {
        String primaryKey = GECKO_PARAM_KEY.getOrDefault(geckoTypeId, "value");
        int paramIndex = 0;

        NodeList paramNodes = block.getElementsByTagName("Parameter");
        for (int i = 0; i < paramNodes.getLength(); i++) {
            Element param = (Element) paramNodes.item(i);
            if (!param.getParentNode().equals(block)) {
                continue; // skip nested parameters
            }

            String paramName = param.getAttribute("name").trim();
            // Value may be in "value" attribute or in <Expression> child text
            String valueStr = param.getAttribute("value").trim();
            if (valueStr.isEmpty()) {
                String expr = getChildText(param, "Expression");
                if (expr != null) {
                    valueStr = expr.trim();
                }
            }

            double value = parseDouble(valueStr, Double.NaN);
            if (Double.isNaN(value)) {
                continue;
            }

            // Store by index key (param0, param1, …)
            comp.setParameter("param" + paramIndex, value);
            paramIndex++;

            // Also store with semantic key if name is recognized
            String semanticKey = PLECS_PARAM_NAME_MAP.get(paramName.toLowerCase(Locale.ROOT));
            if (semanticKey != null) {
                comp.setParameter(semanticKey, value);
            }

            // Store the first numeric parameter as the primary key
            if (paramIndex == 1) {
                comp.setParameter(primaryKey, value);
            }
        }

        // If no <Parameter> elements, try inline attributes (older PLECS format)
        if (paramIndex == 0) {
            extractInlineParameters(block, geckoTypeId, comp, primaryKey);
        }
    }

    private void extractInlineParameters(Element block, int geckoTypeId,
                                         CircuitModel.ComponentData comp, String primaryKey) {
        // Older PLECS format stores values as attributes like R="10"
        String[] inlineNames;
        switch (geckoTypeId) {
            case 1 -> inlineNames = new String[]{"R", "r"};
            case 2 -> inlineNames = new String[]{"L", "l"};
            case 3 -> inlineNames = new String[]{"C", "c"};
            case 4 -> inlineNames = new String[]{"V", "v", "Vpeak", "Vdc"};
            case 5 -> inlineNames = new String[]{"I", "i", "Ipeak", "Idc"};
            case 6 -> inlineNames = new String[]{"Vf", "vf"};
            case 7 -> inlineNames = new String[]{"Ron", "ron"};
            default -> inlineNames = new String[]{};
        }

        for (String attr : inlineNames) {
            String val = block.getAttribute(attr);
            if (!val.isEmpty()) {
                double value = parseDouble(val, Double.NaN);
                if (!Double.isNaN(value)) {
                    comp.setParameter("param0", value);
                    comp.setParameter(primaryKey, value);
                    return;
                }
            }
        }
    }

    // ── Position and orientation ───────────────────────────────────────────────

    private int[] parsePosition(Element block) {
        // Try <Position xpos="100" ypos="200"/>
        NodeList posNodes = block.getElementsByTagName("Position");
        if (posNodes.getLength() > 0) {
            Element pos = (Element) posNodes.item(0);
            String xAttr = pos.getAttribute("xpos");
            String yAttr = pos.getAttribute("ypos");
            if (!xAttr.isEmpty() && !yAttr.isEmpty()) {
                return new int[]{
                    (int) parseDouble(xAttr, 0),
                    (int) parseDouble(yAttr, 0)
                };
            }
            // Try plain text "x y" format
            String text = pos.getTextContent().trim();
            int[] pt = parsePoint(text);
            if (pt != null) {
                return pt;
            }
        }
        return new int[]{0, 0};
    }

    private int parseOrientation(Element block) {
        NodeList rotNodes = block.getElementsByTagName("Rotation");
        if (rotNodes.getLength() > 0) {
            String rot = rotNodes.item(0).getTextContent().trim();
            try {
                return Integer.parseInt(rot);
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        return 0;
    }

    // ── Utility helpers ────────────────────────────────────────────────────────

    private String getChildText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() > 0) {
            String text = nodes.item(0).getTextContent();
            if (text != null && !text.isBlank()) {
                return text.trim();
            }
        }
        return null;
    }

    private double parseDouble(String text, double defaultValue) {
        if (text == null || text.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private int[] parsePoint(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String[] parts = text.trim().split("\\s+");
        if (parts.length >= 2) {
            try {
                return new int[]{
                    (int) Double.parseDouble(parts[0]),
                    (int) Double.parseDouble(parts[1])
                };
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        return null;
    }

    private boolean isGzipCompressed(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] sig = new byte[2];
            if (fis.read(sig) < 2) {
                return false;
            }
            return sig[0] == (byte) 0x1f && sig[1] == (byte) 0x8b;
        }
    }

    private SolverType mapSolverType(String solver) {
        return switch (solver.toLowerCase(Locale.ROOT)) {
            case "ode23tb", "trapezoidal", "trz" -> SolverType.SOLVER_TRZ;
            case "ode15s", "gear", "gs" -> SolverType.SOLVER_GS;
            default -> SolverType.SOLVER_BE;
        };
    }

    // ── Component type mapping table ───────────────────────────────────────────

    private static Map<String, Integer> buildTypeMap() {
        Map<String, Integer> map = new HashMap<>();
        // Resistor → 1
        map.put("resistor", 1);
        // Inductor → 2
        map.put("inductor", 2);
        // Capacitor → 3
        map.put("capacitor", 3);
        // Voltage sources → 4
        map.put("voltagesource", 4);
        map.put("voltagesourcedc", 4);
        map.put("voltagesourceac", 4);
        map.put("sinevoltagesource", 4);
        map.put("stepsource", 4);
        map.put("signalgenerator", 4);
        // Current sources → 5
        map.put("currentsource", 5);
        map.put("currentsourcedc", 5);
        map.put("currentsourceac", 5);
        map.put("sinecurrentsource", 5);
        // Diode → 6
        map.put("diode", 6);
        // Switches → 7
        map.put("switch", 7);
        map.put("idealswitch", 7);
        map.put("igbt", 7);
        map.put("mosfet", 7);
        map.put("thyristor", 7);
        map.put("gto", 7);
        map.put("triac", 7);
        map.put("bjt", 7);
        return Collections.unmodifiableMap(map);
    }

    // ── Public result type ─────────────────────────────────────────────────────

    /**
     * Result of parsing a PLECS file, containing the converted {@link CircuitModel}
     * and any warnings about unsupported or partially-converted elements.
     *
     * @param model    the converted circuit model
     * @param warnings list of conversion warnings (unsupported types, missing parameters, etc.)
     */
    public record ParseResult(CircuitModel model, List<String> warnings) {}

    /**
     * Exception thrown when the PLECS file cannot be parsed.
     */
    public static class PlecsParseException extends Exception {
        public PlecsParseException(String message) {
            super(message);
        }

        public PlecsParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
