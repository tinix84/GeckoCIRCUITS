package gecko.core.io.importer;

import gecko.core.io.CircuitModel;
import gecko.core.io.CircuitModel.ComponentData;
import gecko.core.io.CircuitModel.ConnectionData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Creates native circuit data from a grid drawing with explicit terminal connections. */
public final class SchematicDraft {
    private static final int MAX_PARTS = 64;
    private static final int MAX_WIRES = 256;
    private static final int VERTICAL_ORIENTATION = 503;

    private SchematicDraft() { }

    public enum Kind {
        RESISTOR(1, 2), INDUCTOR(2, 2), CAPACITOR(3, 2), VOLTAGE_SOURCE(4, 2), CURRENT_SOURCE(5, 2),
        DIODE(6, 2), SWITCH(7, 2), TRANSFORMER(23, 4), GROUND(0, 1);

        private final int type;
        private final int ports;

        Kind(int type, int ports) {
            this.type = type;
            this.ports = ports;
        }

        int[] position(Part part, int port) {
            if (this == GROUND) return new int[]{part.x, part.y};
            if (this == TRANSFORMER) {
                return new int[]{part.x + (port < 2 ? -1 : 1), part.y + (port % 2 == 0 ? 2 : -2)};
            }
            return new int[]{part.x, part.y + (port == 0 ? -2 : 2)};
        }
    }

    public record Part(Kind kind, String name, int x, int y, double value) { }
    public record Terminal(String part, int port) { }
    public record Wire(Terminal from, Terminal to) { }
    private record GridPoint(int x, int y) { }

    public static CircuitModel build(List<Part> parts, List<Wire> wires, double duration, double step) {
        if (parts == null || parts.isEmpty() || parts.size() > MAX_PARTS
                || wires == null || wires.size() > MAX_WIRES) {
            throw new IllegalArgumentException("Require 1 to 64 parts and at most 256 wires.");
        }
        if (!Double.isFinite(duration) || !Double.isFinite(step) || step <= 0 || duration < step
                || duration / step > 2_000_000) {
            throw new IllegalArgumentException("Use a positive time step and at most two million simulation steps.");
        }

        Map<String, Part> namedParts = new LinkedHashMap<>();
        Map<Terminal, Terminal> roots = new LinkedHashMap<>();
        for (Part part : parts) {
            validatePart(part);
            if (namedParts.putIfAbsent(part.name, part) != null) {
                throw new IllegalArgumentException("Use a unique name for each part.");
            }
            for (int port = 0; port < part.kind.ports; port++) {
                var terminal = new Terminal(part.name, port);
                roots.put(terminal, terminal);
            }
        }
        if (namedParts.values().stream().noneMatch(part -> part.kind != Kind.GROUND)) {
            throw new IllegalArgumentException("Add at least one electrical component.");
        }

        for (Wire wire : wires) {
            if (wire == null || wire.from == null || wire.to == null || !roots.containsKey(wire.from)
                    || !roots.containsKey(wire.to) || wire.from.equals(wire.to)) {
                throw new IllegalArgumentException("Connect two different terminals that exist in the drawing.");
            }
            roots.put(root(roots, wire.from), root(roots, wire.to));
        }

        Map<Terminal, String> nodes = new LinkedHashMap<>();
        for (Part part : parts) {
            if (part.kind == Kind.GROUND) nodes.put(root(roots, new Terminal(part.name, 0)), "0");
        }
        if (nodes.isEmpty()) throw new IllegalArgumentException("Add a ground to the drawing.");
        int nextNode = 1;
        for (Terminal terminal : roots.keySet()) {
            Terminal root = root(roots, terminal);
            if (!nodes.containsKey(root)) nodes.put(root, "n" + nextNode++);
        }

        Map<GridPoint, String> layout = new HashMap<>();
        for (Terminal terminal : roots.keySet()) {
            Part part = namedParts.get(terminal.part);
            claimPoint(layout, part.kind.position(part, terminal.port), nodes.get(root(roots, terminal)));
        }

        CircuitModel model = new CircuitModel();
        model.setSimulationDuration(duration);
        model.setTimeStep(step);
        model.setPreSimulationTimeStep(step);
        for (Part part : parts) {
            if (part.kind == Kind.GROUND) continue;
            List<String> labels = new ArrayList<>(part.kind.ports);
            for (int port = 0; port < part.kind.ports; port++) {
                labels.add(nodes.get(root(roots, new Terminal(part.name, port))));
            }
            addPart(model, part, labels);
        }
        for (Wire wire : wires) {
            String label = nodes.get(root(roots, wire.from));
            int[] from = namedParts.get(wire.from.part).kind.position(namedParts.get(wire.from.part), wire.from.port);
            int[] to = namedParts.get(wire.to.part).kind.position(namedParts.get(wire.to.part), wire.to.port);
            int[][] points = from[0] == to[0] || from[1] == to[1]
                    ? new int[][]{from, to}
                    : new int[][]{from, new int[]{to[0], from[1]}, to};
            for (int[] point : points) claimPoint(layout, point, label);
            model.addConnection(new ConnectionData("LK", points, label));
        }

        Map<String, String> probeNodes = new LinkedHashMap<>();
        nodes.values().forEach(node -> probeNodes.put(node, node));
        SpiceImporter.addScope(model, probeNodes);
        model.setDataContainerSignals(probeNodes.keySet().stream().filter(node -> !node.equals("0"))
                .map(node -> "V_" + node).toArray(String[]::new));
        return model;
    }

