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
package gecko.rest.model.conversion;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Response returned after converting an LTspice .asc file to a GeckoCIRCUITS .ipes file.
 *
 * <p>On success, the {@code circuitId} can be used immediately with other circuit endpoints
 * (e.g. {@code /api/v1/circuits/{circuitId}/info}).
 * The {@code ipesBase64} field contains the GZIP-compressed .ipes file encoded as Base64
 * and can be saved directly as a {@code .ipes} file for use in the GeckoCIRCUITS GUI.</p>
 */
@Schema(description = "Result of converting an LTspice .asc file to a GeckoCIRCUITS .ipes file")
public record LtspiceConversionResponse(

    @Schema(description = "Unique identifier for the converted circuit (null on failure)",
            example = "123e4567-e89b-12d3-a456-426614174000")
    String circuitId,

    @Schema(description = "Conversion status: 'converted' or 'failed'", example = "converted")
    String status,

    @Schema(description = "Original source filename", example = "buck_converter.asc")
    String filename,

    @Schema(description = "Number of circuit components successfully converted", example = "5")
    int componentCount,

    @Schema(description = "Warnings generated during conversion (e.g. unresolved nets, skipped types)")
    List<String> warnings,

    @Schema(description = "Error message (only set when status is 'failed')")
    String errorMessage,

    @Schema(description = "Base64-encoded GZIP-compressed .ipes file content for download",
            example = "H4sIAAAAAAAA...")
    String ipesBase64
) {
    /**
     * Creates a successful conversion response.
     */
    public static LtspiceConversionResponse success(
            String circuitId, String filename, int componentCount,
            List<String> warnings, String ipesBase64) {
        return new LtspiceConversionResponse(
                circuitId, "converted", filename, componentCount, warnings, null, ipesBase64);
    }

    /**
     * Creates a failure response.
     */
    public static LtspiceConversionResponse failure(String filename, String errorMessage) {
        return new LtspiceConversionResponse(
                null, "failed", filename, 0, List.of(), errorMessage, null);
    }
}
