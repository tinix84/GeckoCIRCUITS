package gecko.core.simulation.application;

import gecko.core.circuit.circuitcomponents.CircuitTypCore;
import gecko.core.circuit.netlist.CircuitNetlist;
import gecko.core.io.CircuitFileParser;
import gecko.core.io.CircuitModel;
import gecko.core.simulation.HeadlessSimulationEngine;
import gecko.core.simulation.SimulationConfig;
import gecko.core.testutil.TraceComparisonHelper;
import gecko.core.testutil.TraceComparisonHelper.PhysicalSignal;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Loads immutable release evidence and maps physical quantities before numerical comparison. */
final class ReleaseTraceFixture {
    static final Set<String> REQUIRED = Set.of("buck_simple", "boost_simple", "buckBoost_simple",
            "cuk_simple", "sepic_simple", "singlePhase_PWM_converter", "thyristor_RL_single",
            "thyristor_freeWheelingDiode", "2phaseDiodeBridge_AC-Inductor", "diode_RL_singlePH_trafo");
    record Column(PhysicalSignal signal, String kind, String component, List<String> terminals,
                  List<String> referenceTerminals) { }
    record Row(double time, double[] values) { }
    record Case(String id, String input, String trace, List<Column> columns, List<Row> rows,
                double relativeTolerance, double clockTolerance, Map<String, Double> absoluteTolerances) {
        @Override public String toString() { return id; }
    }

    private ReleaseTraceFixture() { }

