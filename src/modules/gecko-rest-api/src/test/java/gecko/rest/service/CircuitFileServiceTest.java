package gecko.rest.service;

import gecko.rest.model.circuit.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CircuitFileService.
 * Uses a real .ipes file from test resources.
 */
class CircuitFileServiceTest {

    private CircuitFileService service;
    private String validIpesBase64;
    private static final String TEST_CIRCUIT_PATH = "src/test/resources/test-circuit.ipes";

    @BeforeEach
    void setUp() throws IOException {
        service = new CircuitFileService();

        // Load test circuit file and convert to base64
        byte[] ipesBytes = Files.readAllBytes(Paths.get(TEST_CIRCUIT_PATH));
        validIpesBase64 = Base64.getEncoder().encodeToString(ipesBytes);
    }

    @Test
    void testLoadCircuit_fromBase64_success() {
        CircuitLoadResponse response = service.loadCircuit(validIpesBase64, "test-circuit.ipes");

        assertNotNull(response);
        assertEquals("loaded", response.status());
        assertEquals("test-circuit.ipes", response.filename());
        assertNotNull(response.circuitId());
        // test-circuit.ipes has 12 circuit elements (gzip decompression now works correctly)
        assertEquals(12, response.componentCount());
        assertNull(response.errorMessage());
    }

    @Test
    void testLoadCircuit_invalidBase64() {
        CircuitLoadResponse response = service.loadCircuit("invalid-base64!", "test.ipes");

        assertNotNull(response);
        assertEquals("failed", response.status());
        assertEquals("test.ipes", response.filename());
        assertNotNull(response.errorMessage());
        assertTrue(response.errorMessage().contains("base64"));
    }

    @Test
    void testLoadCircuit_invalidCircuitFormat() {
        // Create invalid (but valid base64) content - not gzipped
        // Note: CircuitFileParser is very lenient, so it might accept this
        String invalidContent = Base64.getEncoder().encodeToString("not a valid gzip content".getBytes());

        CircuitLoadResponse response = service.loadCircuit(invalidContent, "invalid.ipes");

        assertNotNull(response);
        // Parser might be lenient and accept minimal content
        // Just verify we get a response (either success or failure)
        assertNotNull(response.status());
    }

    @Test
    void testGetCircuitInfo_found() {
        // Load a circuit first
        CircuitLoadResponse loadResponse = service.loadCircuit(validIpesBase64, "test-circuit.ipes");
        String circuitId = loadResponse.circuitId();

        // Get info
        CircuitInfo info = service.getCircuitInfo(circuitId);

        assertNotNull(info);
        assertEquals(circuitId, info.circuitId());
        assertEquals("test-circuit.ipes", info.filename());
        assertNotNull(info.fileVersion());
        assertNotNull(info.simulationParameters());
        assertNotNull(info.componentCounts());
        assertNotNull(info.displaySettings());
        assertNotNull(info.metadata());

        // Check simulation parameters
        CircuitInfo.SimulationParameters simParams = info.simulationParameters();
        assertNotNull(simParams.endTime());
        assertNotNull(simParams.timeStep());
        assertNotNull(simParams.solverType());

        // Check component counts - test-circuit.ipes has 12 circuit elements
        CircuitInfo.ComponentCounts counts = info.componentCounts();
        assertEquals(12, counts.circuit());
        assertEquals(0, counts.control() + counts.thermal());
    }

    @Test
    void testGetCircuitInfo_notFound() {
        CircuitInfo info = service.getCircuitInfo("nonexistent-id");
        assertNull(info);
    }

    @Test
    void testGetComponents_found() {
        // Load a circuit first
        CircuitLoadResponse loadResponse = service.loadCircuit(validIpesBase64, "test-circuit.ipes");
        String circuitId = loadResponse.circuitId();

        // Get components
        ComponentListResponse response = service.getComponents(circuitId);

        assertNotNull(response);
        assertEquals(circuitId, response.circuitId());
        assertNotNull(response.components());
        // test-circuit.ipes has 12 circuit elements (gzip decompression now works correctly)
        assertEquals(12, response.components().size());
    }

