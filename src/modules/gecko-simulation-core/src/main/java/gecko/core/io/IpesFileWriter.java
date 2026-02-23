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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/**
 * Writes a {@link SpiceNetlist} as a GeckoCIRCUITS {@code .ipes} file.
 *
 * <p>The generated file uses a simple vertical-component grid layout:</p>
 * <ul>
 *   <li>Components are placed in a horizontal row, each occupying 6 grid units.</li>
 *   <li>Each component is oriented vertically: the positive/anode terminal at the
 *       top (y&nbsp;−&nbsp;2) and the negative/cathode terminal at the bottom (y&nbsp;+&nbsp;2).</li>
 *   <li>A horizontal ground bus wire is generated at the bottom of the layout.</li>
 *   <li>For non-ground nets, a horizontal rail wire is generated at the top.</li>
 * </ul>
 *
 * <p>The output is gzip-compressed UTF-8 text, which is the format expected by
 * GeckoCIRCUITS {@code .ipes} files.</p>
 *
 * <p>Usage example:</p>
 * <pre>{@code
 * SpiceNetlist netlist = new SpiceNetlistParser().parse(cirContent);
 * byte[] ipesBytes = new IpesFileWriter().write(netlist);
 * Files.write(Path.of("circuit.ipes"), ipesBytes);
 * }</pre>
 */
public class IpesFileWriter {

    // Grid spacing constants (in GeckoCIRCUITS grid units)
    private static final int COMPONENT_SPACING = 6;  // horizontal spacing between components
    private static final int COMPONENT_X_START = 6;  // x position of first component
    private static final int COMPONENT_Y = 7;        // y position of component center
    private static final int TERMINAL_HALF_HEIGHT = 2; // half-height of component in grid units
    private static final int Y_TOP = COMPONENT_Y - TERMINAL_HALF_HEIGHT;   // = 5
    private static final int Y_BOTTOM = COMPONENT_Y + TERMINAL_HALF_HEIGHT; // = 9

    // GeckoCIRCUITS orientation codes
    private static final int ORIENT_VERTICAL_DOWN = 503;  // positive terminal at top, negative at bottom

    // GeckoCIRCUITS LK_U source sub-types (stored as first parameter)
    private static final double SOURCE_SUBTYPE_DC = 401.0;
    private static final double SOURCE_SUBTYPE_AC = 402.0;

    // Default AC frequency if not specified in the SPICE netlist
    private static final double DEFAULT_AC_FREQ = 50.0;

