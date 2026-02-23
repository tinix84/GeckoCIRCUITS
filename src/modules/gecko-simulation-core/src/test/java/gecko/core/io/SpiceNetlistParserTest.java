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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SpiceNetlistParser}.
 */
class SpiceNetlistParserTest {

    private SpiceNetlistParser parser;

    @BeforeEach
    void setUp() {
        parser = new SpiceNetlistParser();
    }

    // ==================== Title and comments ====================

    @Test
    void parse_titleExtracted_fromFirstNonBlankLine() throws Exception {
        String cir = "* My RC Circuit\nR1 1 0 1k\n.end";
        SpiceNetlist netlist = parser.parse(cir);
        assertEquals("* My RC Circuit", netlist.getTitle());
    }

    @Test
    void parse_emptyContent_throwsParseException() {
        assertThrows(SpiceNetlistParser.SpiceParseException.class,
                () -> parser.parse(""));
    }

    @Test
    void parse_blankContent_throwsParseException() {
        assertThrows(SpiceNetlistParser.SpiceParseException.class,
                () -> parser.parse("   \n  "));
    }

    @Test
    void parse_commentsSkipped() throws Exception {
        String cir = "Title\n* this is a comment\n; this too\nR1 1 0 1k\n.end";
        SpiceNetlist netlist = parser.parse(cir);
        assertEquals(1, netlist.getComponentCount());
    }

    @Test
    void parse_continuationLinesJoined() throws Exception {
        String cir = "Title\nR1 1\n+ 0 1k\n.end";
        SpiceNetlist netlist = parser.parse(cir);
        assertEquals(1, netlist.getComponentCount());
        SpiceComponent r = netlist.getComponents().get(0);
        assertEquals("0", r.getNegativeNode());
        assertEquals(1000.0, r.getValue(), 1e-9);
    }

    // ==================== Resistor ====================

    @Test
    void parse_resistor_basicValues() throws Exception {
        SpiceNetlist netlist = parser.parse("Title\nR1 1 0 1k\n.end");
        List<SpiceComponent> comps = netlist.getComponents();
        assertEquals(1, comps.size());
        SpiceComponent r = comps.get(0);
        assertEquals(SpiceComponent.Type.R, r.getType());
        assertEquals("R1", r.getName());
        assertEquals("1", r.getPositiveNode());
        assertEquals("0", r.getNegativeNode());
        assertEquals(1000.0, r.getValue(), 1e-9);
    }

    @Test
    void parse_resistor_floatingPointValue() throws Exception {
        SpiceNetlist netlist = parser.parse("Title\nR2 A B 2.2k\n.end");
        assertEquals(2200.0, netlist.getComponents().get(0).getValue(), 1e-9);
    }

    @Test
    void parse_resistor_geckoTypeId_isOne() throws Exception {
        SpiceNetlist netlist = parser.parse("Title\nR1 1 0 100\n.end");
        assertEquals(1, netlist.getComponents().get(0).getGeckoTypeId());
    }

    // ==================== Inductor ====================

    @Test
    void parse_inductor_millihenry() throws Exception {
        SpiceNetlist netlist = parser.parse("Title\nL1 2 3 10m\n.end");
        SpiceComponent l = netlist.getComponents().get(0);
        assertEquals(SpiceComponent.Type.L, l.getType());
        assertEquals(0.01, l.getValue(), 1e-12);
        assertEquals(2, l.getGeckoTypeId());
    }

    @Test
    void parse_inductor_microhenry() throws Exception {
        SpiceNetlist netlist = parser.parse("Title\nL1 1 2 100u\n.end");
        assertEquals(100e-6, netlist.getComponents().get(0).getValue(), 1e-15);
    }

    // ==================== Capacitor ====================

    @Test
    void parse_capacitor_microfarad() throws Exception {
        SpiceNetlist netlist = parser.parse("Title\nC1 3 0 100u\n.end");
        SpiceComponent c = netlist.getComponents().get(0);
        assertEquals(SpiceComponent.Type.C, c.getType());
        assertEquals(100e-6, c.getValue(), 1e-15);
        assertEquals(3, c.getGeckoTypeId());
    }

    @Test
    void parse_capacitor_nanofarad() throws Exception {
        SpiceNetlist netlist = parser.parse("Title\nC2 A B 10n\n.end");
        assertEquals(10e-9, netlist.getComponents().get(0).getValue(), 1e-18);
    }

    // ==================== Voltage source ====================

    @Test
    void parse_voltageSource_dcKeyword() throws Exception {
        SpiceNetlist netlist = parser.parse("Title\nV1 1 0 DC 12\n.end");
        SpiceComponent v = netlist.getComponents().get(0);
        assertEquals(SpiceComponent.Type.V, v.getType());
        assertEquals(SpiceComponent.SourceMode.DC, v.getSourceMode());
        assertEquals(12.0, v.getDcValue(), 1e-9);
        assertEquals(4, v.getGeckoTypeId());
    }

    @Test
    void parse_voltageSource_bareValue_treatedAsDc() throws Exception {
        SpiceNetlist netlist = parser.parse("Title\nV1 1 0 5\n.end");
        SpiceComponent v = netlist.getComponents().get(0);
        assertEquals(SpiceComponent.SourceMode.DC, v.getSourceMode());
        assertEquals(5.0, v.getDcValue(), 1e-9);
    }

