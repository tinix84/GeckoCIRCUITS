/*  This file is part of GeckoCIRCUITS. Copyright (C) ETH Zurich, Gecko-Simulations AG
 *
 *  GeckoCIRCUITS is free software: you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation, either version 3 of the License, or (at your option) any later version.
 */
package gecko.core.io;

import gecko.core.allg.SolverType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PlecsFileParser}.
 */
class PlecsFileParserTest {

    private PlecsFileParser parser;

    @BeforeEach
    void setUp() {
        parser = new PlecsFileParser();
    }

    // ── Simulation parameters ──────────────────────────────────────────────────

    @Test
    void parse_simulationSettings_extracted() throws Exception {
        String xml = """
                <?xml version="1.0"?>
                <PLECS version="4.7">
                  <Circuit name="Test">
                    <Simulation>
                      <StopTime>0.05</StopTime>
                      <TimeStep>2e-6</TimeStep>
                      <Solver>ode23tb</Solver>
                    </Simulation>
                  </Circuit>
                </PLECS>
                """;

        PlecsFileParser.ParseResult result = parseXml(xml);
        CircuitModel model = result.model();

        assertEquals(0.05, model.getSimulationDuration(), 1e-10);
        assertEquals(2e-6, model.getTimeStep(), 1e-20);
        assertEquals(SolverType.SOLVER_TRZ, model.getSolverType());
    }

    @Test
    void parse_noSimulationSettings_defaults() throws Exception {
        String xml = """
                <?xml version="1.0"?>
                <PLECS version="4.7">
                  <Circuit name="Empty"/>
                </PLECS>
                """;

        PlecsFileParser.ParseResult result = parseXml(xml);
        CircuitModel model = result.model();

        assertEquals(0.02, model.getSimulationDuration(), 1e-10);
        assertEquals(1e-6, model.getTimeStep(), 1e-20);
        assertTrue(result.warnings().stream()
                .anyMatch(w -> w.contains("time step") || w.contains("stop time")),
                "Should warn about missing simulation parameters");
    }

    // ── Component parsing ──────────────────────────────────────────────────────

    @Test
    void parse_resistor_mappedToType1() throws Exception {
        String xml = buildCircuitXml(
            "<Block type=\"Resistor\" name=\"R1\">" +
            "  <Parameter name=\"R\" value=\"100\"/>" +
            "  <Position xpos=\"100\" ypos=\"200\"/>" +
            "</Block>");

        PlecsFileParser.ParseResult result = parseXml(xml);
        CircuitModel model = result.model();

        assertEquals(1, model.getCircuitComponents().size());
        CircuitModel.ComponentData r = model.getCircuitComponents().get(0);
        assertEquals(1, r.getType());
        assertEquals("R1", r.getName());
        assertEquals(100.0, (Double) r.getParameters().get("resistance"), 1e-10);
    }

    @Test
    void parse_inductor_mappedToType2() throws Exception {
        String xml = buildCircuitXml(
            "<Block type=\"Inductor\" name=\"L1\">" +
            "  <Parameter name=\"L\" value=\"1e-3\"/>" +
            "  <Position xpos=\"50\" ypos=\"80\"/>" +
            "</Block>");

        PlecsFileParser.ParseResult result = parseXml(xml);
        CircuitModel model = result.model();

        assertEquals(1, model.getCircuitComponents().size());
        CircuitModel.ComponentData l = model.getCircuitComponents().get(0);
        assertEquals(2, l.getType());
        assertEquals("L1", l.getName());
        assertEquals(1e-3, (Double) l.getParameters().get("inductance"), 1e-15);
    }

