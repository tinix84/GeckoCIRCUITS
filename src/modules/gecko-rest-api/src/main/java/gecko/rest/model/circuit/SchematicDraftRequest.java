package gecko.rest.model.circuit;

import gecko.core.io.importer.SchematicDraft;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** A grid drawing to serialize as a circuit file and load into the circuit library. */
public record SchematicDraftRequest(
        @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_. -]*\\.ipes") String filename,
        @Schema(description = "Simulation duration in seconds") double duration,
        @Schema(description = "Simulation time step in seconds") double step,
        @NotNull @Size(min = 1, max = 64) List<SchematicDraft.Part> parts,
        @NotNull @Size(max = 256) List<SchematicDraft.Wire> wires) { }
