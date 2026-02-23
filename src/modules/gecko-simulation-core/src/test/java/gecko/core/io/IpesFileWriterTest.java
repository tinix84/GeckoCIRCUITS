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
package gecko.core.io;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link IpesFileWriter}.
 */
class IpesFileWriterTest {

    private SpiceNetlistParser parser;
    private IpesFileWriter writer;

    @BeforeEach
    void setUp() {
        parser = new SpiceNetlistParser();
        writer = new IpesFileWriter();
    }

    // ==================== Output format ====================

    @Test
    void write_outputIsGzipCompressed() throws Exception {
        SpiceNetlist netlist = parser.parse("Title\nR1 1 0 1k\n.end");
        byte[] output = writer.write(netlist);

        // GZIP magic bytes: 0x1F 0x8B
        assertTrue(output.length >= 2);
        assertEquals((byte) 0x1F, output[0]);
        assertEquals((byte) 0x8B, output[1]);
    }

    @Test
    void write_gzipContent_isValidUtf8() throws Exception {
        SpiceNetlist netlist = parser.parse("Title\nR1 1 0 1k\n.end");
        byte[] output = writer.write(netlist);

        String content = decompressGzip(output);
        assertFalse(content.isBlank());
    }

    @Test
    void write_parsableByCircuitFileParser() throws Exception {
        SpiceNetlist spiceNetlist = parser.parse("Title\nR1 1 0 1k\nL1 1 2 10m\nC1 2 0 100u\n.tran 1u 10m\n.end");
        byte[] ipesBytes = writer.write(spiceNetlist);

        // Decompress gzip before parsing via the InputStream-based overload
        // (the InputStream overload does not auto-detect gzip; the file-path overload does).
        CircuitFileParser circuitParser = new CircuitFileParser();
        try (java.util.zip.GZIPInputStream gis = new java.util.zip.GZIPInputStream(
                new ByteArrayInputStream(ipesBytes))) {
            CircuitModel model = circuitParser.parse(gis, "test.ipes");
            assertNotNull(model);
            assertEquals(0.01, model.getSimulationDuration(), 1e-10);
            assertEquals(1e-6, model.getTimeStep(), 1e-15);
        }
    }

    // ==================== Component generation ====================

    @Test
    void generateIpesText_resistor_containsCorrectType() throws Exception {
        SpiceNetlist netlist = parser.parse("Title\nR1 1 0 1k\n.end");
        String ipes = writer.generateIpesText(netlist);

        assertTrue(ipes.contains("typ 1"), "Expected resistor type 1");
        assertTrue(ipes.contains("idStringDialog R1"));
    }

    @Test
    void generateIpesText_inductor_containsCorrectType() throws Exception {
        SpiceNetlist netlist = parser.parse("Title\nL1 1 2 10m\n.end");
        String ipes = writer.generateIpesText(netlist);

        assertTrue(ipes.contains("typ 2"), "Expected inductor type 2");
        assertTrue(ipes.contains("idStringDialog L1"));
    }

    @Test
    void generateIpesText_capacitor_containsCorrectType() throws Exception {
        SpiceNetlist netlist = parser.parse("Title\nC1 1 0 100u\n.end");
        String ipes = writer.generateIpesText(netlist);

        assertTrue(ipes.contains("typ 3"), "Expected capacitor type 3");
        assertTrue(ipes.contains("idStringDialog C1"));
    }

    @Test
    void generateIpesText_voltageSource_containsCorrectType() throws Exception {
        SpiceNetlist netlist = parser.parse("Title\nV1 1 0 DC 12\n.end");
        String ipes = writer.generateIpesText(netlist);

        assertTrue(ipes.contains("typ 4"), "Expected voltage source type 4");
        assertTrue(ipes.contains("idStringDialog V1"));
    }

    @Test
    void generateIpesText_currentSource_containsCorrectType() throws Exception {
        SpiceNetlist netlist = parser.parse("Title\nI1 1 0 DC 2\n.end");
        String ipes = writer.generateIpesText(netlist);

        assertTrue(ipes.contains("typ 5"), "Expected current source type 5");
    }

