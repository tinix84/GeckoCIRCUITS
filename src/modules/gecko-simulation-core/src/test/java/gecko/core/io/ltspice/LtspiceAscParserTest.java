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
package gecko.core.io.ltspice;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LtspiceAscParser}.
 */
class LtspiceAscParserTest {

    private static final String SIMPLE_RC = """
            Version 4
            SHEET 1 880 680
            WIRE 80 80 -16 80
            WIRE 240 80 80 80
            WIRE -16 160 80 160
            WIRE 80 160 240 160
            SYMBOL res 160 64 R0
            SYMATTR InstName R1
            SYMATTR Value 1k
            SYMBOL cap 224 96 R0
            SYMATTR InstName C1
            SYMATTR Value 100n
            SYMBOL voltage -16 80 R0
            SYMATTR InstName V1
            SYMATTR Value SINE(0 5 1k)
            FLAG 240 80 Vout
            FLAG -16 80 Vin
            FLAG 240 160 0
            FLAG -16 160 0
            """;

    private final LtspiceAscParser parser = new LtspiceAscParser();

    @Test
    void testParse_simpleRC_componentCount() throws Exception {
        LtspiceCircuit circuit = parseString(SIMPLE_RC);

        assertEquals(3, circuit.getComponents().size(), "Expected 3 components (R, C, V)");
    }

    @Test
    void testParse_simpleRC_wireCount() throws Exception {
        LtspiceCircuit circuit = parseString(SIMPLE_RC);

        assertEquals(4, circuit.getWires().size(), "Expected 4 wires");
    }

    @Test
    void testParse_simpleRC_flagCount() throws Exception {
        LtspiceCircuit circuit = parseString(SIMPLE_RC);

        assertEquals(4, circuit.getFlags().size(), "Expected 4 flags");
    }

    @Test
    void testParse_simpleRC_version() throws Exception {
        LtspiceCircuit circuit = parseString(SIMPLE_RC);

        assertEquals(4, circuit.getVersion());
    }

    @Test
    void testParse_simpleRC_sheetDimensions() throws Exception {
        LtspiceCircuit circuit = parseString(SIMPLE_RC);

        assertEquals(880, circuit.getSheetWidth());
        assertEquals(680, circuit.getSheetHeight());
    }

    @Test
    void testParse_componentAttributes() throws Exception {
        LtspiceCircuit circuit = parseString(SIMPLE_RC);

        LtspiceComponent r1 = findComponent(circuit, "R1");
        assertNotNull(r1, "R1 not found");
        assertEquals("res", r1.getSymbolType());
        assertEquals("1k", r1.getValue());
        assertEquals("R0", r1.getOrientation());
        assertEquals(160, r1.getX());
        assertEquals(64, r1.getY());
    }

    @Test
    void testParse_capacitorAttributes() throws Exception {
        LtspiceCircuit circuit = parseString(SIMPLE_RC);

        LtspiceComponent c1 = findComponent(circuit, "C1");
        assertNotNull(c1, "C1 not found");
        assertEquals("cap", c1.getSymbolType());
        assertEquals("100n", c1.getValue());
    }

    @Test
    void testParse_voltageSourceAttributes() throws Exception {
        LtspiceCircuit circuit = parseString(SIMPLE_RC);

        LtspiceComponent v1 = findComponent(circuit, "V1");
        assertNotNull(v1, "V1 not found");
        assertEquals("voltage", v1.getSymbolType());
        assertEquals("SINE(0 5 1k)", v1.getValue());
    }

    @Test
    void testParse_flagNames() throws Exception {
        LtspiceCircuit circuit = parseString(SIMPLE_RC);

        List<LtspiceFlag> flags = circuit.getFlags();
        assertTrue(flags.stream().anyMatch(f -> "Vout".equals(f.getNetName())));
        assertTrue(flags.stream().anyMatch(f -> "Vin".equals(f.getNetName())));
        assertTrue(flags.stream().anyMatch(LtspiceFlag::isGround));
    }

    @Test
    void testParse_wires() throws Exception {
        LtspiceCircuit circuit = parseString(SIMPLE_RC);

        // Verify first wire WIRE 80 80 -16 80
        LtspiceWire first = circuit.getWires().get(0);
        assertEquals(80, first.getX1());
        assertEquals(80, first.getY1());
        assertEquals(-16, first.getX2());
        assertEquals(80, first.getY2());
    }

    @Test
    void testParse_emptyFile_throwsAscParseException() {
        String empty = "Version 4\nSHEET 1 880 680\n";
        assertThrows(LtspiceAscParser.AscParseException.class,
                () -> parseString(empty),
                "Empty circuit should throw AscParseException");
    }

    @Test
    void testParse_symbolWithPathType() throws Exception {
        // LTspice sometimes writes symbol types as paths like "C:\\...\\lib\\sym\\res"
        String asc = """
                Version 4
                SHEET 1 880 680
                WIRE 0 0 100 0
                SYMBOL C:\\LTspice\\lib\\sym\\res 50 0 R0
                SYMATTR InstName R1
                SYMATTR Value 100
                FLAG 0 0 gnd
                FLAG 100 0 Vout
                """;
        LtspiceCircuit circuit = parseString(asc);
        LtspiceComponent r1 = findComponent(circuit, "R1");
        assertNotNull(r1);
        assertEquals("res", r1.getSymbolType(), "Should normalise path to type name");
    }

    @Test
    void testParse_r90Orientation() throws Exception {
        String asc = """
                Version 4
                SHEET 1 880 680
                WIRE 0 0 100 0
                SYMBOL res 50 0 R90
                SYMATTR InstName R1
                SYMATTR Value 100
                FLAG 0 0 n1
                FLAG 100 0 n2
                """;
        LtspiceCircuit circuit = parseString(asc);
        LtspiceComponent r1 = findComponent(circuit, "R1");
        assertNotNull(r1);
        assertEquals("R90", r1.getOrientation());
    }

    @Test
    void testParse_inductor() throws Exception {
        String asc = """
                Version 4
                SHEET 1 880 680
                WIRE 0 0 100 0
                SYMBOL ind 50 0 R0
                SYMATTR InstName L1
                SYMATTR Value 1m
                FLAG 0 0 a
                FLAG 100 0 b
                """;
        LtspiceCircuit circuit = parseString(asc);
        LtspiceComponent l1 = findComponent(circuit, "L1");
        assertNotNull(l1);
        assertEquals("ind", l1.getSymbolType());
        assertEquals("1m", l1.getValue());
    }

    // -------------------------------------------------------------------------

    private LtspiceCircuit parseString(String content) throws IOException, LtspiceAscParser.AscParseException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return parser.parse(new ByteArrayInputStream(bytes), "test.asc");
    }

    private static LtspiceComponent findComponent(LtspiceCircuit circuit, String instName) {
        return circuit.getComponents().stream()
                .filter(c -> instName.equals(c.getInstName()))
                .findFirst()
                .orElse(null);
    }
}
