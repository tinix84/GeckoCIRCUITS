package gecko.rest.service;

import gecko.core.io.CircuitFileParser;
import gecko.core.io.CircuitModel;
import gecko.core.io.IpesFileWriter;
import gecko.core.io.ParameterOverrideApplicator;
import gecko.core.io.SpiceNetlist;
import gecko.core.io.SpiceNetlistParser;
import gecko.rest.model.circuit.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Service for loading and parsing .ipes circuit files.
 * Uses CircuitFileParser and CircuitModel from gecko-simulation-core.
 */
@Service
public class CircuitFileService {

    private final CircuitFileParser parser = new CircuitFileParser();
    private final SpiceNetlistParser spiceParser = new SpiceNetlistParser();
    private final IpesFileWriter ipesWriter = new IpesFileWriter();

    // In-memory storage of parsed circuits (circuit ID -> parsed data)
    private final Map<String, ParsedCircuit> circuits = new ConcurrentHashMap<>();


    /**
     * Load circuit from multipart file upload.
     */
    public CircuitLoadResponse loadCircuit(MultipartFile file) {
        try {
            // Read file content
            byte[] content = file.getBytes();
            String filename = file.getOriginalFilename();

            return loadCircuitFromBytes(content, filename);
        } catch (IOException e) {
            return CircuitLoadResponse.failure(file.getOriginalFilename(),
                    "Failed to read file: " + e.getMessage());
        }
    }

    /**
     * Load circuit from base64 encoded content.
     */
    public CircuitLoadResponse loadCircuit(String base64Content, String filename) {
        try {
            // Decode base64
            byte[] content = Base64.getDecoder().decode(base64Content);
            return loadCircuitFromBytes(content, filename);
        } catch (IllegalArgumentException e) {
            return CircuitLoadResponse.failure(filename,
                    "Invalid base64 encoding: " + e.getMessage());
        }
    }

    /**
     * Get detailed circuit information.
     */
    public CircuitInfo getCircuitInfo(String circuitId) {
        ParsedCircuit parsed = circuits.get(circuitId);
        if (parsed == null) {
            return null;
        }

        CircuitModel model = parsed.model;

        // Build simulation parameters
        CircuitInfo.SimulationParameters simParams = new CircuitInfo.SimulationParameters(
            model.getSimulationDuration(),
            model.getTimeStep(),
            solverTypeToString(model.getSolverType()),
            model.getPreSimulationTime(),
            model.getPreSimulationTimeStep()
        );

        // Build component counts
        CircuitInfo.ComponentCounts counts = new CircuitInfo.ComponentCounts(
            model.getCircuitComponents().size(),
            model.getControlComponents().size(),
            model.getThermalComponents().size(),
            model.getConnections().size()
        );

        // Build display settings
        CircuitInfo.DisplaySettings displaySettings = new CircuitInfo.DisplaySettings(
            model.getWindowWidth() > 0 ? model.getWindowWidth() : null,
            model.getWindowHeight() > 0 ? model.getWindowHeight() : null,
            model.getFontSize()
        );

        // Build metadata
        CircuitInfo.Metadata metadata = new CircuitInfo.Metadata(
            model.getCreationDate(),
            model.getUniqueFileId()
        );

        return new CircuitInfo(
            circuitId,
            parsed.filename,
            model.getFileVersion(),
            simParams,
            counts,
            displaySettings,
            metadata
        );
    }

    /**
     * Get component list for a circuit.
     */
    public ComponentListResponse getComponents(String circuitId) {
        ParsedCircuit parsed = circuits.get(circuitId);
        if (parsed == null) {
            return null;
        }

        CircuitModel model = parsed.model;
        List<ComponentInfo> components = new ArrayList<>();

        // Add circuit components
        for (CircuitModel.ComponentData comp : model.getCircuitComponents()) {
            components.add(componentDataToInfo(comp, "circuit"));
        }

        // Add control components
        for (CircuitModel.ComponentData comp : model.getControlComponents()) {
            components.add(componentDataToInfo(comp, "control"));
        }

        // Add thermal components
        for (CircuitModel.ComponentData comp : model.getThermalComponents()) {
            components.add(componentDataToInfo(comp, "thermal"));
        }

        return new ComponentListResponse(circuitId, components);
    }

