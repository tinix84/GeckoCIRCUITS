package gecko.rest.model.circuit;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for importing a SPICE netlist (.cir file) into GeckoCIRCUITS.
 *
 * <p>The SPICE content may be provided either as plain text or Base64-encoded.
 * Use {@code encoded = true} when sending binary or non-ASCII content.</p>
 */
public class SpiceImportRequest {

    @NotBlank(message = "Netlist content must not be blank")
    @Schema(description = "SPICE netlist content: plain text or Base64-encoded string",
            example = "* RC circuit\nV1 1 0 DC 12\nR1 1 2 1k\nC1 2 0 100u\n.tran 1u 10m\n.end")
    private String content;

    @Schema(description = "Original filename (used for the circuit name; optional)",
            example = "my_circuit.cir")
    private String filename;

    @Schema(description = "Set to true when 'content' is Base64-encoded", defaultValue = "false")
    private boolean encoded = false;

    public SpiceImportRequest() { /* Jackson deserialization */ }

    public SpiceImportRequest(String content, String filename, boolean encoded) {
        this.content = content;
        this.filename = filename;
        this.encoded = encoded;
    }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public boolean isEncoded() { return encoded; }
    public void setEncoded(boolean encoded) { this.encoded = encoded; }
}
