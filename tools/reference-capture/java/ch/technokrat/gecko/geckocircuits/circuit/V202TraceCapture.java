package ch.technokrat.gecko.geckocircuits.circuit;

import ch.technokrat.gecko.geckocircuits.circuit.circuitcomponents.AbstractCircuitBlockInterface;
import ch.technokrat.gecko.geckocircuits.circuit.circuitcomponents.CircuitTyp;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/** Observation-only sink, compiled against the pinned v2.02 source. */
public final class V202TraceCapture implements AutoCloseable {
    public static V202TraceCapture active;
    private final Path directory;
    private final PrintWriter csv;
    private final PrintWriter exact;
    private final Map<String, Integer> commands = new TreeMap<>();
    private final List<Column> columns = new ArrayList<>();
    private long steps;
    private double firstTime;
    private double lastTime;

    private static final class Column {
        final String name, unit, kind, binding;
        final int index;
        Column(String name, String unit, String kind, int index, String binding) {
            this.name = name; this.unit = unit; this.kind = kind;
            this.index = index; this.binding = binding;
        }
    }

    public V202TraceCapture(Path directory) throws IOException {
        this.directory = directory;
        Files.createDirectories(directory);
        csv = new PrintWriter(new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(
                Files.newOutputStream(directory.resolve("trace.csv.gz"), StandardOpenOption.CREATE_NEW)),
                StandardCharsets.UTF_8)));
        exact = new PrintWriter(new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(
                Files.newOutputStream(directory.resolve("trace-exact.csv.gz"), StandardOpenOption.CREATE_NEW)),
                StandardCharsets.UTF_8)));
    }

    public static void gate(AbstractCircuitBlockInterface device, double signal) {
        if (active != null) active.commands.put(identity(device), signal > 0.5 ? 1 : 0);
    }

    public static void step(double time, NetListLK netlist, LKMatrices matrix) {
        if (active != null) active.record(time, netlist, matrix);
    }

    private static String identity(AbstractCircuitBlockInterface device) {
        String name = device.getStringID();
        if (name == null || !name.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("Unsupported or missing component identity: " + name);
        }
        return name + "@" + device.getUniqueObjectIdentifier();
    }

    private void initialize(NetListLK nl, LKMatrices matrix) throws IOException {
        Map<Integer, SortedSet<String>> nodes = new TreeMap<>();
        Set<String> devices = new HashSet<>();
        for (int i = 0; i < nl.elementANZAHLneu; i++) {
            String id = identity(nl.eLKneu[i]);
            if (!devices.add(id)) throw new IllegalStateException("Duplicate component: " + id);
            nodes.computeIfAbsent(nl.knotenX[i], n -> new TreeSet<>()).add(id + ":XIN:0");
            nodes.computeIfAbsent(nl.knotenY[i], n -> new TreeSet<>()).add(id + ":YOUT:0");
        }
        if (!nodes.containsKey(0)) throw new IllegalStateException("Numerical datum has no terminal identity.");
        String datum = String.join("|", nodes.get(0));
        for (int node = 0; node <= nl.knotenMAX; node++) {
            if (!nodes.containsKey(node)) throw new IllegalStateException("Unmapped node: " + node);
            String terminals = String.join("|", nodes.get(node));
            columns.add(new Column("V:" + terminals + ":ref=" + datum, "V", "node_voltage", node,
                    "\"terminals\":" + array(nodes.get(node)) + ",\"reference_terminals\":" + array(nodes.get(0))));
        }
        Set<Integer> matrixIndexes = new HashSet<>();
        for (int node = 0; node <= nl.knotenMAX; node++) matrixIndexes.add(node);
        for (int i = 0; i < nl.elementANZAHLneu; i++) {
            String id = identity(nl.eLKneu[i]);
            String binding = "\"component\":" + quote(id) + ",\"positive_terminal\":\"XIN:0\",\"negative_terminal\":\"YOUT:0\"";
            if (nl.spgQuelleNr[i] > 0) {
                int index = nl.knotenMAX + nl.spgQuelleNr[i];
                if (!matrixIndexes.add(index)) throw new IllegalStateException("Duplicate auxiliary index.");
                columns.add(new Column("I_aux:" + id, "A", "auxiliary_current", index, binding));
            }
            columns.add(new Column("I:" + id, "A", "component_current", i, binding));
            CircuitTyp type = nl.typ[i];
            if (type == CircuitTyp.LK_S || type == CircuitTyp.LK_MOSFET || type == CircuitTyp.LK_D
                    || type == CircuitTyp.LK_THYR || type == CircuitTyp.LK_IGBT) {
                columns.add(new Column("R:" + id, "ohm", "device_resistance", i, binding));
                columns.add(new Column("conducting:" + id, "1", "conduction_state", i, binding));
                if (type != CircuitTyp.LK_D) {
                    if (!commands.containsKey(id)) throw new IllegalStateException("Missing consumed command: " + id);
                    columns.add(new Column("command:" + id, "1", "gate_command", i, binding));
                }
            }
        }
        if (matrixIndexes.size() != matrix.p.length) throw new IllegalStateException("Unmapped matrix unknowns.");
        Set<String> names = new HashSet<>();
        StringBuilder header = new StringBuilder("time");
        StringBuilder schema = new StringBuilder("{\"schema_version\":1,\"timestamp\":\"native committed-step time\",\"columns\":[");
        for (Column column : columns) {
            if (!names.add(column.name)) throw new IllegalStateException("Duplicate signal: " + column.name);
            header.append(',').append(column.name);
            if (schema.charAt(schema.length() - 1) != '[') schema.append(',');
            schema.append("{\"name\":").append(quote(column.name)).append(",\"unit\":").append(quote(column.unit))
                    .append(",\"kind\":").append(quote(column.kind)).append(",\"release_index\":").append(column.index)
                    .append(',').append(column.binding).append('}');
        }
        schema.append("]}\n");
        Files.write(directory.resolve("schema.json"), schema.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
        csv.println(header);
        exact.println(header);
    }

    private void record(double time, NetListLK nl, LKMatrices matrix) {
        try {
            if (steps == 0) { initialize(nl, matrix); firstTime = time; }
            if (!Double.isFinite(time) || (steps > 0 && time <= lastTime)) throw new IllegalStateException("Invalid native timestamp.");
            StringBuilder row = new StringBuilder(String.format(Locale.ROOT, "%.12e", time));
            StringBuilder raw = new StringBuilder(Double.toHexString(time));
            for (Column column : columns) {
                double value;
                switch (column.kind) {
                    case "node_voltage": case "auxiliary_current": value = matrix.p[column.index]; break;
                    case "component_current": value = nl.eLKneu[column.index]._currentInAmps; break;
                    case "device_resistance": value = nl.parameter[column.index][0]; break;
                    case "gate_command": value = commands.get(identity(nl.eLKneu[column.index])); break;
                    case "conduction_state": value = conduction(nl, column.index); break;
                    default: throw new IllegalStateException(column.kind);
                }
                if (!Double.isFinite(value)) throw new IllegalStateException("Nonfinite signal: " + column.name);
                row.append(',').append(String.format(Locale.ROOT, "%.12e", value));
                raw.append(',').append(Double.toHexString(value));
            }
            csv.println(row);
            exact.println(raw);
            if (csv.checkError() || exact.checkError()) throw new IOException("Trace write failed.");
            lastTime = time; steps++;
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private static int conduction(NetListLK nl, int i) {
        double[] p = nl.parameter[i];
        int onIndex = nl.typ[i] == CircuitTyp.LK_S ? 1 : 2;
        int offIndex = nl.typ[i] == CircuitTyp.LK_S ? 2 : 3;
        if (p[0] == p[onIndex]) return 1;
        if (p[0] == p[offIndex]) return 0;
        throw new IllegalStateException("Device state is neither ON nor OFF: " + identity(nl.eLKneu[i]));
    }

    private static String array(Collection<String> values) {
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        for (String value : values) joiner.add(quote(value));
        return joiner.toString();
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Override public void close() throws IOException {
        csv.close();
        exact.close();
        if (csv.checkError() || exact.checkError()) throw new IOException("Trace close failed.");
        String summary = String.format(Locale.ROOT, "{\"steps\":%d,\"first_time\":%.17g,\"last_time\":%.17g}\n", steps, firstTime, lastTime);
        Files.write(directory.resolve("capture.json"), summary.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
    }
}
