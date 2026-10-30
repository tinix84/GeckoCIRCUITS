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

import gecko.core.io.CircuitModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AscToIpesConverter}.
 */
class AscToIpesConverterTest {

    private final AscToIpesConverter converter = new AscToIpesConverter();

    // -------------------------------------------------------------------------
    // Value parsing
    // -------------------------------------------------------------------------

    @Test
    void testParseValue_plain() {
        assertEquals(100.0, AscToIpesConverter.parseValue("100", new ArrayList<>(), "R1"), 1e-12);
    }

    @Test
    void testParseValue_kiloSuffix() {
        assertEquals(1000.0, AscToIpesConverter.parseValue("1k", new ArrayList<>(), "R1"), 1e-9);
    }

    @Test
    void testParseValue_milliSuffix() {
        assertEquals(0.001, AscToIpesConverter.parseValue("1m", new ArrayList<>(), "L1"), 1e-12);
    }

    @Test
    void testParseValue_nanoSuffix() {
        assertEquals(100e-9, AscToIpesConverter.parseValue("100n", new ArrayList<>(), "C1"), 1e-18);
    }

    @Test
    void testParseValue_microSuffix() {
        assertEquals(4.7e-6, AscToIpesConverter.parseValue("4.7u", new ArrayList<>(), "C2"), 1e-18);
    }

    @Test
    void testParseValue_picoSuffix() {
        assertEquals(10e-12, AscToIpesConverter.parseValue("10p", new ArrayList<>(), "C3"), 1e-22);
    }

    @Test
    void testParseValue_MegaSuffix() {
        assertEquals(1e6, AscToIpesConverter.parseValue("1M", new ArrayList<>(), "R2"), 1e-3);
    }

    @Test
    void testParseValue_MegSuffix() {
        assertEquals(2e6, AscToIpesConverter.parseValue("2Meg", new ArrayList<>(), "R3"), 1e-3);
    }

    @Test
    void testParseValue_sine_extractsAmplitude() {
        assertEquals(5.0, AscToIpesConverter.parseValue("SINE(0 5 1k)", new ArrayList<>(), "V1"), 1e-9);
    }

    @Test
    void testParseValue_pulse_extractsHighLevel() {
        assertEquals(3.3, AscToIpesConverter.parseValue("PULSE(0 3.3 0 1n 1n 500n 1u)", new ArrayList<>(), "V2"), 1e-9);
    }

    @Test
    void testParseValue_null_returnsZero() {
        assertEquals(0.0, AscToIpesConverter.parseValue(null, new ArrayList<>(), "V1"), 1e-12);
    }

    @Test
    void testParseValue_empty_returnsZero() {
        assertEquals(0.0, AscToIpesConverter.parseValue("", new ArrayList<>(), "V1"), 1e-12);
    }

    // -------------------------------------------------------------------------
    // Symbol type mapping
    // -------------------------------------------------------------------------

    @Test
    void testMapSymbolType_res() {
        assertEquals(1, AscToIpesConverter.mapSymbolType("res"));
    }

    @Test
    void testMapSymbolType_ind() {
        assertEquals(2, AscToIpesConverter.mapSymbolType("ind"));
    }

    @Test
    void testMapSymbolType_ind2() {
        assertEquals(2, AscToIpesConverter.mapSymbolType("ind2"));
    }

    @Test
    void testMapSymbolType_cap() {
        assertEquals(3, AscToIpesConverter.mapSymbolType("cap"));
    }

    @Test
    void testMapSymbolType_voltage() {
        assertEquals(4, AscToIpesConverter.mapSymbolType("voltage"));
    }

    @Test
    void testMapSymbolType_current() {
        assertEquals(5, AscToIpesConverter.mapSymbolType("current"));
    }

    @Test
    void testMapSymbolType_diode() {
        assertEquals(6, AscToIpesConverter.mapSymbolType("diode"));
    }

    @Test
    void testMapSymbolType_sw() {
        assertEquals(7, AscToIpesConverter.mapSymbolType("sw"));
    }

    @Test
    void testMapSymbolType_unsupported() {
        assertEquals(-1, AscToIpesConverter.mapSymbolType("nmos"));
    }

    @Test
    void testMapSymbolType_null() {
        assertEquals(-1, AscToIpesConverter.mapSymbolType(null));
    }

    // -------------------------------------------------------------------------
    // Orientation
    // -------------------------------------------------------------------------

    @Test
    void testMapOrientation_r0() {
        assertEquals(0, AscToIpesConverter.mapOrientation("R0"));
    }

    @Test
    void testMapOrientation_r90() {
        assertEquals(1, AscToIpesConverter.mapOrientation("R90"));
    }

    @Test
    void testMapOrientation_null() {
        assertEquals(0, AscToIpesConverter.mapOrientation(null));
    }

    // -------------------------------------------------------------------------
    // Pin offsets rotation
    // -------------------------------------------------------------------------

    @Test
    void testPinOffsets_res_R0() {
        int[][] pins = AscToIpesConverter.getPinOffsets("res", "R0");
        assertEquals(2, pins.length);
        assertEquals(0, pins[0][0]); // dx of pin A
        assertTrue(pins[0][1] < 0, "pin A should be above symbol origin");
        assertTrue(pins[1][1] > 0, "pin B should be below symbol origin");
    }

    @Test
    void testPinOffsets_res_R90_rotated() {
        int[][] r0 = AscToIpesConverter.getPinOffsets("res", "R0");
        int[][] r90 = AscToIpesConverter.getPinOffsets("res", "R90");
        // After 90° CW rotation: (0, -48) → (48, 0)  and  (0, 48) → (-48, 0)
        assertNotEquals(r0[0][0], r90[0][0], "R90 pin should differ from R0");
    }