    /**
     * Validate a circuit.
     */
    public ValidationResponse validateCircuit(String circuitId) {
        ParsedCircuit parsed = circuits.get(circuitId);
        if (parsed == null) {
            return null;
        }

        CircuitModel model = parsed.model;
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        // Check simulation parameters
        if (!model.hasValidSimulationParameters()) {
            errors.add("Invalid simulation parameters: time step must be positive and less than duration");
        }

        // Note: Component parsing not yet implemented in CircuitFileParser
        // Component count will be 0 for now - this is expected
        if (model.getTotalComponentCount() == 0) {
            warnings.add("Component extraction not yet implemented (circuit file parsed for metadata only)");
        }

        // Check for disconnected components (future enhancement)
        // For now, skip this check since components aren't parsed yet

        if (errors.isEmpty()) {
            return warnings.isEmpty()
                ? ValidationResponse.success()
                : ValidationResponse.successWithWarnings(warnings);
        } else {
            return ValidationResponse.failure(warnings, errors);
        }
    }

    /**
     * Get raw circuit file content (decompressed ASCII).
     */
    public String getRawCircuit(String circuitId) {
        ParsedCircuit parsed = circuits.get(circuitId);
        if (parsed == null) {
            return null;
        }
        return parsed.model.toString(); // CircuitModel doesn't store raw content, return string representation
    }

    /**
     * Delete circuit from memory.
     */
    public boolean deleteCircuit(String circuitId) {
        return circuits.remove(circuitId) != null;
    }

    /**
     * Get all loaded circuits.
     */
    public CircuitListResponse getAllCircuits() {
        List<CircuitListResponse.CircuitSummary> summaries = circuits.entrySet().stream()
            .map(entry -> {
                String id = entry.getKey();
                ParsedCircuit parsed = entry.getValue();
                return new CircuitListResponse.CircuitSummary(
                    id,
                    parsed.filename,
                    parsed.model.getTotalComponentCount(),
                    parsed.loadedAt.toString()
                );
            })
            .collect(Collectors.toList());

        return new CircuitListResponse(summaries, summaries.size());
    }

