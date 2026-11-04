package gecko.rest.model.circuit;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Response returned by the PLECS-to-GeckoCIRCUITS conversion endpoint.
 *
 * <p>On success, {@link #circuitId()} can be used with the standard
 * {@code /api/v1/circuits} endpoints for further processing.</p>
 */
@Schema(description = "Result of converting a PLECS file to GeckoCIRCUITS format")
public record PlecsConversionResponse(

    @Schema(description = "Unique circuit identifier (null when conversion failed)",
            example = "123e4567-e89b-12d3-a456-426614174000")
    String circuitId,

    @Schema(description = "Conversion status: 'converted' or 'failed'", example = "converted")
    String status,

    @Schema(description = "Original PLECS filename", example = "buck_converter.plecs")
    String filename,

    @Schema(description = "Number of circuit components successfully converted", example = "7")
    int componentCount,

    @Schema(description = "Conversion warnings for unsupported or partially-converted elements")
    List<String> warnings,

    @Schema(description = "Error message when status is 'failed'")
    String errorMessage
) {
    /**
     * Creates a successful conversion response.
     */
    public static PlecsConversionResponse success(String circuitId, String filename,
                                                   int componentCount, List<String> warnings) {
        return new PlecsConversionResponse(circuitId, "converted", filename,
                componentCount, warnings, null);
    }

    /**
     * Creates a failed conversion response.
     */
    public static PlecsConversionResponse failure(String filename, String errorMessage) {
        return new PlecsConversionResponse(null, "failed", filename,
                0, List.of(), errorMessage);
    }
}
