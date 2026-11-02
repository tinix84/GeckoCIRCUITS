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
package gecko.core.circuit.netlist;

import gecko.core.circuit.circuitcomponents.CircuitTypCore;
import gecko.core.circuit.matrix.StamperRegistry;
import gecko.core.io.CircuitModel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Factory class for building CircuitNetlist from various sources.
 *
 * <p>Bridges the gap between circuit file parsing (CircuitModel) and simulation
 * (CircuitNetlist/INetList). Provides static factory methods to construct netlists
 * with proper initialization of MNA (Modified Nodal Analysis) data structures.</p>
 *
 * <p>This factory is used by the headless simulation engine to convert parsed circuit
 * models into executable netlist representations. Current implementation provides:
 * <ul>
 *   <li>Empty netlist creation for testing matrix solvers</li>
 *   <li>Netlist construction from CircuitModel with topology extraction via label matching</li>
 *   <li>Coordinate-wire resolution when saved terminal labels are missing</li>
 * </ul>
 *
 * @author Phase 3 - Circuit Extraction Refactoring
 * @since v2.18.0
 */
public class NetlistBuilder {

    private NetlistBuilder() {
        // Utility class - not instantiable
    }

    /**
     * Build a simple CircuitNetlist for testing with given dimensions.
     *
     * <p>Creates an empty netlist with the specified size and initializes all arrays
     * with zero/empty values. This is useful for unit testing the matrix solver without
     * needing actual circuit components.</p>
     *
     * <p>The netlist will have:
     * <ul>
     *   <li>nodeCount nodes (numbered 0 to nodeCount-1, plus ground)</li>
     *   <li>voltageSourceCount voltage sources (numbered 1 to voltageSourceCount)</li>
     *   <li>elementCount passive components</li>
     *   <li>All components initialized as RESISTOR type with zero resistance</li>
     *   <li>All nodes connected to ground (node 0)</li>
     * </ul>
     *
     * @param nodeCount number of nodes in circuit (excluding ground, typically >= 1)
     * @param voltageSourceCount number of independent voltage sources (typically >= 0)
     * @param elementCount number of passive components (typically >= 0)
     * @return configured CircuitNetlist with initialized but empty circuit data
     *
     * @throws IllegalArgumentException if any count is negative
     */
    public static CircuitNetlist buildEmpty(
            int nodeCount, int voltageSourceCount, int elementCount) {

        // Validate parameters
        if (nodeCount < 0) {
            throw new IllegalArgumentException("nodeCount must be non-negative, got: " + nodeCount);
        }
        if (voltageSourceCount < 0) {
            throw new IllegalArgumentException("voltageSourceCount must be non-negative, got: " + voltageSourceCount);
        }
        if (elementCount < 0) {
            throw new IllegalArgumentException("elementCount must be non-negative, got: " + elementCount);
        }

        // Create component arrays with default values
        CircuitTypCore[] types = new CircuitTypCore[elementCount];
        int[] nodeX = new int[elementCount];
        int[] nodeY = new int[elementCount];
        int[] voltageSourceNr = new int[elementCount];
        double[][] params = new double[elementCount][];

        // Initialize with defaults
        for (int i = 0; i < elementCount; i++) {
            types[i] = CircuitTypCore.LK_R;  // Default to resistor
            nodeX[i] = 0;                     // Connected to ground
            nodeY[i] = 0;                     // Connected to ground
            voltageSourceNr[i] = -1;          // Not a voltage source
            params[i] = new double[1];        // Single parameter array
            params[i][0] = 0.0;               // Zero resistance
        }

        // Create netlist and initialize
        CircuitNetlist netlist = new CircuitNetlist();
        netlist.initNetlist(types, nodeX, nodeY, voltageSourceNr, params,
                            nodeCount > 0 ? nodeCount - 1 : 0,  // maxNodeIndex
                            voltageSourceCount,                   // maxVoltageSourceIndex
                            elementCount);

        return netlist;
    }