    @Test
    void parse_capacitor_mappedToType3() throws Exception {
        String xml = buildCircuitXml(
            "<Block type=\"Capacitor\" name=\"C1\">" +
            "  <Parameter name=\"C\" value=\"100e-6\"/>" +
            "</Block>");

        PlecsFileParser.ParseResult result = parseXml(xml);
        CircuitModel model = result.model();

        assertEquals(1, model.getCircuitComponents().size());
        CircuitModel.ComponentData c = model.getCircuitComponents().get(0);
        assertEquals(3, c.getType());
        assertEquals(100e-6, (Double) c.getParameters().get("capacitance"), 1e-18);
    }

    @Test
    void parse_voltageSource_mappedToType4() throws Exception {
        String xml = buildCircuitXml(
            "<Block type=\"VoltageSource\" name=\"Vdc\">" +
            "  <Parameter name=\"V\" value=\"400\"/>" +
            "</Block>");

        PlecsFileParser.ParseResult result = parseXml(xml);
        assertEquals(1, result.model().getCircuitComponents().size());
        assertEquals(4, result.model().getCircuitComponents().get(0).getType());
    }

    @Test
    void parse_currentSource_mappedToType5() throws Exception {
        String xml = buildCircuitXml(
            "<Block type=\"CurrentSource\" name=\"Idc\">" +
            "  <Parameter name=\"I\" value=\"10\"/>" +
            "</Block>");

        PlecsFileParser.ParseResult result = parseXml(xml);
        assertEquals(1, result.model().getCircuitComponents().size());
        assertEquals(5, result.model().getCircuitComponents().get(0).getType());
    }

    @Test
    void parse_diode_mappedToType6() throws Exception {
        String xml = buildCircuitXml(
            "<Block type=\"Diode\" name=\"D1\">" +
            "  <Parameter name=\"Vf\" value=\"0.7\"/>" +
            "</Block>");

        PlecsFileParser.ParseResult result = parseXml(xml);
        assertEquals(1, result.model().getCircuitComponents().size());
        CircuitModel.ComponentData d = result.model().getCircuitComponents().get(0);
        assertEquals(6, d.getType());
        assertEquals(0.7, (Double) d.getParameters().get("forwardVoltage"), 1e-10);
    }

    @Test
    void parse_igbt_mappedToType7() throws Exception {
        String xml = buildCircuitXml(
            "<Block type=\"IGBT\" name=\"Q1\">" +
            "  <Parameter name=\"Ron\" value=\"0.01\"/>" +
            "</Block>");

        PlecsFileParser.ParseResult result = parseXml(xml);
        assertEquals(1, result.model().getCircuitComponents().size());
        CircuitModel.ComponentData q = result.model().getCircuitComponents().get(0);
        assertEquals(7, q.getType());
        assertEquals(0.01, (Double) q.getParameters().get("resistance"), 1e-10);
    }

    @Test
    void parse_unsupportedBlockType_warningGenerated() throws Exception {
        String xml = buildCircuitXml(
            "<Block type=\"Transformer\" name=\"T1\"/>");

        PlecsFileParser.ParseResult result = parseXml(xml);

        assertEquals(0, result.model().getCircuitComponents().size());
        assertTrue(result.warnings().stream()
                .anyMatch(w -> w.contains("Transformer")),
                "Expected warning about unsupported Transformer block");
    }

    @Test
    void parse_multipleComponents_allConverted() throws Exception {
        String xml = buildCircuitXml(
            "<Block type=\"Resistor\" name=\"R1\"><Parameter name=\"R\" value=\"10\"/></Block>" +
            "<Block type=\"Inductor\" name=\"L1\"><Parameter name=\"L\" value=\"1e-3\"/></Block>" +
            "<Block type=\"Capacitor\" name=\"C1\"><Parameter name=\"C\" value=\"47e-6\"/></Block>" +
            "<Block type=\"Diode\" name=\"D1\"><Parameter name=\"Vf\" value=\"0.7\"/></Block>");

        PlecsFileParser.ParseResult result = parseXml(xml);
        assertEquals(4, result.model().getCircuitComponents().size());
        assertEquals(0, result.warnings().stream()
                .filter(w -> w.startsWith("Unsupported")).count());
    }

