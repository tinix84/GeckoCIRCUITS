package gecko.rest.service;

import gecko.core.io.CircuitModel;
import gecko.core.io.PlecsFileParser;
import gecko.rest.model.circuit.PlecsConversionResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for converting external circuit formats to GeckoCIRCUITS.
 *
 * <p>Currently supports PLECS {@code .plecs} files.
 * Converted circuits are stored in memory and accessible via the
 * standard {@code /api/v1/circuits} endpoints.</p>
 */
@Service
public class ConversionService {

    private final PlecsFileParser plecsParser = new PlecsFileParser();

    /**
     * Shared in-memory circuit store.
     * Key: circuit UUID, Value: circuit model + metadata.
     */
    private final Map<String, StoredCircuit> circuits = new ConcurrentHashMap<>();

    /**
     * Converts an uploaded PLECS {@code .plecs} file to a GeckoCIRCUITS circuit model
     * and stores it in memory for further use.
     *
     * @param file the uploaded {@code .plecs} file
     * @return conversion response containing the circuit ID and any warnings
     */
    public PlecsConversionResponse convertPlecs(MultipartFile file) {
        String filename = file.getOriginalFilename();
        try {
            byte[] bytes = file.getBytes();
            return convertPlecsBytes(bytes, filename);
        } catch (IOException e) {
            return PlecsConversionResponse.failure(filename,
                    "Failed to read uploaded file: " + e.getMessage());
        }
    }

    /**
     * Converts PLECS content provided as raw bytes.
     *
     * @param bytes    raw {@code .plecs} file bytes (may be gzip-compressed)
     * @param filename original filename (used for error messages and metadata)
     * @return conversion response containing the circuit ID and any warnings
     */
    public PlecsConversionResponse convertPlecsBytes(byte[] bytes, String filename) {
        try {
            PlecsFileParser.ParseResult result = plecsParser.parse(bytes, filename);
            CircuitModel model = result.model();
            List<String> warnings = result.warnings();

            String circuitId = UUID.randomUUID().toString();
            circuits.put(circuitId, new StoredCircuit(filename, model, Instant.now()));

            return PlecsConversionResponse.success(
                    circuitId, filename, model.getTotalComponentCount(), warnings);

        } catch (PlecsFileParser.PlecsParseException e) {
            return PlecsConversionResponse.failure(filename,
                    "Failed to parse PLECS file: " + e.getMessage());
        } catch (IOException e) {
            return PlecsConversionResponse.failure(filename,
                    "I/O error while reading PLECS file: " + e.getMessage());
        } catch (Exception e) {
            return PlecsConversionResponse.failure(filename,
                    "Unexpected conversion error: " + e.getMessage());
        }
    }

    /**
     * Returns the stored circuit model for the given circuit ID, or {@code null} if not found.
     */
    public CircuitModel getModel(String circuitId) {
        StoredCircuit stored = circuits.get(circuitId);
        return stored != null ? stored.model() : null;
    }

    /**
     * Internal record for in-memory circuit storage.
     */
    private record StoredCircuit(String filename, CircuitModel model, Instant loadedAt) {}
}
