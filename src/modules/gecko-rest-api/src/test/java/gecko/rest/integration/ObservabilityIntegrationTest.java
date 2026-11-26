package gecko.rest.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "gecko.api.auth-enabled=true", "gecko.api.keys=observability-test-key"
})
@ActiveProfiles("observability")
@AutoConfigureMockMvc
@AutoConfigureObservability
class ObservabilityIntegrationTest {
    private static final String KEY = "observability-test-key";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    @Test
    void exposesPublicProbesAndCountsOneCompletedSimulationInPrivateMetrics() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());

        var fixture = Path.of("../gecko-simulation-core/src/test/resources/ipes/buck_simple.ipes");
        var file = new MockMultipartFile("file", "buck_simple.ipes", "application/gzip", Files.readAllBytes(fixture));
        String circuitId = json.readTree(mvc.perform(multipart("/api/v1/circuits/parse").file(file)
                .header("X-API-Key", KEY)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray())
                .path("circuitId").asText();
        var request = Map.of("circuitId", circuitId, "simulationTime", 0.002, "timeStep", 0.00001);
        String simulationId = json.readTree(mvc.perform(post("/api/v1/simulations")
                .header("X-API-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray())
                .path("simulationId").asText();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                mvc.perform(get("/api/v1/simulations/{id}", simulationId).header("X-API-Key", KEY))
                        .andExpect(jsonPath("$.status").value("COMPLETED")));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> mvc.perform(get("/actuator/prometheus").header("X-API-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(content().string(matchesPattern("(?s).*\\ngecko_simulations_total\\{status=\"COMPLETED\",?\\} 1\\.0\\n.*")))
                .andExpect(content().string(containsString("gecko_simulation_duration_seconds_bucket")))
                .andExpect(content().string(containsString("gecko_simulations_active 0.0")))
                .andExpect(content().string(containsString("gecko_simulation_queue_depth 0.0"))));
    }
}
