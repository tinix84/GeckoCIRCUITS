
package gecko.rest.controller;

import gecko.rest.service.ResearchBundleService;
import gecko.rest.service.ResearchBundleService.Verification;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.parameters.RequestBody;

import java.io.IOException;

@RestController
@Tag(name = "Research Bundles", description = "Export and verify a reproducible simulation")
public class ResearchBundleController {
    private static final String ZIP = "application/zip";
    private final ResearchBundleService bundles;

    public ResearchBundleController(ResearchBundleService bundles) {
        this.bundles = bundles;
    }

    @GetMapping(value = "/api/v1/simulations/{simulationId}/bundle", produces = ZIP)
    @PreAuthorize("hasAnyRole('ADMIN', 'USER', 'READONLY')")
    @Operation(operationId = "exportResearchBundle", summary = "Export a completed simulation as a research bundle")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = ZIP,
            schema = @Schema(type = "string", format = "binary")))
    public ResponseEntity<byte[]> exportBundle(@PathVariable String simulationId,
                                               Authentication authentication) throws IOException {
        boolean admin = authentication.getAuthorities().stream().anyMatch(role -> role.getAuthority().equals("ROLE_ADMIN"));
        byte[] archive = bundles.export(simulationId, authentication.getName(), admin);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(ZIP))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("simulation_" + simulationId + ".zip").build().toString())
                .body(archive);
    }

    @PostMapping(value = "/api/v1/bundles/verify", consumes = ZIP, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @Operation(operationId = "verifyResearchBundle", summary = "Replay a research bundle and compare signals",
            requestBody = @RequestBody(required = true, content = @Content(mediaType = ZIP,
                    schema = @Schema(type = "string", format = "binary"))))
    public Verification verifyBundle(HttpServletRequest request) throws IOException {
        try (var input = request.getInputStream()) {
            return bundles.verify(input);
        }
    }
}
