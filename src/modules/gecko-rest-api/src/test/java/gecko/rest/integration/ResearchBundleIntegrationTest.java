package gecko.rest.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ResearchBundleIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    @Test
    void buckReplaysFromTheCapturedCircuitAfterTheRegistryEntryIsGone() throws Exception {
        Path buck = Path.of("../gecko-simulation-core/src/test/resources/ipes/buck_simple.ipes");
        MockMultipartFile file = new MockMultipartFile("file", "buck_simple.ipes", "application/gzip",
                Files.readAllBytes(buck));
        String circuitId = read(mvc.perform(multipart("/api/v1/circuits/parse").file(file))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray())
                .path("circuitId").asText();
        var request = json.valueToTree(Map.of("circuitId", circuitId, "simulationTime", 0.002, "timeStep", 0.00001));
        String simulationId = read(mvc.perform(post("/api/v1/simulations")
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray())
                .path("simulationId").asText();
        mvc.perform(delete("/api/v1/circuits/{id}", circuitId)).andExpect(status().isNoContent());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                mvc.perform(get("/api/v1/simulations/{id}", simulationId))
                        .andExpect(jsonPath("$.status").value("COMPLETED")));

        byte[] bundle = mvc.perform(get("/api/v1/simulations/{id}/bundle", simulationId))
                .andExpect(status().isOk()).andExpect(content().contentType("application/zip"))
                .andReturn().getResponse().getContentAsByteArray();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bundle))) {
            ZipEntry entry;
            boolean checkedManifest = false;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals("request.json")) {
                    JsonNode savedRequest = read(zip.readAllBytes());
                    request.fields().forEachRemaining(field ->
                            assertEquals(field.getValue(), savedRequest.get(field.getKey())));
                } else if (entry.getName().equals("manifest.json")) {
                    JsonNode manifest = read(zip.readAllBytes());
                    assertTrue(manifest.path("gitSha").asText().matches("[a-f0-9]{40}|unknown"));
                    assertTrue(manifest.path("sha256").path("circuit.ipes").asText().matches("[a-f0-9]{64}"));
                    assertTrue(manifest.path("signals").size() > 1);
                    checkedManifest = true;
                }
            }
            assertTrue(checkedManifest);
        }

        JsonNode result = read(mvc.perform(post("/api/v1/bundles/verify")
                        .contentType("application/zip").content(bundle))
                .andExpect(status().isOk()).andExpect(jsonPath("$.passed").value(true))
                .andReturn().getResponse().getContentAsByteArray());
        JsonNode deviations = result.path("maxRelativeDeviation");
        assertTrue(deviations.size() > 1);
        for (JsonNode value : deviations) {
            assertTrue(value.isNumber());
            assertEquals(0, value.asDouble());
        }

        mvc.perform(post("/api/v1/bundles/verify").contentType("application/zip")
                        .content(changeCircuitWithoutChangingManifest(bundle)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Checksum mismatch for circuit.ipes"));
    }

    private JsonNode read(byte[] content) throws Exception {
        return json.readTree(content);
    }

    private static byte[] changeCircuitWithoutChangingManifest(byte[] bundle) throws Exception {
        var modified = new ByteArrayOutputStream();
        try (var source = new ZipInputStream(new ByteArrayInputStream(bundle));
             var target = new ZipOutputStream(modified)) {
            ZipEntry entry;
            while ((entry = source.getNextEntry()) != null) {
                target.putNextEntry(new ZipEntry(entry.getName()));
                source.transferTo(target);
                if (entry.getName().equals("circuit.ipes")) target.write(1);
                target.closeEntry();
            }
        }
        return modified.toByteArray();
    }
}