    private static void claimPoint(Map<GridPoint, String> layout, int[] point, String node) {
        String previous = layout.putIfAbsent(new GridPoint(point[0], point[1]), node);
        if (previous != null && !previous.equals(node)) {
            throw new IllegalArgumentException("Move a part to separate wires or terminals that share a grid point.");
        }
    }

    private static Terminal root(Map<Terminal, Terminal> parents, Terminal start) {
        Terminal terminal = start;
        while (!terminal.equals(parents.get(terminal))) terminal = parents.get(terminal);
        return terminal;
    }

    private static void validatePart(Part part) {
        if (part == null || part.kind == null || part.name == null
                || !part.name.matches("[A-Za-z][A-Za-z0-9_.-]{0,39}")
                || part.x < 0 || part.x > 200 || part.y < 0 || part.y > 200
                || !Double.isFinite(part.value)) {
            throw new IllegalArgumentException("Use valid part names, grid positions, and numeric values.");
        }
        switch (part.kind) {
            case RESISTOR, INDUCTOR, CAPACITOR, TRANSFORMER -> {
                if (part.value <= 0) throw new IllegalArgumentException("Use a positive value for " + part.name + ".");
            }
            case SWITCH -> {
                if (part.value != 0 && part.value != 1) throw new IllegalArgumentException("Use 0 or 1 for a switch state.");
            }
            default -> { }
        }
    }

    private static void addPart(CircuitModel model, Part part, List<String> labels) {
        var data = new ComponentData(part.kind.type, part.name, part.x, part.y, VERTICAL_ORIENTATION);
        data.setParameterStrings(new String[]{"", "", "0"});
        data.getImportTokens().put("uniqueObjectIdentifier", Integer.toString(10000 + model.getCircuitComponents().size()));
        int split = labels.size() / 2;
        data.setTerminalXLabels(labels.subList(0, split).toArray(String[]::new));
        data.setTerminalYLabels(labels.subList(split, labels.size()).toArray(String[]::new));
        switch (part.kind) {
            case RESISTOR -> data.setNumericParameters(new double[]{part.value, 0, 0});
            case INDUCTOR -> data.setNumericParameters(new double[]{part.value, 0, 0, 0, 0});
            case CAPACITOR -> data.setNumericParameters(new double[]{part.value, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
            case VOLTAGE_SOURCE, CURRENT_SOURCE -> SpiceImporter.source(data, Double.toString(part.value), model, Map.of());
            case DIODE -> data.setNumericParameters(new double[]{1e12, 0, 1e-6, 1e12, 0, 0, 0, 0, 0, 0, 0, 0, 1});
            case SWITCH -> {
                data.setNumericParameters(new double[]{1e12, 1e-6, 1e12, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1});
                ComponentData state = SpiceImporter.control(model, 3, "STATE." + part.name, new double[]{part.value}, new String[0]);
                ComponentData gate = SpiceImporter.control(model, 6, "GATE." + part.name, new double[]{1}, state.getTerminalYLabels());
                gate.setTerminalYLabels(new String[0]);
                gate.setParameterStrings(new String[]{part.name, "", "0"});
                data.setParameterStrings(new String[]{gate.getName(), "", "0"});
            }
            case TRANSFORMER -> data.setNumericParameters(new double[]{part.value, 1, -1});
            case GROUND -> throw new IllegalArgumentException("Ground is a connection, not a component.");
        }
        model.addCircuitComponent(data);
    }
}
