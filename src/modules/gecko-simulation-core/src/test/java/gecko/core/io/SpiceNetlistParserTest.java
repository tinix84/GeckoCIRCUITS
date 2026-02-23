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
        assertEquals(GeckoElementDictionary.R, r.getElementType());
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
        assertEquals(GeckoElementDictionary.L, l.getElementType());
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
        assertEquals(GeckoElementDictionary.C, c.getElementType());
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
        assertEquals(GeckoElementDictionary.V, v.getElementType());
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
        assertEquals(GeckoElementDictionary.I, i.getElementType());
        assertEquals(2.5, i.getDcValue(), 1e-9);
        assertEquals(5, i.getGeckoTypeId());
    }

    // ==================== Diode ====================

    @Test
    void parse_diode_defaultForwardVoltage() throws Exception {
        SpiceNetlist netlist = parser.parse("Title\nD1 A K 1N4148\n.end");
        SpiceComponent d = netlist.getComponents().get(0);
        assertEquals(GeckoElementDictionary.D, d.getElementType());
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
        assertEquals(GeckoElementDictionary.V, comps.get(0).getElementType());
        assertEquals(GeckoElementDictionary.R, comps.get(1).getElementType());
        assertEquals(GeckoElementDictionary.L, comps.get(2).getElementType());
        assertEquals(GeckoElementDictionary.C, comps.get(3).getElementType());
    }

    @Test
    void parse_unsupportedElements_silentlyIgnored() throws Exception {
        // Standard SPICE Q (BJT) and X (subcircuit) are not in gecko dict → skipped
        String cir = "Title\nX1 A B subckt\nR1 1 0 1k\n.end";
        SpiceNetlist netlist = parser.parse(cir);
        assertEquals(1, netlist.getComponentCount());
        assertEquals(GeckoElementDictionary.R, netlist.getComponents().get(0).getElementType());
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

    // ==================== Gecko-dialect: gate-controlled switches ====================

    @Test
    void parse_idealSwitch_withGateSignal() throws Exception {
        SpiceNetlist netlist = parser.parse("Title\nS1 in out GATE.1 0.01\n.end");
        SpiceComponent s = netlist.getComponents().get(0);
        assertEquals(GeckoElementDictionary.S, s.getElementType());
        assertEquals("S1", s.getName());
        assertEquals("in", s.getPositiveNode());
        assertEquals("out", s.getNegativeNode());
        assertEquals("GATE.1", s.getGateSignal());
        assertEquals(0.01, s.getValue(), 1e-12);
        assertEquals(7, s.getGeckoTypeId());
        assertTrue(s.hasGateSignal());
    }

    @Test
    void parse_idealSwitch_defaultRon_whenNotSpecified() throws Exception {
        SpiceNetlist netlist = parser.parse("Title\nS1 a b GATE.1\n.end");
        SpiceComponent s = netlist.getComponents().get(0);
        assertEquals(GeckoElementDictionary.S, s.getElementType());
        assertEquals("GATE.1", s.getGateSignal());
        assertEquals(0.01, s.getValue(), 1e-12); // default ron
    }

    @Test
    void parse_thyristor_withGateAndParams() throws Exception {
        SpiceNetlist netlist = parser.parse("Title\nTHYR1 anode cathode GATE.2 0.8 0.005\n.end");
        SpiceComponent t = netlist.getComponents().get(0);
        assertEquals(GeckoElementDictionary.THYR, t.getElementType());
        assertEquals("THYR1", t.getName());
        assertEquals("anode", t.getPositiveNode());
        assertEquals("cathode", t.getNegativeNode());
        assertEquals("GATE.2", t.getGateSignal());
        assertEquals(0.8, t.getValue2(), 1e-9); // vf
        assertEquals(0.005, t.getValue(), 1e-9); // ron
        assertEquals(8, t.getGeckoTypeId());
    }

    @Test
    void parse_igbt_withGateAndParams() throws Exception {
        SpiceNetlist netlist = parser.parse("Title\nIGBT1 C E GATE.3 0.6 0.01\n.end");
        SpiceComponent igbt = netlist.getComponents().get(0);
        assertEquals(GeckoElementDictionary.IGBT, igbt.getElementType());
        assertEquals("C", igbt.getPositiveNode());
        assertEquals("E", igbt.getNegativeNode());
        assertEquals("GATE.3", igbt.getGateSignal());
        assertEquals(0.6, igbt.getValue2(), 1e-9); // vf
        assertEquals(0.01, igbt.getValue(), 1e-9); // ron
        assertEquals(10, igbt.getGeckoTypeId());
    }

    @Test
    void parse_mosfet_withGateSignal() throws Exception {
        SpiceNetlist netlist = parser.parse("Title\nMOSFET1 drain source GATE.4\n.end");
        SpiceComponent m = netlist.getComponents().get(0);
        assertEquals(GeckoElementDictionary.MOSFET, m.getElementType());
        assertEquals("GATE.4", m.getGateSignal());
        assertEquals(28, m.getGeckoTypeId());
    }

    // ==================== Gecko-dialect: BJT ====================

    @Test
    void parse_bjt_npn() throws Exception {
        SpiceNetlist netlist = parser.parse("Title\nBJT1 collector emitter base NPN 200\n.end");
        SpiceComponent bjt = netlist.getComponents().get(0);
        assertEquals(GeckoElementDictionary.BJT, bjt.getElementType());
        assertEquals("collector", bjt.getPositiveNode());
        assertEquals("emitter", bjt.getNegativeNode());
        assertEquals("base", bjt.getExtraNodes().get(0));
        assertEquals(200.0, bjt.getValue(), 1e-9); // beta
        assertEquals(1.0, bjt.getValue2(), 1e-9); // isNpn = 1.0
        assertEquals(33, bjt.getGeckoTypeId());
    }

    @Test
    void parse_bjt_pnp() throws Exception {
        SpiceNetlist netlist = parser.parse("Title\nBJT1 c e b PNP 50\n.end");
        SpiceComponent bjt = netlist.getComponents().get(0);
        assertEquals(0.0, bjt.getValue2(), 1e-9); // isNpn = 0.0 (PNP)
    }

    // ==================== Gecko-dialect: coupled inductors ====================

    @Test
    void parse_coupledInductor_lc() throws Exception {
        SpiceNetlist netlist = parser.parse("Title\nLc1 n1 n2 5m\n.end");
        SpiceComponent lc = netlist.getComponents().get(0);
        assertEquals(GeckoElementDictionary.LC, lc.getElementType());
        assertEquals(0.005, lc.getValue(), 1e-9);
        assertEquals(12, lc.getGeckoTypeId());
    }

    @Test
    void parse_mutualCoupling_k() throws Exception {
        SpiceNetlist netlist = parser.parse("Title\nK1 Lc1 Lc2 0.85\n.end");
        SpiceComponent k = netlist.getComponents().get(0);
        assertEquals(GeckoElementDictionary.K, k.getElementType());
        assertEquals("Lc1", k.getPositiveNode()); // first coupled inductor
        assertEquals("Lc2", k.getNegativeNode()); // second coupled inductor
        assertEquals(0.85, k.getValue(), 1e-9);   // coupling coefficient
        assertNull(k.getGateSignal());
        assertEquals(9, k.getGeckoTypeId());
    }

    // ==================== Gecko-dialect: transformer ====================

    @Test
    void parse_transformer_withRatio() throws Exception {
        SpiceNetlist netlist = parser.parse("Title\nTRANS1 p+ p- s+ s- 10\n.end");
        SpiceComponent trans = netlist.getComponents().get(0);
        assertEquals(GeckoElementDictionary.TRANS, trans.getElementType());
        assertEquals("p+", trans.getPositiveNode());
        assertEquals("p-", trans.getNegativeNode());
        assertEquals("s+", trans.getExtraNodes().get(0));
        assertEquals("s-", trans.getExtraNodes().get(1));
        assertEquals(10.0, trans.getValue(), 1e-9);
        assertEquals(23, trans.getGeckoTypeId());
    }

    @Test
    void parse_transformer_defaultRatio() throws Exception {
        SpiceNetlist netlist = parser.parse("Title\nTRANS1 p+ p- s+ s-\n.end");
        SpiceComponent trans = netlist.getComponents().get(0);
        assertEquals(1.0, trans.getValue(), 1e-9); // default 1:1
    }

    // ==================== Gecko-dialect: op-amp ====================

    @Test
    void parse_opamp() throws Exception {
        SpiceNetlist netlist = parser.parse("Title\nOPAMP1 in+ in- out ref\n.end");
        SpiceComponent op = netlist.getComponents().get(0);
        assertEquals(GeckoElementDictionary.OPAMP, op.getElementType());
        assertEquals("in+", op.getPositiveNode());
        assertEquals("in-", op.getNegativeNode());
        assertEquals("out", op.getExtraNodes().get(0));
        assertEquals("ref", op.getExtraNodes().get(1));
        assertEquals(22, op.getGeckoTypeId());
    }

    // ==================== Gecko-dialect: LISN ====================

    @Test
    void parse_lisn() throws Exception {
        SpiceNetlist netlist = parser.parse("Title\nLISN1 a1 a2 a3 b1 b2 b3\n.end");
        SpiceComponent lisn = netlist.getComponents().get(0);
        assertEquals(GeckoElementDictionary.LISN, lisn.getElementType());
        assertEquals("a1", lisn.getPositiveNode());
        assertEquals(13, lisn.getGeckoTypeId());
    }

    // ==================== GeckoElementDictionary lookups ====================

    @Test
    void geckoDict_fromElementName_resistor() {
        assertEquals(GeckoElementDictionary.R, GeckoElementDictionary.fromElementName("R1"));
        assertEquals(GeckoElementDictionary.R, GeckoElementDictionary.fromElementName("Rload"));
    }

    @Test
    void geckoDict_fromElementName_igbtBeatsCurrentSource() {
        // IGBT starts with 'I' but should win over current source 'I' keyword
        assertEquals(GeckoElementDictionary.IGBT, GeckoElementDictionary.fromElementName("IGBT1"));
    }

    @Test
    void geckoDict_fromElementName_lcBeatsL() {
        // Lc should win over plain L
        assertEquals(GeckoElementDictionary.LC, GeckoElementDictionary.fromElementName("Lc1"));
        assertEquals(GeckoElementDictionary.L, GeckoElementDictionary.fromElementName("L1"));
    }

    @Test
    void geckoDict_fromGeckoTypeId_roundtrip() {
        for (GeckoElementDictionary entry : GeckoElementDictionary.values()) {
            assertEquals(entry, GeckoElementDictionary.fromGeckoTypeId(entry.geckoTypeId));
        }
    }

    @Test
    void geckoDict_formatDescription_containsKeyword() {
        for (GeckoElementDictionary entry : GeckoElementDictionary.values()) {
            assertTrue(entry.formatDescription().startsWith(entry.keyword),
                    "Format should start with keyword: " + entry.keyword);
        }
    }

}