    /**
     * Build a CircuitNetlist from a parsed CircuitModel.
     *
     * <p>This is the main entry point for converting circuit file data into a netlist
     * suitable for simulation. The CircuitModel contains component lists and connection
     * information extracted from .ipes circuit files.</p>
     *
     * <p>Implementation:
     * <ul>
     *   <li>Collects electrical components from the circuit list</li>
     *   <li>Resolves terminal labels and validates connectivity to the reference node</li>
     *   <li>Rejects missing or ambiguous terminal topology</li>
     *   <li>Assigns voltage source numbers to voltage sources and coupable inductors</li>
     *   <li>Extracts component parameters from parsed model</li>
     *   <li>Initializes netlist with proper node and voltage source counts</li>
     * </ul>
     *
     * @param model circuit model from CircuitFileParser, may be null
     * @return netlist with validated topology, or an empty netlist for a null or empty model
     *
     * @see CircuitModel#getCircuitComponents()
     * @see CircuitModel#getControlComponents()
     * @see CircuitModel#getThermalComponents()
     * @see CircuitModel#getTotalComponentCount()
     */
    public static CircuitNetlist buildFromCircuitModel(CircuitModel model) {
        if (model == null) {
            return buildEmpty(0, 0, 0);
        }

        // Collect only LK (circuit) components — control and thermal are handled separately
        List<CircuitModel.ComponentData> allComponents = new ArrayList<>();
        allComponents.addAll(model.getCircuitComponents());

        if (allComponents.isEmpty()) {
            return buildEmpty(0, 0, 0);
        }

        StamperRegistry registry = StamperRegistry.createDefault();
        for (CircuitModel.ComponentData component : allComponents) {
            if (component == null) {
                throw new IllegalArgumentException("Circuit component cannot be null");
            }
            CircuitTypCore type;
            try {
                type = CircuitTypCore.fromTypeNumber(component.getType());
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("Unsupported electrical component '" + component.getName()
                        + "' with type " + component.getType(), error);
            }
            if (!registry.hasStamper(type)) {
                throw new IllegalArgumentException("Unsupported electrical component '" + component.getName()
                        + "' with type " + component.getType() + " (" + type + ")");
            }
        }

        String[][] terminalLabels = new String[allComponents.size()][2];
        boolean missingLabel = false;
        for (int i = 0; i < allComponents.size(); i++) {
            terminalLabels[i][0] = terminalLabel(allComponents.get(i), true);
            terminalLabels[i][1] = terminalLabel(allComponents.get(i), false);
            missingLabel |= terminalLabels[i][0].isEmpty() || terminalLabels[i][1].isEmpty();
        }
        boolean hasElectricalWires = model.getConnections().stream()
                .anyMatch(connection -> "LK".equals(connection.getType()));
        if (hasElectricalWires && missingLabel) {
            terminalLabels = CoordinateTopologyResolver.resolve(allComponents, model.getConnections(), terminalLabels);
        }

        // Keep legacy label-only models working without inferring geometry at default positions.
        boolean hasLabels = allComponents.stream()
                .anyMatch(comp -> comp.getTerminalXLabels().length > 0 || comp.getTerminalYLabels().length > 0);

        if (hasLabels || hasElectricalWires) {
            // Use label-based topology extraction
            return buildFromComponentsWithLabels(allComponents, terminalLabels);
        } else {
            throw new IllegalArgumentException("Component '" + allComponents.get(0).getName()
                    + "' has no terminal topology. Use buildEmpty for explicit preallocation.");
        }
    }

