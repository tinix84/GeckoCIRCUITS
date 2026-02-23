/*  This file is part of GeckoCIRCUITS. Copyright (C) ETH Zurich, Gecko-Simulations AG
 *
 *  GeckoCIRCUITS is free software: you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation, either version 3 of the License, or (at your option) any later version.
 *
 *  GeckoCIRCUITS is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR
 *  PURPOSE.  See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  GeckoCIRCUITS.  If not, see <http://www.gnu.org/licenses/>.
 */
package gecko.rest.controller;

import gecko.rest.model.conversion.LtspiceConversionResponse;
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
 * REST controller for converting circuit files from external formats to
 * the GeckoCIRCUITS .ipes format.
 *
 * <p>Currently supported conversions:
 * <ul>
 *   <li>LTspice .asc → GeckoCIRCUITS .ipes
 *       ({@code POST /api/v1/convert/ltspice})</li>
 * </ul>
 *
 * <p>On success the response includes:
 * <ul>
 *   <li>A {@code circuitId} that can be used with the
 *       {@code /api/v1/circuits} endpoints immediately.</li>
 *   <li>A {@code ipesBase64} field containing the GZIP-compressed .ipes file
 *       encoded as Base64, suitable for saving and opening in the GeckoCIRCUITS GUI.</li>
 *   <li>A {@code warnings} list describing any components or connections that
 *       could not be fully converted.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/convert")
@Tag(name = "Circuit Conversion", description = "Convert external circuit formats to GeckoCIRCUITS .ipes")
public class ConversionController {

    private final ConversionService conversionService;

    public ConversionController(ConversionService conversionService) {
        this.conversionService = conversionService;
    }

    /**
     * Converts an LTspice .asc schematic to a GeckoCIRCUITS .ipes file.
     *
     * <p><b>Supported LTspice components:</b>
     * resistor ({@code res}), capacitor ({@code cap}), inductor ({@code ind}/{@code ind2}),
     * voltage source ({@code voltage}), current source ({@code current}),
     * diode ({@code diode}), ideal switch ({@code sw}).
     *
     * <p><b>Limitations:</b>
     * <ul>
     *   <li>Multi-pin components (transistors, op-amps) are not yet supported.</li>
     *   <li>SPICE simulation directives ({@code .tran}, {@code .ac}, …) are not parsed;
     *       default simulation parameters are used.</li>
     *   <li>Component orientations and positions are approximated from LTspice
     *       coordinates; minor adjustments may be needed in GeckoCIRCUITS.</li>
     *   <li>Connection wires are not yet written to the .ipes file;
     *       nodes are labelled correctly but must be re-wired in GeckoCIRCUITS.</li>
     * </ul>
     */
    @PostMapping(value = "/ltspice", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
        summary = "Convert LTspice .asc to GeckoCIRCUITS .ipes",
        description = "Upload an LTspice .asc schematic file and receive a GeckoCIRCUITS "
                + ".ipes file in return. The converted circuit is also loaded into memory "
                + "and accessible via its circuitId."
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "201",
            description = "Conversion successful",
            content = @Content(schema = @Schema(implementation = LtspiceConversionResponse.class))
        ),
        @ApiResponse(
            responseCode = "400",
            description = "Invalid or unsupported .asc file"
        ),
        @ApiResponse(
            responseCode = "422",
            description = "File could be parsed but the circuit has no convertible components"
        )
    })
    public ResponseEntity<LtspiceConversionResponse> convertLtspice(
            @Parameter(description = "LTspice .asc schematic file")
            @RequestParam("file") MultipartFile file) {

        LtspiceConversionResponse response = conversionService.convert(file);

        if ("failed".equals(response.status())) {
            return ResponseEntity.badRequest().body(response);
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