    /**
     * Converts a {@link SpiceNetlist} to gzip-compressed {@code .ipes} file bytes.
     *
     * @param netlist the parsed SPICE netlist
     * @return gzip-compressed .ipes file content
     * @throws IOException if writing fails
     */
    public byte[] write(SpiceNetlist netlist) throws IOException {
        String ipesText = generateIpesText(netlist);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(baos);
             Writer writer = new OutputStreamWriter(gzip, StandardCharsets.UTF_8)) {
            writer.write(ipesText);
        }
        return baos.toByteArray();
    }

    /**
     * Generates the plain-text (uncompressed) content of the .ipes file.
     * Exposed for testing without dealing with gzip decompression.
     *
     * @param netlist the parsed SPICE netlist
     * @return the uncompressed .ipes text
     */
    String generateIpesText(SpiceNetlist netlist) {
        List<SpiceComponent> components = netlist.getComponents();
        StringBuilder sb = new StringBuilder(2048);

        // 1. Wire connections
        List<WireSegment> wires = buildWires(components);
        appendWireSection(sb, wires);

        // 2. Circuit elements
        appendElementSection(sb, components);

        // 3. Control elements (none generated from SPICE)
        sb.append("controlANZAHL 0\n");
        sb.append("\n");

        // 4. Simulation parameters (at end of file, as in real .ipes files)
        appendSimulationParameters(sb, netlist);

        return sb.toString();
    }

    // ==================== Wire generation ====================

    /**
     * A simple wire segment: a horizontal line at a given y level between x_start and x_end,
     * with the given node label.
     */
    private record WireSegment(String label, int xStart, int xEnd, int y) {}

    /**
     * Builds wire segments for the circuit:
     * <ul>
     *   <li>One horizontal wire per unique node at y=Y_TOP (for all non-ground nets)</li>
     *   <li>One horizontal ground bus wire at y=Y_BOTTOM spanning all components</li>
     * </ul>
     */
    private List<WireSegment> buildWires(List<SpiceComponent> components) {
        if (components.isEmpty()) {
            return Collections.emptyList();
        }

        // Collect all unique nets and find which x positions connect to each net
        Map<String, List<Integer>> netToXPositions = new LinkedHashMap<>();
        for (int i = 0; i < components.size(); i++) {
            SpiceComponent comp = components.get(i);
            int x = componentX(i);
            addNetX(netToXPositions, comp.getPositiveNode(), x);
            addNetX(netToXPositions, comp.getNegativeNode(), x);
        }

        int xMin = componentX(0) - TERMINAL_HALF_HEIGHT;
        int xMax = componentX(components.size() - 1) + TERMINAL_HALF_HEIGHT;

        List<WireSegment> wires = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> entry : netToXPositions.entrySet()) {
            String net = entry.getKey();
            List<Integer> xPositions = entry.getValue();
            if (xPositions.size() < 2) {
                // A net with only one connection still needs a short stub for visual clarity
                int x = xPositions.get(0);
                int yLevel = groundNet(net) ? Y_BOTTOM : Y_TOP;
                wires.add(new WireSegment(net, x - 1, x + 1, yLevel));
                continue;
            }
            int xWireMin = xPositions.stream().mapToInt(Integer::intValue).min().orElse(xMin);
            int xWireMax = xPositions.stream().mapToInt(Integer::intValue).max().orElse(xMax);
            int yLevel = groundNet(net) ? Y_BOTTOM : Y_TOP;
            wires.add(new WireSegment(net, xWireMin - 1, xWireMax + 1, yLevel));
        }

        return wires;
    }

    private static void addNetX(Map<String, List<Integer>> map, String net, int x) {
        map.computeIfAbsent(net, k -> new ArrayList<>()).add(x);
    }

    /** Returns true if the given node name represents the ground reference. */
    private static boolean groundNet(String net) {
        return "0".equals(net) || "gnd".equalsIgnoreCase(net) || "ground".equalsIgnoreCase(net);
    }

    /** Returns the x grid position for the i-th component. */
    private static int componentX(int index) {
        return COMPONENT_X_START + index * COMPONENT_SPACING;
    }

    // ==================== .ipes text builders ====================

    private void appendWireSection(StringBuilder sb, List<WireSegment> wires) {
        sb.append("verbindungLeistungskreisANZAHL ").append(wires.size()).append('\n');
        for (int i = 0; i < wires.size(); i++) {
            WireSegment wire = wires.get(i);
            sb.append('\n');
            sb.append("verbindungLK (").append(i).append(")\n");
            sb.append("<Verbindung>\n");
            sb.append("label ").append(wire.label()).append('\n');

            // Build the x[] and y[] coordinate arrays (integer grid coordinates)
            int len = wire.xEnd() - wire.xStart() + 1;
            sb.append("x[]");
            for (int x = wire.xStart(); x <= wire.xEnd(); x++) {
                sb.append(' ').append(x);
            }
            sb.append('\n');
            sb.append("y[]");
            for (int j = 0; j < len; j++) {
                sb.append(' ').append(wire.y());
            }
            sb.append('\n');
            sb.append("enabledShorted 1\n");
            sb.append("parentSheetIdentifier 0\n");
            sb.append("connectorType 0\n");
            sb.append("<\\Verbindung>\n");
        }
        sb.append('\n');
    }

    private void appendElementSection(StringBuilder sb, List<SpiceComponent> components) {
        sb.append("elementANZAHL ").append(components.size()).append('\n');
        for (int i = 0; i < components.size(); i++) {
            sb.append('\n');
            sb.append("e (").append(i).append(")\n");
            appendElementBlock(sb, components.get(i), i);
        }
        sb.append('\n');
    }

    private void appendElementBlock(StringBuilder sb, SpiceComponent comp, int index) {
        GeckoElementDictionary dict = comp.getElementType();

        sb.append("<ElementLK>\n");
        sb.append("labelAnfangsKnoten[] /").append(comp.getPositiveNode()).append('\n');
        sb.append("labelEndKnoten[] /").append(comp.getNegativeNode()).append('\n');

        // For multi-terminal elements, additional nodes are appended to the label arrays
        // using the gecko .ipes /node1/node2 syntax
        if (dict == GeckoElementDictionary.TRANS || dict == GeckoElementDictionary.OPAMP) {
            // Secondary/output nodes go to labelEndKnoten
            for (String extra : comp.getExtraNodes()) {
                sb.append("labelEndKnoten[] /").append(extra).append('\n');
            }
        } else if (dict == GeckoElementDictionary.BJT) {
            // Base node is an additional XIN terminal
            for (String extra : comp.getExtraNodes()) {
                sb.append("labelAnfangsKnoten[] /").append(extra).append('\n');
            }
        }

        sb.append("enabledShorted 1\n");
        sb.append("parentSheetIdentifier 0\n");
        sb.append("typ ").append(comp.getGeckoTypeId()).append('\n');
        sb.append("uniqueObjectIdentifier ").append(uniqueId(index)).append('\n');
        sb.append("x ").append(componentX(index)).append('\n');
        sb.append("y ").append(COMPONENT_Y).append('\n');
        sb.append("parameter[]");
        appendParameters(sb, comp);
        sb.append('\n');
        sb.append("parameterString[]");
        appendParameterStrings(sb, comp);
        sb.append('\n');
        sb.append("nameOpt[]");
        appendNameOpt(sb, comp);
        sb.append('\n');
        sb.append("orientierung ").append(ORIENT_VERTICAL_DOWN).append('\n');
        sb.append("idStringDialog ").append(comp.getName()).append('\n');
        sb.append('\n');
        sb.append("<\\ElementLK>\n");
    }

    /**
     * Appends the {@code parameter[]} array for the component.
     *
     * <p>Parameter layout per component type (derived from real .ipes files):</p>
     * <ul>
     *   <li>R:     {@code resistance}</li>
     *   <li>L/Lc:  {@code inductance  initialCurrent}</li>
     *   <li>C:     {@code capacitance initialVoltage}</li>
     *   <li>V/I:   {@code subType amplitude freq phase 0 0.5 0 0 0 0 0}</li>
     *   <li>D:     {@code roff vf ron roff 0 0 0 0 0 0 0 0 1.0}</li>
     *   <li>S:     {@code ron ron roff 0 0 0 0 0 0 0 0 0 1.0}</li>
     *   <li>THYR:  {@code roff vf ron roff 0 0 0 0 0 0 0 0}</li>
     *   <li>IGBT/MOSFET: {@code ron vf roff roff 0 0 -1 -1 1.0 0 0 0 1.0}</li>
     *   <li>K:     {@code k x1 y1 x2 y2 i1 i2} (positions from layout)</li>
     *   <li>TRANS: {@code ratio 1 1 0}</li>
     *   <li>OPAMP: {@code gain Rin Rout freq vlim+ vlim- Ra Rb}</li>
     *   <li>BJT:   {@code beta backBeta Rbase Remit Rcol vf isNpn}</li>
     *   <li>LISN:  {@code (no user params)}</li>
     * </ul>
     */
    private void appendParameters(StringBuilder sb, SpiceComponent comp) {
        switch (comp.getElementType()) {
            case R -> sb.append(' ').append(comp.getValue());
            case L, LC -> sb.append(' ').append(comp.getValue()).append(" 0.0");
            case C -> sb.append(' ').append(comp.getValue()).append(" 0.0");
            case V, I -> appendSourceParameters(sb, comp);
            case D -> {
                double vf = comp.getValue();
                sb.append(" 1.0E7 ").append(vf).append(" 0.01 1.0E7 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 1.0");
            }
            // Ideal switch: ron, ron, roff, state…
            case S -> {
                double ron = comp.getValue();
                sb.append(' ').append(ron)
                  .append(' ').append(ron)
                  .append(" 1.0E7 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 1.0");
            }
            // Thyristor: roff, vf, ron, roff, …
            case THYR -> {
                double vf = comp.getValue2();
                double ron = comp.getValue();
                sb.append(" 1.0E7 ").append(vf).append(' ').append(ron)
                  .append(" 1.0E7 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0");
            }
            // IGBT / MOSFET: ron, vf, roff, roff_sat, x, y, -1 -1 1.0 0 0 0 1.0
            // The third param (0.01) is the saturation resistance (same default as ron)
            case IGBT, MOSFET -> {
                double ron = comp.getValue();
                double vf = comp.getValue2();
                sb.append(' ').append(ron).append(' ').append(vf)
                  .append(' ').append(ron) // saturation resistance = same as ron
                  .append(" 1.0E7 0.0 0.0 -1.0 -1.0 1.0 0.0 0.0 0.0 1.0");
            }
            // Mutual coupling: k, and dummy positions (actual positions come from layout)
            case K -> sb.append(' ').append(comp.getValue()).append(" 0.0 0.0 0.0 0.0 0 0");
            // Transformer: ratio, w1, w2, polarity
            case TRANS -> sb.append(' ').append(comp.getValue()).append(" 1.0 1.0 0.0");
            // Op-amp: gain, Rin, Rout, freq, Vlim+, Vlim-, Ra, Rb
            case OPAMP -> sb.append(" 1.0E6 1.0E6 1.0 1.0E6 1.0E10 -1.0E10 9.0E5 1.0E5");
            // BJT: forwardBeta, backBeta, Rbase, Remit, Rcol, vf, isNpn
            case BJT -> {
                double beta = comp.getValue();
                double isNpn = comp.getValue2();
                sb.append(' ').append(beta)
                  .append(' ').append(beta / 5.0)
                  .append(" 1.0 0.01 0.01 0.6 ").append(isNpn);
            }
            case LISN -> { /* LISN has no user-settable parameters */ }
        }
    }

    private void appendSourceParameters(StringBuilder sb, SpiceComponent comp) {
        boolean isAc = comp.getSourceMode() == SpiceComponent.SourceMode.AC;
        double subType = isAc ? SOURCE_SUBTYPE_AC : SOURCE_SUBTYPE_DC;
        double amplitude = isAc ? comp.getAcAmplitude() : comp.getDcValue();
        sb.append(' ').append(subType)
          .append(' ').append(amplitude)
          .append(' ').append(DEFAULT_AC_FREQ)
          .append(" 0.0 0.0 0.5 0.0 0.0 0.0 0.0 0.0");
    }

    /**
     * Appends the {@code parameterString[]} array.
     *
     * <p>For gate-controlled switches (S, THYR, IGBT, MOSFET) this stores the
     * gate signal reference using the gecko convention {@code /<gateName>/NIX_NIX_NIX/0}.
     * For K (mutual coupling) the two referenced Lc inductor names are stored.</p>
     */
    private void appendParameterStrings(StringBuilder sb, SpiceComponent comp) {
        GeckoElementDictionary dict = comp.getElementType();
        if (dict.hasGateSignal && comp.hasGateSignal()) {
            // Gate-controlled switches: /gateName/NIX_NIX_NIX/0
            sb.append(" /").append(comp.getGateSignal()).append("/NIX_NIX_NIX/0");
        } else if (dict == GeckoElementDictionary.K) {
            // Mutual coupling: /Lc1_name/Lc2_name/1
            sb.append(" /").append(comp.getPositiveNode())
              .append('/').append(comp.getNegativeNode()).append("/1");
        } else {
            sb.append(" /NIX_NIX_NIX/NIX_NIX_NIX/0");
        }
    }

    /**
     * Appends the {@code nameOpt[]} array (all NIX for converted SPICE/Gecko .cir circuits).
     * The count must match the number of user parameters for each type
     * (verified against real .ipes files from the education examples).
     */
    private void appendNameOpt(StringBuilder sb, SpiceComponent comp) {
        int count = switch (comp.getElementType()) {
            case R -> 3;
            case L, LC -> 5;  // L and Lc (typ 2 and 12): 5 nameOpt entries
            case C -> 11;
            case V, I -> 15;
            case D, S, IGBT, MOSFET -> 13;
            case THYR -> 12;
            case K -> 7;
            case TRANS -> 4;
            case OPAMP -> 8;
            case BJT -> 7;
            case LISN -> 0;
        };
        sb.append(" /NIX_NIX_NIX".repeat(count));
    }

    private void appendSimulationParameters(StringBuilder sb, SpiceNetlist netlist) {
        sb.append("DtStor ").append(LocalDate.now()).append('\n');
        sb.append("tDURATION ").append(netlist.getSimulationDuration()).append('\n');
        sb.append("dt ").append(netlist.getTimeStep()).append('\n');
        sb.append("T_pre -1.0\n");
        sb.append("dt_pre ").append(netlist.getTimeStep()).append('\n');
        sb.append("tPAUSE -1.0\n");
        sb.append("solverType 0\n");
        sb.append('\n');
        sb.append("dpix 16\n");
        sb.append("fontSize 12\n");
        sb.append("fontTyp Dialog.plain\n");
        sb.append("fensterWidth 1000\n");
        sb.append("fensterHeight 700\n");
        sb.append("FileVersion 160\n");
        sb.append("UniqueFileId ").append(Math.abs(netlist.getTitle().hashCode())).append('\n');
        sb.append("=======================\n");
    }

    /**
     * Generates a deterministic unique object identifier for the given component index.
     * Uses a large prime offset to reduce the chance of collisions with existing circuits.
     */
    private static int uniqueId(int index) {
        return 100_000_001 + index * 7;
    }
}
