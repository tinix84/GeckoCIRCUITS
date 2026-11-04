package gecko.rest.controller;

import gecko.rest.model.circuit.PlecsConversionResponse;
import gecko.rest.service.ConversionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * REST controller for converting external circuit formats to GeckoCIRCUITS.
 *
 * <p>Provides conversion endpoints under {@code /api/v1/convert}.
 * Currently supports PLECS {@code .plecs} files.
 * After a successful conversion the returned {@code circuitId} can be used
 * with the standard {@code /api/v1/circuits} endpoints.</p>
 */
@RestController
@RequestMapping("/api/v1/convert")
@Tag(name = "Conversion", description = "Convert external circuit formats to GeckoCIRCUITS")
public class ConversionController {

    private final ConversionService conversionService;

    public ConversionController(ConversionService conversionService) {
        this.conversionService = conversionService;
    }

    /**
     * Convert a PLECS {@code .plecs} file to GeckoCIRCUITS format.
     *
     * <p>The converted circuit is stored in memory and can be accessed via its
     * {@code circuitId} using the standard circuit endpoints. Conversion warnings
     * are included in the response for any unsupported or partially-converted
     * PLECS elements.</p>
     */
    @PostMapping(value = "/plecs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
        summary = "Convert PLECS file to GeckoCIRCUITS",
        description = "Upload a PLECS .plecs file and convert it to a GeckoCIRCUITS circuit. "
            + "The converted circuit is stored in memory with a unique ID that can be used "
            + "for subsequent simulation and analysis requests. "
            + "Unsupported PLECS elements are listed in the 'warnings' field."
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "201",
            description = "PLECS file converted successfully",
            content = @Content(schema = @Schema(implementation = PlecsConversionResponse.class))
        ),
        @ApiResponse(
            responseCode = "400",
            description = "Invalid file format or conversion error"
        ),
        @ApiResponse(
            responseCode = "415",
            description = "Unsupported media type – must upload a .plecs file"
        )
    })
    public ResponseEntity<PlecsConversionResponse> convertPlecs(
            @Parameter(description = "PLECS circuit file (.plecs)")
            @RequestParam("file") MultipartFile file) {

        PlecsConversionResponse response = conversionService.convertPlecs(file);

        if ("failed".equals(response.status())) {
            return ResponseEntity.badRequest().body(response);
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
