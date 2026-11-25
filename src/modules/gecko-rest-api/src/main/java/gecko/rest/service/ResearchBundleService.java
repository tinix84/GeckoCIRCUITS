package gecko.rest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import gecko.rest.model.SimulationRequest;
import org.springframework.beans.factory.annotation.Value;
import gecko.core.io.BuildIdentity;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Exports self-contained simulation inputs and compares results after replay. */
@Service
public class ResearchBundleService {
    private static final String CIRCUIT = "circuit.ipes";
    private static final String REQUEST = "request.json";
    private static final String MANIFEST = "manifest.json";
    private static final String CSV_HEADER = "value\n";
    private static final int MAX_BYTES = 32 * 1024 * 1024;
    private static final int MAX_ENTRIES = 1024;
    private static final double TOLERANCE = 0.01;

    private final SimulationService simulations;
    private final ObjectMapper json;
    private final Validator validator;
    private final String version;
    private final String gitSha;

    public ResearchBundleService(SimulationService simulations, ObjectMapper json, Validator validator,
            @Value("${app.version}") String version,
            @Value("${gecko.build.git-sha:}") String configuredSha) throws IOException {
        this.simulations = simulations;
        this.json = json;
        this.validator = validator;
        this.version = version;
        var properties = new Properties();
        try (var input = BuildIdentity.class.getResourceAsStream("/META-INF/gecko-build.properties")) {
            if (input != null) properties.load(input);
        }
        String packagedSha = properties.getProperty("revision", "");
        String revision = packagedSha.isBlank() ? configuredSha : packagedSha;
        this.gitSha = revision.matches("[a-f0-9]{40}") ? revision : "unknown";
    }