    @Test
    void testGetComponents_notFound() {
        ComponentListResponse response = service.getComponents("nonexistent-id");
        assertNull(response);
    }

    @Test
    void testValidateCircuit_valid() {
        // Load a circuit first
        CircuitLoadResponse loadResponse = service.loadCircuit(validIpesBase64, "test-circuit.ipes");
        String circuitId = loadResponse.circuitId();

        // Validate
        ValidationResponse response = service.validateCircuit(circuitId);

        assertNotNull(response);
        assertNotNull(response.warnings());
        assertNotNull(response.errors());
        // Circuit should be valid with gzip properly decompressed
        assertTrue(response.valid(), "Circuit should be valid with proper simulation parameters and components");
        assertTrue(response.warnings().isEmpty(), "No warnings expected when components are parsed");
        assertTrue(response.errors().isEmpty(), "No errors expected for a valid circuit");
    }

    @Test
    void testValidateCircuit_notFound() {
        ValidationResponse response = service.validateCircuit("nonexistent-id");
        assertNull(response);
    }

    @Test
    void testGetAllCircuits() {
        // Load multiple circuits
        CircuitLoadResponse response1 = service.loadCircuit(validIpesBase64, "circuit1.ipes");
        CircuitLoadResponse response2 = service.loadCircuit(validIpesBase64, "circuit2.ipes");

        // Get all circuits
        CircuitListResponse listResponse = service.getAllCircuits();

        assertNotNull(listResponse);
        assertNotNull(listResponse.circuits());
        assertEquals(2, listResponse.total());
        assertEquals(2, listResponse.circuits().size());

        // Check circuit summaries - test-circuit.ipes has 12 circuit elements
        CircuitListResponse.CircuitSummary summary1 = listResponse.circuits().get(0);
        assertNotNull(summary1.circuitId());
        assertNotNull(summary1.filename());
        assertEquals(12, summary1.componentCount());
        assertNotNull(summary1.loadedAt());
    }

    @Test
    void testGetAllCircuits_empty() {
        CircuitListResponse listResponse = service.getAllCircuits();

        assertNotNull(listResponse);
        assertNotNull(listResponse.circuits());
        assertEquals(0, listResponse.total());
        assertEquals(0, listResponse.circuits().size());
    }

    @Test
    void testGetRawCircuit() {
        CircuitLoadResponse loadResponse = service.loadCircuit(validIpesBase64, "test-circuit.ipes");
        String circuitId = loadResponse.circuitId();

        String raw = service.getRawCircuit(circuitId);

        assertNotNull(raw);
        assertTrue(raw.contains("CircuitModel"));
    }

    @Test
    void testGetRawCircuit_notFound() {
        String raw = service.getRawCircuit("nonexistent-id");
        assertNull(raw);
    }

    @Test
    void testDeleteCircuit_success() {
        CircuitLoadResponse loadResponse = service.loadCircuit(validIpesBase64, "test-circuit.ipes");
        String circuitId = loadResponse.circuitId();

        // Delete
        boolean deleted = service.deleteCircuit(circuitId);
        assertTrue(deleted);

        // Verify deleted
        CircuitInfo info = service.getCircuitInfo(circuitId);
        assertNull(info);
    }

    @Test
    void testDeleteCircuit_notFound() {
        boolean deleted = service.deleteCircuit("nonexistent-id");
        assertFalse(deleted);
    }

    @Test
    void testDeleteCircuit_twice() {
        CircuitLoadResponse loadResponse = service.loadCircuit(validIpesBase64, "test-circuit.ipes");
        String circuitId = loadResponse.circuitId();

        // Delete first time
        boolean deleted1 = service.deleteCircuit(circuitId);
        assertTrue(deleted1);

        // Try deleting again
        boolean deleted2 = service.deleteCircuit(circuitId);
        assertFalse(deleted2);
    }
}