    /**
     * Clone an existing circuit with optional parameter overrides.
     * Creates a new independent copy that can be modified without affecting the original.
     *
     * @param circuitId the source circuit ID
     * @param overrides optional parameter overrides (dot-notation ComponentName.parameterKey)
     * @return response containing new circuit ID and metadata
     * @throws ResponseStatusException 404 if circuit not found
     */
    public CircuitLoadResponse cloneCircuit(String circuitId, Map<String, Double> overrides) {
        ParsedCircuit sourceParsed = circuits.get(circuitId);
        if (sourceParsed == null) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Circuit not found: " + circuitId
            );
        }

        try {
            // Create a new model by copying key properties from the original
            CircuitModel originalModel = sourceParsed.model;
            CircuitModel newModel = copyCircuitModel(originalModel);

            // Apply parameter overrides if provided
            if (overrides != null && !overrides.isEmpty()) {
                ParameterOverrideApplicator.applyOverrides(newModel, overrides);
            }

            // Generate unique circuit ID
            String newCircuitId = UUID.randomUUID().toString();

            // Create parsed circuit with timestamp (cloned circuit has no raw .ipes bytes, so set to null)
            ParsedCircuit newParsed = new ParsedCircuit(
                sourceParsed.filename,
                newModel,
                Instant.now(),
                null
            );

            // Store in memory
            circuits.put(newCircuitId, newParsed);

            return CircuitLoadResponse.success(newCircuitId, sourceParsed.filename, newModel.getTotalComponentCount());

        } catch (Exception e) {
            throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Failed to clone circuit: " + e.getMessage(),
                e
            );
        }
    }

    /**
     * Update simulation parameters of a loaded circuit.
     * Only provided parameters are updated; null values are ignored.
     *
     * @param circuitId the circuit ID to update
     * @param update parameter update request
     * @return updated circuit info
     * @throws ResponseStatusException 404 if circuit not found
     */
    public CircuitInfo updateCircuitParameters(String circuitId, CircuitParameterUpdate update) {
        ParsedCircuit parsed = circuits.get(circuitId);
        if (parsed == null) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Circuit not found: " + circuitId
            );
        }

        CircuitModel model = parsed.model;

        // Update simulation duration if provided
        if (update.getSimulationDuration() != null) {
            model.setSimulationDuration(update.getSimulationDuration());
        }

        // Update time step if provided
        if (update.getTimeStep() != null) {
            model.setTimeStep(update.getTimeStep());
        }

        // Update solver type if provided
        if (update.getSolverType() != null) {
            gecko.core.allg.SolverType solverType = stringSolverType(update.getSolverType());
            model.setSolverType(solverType);
        }

        return getCircuitInfo(circuitId);
    }

    // ========== Private Helper Methods ==========

    /**
     * Imports a SPICE netlist (.cir) and converts it to a GeckoCIRCUITS circuit.
     *
     * <p>The SPICE text is parsed, converted to .ipes format, then parsed again
     * using {@link CircuitFileParser} so the result is stored identically to any
     * other loaded circuit and supports all existing endpoints.</p>
     *
     * @param spiceContent the raw SPICE netlist text (not Base64)
     * @param filename     original filename (used for the circuit name)
     * @return load response with the new circuit ID, or a failure response
     */
    public CircuitLoadResponse importFromSpice(String spiceContent, String filename) {
        try {
            SpiceNetlist netlist = spiceParser.parse(spiceContent);
            byte[] ipesBytes = ipesWriter.write(netlist);
            String displayName = (filename != null && !filename.isBlank()) ? filename
                    : (netlist.getTitle().isBlank() ? "spice_import.ipes" : netlist.getTitle() + ".ipes");
            return loadCircuitFromBytes(ipesBytes, displayName);
        } catch (SpiceNetlistParser.SpiceParseException e) {
            String name = (filename != null) ? filename : "spice_import.cir";
            return CircuitLoadResponse.failure(name, "SPICE parse error: " + e.getMessage());
        } catch (IOException e) {
            String name = (filename != null) ? filename : "spice_import.cir";
            return CircuitLoadResponse.failure(name, "Failed to generate .ipes: " + e.getMessage());
        }
    }

    /**
     * Exports a loaded circuit as a GeckoCIRCUITS {@code .ipes} file (gzip-compressed bytes).
     *
     * <p>Only circuits that were originally imported from SPICE can be regenerated
     * via this method; circuits loaded from existing .ipes files do not retain the
     * original compressed bytes, so this method re-generates the .ipes from the
     * in-memory {@link CircuitModel}.</p>
     *
     * @param circuitId the circuit ID to export
     * @return the gzip-compressed .ipes file content, or {@code null} if not found
     * @throws ResponseStatusException 500 if serialization fails
     */
    public byte[] exportToIpes(String circuitId) {
        ParsedCircuit parsed = circuits.get(circuitId);
        if (parsed == null) {
            return null;
        }
        // Return the stored raw bytes if available, otherwise indicate unsupported
        byte[] raw = parsed.rawIpesBytes;
        if (raw == null) {
            throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Raw .ipes bytes not available for circuit: " + circuitId
            );
        }
        return raw;
    }

    private CircuitLoadResponse loadCircuitFromBytes(byte[] content, String filename) {
        try {
            // Parse using CircuitFileParser - auto-detect gzip
            CircuitModel model;
            try (ByteArrayInputStream bais = new ByteArrayInputStream(content)) {
                // Check for gzip magic bytes and decompress if needed
                if (content.length >= 2
                        && (content[0] & 0xFF) == 0x1F
                        && (content[1] & 0xFF) == 0x8B) {
                    try (java.util.zip.GZIPInputStream gis =
                                 new java.util.zip.GZIPInputStream(bais)) {
                        model = parser.parse(gis, filename);
                    }
                } else {
                    model = parser.parse(bais, filename);
                }
            }

            // Generate unique circuit ID
            String circuitId = UUID.randomUUID().toString();

            // Create parsed circuit with timestamp; store raw bytes for later download
            ParsedCircuit parsed = new ParsedCircuit(filename, model, Instant.now(), content);

            // Store in memory
            circuits.put(circuitId, parsed);

            return CircuitLoadResponse.success(circuitId, filename, model.getTotalComponentCount());

        } catch (CircuitFileParser.CircuitParseException e) {
            return CircuitLoadResponse.failure(filename,
                    "Failed to parse circuit: " + e.getMessage());
        } catch (IOException e) {
            return CircuitLoadResponse.failure(filename,
                    "Failed to read file: " + e.getMessage());
        } catch (Exception e) {
            return CircuitLoadResponse.failure(filename,
                    "Unexpected error: " + e.getMessage());
        }
    }

    /**
     * Creates a deep copy of a CircuitModel by copying all essential fields.
     * Component lists are copied element-by-element.
     */
    private CircuitModel copyCircuitModel(CircuitModel source) {
        CircuitModel copy = new CircuitModel();

        // Copy simulation parameters
        copy.setSimulationDuration(source.getSimulationDuration());
        copy.setTimeStep(source.getTimeStep());
        copy.setPreSimulationTime(source.getPreSimulationTime());
        copy.setPreSimulationTimeStep(source.getPreSimulationTimeStep());
        copy.setPauseTime(source.getPauseTime());
        copy.setSolverType(source.getSolverType());

        // Copy file metadata
        copy.setFilePath(source.getFilePath());
        copy.setFileVersion(source.getFileVersion());
        copy.setUniqueFileId(source.getUniqueFileId());
        copy.setCreationDate(source.getCreationDate());

        // Copy display settings
        copy.setDisplayPixels(source.getDisplayPixels());
        copy.setFontSize(source.getFontSize());
        copy.setFontType(source.getFontType());
        copy.setWindowWidth(source.getWindowWidth());
        copy.setWindowHeight(source.getWindowHeight());

        // Deep copy component lists (copy each component)
        for (CircuitModel.ComponentData comp : source.getCircuitComponents()) {
            copy.getCircuitComponents().add(copyComponentData(comp));
        }
        for (CircuitModel.ComponentData comp : source.getControlComponents()) {
            copy.getControlComponents().add(copyComponentData(comp));
        }
        for (CircuitModel.ComponentData comp : source.getThermalComponents()) {
            copy.getThermalComponents().add(copyComponentData(comp));
        }

        // Deep copy connections
        for (CircuitModel.ConnectionData conn : source.getConnections()) {
            copy.getConnections().add(copyConnectionData(conn));
        }

        // Copy optimizer parameters
        copy.getOptimizerParameters().putAll(source.getOptimizerParameters());

        // Copy data container signals
        if (source.getDataContainerSignals() != null) {
            copy.setDataContainerSignals(source.getDataContainerSignals().clone());
        }

        // Copy scripting code
        copy.setScripterCode(source.getScripterCode());
        copy.setScripterImports(source.getScripterImports());
        copy.setScripterDeclarations(source.getScripterDeclarations());

        return copy;
    }

    /**
     * Creates a copy of ComponentData with independent parameter map.
     */
    private CircuitModel.ComponentData copyComponentData(CircuitModel.ComponentData source) {
        CircuitModel.ComponentData copy = new CircuitModel.ComponentData(
            source.getType(),
            source.getName(),
            source.getPosition()[0],
            source.getPosition()[1],
            source.getOrientation()
        );

        // Deep copy parameters
        if (source.getParameters() != null) {
            source.getParameters().forEach((key, value) -> copy.setParameter(key, value));
        }

        return copy;
    }

    /**
     * Creates a copy of ConnectionData.
     */
    private CircuitModel.ConnectionData copyConnectionData(CircuitModel.ConnectionData source) {
        int[][] pointsCopy = new int[source.getPoints().length][];
        for (int i = 0; i < source.getPoints().length; i++) {
            pointsCopy[i] = source.getPoints()[i].clone();
        }
        return new CircuitModel.ConnectionData(source.getType(), pointsCopy);
    }

    private ComponentInfo componentDataToInfo(CircuitModel.ComponentData comp, String domain) {
        return new ComponentInfo(
            comp.getType(),
            comp.getName(),
            domain,
            comp.getPosition(),
            comp.getOrientation(),
            comp.getParameters()
        );
    }

    private String solverTypeToString(gecko.core.allg.SolverType solverType) {
        return switch (solverType) {
            case SOLVER_BE -> "backward-euler";
            case SOLVER_TRZ -> "trapezoidal";
            case SOLVER_GS -> "gear-shichman";
        };
    }

    private gecko.core.allg.SolverType stringSolverType(String solverType) {
        if (solverType == null) {
            return gecko.core.allg.SolverType.SOLVER_BE;
        }

        return switch (solverType.toLowerCase()) {
            case "trapezoidal", "trz" -> gecko.core.allg.SolverType.SOLVER_TRZ;
            case "gear-shichman", "gs" -> gecko.core.allg.SolverType.SOLVER_GS;
            default -> gecko.core.allg.SolverType.SOLVER_BE;
        };
    }

    // ========== Internal Data Structure ==========

    private static final class ParsedCircuit {
        final String filename;
        final CircuitModel model;
        final Instant loadedAt;
        /** Raw gzip-compressed .ipes bytes; may be null for circuits loaded from byte arrays. */
        final byte[] rawIpesBytes;

        ParsedCircuit(String filename, CircuitModel model, Instant loadedAt, byte[] rawIpesBytes) {
            this.filename = filename;
            this.model = model;
            this.loadedAt = loadedAt;
            this.rawIpesBytes = rawIpesBytes;
        }
    }
}