    @Test
    void generateIpesText_diode_containsCorrectType() throws Exception {
        SpiceNetlist netlist = parser.parse("Title\nD1 A K 1N4148\n.end");
        String ipes = writer.generateIpesText(netlist);

        assertTrue(ipes.contains("typ 6"), "Expected diode type 6");
        assertTrue(ipes.contains("idStringDialog D1"));
    }

    // ==================== Node labels ====================

    @Test
    void generateIpesText_nodeLabels_presentInElementBlock() throws Exception {
        SpiceNetlist netlist = parser.parse("Title\nR1 VCC GND 1k\n.end");
        String ipes = writer.generateIpesText(netlist);

        assertTrue(ipes.contains("labelAnfangsKnoten[] /VCC"));
        assertTrue(ipes.contains("labelEndKnoten[] /GND"));
    }

    @Test
    void generateIpesText_groundNode_correctLabel() throws Exception {
        SpiceNetlist netlist = parser.parse("Title\nR1 1 0 1k\n.end");
        String ipes = writer.generateIpesText(netlist);

        assertTrue(ipes.contains("labelEndKnoten[] /0"));
    }

    // ==================== Simulation parameters ====================

    @Test
    void generateIpesText_tranStatement_setsDuration() throws Exception {
        SpiceNetlist netlist = parser.parse("Title\n.tran 1u 5m\n.end");
        String ipes = writer.generateIpesText(netlist);

        assertTrue(ipes.contains("tDURATION 0.005"), "Expected tDURATION 0.005");
        assertTrue(ipes.contains("dt 1.0E-6") || ipes.contains("dt 1e-6"),
                "Expected dt 1e-6, got: " + extractLine(ipes, "dt "));
    }

    @Test
    void generateIpesText_defaultDuration_whenNoTran() throws Exception {
        SpiceNetlist netlist = parser.parse("Title\nR1 1 0 1k\n.end");
        String ipes = writer.generateIpesText(netlist);

        assertTrue(ipes.contains("tDURATION " + SpiceNetlist.DEFAULT_DURATION));
    }

    // ==================== Wire sections ====================

    @Test
    void generateIpesText_wiresGenerated_forConnectedNet() throws Exception {
        SpiceNetlist netlist = parser.parse("Title\nR1 1 0 1k\nC1 1 0 100u\n.end");
        String ipes = writer.generateIpesText(netlist);

        assertTrue(ipes.contains("verbindungLeistungskreisANZAHL"));
        // Both components share nodes "1" and "0", so at least 2 wire segments
        assertFalse(ipes.contains("verbindungLeistungskreisANZAHL 0"),
                "Expected at least some wire segments for connected components");
    }

    @Test
    void generateIpesText_groundWireLabel_isZero() throws Exception {
        SpiceNetlist netlist = parser.parse("Title\nR1 A 0 1k\n.end");
        String ipes = writer.generateIpesText(netlist);
        assertTrue(ipes.contains("label 0"), "Expected a wire labelled '0' for ground");
    }

    // ==================== Full circuit ====================

    @Test
    void generateIpesText_rcCircuit_allElementsPresent() throws Exception {
        String cir = """
                * RC low-pass filter
                V1 in 0 AC 1
                R1 in out 1k
                C1 out 0 100n
                .tran 1n 100u
                .end
                """;
        SpiceNetlist netlist = parser.parse(cir);
        String ipes = writer.generateIpesText(netlist);

        assertTrue(ipes.contains("elementANZAHL 3"));
        assertTrue(ipes.contains("typ 4")); // V source
        assertTrue(ipes.contains("typ 1")); // R
        assertTrue(ipes.contains("typ 3")); // C
        assertTrue(ipes.contains("controlANZAHL 0"));
    }

    // ==================== Helper ====================

    private String decompressGzip(byte[] data) throws Exception {
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(data));
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(gis, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }

    private String extractLine(String text, String prefix) {
        for (String line : text.split("\n")) {
            if (line.startsWith(prefix)) {
                return line;
            }
        }
        return "<not found>";
    }
}