    @Test
    void parse_voltageSource_acSinusoidal() throws Exception {
        SpiceNetlist netlist = parser.parse("Title\nV1 1 0 AC 120 0\n.end");
        SpiceComponent v = netlist.getComponents().get(0);
        assertEquals(SpiceComponent.SourceMode.AC, v.getSourceMode());
        assertEquals(120.0, v.getAcAmplitude(), 1e-9);
    }

    @Test
    void parse_voltageSource_dcAndAcCombined() throws Exception {
        SpiceNetlist netlist = parser.parse("Title\nV1 1 0 DC 5 AC 100\n.end");
        SpiceComponent v = netlist.getComponents().get(0);
        // AC mode takes precedence when AC keyword appears last
        assertEquals(SpiceComponent.SourceMode.AC, v.getSourceMode());
        assertEquals(5.0, v.getDcValue(), 1e-9);
        assertEquals(100.0, v.getAcAmplitude(), 1e-9);
    }

    // ==================== Current source ====================

    @Test
    void parse_currentSource_dc() throws Exception {
        SpiceNetlist netlist = parser.parse("Title\nI1 1 0 DC 2.5\n.end");
        SpiceComponent i = netlist.getComponents().get(0);
        assertEquals(SpiceComponent.Type.I, i.getType());
        assertEquals(2.5, i.getDcValue(), 1e-9);
        assertEquals(5, i.getGeckoTypeId());
    }

    // ==================== Diode ====================

    @Test
    void parse_diode_defaultForwardVoltage() throws Exception {
        SpiceNetlist netlist = parser.parse("Title\nD1 A K 1N4148\n.end");
        SpiceComponent d = netlist.getComponents().get(0);
        assertEquals(SpiceComponent.Type.D, d.getType());
        assertEquals("A", d.getPositiveNode());
        assertEquals("K", d.getNegativeNode());
        assertEquals(0.6, d.getValue(), 1e-9);
        assertEquals(6, d.getGeckoTypeId());
    }

    // ==================== .tran statement ====================

    @Test
    void parse_tranStatement_extractsTimeStep() throws Exception {
        SpiceNetlist netlist = parser.parse("Title\n.tran 1u 10m\n.end");
        assertEquals(1e-6, netlist.getTimeStep(), 1e-15);
        assertEquals(10e-3, netlist.getSimulationDuration(), 1e-12);
    }

    @Test
    void parse_tranStatement_microsecond() throws Exception {
        SpiceNetlist netlist = parser.parse("Title\n.tran 500n 1m\n.end");
        assertEquals(500e-9, netlist.getTimeStep(), 1e-18);
        assertEquals(1e-3, netlist.getSimulationDuration(), 1e-12);
    }

    @Test
    void parse_noTranStatement_usesDefaults() throws Exception {
        SpiceNetlist netlist = parser.parse("Title\nR1 1 0 1k\n.end");
        assertEquals(SpiceNetlist.DEFAULT_TIME_STEP, netlist.getTimeStep());
        assertEquals(SpiceNetlist.DEFAULT_DURATION, netlist.getSimulationDuration());
    }

    // ==================== Multiple components ====================

    @Test
    void parse_rlcCircuit_allComponentsParsed() throws Exception {
        String cir = """
                * Simple RLC series circuit
                V1 1 0 DC 12
                R1 1 2 100
                L1 2 3 10m
                C1 3 0 100u
                .tran 1u 10m
                .end
                """;
        SpiceNetlist netlist = parser.parse(cir);
        assertEquals(4, netlist.getComponentCount());

        List<SpiceComponent> comps = netlist.getComponents();
        assertEquals(SpiceComponent.Type.V, comps.get(0).getType());
        assertEquals(SpiceComponent.Type.R, comps.get(1).getType());
        assertEquals(SpiceComponent.Type.L, comps.get(2).getType());
        assertEquals(SpiceComponent.Type.C, comps.get(3).getType());
    }

    @Test
    void parse_unsupportedElements_silentlyIgnored() throws Exception {
        // Transistors (Q, M), subcircuits (X), etc. should be silently skipped
        String cir = "Title\nQ1 C B E NPN\nM1 D G S B NMOS\nR1 1 0 1k\n.end";
        SpiceNetlist netlist = parser.parse(cir);
        assertEquals(1, netlist.getComponentCount());
        assertEquals(SpiceComponent.Type.R, netlist.getComponents().get(0).getType());
    }

    // ==================== SI prefix parsing ====================

    @ParameterizedTest(name = "value=\"{0}\" → {1}")
    @CsvSource({
        "1k,      1000.0",
        "2.2k,    2200.0",
        "10m,     0.01",
        "100u,    0.0001",
        "10n,     1.0E-8",
        "100p,    1.0E-10",
        "1f,      1.0E-15",
        "1MEG,    1000000.0",
        "2G,      2.0E9",
        "1.5,     1.5",
        "0,       0.0"
    })
    void parseValue_siPrefixes(String input, double expected) {
        assertEquals(expected, SpiceNetlistParser.parseValue(input), Math.abs(expected) * 1e-9 + 1e-30);
    }
}