    // -------------------------------------------------------------------------
    // Full conversion
    // -------------------------------------------------------------------------

    @Test
    void testConvert_simpleRC_producesComponents() {
        LtspiceCircuit circuit = buildSimpleRC();
        AscToIpesConverter.ConversionResult result = converter.convert(circuit);
        CircuitModel model = result.getModel();

        // R and C should be converted (voltage is type 4 = supported too)
        assertEquals(3, model.getCircuitComponents().size(),
                "Expected 3 circuit components");
    }

    @Test
    void testConvert_unsupportedType_skipped() {
        LtspiceCircuit circuit = new LtspiceCircuit();
        circuit.setSourceName("test");
        LtspiceComponent nmos = new LtspiceComponent();
        nmos.setSymbolType("nmos");
        nmos.setInstName("M1");
        nmos.setValue("2n7000");
        circuit.addComponent(nmos);
        // Need at least one wire or flag to avoid exception
        circuit.addWire(new LtspiceWire(0, 0, 100, 0));

        AscToIpesConverter.ConversionResult result = converter.convert(circuit);
        assertEquals(0, result.getModel().getCircuitComponents().size(),
                "nmos should be skipped");
        assertTrue(result.getWarnings().stream()
                .anyMatch(w -> w.contains("nmos")),
                "Warning should mention skipped component type");
    }

    @Test
    void testConvert_netConnectivity() {
        // Build circuit: V1 between 'vin' and 'gnd', R1 between 'vin' and 'vout',
        // C1 between 'vout' and 'gnd'
        // Simulate the LTspice coordinate system:
        //   V1 at (-16, 80), pins at (-16, 80-112)=(-16,-32) and (-16, 80+112)=(-16, 192)
        //   FLAGS: (-16,-32)="vin", (-16,192)="gnd", (80,80)="vout", ...
        LtspiceCircuit circuit = new LtspiceCircuit();
        circuit.setSourceName("connectivity_test");

        // Voltage source V1 at (-16, 80) R0
        LtspiceComponent v1 = comp("voltage", "V1", "SINE(0 5 1k)", -16, 80, "R0");
        circuit.addComponent(v1);

        // Resistor R1 at (80, 64) R0
        LtspiceComponent r1 = comp("res", "R1", "1k", 80, 64, "R0");
        circuit.addComponent(r1);

        // Capacitor C1 at (144, 96) R0
        LtspiceComponent c1 = comp("cap", "C1", "100n", 144, 96, "R0");
        circuit.addComponent(c1);

        // Wires: vin wire at y=-32 from x=-16 to x=80 (top of R1)
        circuit.addWire(new LtspiceWire(-16, -32, 80, -32));
        // bottom of R1 at (80, 64+48) = (80, 112) to top of C1 at (144, 96-48)=(144,48)?
        // Actually just add wires near expected pin positions
        circuit.addWire(new LtspiceWire(-16, 192, 144, 192)); // gnd rail

        // FLAGs
        circuit.addFlag(new LtspiceFlag(-16, -32, "vin"));
        circuit.addFlag(new LtspiceFlag(-16, 192, "gnd"));

        AscToIpesConverter.ConversionResult result = converter.convert(circuit);
        assertNotNull(result.getModel());
        // 3 components should be converted
        assertEquals(3, result.getModel().getCircuitComponents().size());
    }

    @Test
    void testConvert_defaultSimulationParameters() {
        LtspiceCircuit circuit = buildSimpleRC();
        AscToIpesConverter.ConversionResult result = converter.convert(circuit);
        CircuitModel model = result.getModel();

        assertTrue(model.getSimulationDuration() > 0, "Simulation duration should be set");
        assertTrue(model.getTimeStep() > 0, "Time step should be set");
    }

    @Test
    void testConvert_warningsIncludeSimulationNote() {
        LtspiceCircuit circuit = buildSimpleRC();
        AscToIpesConverter.ConversionResult result = converter.convert(circuit);

        assertTrue(result.getWarnings().stream()
                        .anyMatch(w -> w.toLowerCase().contains("simulation")),
                "Should warn about default simulation parameters");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static LtspiceComponent comp(String type, String inst, String value,
                                          int x, int y, String orientation) {
        LtspiceComponent c = new LtspiceComponent();
        c.setSymbolType(type);
        c.setInstName(inst);
        c.setValue(value);
        c.setX(x);
        c.setY(y);
        c.setOrientation(orientation);
        return c;
    }

    private static LtspiceCircuit buildSimpleRC() {
        LtspiceCircuit circuit = new LtspiceCircuit();
        circuit.setSourceName("simple_rc.asc");

        // Components
        circuit.addComponent(comp("voltage", "V1", "SINE(0 5 1k)", -16, 80, "R0"));
        circuit.addComponent(comp("res", "R1", "1k", 80, 64, "R0"));
        circuit.addComponent(comp("cap", "C1", "100n", 144, 96, "R0"));

        // Wires forming two-node net (vin and gnd)
        circuit.addWire(new LtspiceWire(-16, 0, 80, 0));   // top rail
        circuit.addWire(new LtspiceWire(-16, 192, 160, 192)); // bottom rail (gnd)

        // Flags
        circuit.addFlag(new LtspiceFlag(-16, 0, "vin"));
        circuit.addFlag(new LtspiceFlag(-16, 192, "gnd"));

        return circuit;
    }
}