    static List<Case> cases() throws Exception {
        Map<String, Object> manifest = document("/golden/golden-traces.yaml");
        Map<String, Object> comparison = object(manifest.get("comparison"));
        assertEquals(Boolean.TRUE, comparison.get("enabled"), "Release comparison is disabled.");
        assertEquals(Boolean.TRUE, comparison.get("absolute_tolerances_approved"), "The absolute floors require user review.");
        Map<String, Object> reference = object(manifest.get("reference"));
        assertEquals("b34b9b9d8110374097a1fc5839491e66cff5a39a", reference.get("source_commit"));
        Map<String, Object> evidence = document(relative((String) reference.get("evidence_file")));
        Map<String, Object> hashes = object(evidence.get("retained_fixture_hashes"));
        Map<String, Object> accepted = object(object(evidence.get("analysis")).get("circuits"));
        Map<String, Double> floors = new LinkedHashMap<>();
        object(comparison.get("absolute_tolerances")).forEach((unit, value) -> floors.put(unit, number(value)));
        assertEquals(Set.of("V", "A", "ohm"), floors.keySet());
        Map<String, Object> proposals = object(object(evidence.get("analysis")).get("proposed_tolerances"));
        floors.forEach((unit, value) -> assertEquals(number(object(proposals.get(unit)).get("proposed_absolute_tolerance")),
                value.doubleValue(), "The absolute floor must match the reviewed capture proposal."));
        double tolerance = number(object(manifest.get("defaults")).get("tolerance_percent")) / 100;
        assertEquals(.01, tolerance, "The release tolerance must remain 1%.");
        double clock = number(comparison.get("timestamp_absolute_tolerance_s"));
        assertEquals(1e-12, clock);
        List<Case> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Object value : list(manifest.get("circuits"))) {
            Map<String, Object> circuit = object(value);
            String id = (String) circuit.get("id");
            assertTrue(ids.add(id), "Duplicate manifest circuit: " + id);
            String input = "/ipes/v202/" + id + ".ipes";
            assertDigest(input, (String) object(accepted.get(id)).get("input_sha256"));
            String trace = relative((String) object(circuit.get("trace_files")).get("v202"));
            String schema = relative((String) circuit.get("signal_schema"));
            Map<String, Object> expected = object(hashes.get(id));
            assertDigest(trace, (String) expected.get("trace_sha256"));
            assertDigest(schema, (String) expected.get("schema_sha256"));
            List<Column> columns = columns(document(schema));
            List<Row> rows;
            try (var stream = new GZIPInputStream(resource(trace))) {
                rows = readRows(new InputStreamReader(stream, StandardCharsets.UTF_8), columns);
            }
            assertEquals(((Number) object(accepted.get(id)).get("samples")).intValue(), rows.size(), id + " reference step count");
            result.add(new Case(id, input, trace, columns, rows, tolerance, clock, Map.copyOf(floors)));
        }
        assertEquals(REQUIRED, ids, "All ten contract circuits are mandatory.");
        return result;
    }

    static List<Row> readRows(Reader input, List<Column> columns) throws IOException {
        BufferedReader reader = new BufferedReader(input);
        String[] header = Objects.requireNonNull(reader.readLine(), "Missing trace header").split(",", -1);
        List<String> expectedHeader = new ArrayList<>(List.of("time"));
        columns.forEach(column -> expectedHeader.add(column.signal().name()));
        assertEquals(expectedHeader, Arrays.asList(header), "Trace schema/header mismatch");
        List<Row> rows = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null) {
            String[] cells = line.split(",", -1);
            assertEquals(header.length, cells.length, "Truncated or extra trace fields");
            double time = Double.parseDouble(cells[0]);
            assertTrue(Double.isFinite(time), "Nonfinite native time");
            if (!rows.isEmpty()) assertTrue(time > rows.getLast().time(), "Unordered native time");
            double[] values = new double[columns.size()];
            for (int index = 0; index < values.length; index++) {
                values[index] = Double.parseDouble(cells[index + 1]);
                assertTrue(Double.isFinite(values[index]), "Nonfinite reference value");
                if (columns.get(index).signal().discrete()) {
                    assertTrue(values[index] == 0 || values[index] == 1, "Invalid binary reference");
                }
            }
            rows.add(new Row(time, values));
        }
        assertFalse(rows.isEmpty(), "Empty reference trace");
        return rows;
    }

    private static List<Column> columns(Map<String, Object> schema) {
        Set<String> names = new HashSet<>();
        List<Column> result = new ArrayList<>();
        for (Object value : list(schema.get("columns"))) {
            Map<String, Object> item = object(value);
            String kind = (String) item.get("kind");
            String unit = switch (kind) {
                case "node_voltage" -> "V";
                case "component_current", "auxiliary_current" -> "A";
                case "device_resistance" -> "ohm";
                case "gate_command", "conduction_state" -> "1";
                default -> throw new IllegalArgumentException("Unknown physical signal kind: " + kind);
            };
            assertEquals(unit, item.get("unit"));
            String name = (String) item.get("name");
            assertTrue(names.add(name), "Duplicate physical signal identity: " + name);
            boolean discrete = kind.equals("gate_command") || kind.equals("conduction_state");
            List<String> terminals = kind.equals("node_voltage") ? strings(item.get("terminals")) : List.of();
            List<String> reference = kind.equals("node_voltage") ? strings(item.get("reference_terminals")) : List.of();
            String component = (String) item.get("component");
            if (kind.equals("node_voltage")) {
                assertFalse(terminals.isEmpty());
                assertFalse(reference.isEmpty());
            } else {
                assertNotNull(component, "Missing physical component binding");
                assertEquals("XIN:0", item.get("positive_terminal"));
                assertEquals("YOUT:0", item.get("negative_terminal"));
            }
            result.add(new Column(new PhysicalSignal(name, unit, discrete), kind, component, terminals, reference));
        }
        return List.copyOf(result);
    }

    static void compare(Case fixture, int mutateStep, int mutateColumn) throws Exception {
        Path file = Path.of(Objects.requireNonNull(ReleaseTraceFixture.class.getResource(fixture.input())).toURI());
        CircuitModel model = new CircuitFileParser().parse(file.toString());
        HeadlessSimulationEngine engine = new HeadlessSimulationEngine();
        List<PhysicalSignal> signals = fixture.columns().stream().map(Column::signal).toList();
        Mapping[] mapping = new Mapping[1];
        int[] step = {0};
        engine.setStepTraceListener((time, p, currents, netlist) -> {
            int index = step[0]++;
            assertTrue(index < fixture.rows().size(), () -> fixture.id() + " first divergence: divergentStep=" + index
                    + ", nativeTime=" + time + " s, committed step count [steps] limit=0, expected="
                    + fixture.rows().size() + ", actual=" + (index + 1));
            if (mapping[0] == null) mapping[0] = new Mapping(fixture.columns(), model, netlist);
            double[] actual = mapping[0].values(p, currents, netlist);
            Row expected = fixture.rows().get(index);
            double[] reference = expected.values();
            if (index == mutateStep) {
                reference = reference.clone();
                reference[mutateColumn] *= 1.05;
            }
            var result = TraceComparisonHelper.comparePhysicalStep(index, expected.time(), time, signals,
                    reference, actual, fixture.relativeTolerance(), fixture.absoluteTolerances(), fixture.clockTolerance());
            StringJoiner switching = new StringJoiner("; ");
            if (!result.match()) {
                for (int signal = 0; signal < signals.size(); signal++) {
                    if (signals.get(signal).discrete()) switching.add(signals.get(signal).name()
                            + " expected=" + reference[signal] + " actual=" + actual[signal]);
                }
            }
            assertTrue(result.match(), () -> fixture.id() + " first divergence at t=" + time + " s: " + result
                    + "; switching: " + switching);
        });
        var outcome = engine.runSimulation(SimulationConfig.builder().circuitFile(file.toString()).build());
        assertTrue(outcome.isSuccess(), fixture.id() + " execution failed: " + outcome.getErrorMessage());
        assertEquals(fixture.rows().size(), step[0], () -> fixture.id() + " first divergence: divergentStep=" + step[0]
                + ", nativeTime=" + fixture.rows().get(step[0]).time() + " s, committed step count [steps] limit=0, expected="
                + fixture.rows().size() + ", actual=" + step[0]);
    }

    static final class Mapping {
        private final List<Column> columns;
        private final int[] index;
        private final int[] reference;

        Mapping(List<Column> columns, CircuitModel model, CircuitNetlist netlist) {
            this.columns = columns;
            index = new int[columns.size()];
            reference = new int[columns.size()];
            Map<String, Integer> components = new HashMap<>();
            for (var component : model.getCircuitComponents()) {
                int position = netlist.getElementIndexByName(component.getName());
                if (position < 0) continue; // Mutual coupling is represented by its winding rows.
                assertNotNull(component.getSavedBlock(), "A source identity is required.");
                String identity = component.getName() + "@" + component.getSavedBlock().uniqueObjectIdentifier();
                assertNull(components.put(identity, position), "Duplicate saved component identity");
            }
            assertEquals(netlist.getElementCount(), components.size(), "Unmapped Core components");
            Set<Integer> physicalNodes = new HashSet<>();
            Set<Integer> physicalCurrents = new HashSet<>();
            Set<Integer> auxiliary = new HashSet<>();
            Set<Integer> commands = new HashSet<>();
            Set<Integer> states = new HashSet<>();
            Set<Integer> resistances = new HashSet<>();
            for (int columnIndex = 0; columnIndex < columns.size(); columnIndex++) {
                Column column = columns.get(columnIndex);
                if (column.kind().equals("node_voltage")) {
                    index[columnIndex] = node(column.terminals(), components, netlist);
                    reference[columnIndex] = node(column.referenceTerminals(), components, netlist);
                    assertTrue(physicalNodes.add(index[columnIndex]), "Duplicated physical node");
                } else {
                    Integer component = components.get(column.component());
                    assertNotNull(component, "Unmapped physical component: " + column.component());
                    index[columnIndex] = component;
                    switch (column.kind()) {
                        case "component_current" -> assertTrue(physicalCurrents.add(component));
                        case "auxiliary_current" -> {
                            assertTrue(netlist.getVoltageSourceNumber(component) > 0, "Missing MNA current unknown");
                            assertTrue(auxiliary.add(component));
                        }
                        case "gate_command" -> assertTrue(commands.add(component));
                        case "conduction_state" -> assertTrue(states.add(component));
                        case "device_resistance" -> assertTrue(resistances.add(component), "Duplicate resistance binding");
                        default -> { }
                    }
                }
            }
            Set<Integer> connectedNodes = new HashSet<>();
            for (int component = 0; component < netlist.getElementCount(); component++) {
                connectedNodes.add(netlist.getNodeX(component));
                connectedNodes.add(netlist.getNodeY(component));
            }
            // A floating circuit can reserve an unused public ground slot without a physical terminal.
            assertEquals(connectedNodes, physicalNodes, "Incomplete physical node mapping");
            assertEquals(netlist.getElementCount(), physicalCurrents.size(), "Incomplete current mapping");
            assertEquals(netlist.getVoltageSourceMax(), auxiliary.size(), "Incomplete auxiliary-current mapping");
            for (int component = 0; component < netlist.getElementCount(); component++) {
                CircuitTypCore type = netlist.getType(component);
                boolean controlled = type == CircuitTypCore.LK_S || type == CircuitTypCore.LK_MOSFET
                        || type == CircuitTypCore.LK_THYR || type == CircuitTypCore.LK_IGBT;
                assertEquals(controlled, commands.contains(component), "Incomplete command mapping");
                assertEquals(controlled || type == CircuitTypCore.LK_D, states.contains(component), "Incomplete conduction mapping");
                assertEquals(controlled || type == CircuitTypCore.LK_D, resistances.contains(component), "Incomplete resistance mapping");
            }
        }

        private static int node(List<String> terminals, Map<String, Integer> components, CircuitNetlist netlist) {
            Integer node = null;
            for (String terminal : terminals) {
                String[] parts = terminal.split(":", -1);
                assertEquals(3, parts.length, "Invalid terminal identity");
                assertEquals("0", parts[2], "Unsupported terminal port");
                Integer component = components.get(parts[0]);
                assertNotNull(component, "Unmapped terminal component: " + terminal);
                int observed = switch (parts[1]) {
                    case "XIN" -> netlist.getNodeX(component);
                    case "YOUT" -> netlist.getNodeY(component);
                    default -> throw new IllegalArgumentException("Unknown terminal side: " + terminal);
                };
                if (node == null) node = observed;
                else assertEquals(node.intValue(), observed, "Terminal connectivity differs: " + terminals);
            }
            return Objects.requireNonNull(node, "Missing physical terminal group");
        }

        double[] values(double[] p, double[] currents, CircuitNetlist netlist) {
            double[] result = new double[columns.size()];
            for (int columnIndex = 0; columnIndex < result.length; columnIndex++) {
                int component = index[columnIndex];
                Column column = columns.get(columnIndex);
                result[columnIndex] = switch (column.kind()) {
                    case "node_voltage" -> p[component] - p[reference[columnIndex]];
                    case "component_current" -> currents[component];
                    case "auxiliary_current" -> p[netlist.getNodeMax() + netlist.getVoltageSourceNumber(component)];
                    case "device_resistance" -> netlist.getParameter(component)[0];
                    case "gate_command" -> netlist.getConsumedGateCommand(component);
                    case "conduction_state" -> conduction(netlist, component);
                    default -> throw new IllegalArgumentException("Unknown physical signal kind: " + column.kind());
                };
            }
            return result;
        }

        private static double conduction(CircuitNetlist netlist, int component) {
            double[] parameters = netlist.getParameter(component);
            boolean ideal = netlist.getType(component) == CircuitTypCore.LK_S;
            if (parameters[0] == parameters[ideal ? 1 : 2]) return 1;
            assertEquals(parameters[ideal ? 2 : 3], parameters[0], "Unmapped device conduction state");
            return 0;
        }
    }

    static Map<String, Object> document(String name) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        try (InputStream stream = resource(name)) {
            return object(new Yaml(new SafeConstructor(options)).load(stream));
        }
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("Expected a metadata object.");
        return (Map<String, Object>) value;
    }
    private static List<?> list(Object value) {
        if (!(value instanceof List<?> values)) throw new IllegalArgumentException("Expected metadata entries.");
        return values;
    }
    private static List<String> strings(Object value) {
        return list(value).stream().map(item -> (String) item).toList();
    }
    private static double number(Object value) {
        double result = ((Number) value).doubleValue();
        if (!Double.isFinite(result) || result < 0) throw new IllegalArgumentException("Invalid numerical comparison limit.");
        return result;
    }
    private static String relative(String name) { return URI.create("file:/golden/golden-traces.yaml").resolve(name).getPath(); }
    private static InputStream resource(String name) { return Objects.requireNonNull(ReleaseTraceFixture.class.getResourceAsStream(name), name); }
    private static void assertDigest(String name, String expected) throws Exception {
        try (InputStream stream = resource(name)) {
            assertEquals(expected, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes())), name + " integrity");
        }
    }
}
