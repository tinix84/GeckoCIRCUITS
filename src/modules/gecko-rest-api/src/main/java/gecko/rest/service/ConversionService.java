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
package gecko.rest.service;

import gecko.core.io.CircuitModel;
import gecko.core.io.IpesFileWriter;
import gecko.core.io.ltspice.AscToIpesConverter;
import gecko.core.io.ltspice.LtspiceAscParser;
import gecko.core.io.ltspice.LtspiceCircuit;
import gecko.rest.model.circuit.CircuitLoadResponse;
import gecko.rest.model.conversion.LtspiceConversionResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;

/**
 * Service that converts LTspice .asc schematics to GeckoCIRCUITS .ipes format.
 *
 * <p>The conversion pipeline is:
 * <ol>
 *   <li>Parse the .asc file with {@link LtspiceAscParser}</li>
 *   <li>Convert to a {@link CircuitModel} with {@link AscToIpesConverter}</li>
 *   <li>Write the model to .ipes bytes with {@link IpesFileWriter}</li>
 *   <li>Load the model into the in-memory circuit store via {@link CircuitFileService}</li>
 * </ol>
 */
@Service
public class ConversionService {

    private final LtspiceAscParser ascParser = new LtspiceAscParser();
    private final AscToIpesConverter converter = new AscToIpesConverter();
    private final IpesFileWriter writer = new IpesFileWriter();
    private final CircuitFileService circuitFileService;

    public ConversionService(CircuitFileService circuitFileService) {
        this.circuitFileService = circuitFileService;
    }

    /**
     * Converts an LTspice .asc file uploaded as a multipart form to a .ipes circuit.
     *
     * @param file the uploaded .asc file
     * @return conversion result including circuit ID and warnings
     */
    public LtspiceConversionResponse convert(MultipartFile file) {
        String filename = file.getOriginalFilename() != null
                ? file.getOriginalFilename() : "circuit.asc";
        try {
            byte[] bytes = file.getBytes();
            return convertBytes(bytes, filename);
        } catch (IOException e) {
            return LtspiceConversionResponse.failure(filename,
                    "Failed to read uploaded file: " + e.getMessage());
        }
    }

    /**
     * Converts LTspice .asc content provided as a Base64-encoded string.
     *
     * @param base64Content Base64-encoded .asc file content
     * @param filename      original filename for error messages
     * @return conversion result
     */
    public LtspiceConversionResponse convertBase64(String base64Content, String filename) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64Content);
            return convertBytes(bytes, filename);
        } catch (IllegalArgumentException e) {
            return LtspiceConversionResponse.failure(filename,
                    "Invalid Base64 encoding: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------

    private LtspiceConversionResponse convertBytes(byte[] bytes, String filename) {
        // Step 1: parse .asc
        LtspiceCircuit ltspiceCircuit;
        try {
            ltspiceCircuit = ascParser.parse(new ByteArrayInputStream(bytes), filename);
        } catch (LtspiceAscParser.AscParseException e) {
            return LtspiceConversionResponse.failure(filename,
                    "Failed to parse .asc file: " + e.getMessage());
        } catch (IOException e) {
            return LtspiceConversionResponse.failure(filename,
                    "IO error reading .asc content: " + e.getMessage());
        }

        // Step 2: convert to CircuitModel
        AscToIpesConverter.ConversionResult result = converter.convert(ltspiceCircuit);
        CircuitModel model = result.getModel();
        List<String> warnings = result.getWarnings();

        // Step 3: write to gzip-compressed .ipes bytes
        byte[] ipesBytes;
        try {
            ipesBytes = writer.writeGzipCompressed(model);
        } catch (IOException e) {
            return LtspiceConversionResponse.failure(filename,
                    "Failed to generate .ipes content: " + e.getMessage());
        }

        // Step 4: write plain-text .ipes and load into in-memory store
        byte[] plainBytes;
        try {
            plainBytes = writer.writePlainText(model);
        } catch (IOException e) {
            return LtspiceConversionResponse.failure(filename,
                    "Failed to serialise circuit model: " + e.getMessage());
        }
        String ipesFilename = toIpesFilename(filename);
        CircuitLoadResponse loadResponse = circuitFileService.loadCircuit(
                Base64.getEncoder().encodeToString(plainBytes), ipesFilename);

        if ("failed".equals(loadResponse.status())) {
            return LtspiceConversionResponse.failure(filename,
                    "Converted circuit could not be loaded: " + loadResponse.errorMessage());
        }

        String ipesBase64 = Base64.getEncoder().encodeToString(ipesBytes);
        return LtspiceConversionResponse.success(
                loadResponse.circuitId(),
                filename,
                model.getTotalComponentCount(),
                warnings,
                ipesBase64);
    }

    private static String toIpesFilename(String ascFilename) {
        if (ascFilename == null) return "converted.ipes";
        int dot = ascFilename.lastIndexOf('.');
        String base = dot >= 0 ? ascFilename.substring(0, dot) : ascFilename;
        return base + ".ipes";
    }
}