    /**
     * Build netlist from components that have terminal labels (from .ipes file parsing).
     *
     * <p>Extracts topology through label matching:
     * <ul>
     *   <li>Builds a label-to-node mapping</li>
     *   <li>Resolves component connectivity through label matching</li>
     *   <li>Assigns voltage source numbers</li>
     * </ul>
     */
    private static CircuitNetlist buildFromComponentsWithLabels(List<CircuitModel.ComponentData> components,
                                                               String[][] terminalLabels) {
        // Step 1: Build node label → index map
        // Ground reference: label "0" or "" → node 0
        Map<String, Integer> labelToNode = new LinkedHashMap<>();
        labelToNode.put("0", 0);
        labelToNode.put("", 0);  // Empty label also maps to ground
        int nextNode = 1;

        for (String[] labels : terminalLabels) {
            for (String label : labels) {
                if (!labelToNode.containsKey(label)) {
                    labelToNode.put(label, nextNode++);
                }
            }
        }

        int nodeCount = nextNode;
        int elementCount = components.size();

        // Step 2: Assign voltage source numbers
        int voltageSourceCount = 0;
        int[] voltageSourceNumbers = new int[elementCount];
        CircuitTypCore[] types = new CircuitTypCore[elementCount];

        for (int i = 0; i < elementCount; i++) {
            CircuitModel.ComponentData comp = components.get(i);
            CircuitTypCore typ = CircuitTypCore.fromTypeNumber(comp.getType());
            types[i] = typ;

            if (typ == CircuitTypCore.LK_U || typ == CircuitTypCore.LK_LKOP2
                    || typ == CircuitTypCore.TH_TEMP || typ == CircuitTypCore.REL_MMF) {
                voltageSourceNumbers[i] = ++voltageSourceCount;
            } else {
                voltageSourceNumbers[i] = -1;
            }
        }

        // Step 3: Build node arrays and parameter arrays
        int[] nodeX = new int[elementCount];
        int[] nodeY = new int[elementCount];
        double[][] params = new double[elementCount][];

        for (int i = 0; i < elementCount; i++) {
            CircuitModel.ComponentData comp = components.get(i);

            nodeX[i] = labelToNode.get(terminalLabels[i][0]);
            nodeY[i] = labelToNode.get(terminalLabels[i][1]);
            if (nodeX[i] == nodeY[i] && (types[i] == CircuitTypCore.LK_U
                    || types[i] == CircuitTypCore.TH_TEMP || types[i] == CircuitTypCore.REL_MMF)) {
                throw new IllegalArgumentException("Voltage source '" + comp.getName()
                        + "' has both terminals on the same node");
            }

            // Extract numeric parameters — use actual parsed count (some components need 11+)
            int paramCount = 0;
            for (String key : comp.getParameters().keySet()) {
                if (key.startsWith("param")) {
                    try {
                        int idx = Integer.parseInt(key.substring(5));
                        paramCount = Math.max(paramCount, idx + 1);
                    } catch (NumberFormatException ignored) {}
                }
            }
            // Minimum 13 slots to accommodate all component parameter indices
            // (capacitors use [10], switches use [8]/[12])
            paramCount = Math.max(paramCount, 13);
            params[i] = new double[paramCount];

            for (int p = 0; p < paramCount; p++) {
                Object val = comp.getParameters().get("param" + p);
                params[i][p] = (val instanceof Number) ? ((Number) val).doubleValue() : 0.0;
            }
            // Ensure primary value is set (safety)
            if (params[i][0] == 0.0) {
                Object primary = comp.getParameters().get(resolveParameterKey(comp.getType()));
                if (primary instanceof Number) {
                    params[i][0] = ((Number) primary).doubleValue();
                }
            }
        }

        // Step 4: Build component name-to-index map
        Map<String, Integer> nameToIndex = new LinkedHashMap<>();
        for (int i = 0; i < elementCount; i++) {
            String name = components.get(i).getName();
            if (name != null && !name.isEmpty()) {
                if (nameToIndex.putIfAbsent(name, i) != null) {
                    throw new IllegalArgumentException("Duplicate component name '" + name + "'");
                }
            }
        }

        validateGroundConnectivity(components, nodeX, nodeY);

        // Step 5: Initialize and return netlist
        CircuitNetlist netlist = new CircuitNetlist();
        netlist.initNetlist(types, nodeX, nodeY, voltageSourceNumbers, params,
                nodeCount - 1, voltageSourceCount, elementCount);
        netlist.setComponentNameToIndex(nameToIndex);
        return netlist;
    }

    private static String terminalLabel(CircuitModel.ComponentData component, boolean xTerminal) {
        String[] labels = xTerminal ? component.getTerminalXLabels() : component.getTerminalYLabels();
        if (labels.length > 1 || (labels.length == 1 && labels[0] == null)) {
            throw new IllegalArgumentException("Component '" + component.getName()
                    + "' requires one valid label per electrical terminal");
        }
        // Parsed legacy files represent the implicit reference node with an absent label.
        return labels.length == 0 ? "" : normalizeLabel(labels[0]);
    }

    static String normalizeLabel(String label) {
        String trimmed = label.trim();
        return trimmed.equalsIgnoreCase("gnd") ? "0" : trimmed;
    }

    private static void validateGroundConnectivity(List<CircuitModel.ComponentData> components,
                                                    int[] nodeX, int[] nodeY) {
        Map<Integer, List<Integer>> adjacency = new NodeIndexer(nodeX, nodeY).buildNodeToElementsMap();
        Set<Integer> connected = new HashSet<>();
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        connected.add(0);
        pending.add(0);
        while (!pending.isEmpty()) {
            int node = pending.removeFirst();
            for (int element : adjacency.getOrDefault(node, List.of())) {
                CircuitTypCore type = CircuitTypCore.fromTypeNumber(components.get(element).getType());
                if (type == CircuitTypCore.LK_I || type == CircuitTypCore.TH_FLOW) {
                    continue; // An ideal current source adds no voltage constraint or conductance.
                }
                int other = nodeX[element] == node ? nodeY[element] : nodeX[element];
                if (connected.add(other)) {
                    pending.addLast(other);
                }
            }
        }
        for (int element = 0; element < components.size(); element++) {
            if (!connected.contains(nodeX[element]) || !connected.contains(nodeY[element])) {
                throw new IllegalArgumentException("Component '" + components.get(element).getName()
                        + "' is not connected to ground (node 0)");
            }
        }
    }

    private static String resolveParameterKey(int type) {
        return switch (type) {
            case 1 -> "resistance";
            case 2 -> "inductance";
            case 3 -> "capacitance";
            case 4 -> "amplitude";
            case 5 -> "amplitude";
            default -> "value";
        };
    }
}