    public byte[] export(String id, String owner, boolean administrator) throws IOException {
        var data = simulations.bundleData(id, owner, administrator);
        List<String> names = data.results().keySet().stream().sorted().toList();
        if (names.size() + 3 > MAX_ENTRIES) throw tooLarge();

        var entries = new LinkedHashMap<String, byte[]>();
        entries.put(CIRCUIT, data.circuit());
        entries.put(REQUEST, json.writeValueAsBytes(data.request()));
        int remaining = MAX_BYTES - entries.get(CIRCUIT).length - entries.get(REQUEST).length;
        for (int index = 0; index < names.size(); index++) {
            byte[] values = csv(data.results().get(names.get(index)), remaining);
            entries.put(csvPath(index), values);
            remaining -= values.length;
        }
        var hashes = new LinkedHashMap<String, String>();
        entries.forEach((name, bytes) -> hashes.put(name, sha256(bytes)));
        byte[] manifest = json.writeValueAsBytes(new Manifest(1, version, gitSha,
                System.getProperty("java.version"), Instant.now(), names, hashes));
        if (manifest.length > remaining) throw tooLarge();
        entries.put(MANIFEST, manifest);

        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    public Verification verify(InputStream input) {
        try {
            int maxArchiveBytes = MAX_BYTES + 512 * 1024;
            byte[] archive = input.readNBytes(maxArchiveBytes + 1);
            if (archive.length > maxArchiveBytes) throw tooLarge();
            Map<String, byte[]> entries = unzip(new ByteArrayInputStream(archive));
            var manifest = json.readValue(required(entries, MANIFEST), Manifest.class);
            if (manifest == null) throw invalid("Invalid bundle manifest");
            List<String> names = manifest.signals();
            if (manifest.formatVersion() != 1 || names == null || !names.contains("time")
                    || names.size() + 3 != entries.size() || new HashSet<>(names).size() != names.size()
                    || names.stream().anyMatch(name -> name == null || name.isBlank())) {
                throw invalid("Invalid bundle manifest");
            }
            entries.remove(MANIFEST);
            if (manifest.sha256() == null || !manifest.sha256().keySet().equals(entries.keySet())) {
                throw invalid("Bundle files do not match the manifest");
            }
            entries.forEach((name, bytes) -> {
                if (!sha256(bytes).equals(manifest.sha256().get(name))) {
                    throw invalid("Checksum mismatch for " + name);
                }
            });
            var request = json.readValue(required(entries, REQUEST), SimulationRequest.class);
            if (request == null || !validator.validate(request).isEmpty()) {
                throw invalid("Invalid simulation request in bundle");
            }
            for (int index = 0; index < names.size(); index++) required(entries, csvPath(index));
            byte[] circuit = unpackCircuit(required(entries, CIRCUIT));
            Map<String, double[]> replay = simulations.runEmbeddedCircuit(circuit, request);
            var deviations = new LinkedHashMap<String, Double>();
            boolean passed = replay.keySet().equals(new TreeSet<>(names));
            for (int index = 0; index < names.size(); index++) {
                String signal = names.get(index);
                Double deviation = deviation(required(entries, csvPath(index)), replay.get(signal),
                        signal.equals("time"));
                deviations.put(signal, deviation);
                passed &= deviation != null && deviation <= TOLERANCE;
            }
            return new Verification(passed, TOLERANCE, deviations);
        } catch (IOException e) {
            throw invalid("Cannot read research bundle");
        }
    }

    private static byte[] csv(double[] samples, int remaining) {
        var csv = new StringBuilder(CSV_HEADER);
        for (double value : samples) {
            csv.append(value).append('\n');
            if (csv.length() > remaining) throw tooLarge();
        }
        if (csv.length() > remaining) throw tooLarge();
        return csv.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /** Divide the maximum absolute error by the peak absolute reference value. */
    private static Double deviation(byte[] bytes, double[] actual, boolean timeAxis) throws IOException {
        try (var csv = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes),
                StandardCharsets.US_ASCII))) {
            if (!"value".equals(csv.readLine())) throw invalid("Invalid result CSV header");
            int count = 0;
            boolean comparable = actual != null;
            double peak = 0;
            double maxError = 0;
            String line;
            while ((line = csv.readLine()) != null) {
                double expected;
                try {
                    expected = Double.parseDouble(line);
                } catch (NumberFormatException e) {
                    throw invalid("Invalid result CSV sample");
                }
                if (!Double.isFinite(expected)) throw invalid("Result CSV has a non-finite sample");
                if (comparable && count < actual.length && Double.isFinite(actual[count])) {
                    peak = Math.max(peak, Math.abs(expected));
                    maxError = Math.max(maxError, Math.abs(actual[count] - expected));
                } else {
                    comparable = false;
                }
                count++;
            }
            if (!comparable || count == 0 || count != actual.length || (timeAxis && maxError != 0)) return null;
            double ratio = maxError == 0 ? 0 : maxError / peak;
            return Double.isFinite(ratio) ? ratio : null;
        }
    }

    private static Map<String, byte[]> unzip(InputStream input) throws IOException {
        int remaining = MAX_BYTES;
        var entries = new LinkedHashMap<String, byte[]>();
        try (var zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entries.size() >= MAX_ENTRIES) throw tooLarge();
                if (!name.equals(CIRCUIT) && !name.equals(REQUEST) && !name.equals(MANIFEST)
                        && !name.matches("results/[0-9]+\\.csv")) {
                    throw invalid("Unexpected file in bundle");
                }
                if (entries.containsKey(name)) throw invalid("Duplicate file in bundle");
                byte[] bytes = zip.readNBytes(remaining + 1);
                if (bytes.length > remaining) throw tooLarge();
                remaining -= bytes.length;
                entries.put(name, bytes);
            }
        }
        return entries;
    }

    static byte[] unpackCircuit(byte[] bytes) throws IOException {
        if (!gzip(bytes)) return bytes;
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            byte[] unpacked = gzip.readNBytes(MAX_BYTES + 1);
            if (unpacked.length > MAX_BYTES) throw tooLarge();
            if (gzip(unpacked)) throw invalid("Unexpected circuit compression");
            return unpacked;
        }
    }

    private static boolean gzip(byte[] bytes) {
        return bytes.length >= 2 && (bytes[0] & 0xff) == 0x1f && (bytes[1] & 0xff) == 0x8b;
    }

    private static byte[] required(Map<String, byte[]> entries, String name) {
        byte[] bytes = entries.get(name);
        if (bytes == null) throw invalid("Missing " + name);
        return bytes;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String csvPath(int index) {
        return "results/" + index + ".csv";
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException tooLarge() {
        return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Research bundle exceeds the size limit");
    }

    private record Manifest(int formatVersion, String geckoVersion, String gitSha, String javaVersion,
                            Instant createdAt, List<String> signals, Map<String, String> sha256) {}

    public record Verification(boolean passed, double tolerance, Map<String, Double> maxRelativeDeviation) {}
}
