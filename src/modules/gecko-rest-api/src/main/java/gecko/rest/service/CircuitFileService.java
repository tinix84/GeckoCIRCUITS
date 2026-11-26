package gecko.rest.service;

import gecko.core.io.CircuitFileParser;
import gecko.core.io.CircuitFileWriter;
import gecko.core.io.importer.SchematicDraft;
import gecko.core.io.CircuitModel;
import gecko.core.preparation.*;
import java.nio.file.Files;
import java.nio.file.Path;
import gecko.rest.config.ApiIdentity;
import gecko.rest.config.ApiKeyProperties;
import gecko.rest.config.SimulationJobLimits;
import gecko.rest.model.circuit.*;
import gecko.rest.persistence.CircuitMetadata;
import gecko.rest.persistence.PostgresPersistence;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
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
    private final CircuitPreparation preparation = new CircuitPreparation();

    private final PostgresPersistence persistence;
    private final ApiKeyProperties keys;
    private final CircuitFileWriter writer = new CircuitFileWriter();

    public CircuitFileService() {
        persistence = null;
        keys = new ApiKeyProperties();
    }

    @Autowired
    public CircuitFileService(ObjectProvider<PostgresPersistence> persistence, ApiKeyProperties keys) {
        this.persistence = persistence.getIfAvailable();
        this.keys = keys;
    }

    // Default storage keeps parsed snapshots in memory.
    private final Map<String, ParsedCircuit> circuits = new ConcurrentHashMap<>();

    public CircuitLoadResponse saveSchematic(SchematicDraftRequest request) {
        try {
            CircuitModel model = SchematicDraft.build(request.parts(), request.wires(), request.duration(), request.step());
            byte[] content = writer.write(model);
            CircuitLoadResponse loaded = loadCircuitFromBytes(content, request.filename());
            if ("failed".equals(loaded.status())) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, loaded.errorMessage());
            }
            return loaded;
        } catch (IllegalArgumentException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, failure.getMessage(), failure);
        } catch (IOException failure) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Cannot serialize circuit", failure);
        }
    }

    public byte[] exportCircuit(String circuitId) {
        ParsedCircuit circuit = findCircuit(circuitId);
        if (circuit == null) throw missingCircuit(circuitId);
        return encode(circuit.snapshot);
    }

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
        ParsedCircuit parsed = findCircuit(circuitId);
        if (parsed == null) {
            return null;
        }

        CircuitModel model = parsed.snapshot.toModel();

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
        ParsedCircuit parsed = findCircuit(circuitId);
        if (parsed == null) {
            return null;
        }

        CircuitModel model = parsed.snapshot.toModel();
        List<ComponentInfo> components = new ArrayList<>();
        List<ConfigurationField> fields = preparation.configurationFields(parsed.snapshot);

        // Add circuit components
        for (CircuitModel.ComponentData comp : model.getCircuitComponents()) {
            components.add(componentDataToInfo(comp, "circuit", fields));
        }

        // Add control components
        for (CircuitModel.ComponentData comp : model.getControlComponents()) {
            components.add(componentDataToInfo(comp, "control", fields));
        }

        // Add thermal components
        for (CircuitModel.ComponentData comp : model.getThermalComponents()) {
            components.add(componentDataToInfo(comp, "thermal", fields));
        }

        List<ConnectionInfo> connections = model.getConnections().stream()
                .map(wire -> new ConnectionInfo(null, null, wire.getLabel(), wire.getType(), wire.getPoints()))
                .toList();
        return new ComponentListResponse(circuitId, components, connections);
    }

    /**
     * Validate a circuit.
     */
    public ValidationResponse validateCircuit(String circuitId) {
        ParsedCircuit parsed = findCircuit(circuitId);
        if (parsed == null) {
            return null;
        }

        return ValidationResponse.fromReport(preparation.prepare(parsed.snapshot, PreparationRequest.defaults()).report());
    }

    /**
     * Get raw circuit file content (decompressed ASCII).
     */
    public String getRawCircuit(String circuitId) {
        ParsedCircuit parsed = findCircuit(circuitId);
        if (parsed == null) {
            return null;
        }
        return parsed.snapshot.toModel().toString(); // CircuitModel doesn't store raw content, return string representation
    }

    /**
     * Delete circuit from memory.
     */
    public boolean deleteCircuit(String circuitId) {
        String tenant = currentTenant();
        if (persistence != null) return persistence.deleteCircuit(circuitId, currentOwner(), tenant);
        ParsedCircuit circuit = circuits.get(circuitId);
        return circuit != null && visible(circuit, tenant) && circuits.remove(circuitId, circuit);
    }

    /**
     * Get all loaded circuits.
     */
    public CircuitListResponse getAllCircuits() {
        String tenant = currentTenant();
        List<CircuitListResponse.CircuitSummary> summaries = (persistence == null
                ? circuits.entrySet().stream().filter(entry -> visible(entry.getValue(), tenant))
                : persistence.circuits(tenant).stream().map(row -> Map.entry(row.id(), parseStored(row))))
            .map(entry -> {
                String id = entry.getKey();
                ParsedCircuit parsed = entry.getValue();
                return new CircuitListResponse.CircuitSummary(
                    id,
                    parsed.filename,
                    parsed.snapshot.toModel().getTotalComponentCount(),
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
        ParsedCircuit sourceParsed = findCircuit(circuitId);
        if (sourceParsed == null) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Circuit not found: " + circuitId
            );
        }

        PreparationResult result = preparation.prepare(sourceParsed.snapshot,
                new PreparationRequest(SolverOverrides.none(), overrides == null ? Map.of() : overrides));
        CircuitSnapshot snapshot = effectiveSnapshot(result);
        String newCircuitId = UUID.randomUUID().toString();
        ParsedCircuit clone = new ParsedCircuit(sourceParsed.filename, snapshot, Instant.now(), currentTenant());
        saveCircuit(newCircuitId, clone, persistence == null ? null : encode(snapshot));
        return CircuitLoadResponse.success(newCircuitId, sourceParsed.filename, snapshot.toModel().getTotalComponentCount());
    }

    /**
     * Update solver settings and numeric component parameters of a loaded circuit.
     * Only provided parameters are updated; null values are ignored.
     *
     * @param circuitId the circuit ID to update
     * @param update parameter update request
     * @return updated circuit info
     * @throws ResponseStatusException 404 if circuit not found
     */
    public CircuitInfo updateCircuitParameters(String circuitId, CircuitParameterUpdate update) {
        String tenant = currentTenant();
        if (persistence == null) {
            circuits.compute(circuitId, (id, current) -> {
                if (current == null || !visible(current, tenant)) throw missingCircuit(id);
                return applyParameterUpdate(id, current, update);
            });
        } else if (!persistence.updateCircuit(circuitId, currentOwner(), tenant, row -> {
            row.replacePayload(encode(applyParameterUpdate(circuitId, parseStored(row), update).snapshot));
            return row;
        })) {
            throw missingCircuit(circuitId);
        }
        return getCircuitInfo(circuitId);
    }

    private ParsedCircuit applyParameterUpdate(String id, ParsedCircuit current, CircuitParameterUpdate update) {
        if (current == null) throw missingCircuit(id);
        PreparationResult result = preparation.prepare(current.snapshot, new PreparationRequest(
                new SolverOverrides(update.getSolverType() == null ? null : parseSolverType(update.getSolverType()),
                        update.getTimeStep(), update.getSimulationDuration()),
                update.getComponentParameters() == null ? Map.of() : update.getComponentParameters()));
        return new ParsedCircuit(current.filename, effectiveSnapshot(result), current.loadedAt, current.tenant);
    }

    private static CircuitPreparationException missingCircuit(String id) {
        return CircuitPreparationException.failure(HttpStatus.NOT_FOUND, "SOURCE_NOT_FOUND",
                Diagnostic.Category.PARSING, "Circuit not found: " + id, "circuitId");
    }

    private ParsedCircuit findCircuit(String id) {
        String tenant = currentTenant();
        if (persistence != null) return persistence.circuit(id, tenant).map(this::parseStored).orElse(null);
        ParsedCircuit circuit = circuits.get(id);
        return circuit != null && visible(circuit, tenant) ? circuit : null;
    }

    private static boolean visible(ParsedCircuit circuit, String tenant) {
        return tenant == null || tenant.equals(circuit.tenant);
    }

    private String currentTenant() {
        return ApiIdentity.requireTenant(keys.isTenantEnabled());
    }

    private ParsedCircuit parseStored(CircuitMetadata row) {
        try (var input = new ByteArrayInputStream(row.payload())) {
            return new ParsedCircuit(row.name(), capture(parser.parse(input, row.name())), row.uploadedAt(), row.tenant());
        } catch (CircuitFileParser.CircuitParseException | IOException e) {
            throw new IllegalStateException("Cannot read stored circuit " + row.id(), e);
        }
    }

    private CircuitSnapshot capture(CircuitModel model) {
        CircuitSnapshot snapshot = preparation.capture(model);
        PreparationResult result = preparation.prepare(snapshot, PreparationRequest.defaults());
        if (result instanceof PreparationResult.Ready ready) return ready.circuit();
        if (result instanceof PreparationResult.Inspectable inspectable) return inspectable.circuit();
        return snapshot;
    }

    private byte[] encode(CircuitSnapshot snapshot) {
        try {
            return writer.write(snapshot.toModel());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Cannot serialize circuit", e);
        }
    }

    private void saveCircuit(String id, ParsedCircuit circuit, byte[] payload) {
        int limit = circuit.tenant == null ? Integer.MAX_VALUE : keys.quotaFor(circuit.tenant).getMaxCircuits();
        if (persistence != null) {
            persistence.addCircuit(new CircuitMetadata(id, circuit.filename, currentOwner(),
                    circuit.tenant, circuit.loadedAt, payload), limit);
        } else {
            synchronized (circuits) {
                if (circuit.tenant != null) {
                    SimulationJobLimits.requireTenantCapacity(circuitCount(circuit.tenant), 1, limit);
                }
                circuits.put(id, circuit);
            }
        }
    }

    public long circuitCount(String tenant) {
        if (persistence != null) return persistence.circuitCount(tenant);
        return circuits.values().stream().filter(circuit -> tenant.equals(circuit.tenant)).count();
    }

    private static String currentOwner() {
        var principal = SecurityContextHolder.getContext().getAuthentication();
        return principal == null || !principal.isAuthenticated() ? null : principal.getName();
    }

    // ========== Private Helper Methods ==========

    private CircuitLoadResponse loadCircuitFromBytes(byte[] content, String filename) {
        try {
            // Parse using CircuitFileParser
            CircuitModel model;
            try (ByteArrayInputStream bais = new ByteArrayInputStream(content)) {
                model = parser.parse(bais, filename);
            }

            CircuitSnapshot snapshot = capture(model);

            // Generate unique circuit ID
            String circuitId = UUID.randomUUID().toString();

            // Create parsed circuit with timestamp
            ParsedCircuit parsed = new ParsedCircuit(
                filename,
                snapshot,
                Instant.now(),
                currentTenant()
            );

            saveCircuit(circuitId, parsed, content);

            return CircuitLoadResponse.success(circuitId, filename, model.getTotalComponentCount());

        } catch (CircuitFileParser.CircuitParseException e) {
            return CircuitLoadResponse.failure(filename,
                    "Failed to parse circuit: " + e.getMessage());
        } catch (IOException e) {
            return CircuitLoadResponse.failure(filename,
                    "Failed to read file: " + e.getMessage());
        } catch (ResponseStatusException | org.springframework.dao.DataAccessException e) {
            throw e;
        } catch (RuntimeException e) {
            return CircuitLoadResponse.failure(filename,
                    "Unexpected error: " + e.getMessage());
        }
    }

    /**
     * Creates a deep copy of a CircuitModel by copying all essential fields.
     * Component lists are copied element-by-element.
     */
    CircuitModel copyCircuitModel(CircuitModel source) {
        return preparation.capture(source).toModel();
    }

    /**
     * Owns captured input independently of later registry changes or filesystem writes.
     *
     * @param snapshot the immutable circuit input.
     * @param stored whether an exact live registry entry supplied the input.
     */
    public record ResolvedSource(CircuitSnapshot snapshot, boolean stored) {}

    /**
     * Captures exactly one selected source without checking Headless readiness.
     * An explicit ID requires an exact live registry entry.
     * A legacy value selects an exact live entry or keeps its complete path meaning.
     * Path input reads its bytes once before parsing.
     *
     * @param circuitId the explicit registry ID, or null when the legacy field selects the source.
     * @param circuitFile the legacy registry ID or complete path, or null when an explicit ID selects the source.
     * @return the owned snapshot and its stored-source classification.
     * @throws CircuitPreparationException for invalid selection, missing input, or malformed captured bytes.
     */
    public ResolvedSource resolveSource(String circuitId, String circuitFile) {
        if ((circuitId == null) == (circuitFile == null)
                || (circuitId != null && circuitId.isBlank()) || (circuitFile != null && circuitFile.isBlank())) {
            throw CircuitPreparationException.failure(HttpStatus.BAD_REQUEST, "INVALID_SOURCE", Diagnostic.Category.CONFIGURATION,
                    "Select exactly one circuit source", null);
        }
        ParsedCircuit stored = findCircuit(circuitId == null ? circuitFile : circuitId);
        if (stored != null) return new ResolvedSource(stored.snapshot, true);
        if (keys.isTenantEnabled() || circuitId != null) {
            String id = circuitId == null ? circuitFile : circuitId;
            throw missingCircuit(id);
        }
        byte[] content;
        try {
            content = Files.readAllBytes(Path.of(circuitFile));
        } catch (java.nio.file.InvalidPathException | IOException e) {
            throw CircuitPreparationException.failure(HttpStatus.NOT_FOUND, "SOURCE_NOT_FOUND", Diagnostic.Category.PARSING,
                    "Cannot read circuit source: " + circuitFile, "circuitFile");
        }
        try (InputStream input = new ByteArrayInputStream(content)) {
            return new ResolvedSource(preparation.capture(parser.parse(input, circuitFile)), false);
        } catch (CircuitFileParser.CircuitParseException | IOException e) {
            throw CircuitPreparationException.failure(HttpStatus.BAD_REQUEST, "MALFORMED_SOURCE", Diagnostic.Category.PARSING,
                    "Cannot parse circuit source", "circuitFile");
        }
    }

    private CircuitSnapshot effectiveSnapshot(PreparationResult result) {
        if (result instanceof PreparationResult.Ready ready) return ready.circuit();
        if (result instanceof PreparationResult.Inspectable inspectable) return inspectable.circuit();
        throw new CircuitPreparationException(HttpStatus.BAD_REQUEST, result.report());
    }

    private ComponentInfo componentDataToInfo(CircuitModel.ComponentData comp, String domain, List<ConfigurationField> fields) {
        String coreDomain = switch (domain) {
            case "circuit" -> "LK";
            case "control" -> "CONTROL";
            default -> "THERMAL";
        };
        return new ComponentInfo(
            comp.getType(),
            comp.getName(),
            domain,
            comp.getPosition(),
            comp.getOrientation(),
            comp.getParameters(),
            fields.stream().filter(field -> field.domain().equals(coreDomain) && field.component().equals(comp.getName())).toList()
        );
    }

    private String solverTypeToString(gecko.core.allg.SolverType solverType) {
        return switch (solverType) {
            case SOLVER_BE -> "backward-euler";
            case SOLVER_TRZ -> "trapezoidal";
            case SOLVER_GS -> "gear-shichman";
        };
    }

    static gecko.core.allg.SolverType parseSolverType(String value) {
        if (value == null) return gecko.core.allg.SolverType.SOLVER_BE;
        return switch (value.toLowerCase(Locale.ROOT).trim()) {
            case "backward-euler", "be" -> gecko.core.allg.SolverType.SOLVER_BE;
            case "trapezoidal", "trz" -> gecko.core.allg.SolverType.SOLVER_TRZ;
            case "gear-shichman", "gear", "gs" -> gecko.core.allg.SolverType.SOLVER_GS;
            default -> throw CircuitPreparationException.failure(HttpStatus.BAD_REQUEST, "INVALID_SETTING",
                    Diagnostic.Category.CONFIGURATION, "Unknown solver type: " + value, "solverType");
        };
    }

    // ========== Internal Data Structure ==========

    private record ParsedCircuit(
        String filename,
        CircuitSnapshot snapshot,
        Instant loadedAt,
        String tenant
    ) {}
}