    @Test
    void parse_parameterViaExpressionChild_extracted() throws Exception {
        String xml = buildCircuitXml(
            "<Block type=\"Resistor\" name=\"R1\">" +
            "  <Parameter name=\"R\"><Expression>47</Expression></Parameter>" +
            "</Block>");

        PlecsFileParser.ParseResult result = parseXml(xml);
        assertEquals(1, result.model().getCircuitComponents().size());
        CircuitModel.ComponentData r = result.model().getCircuitComponents().get(0);
        assertEquals(47.0, (Double) r.getParameters().get("resistance"), 1e-10);
    }

    @Test
    void parse_componentPosition_extracted() throws Exception {
        String xml = buildCircuitXml(
            "<Block type=\"Resistor\" name=\"R1\">" +
            "  <Parameter name=\"R\" value=\"1\"/>" +
            "  <Position xpos=\"120\" ypos=\"240\"/>" +
            "</Block>");

        PlecsFileParser.ParseResult result = parseXml(xml);
        int[] pos = result.model().getCircuitComponents().get(0).getPosition();
        assertEquals(120, pos[0]);
        assertEquals(240, pos[1]);
    }

    // ── Error handling ─────────────────────────────────────────────────────────

    @Test
    void parse_invalidXml_throwsPlecsParseException() {
        String invalid = "this is not xml at all";
        byte[] bytes = invalid.getBytes(StandardCharsets.UTF_8);
        assertThrows(PlecsFileParser.PlecsParseException.class,
                () -> parser.parse(bytes, "bad.plecs"));
    }

    @Test
    void parse_wrongRootElement_throwsPlecsParseException() {
        String xml = "<?xml version=\"1.0\"?><SomeOtherTool/>";
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        assertThrows(PlecsFileParser.PlecsParseException.class,
                () -> parser.parse(bytes, "other.xml"));
    }

    // ── Solver mapping ─────────────────────────────────────────────────────────

    @Test
    void parse_solverOde15s_mappedToGearShichman() throws Exception {
        String xml = """
                <?xml version="1.0"?>
                <PLECS version="4.7">
                  <Circuit name="Test">
                    <Simulation>
                      <StopTime>0.01</StopTime>
                      <TimeStep>1e-6</TimeStep>
                      <Solver>ode15s</Solver>
                    </Simulation>
                  </Circuit>
                </PLECS>
                """;

        PlecsFileParser.ParseResult result = parseXml(xml);
        assertEquals(SolverType.SOLVER_GS, result.model().getSolverType());
    }

    @Test
    void parse_unknownSolver_defaultsToBackwardEuler() throws Exception {
        String xml = """
                <?xml version="1.0"?>
                <PLECS version="4.7">
                  <Circuit name="Test">
                    <Simulation>
                      <StopTime>0.01</StopTime>
                      <TimeStep>1e-6</TimeStep>
                      <Solver>someUnknownSolver</Solver>
                    </Simulation>
                  </Circuit>
                </PLECS>
                """;

        PlecsFileParser.ParseResult result = parseXml(xml);
        assertEquals(SolverType.SOLVER_BE, result.model().getSolverType());
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private PlecsFileParser.ParseResult parseXml(String xml) throws Exception {
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        return parser.parse(bytes, "test.plecs");
    }

    private String buildCircuitXml(String blocksXml) {
        return "<?xml version=\"1.0\"?>"
            + "<PLECS version=\"4.7\">"
            + "  <Circuit name=\"TestCircuit\">"
            + "    <Simulation>"
            + "      <StopTime>0.02</StopTime>"
            + "      <TimeStep>1e-6</TimeStep>"
            + "    </Simulation>"
            + "    <Blocks>" + blocksXml + "</Blocks>"
            + "  </Circuit>"
            + "</PLECS>";
    }
}
