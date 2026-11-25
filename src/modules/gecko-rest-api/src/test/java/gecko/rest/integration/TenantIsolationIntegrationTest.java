package gecko.rest.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import gecko.rest.config.ApiIdentity;
import gecko.rest.config.JwtService;
import gecko.rest.service.SimulationService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "gecko.api.auth-enabled=true", "gecko.api.keys=alice,bob,admin",
    "gecko.api.default-role=USER", "gecko.api.jwt.enabled=true",
    "gecko.api.jwt.secret=only-for-testing-tenants-42-1234567",
    "gecko.api.quota.alpha.max-circuits=1", "gecko.api.quota.beta.max-concurrent=1",
    "gecko.jobs.workers=1"
})
@ActiveProfiles("tenant")
@AutoConfigureMockMvc
class TenantIsolationIntegrationTest {
    private static final String CIRCUITS = "/api/v1/circuits";
    private static final String SIMULATIONS = "/api/v1/simulations";
    private static final String ADMIN = "/api/v1/admin/tenants";
    private static final String KEY = "X-API-Key";

    @DynamicPropertySource
    static void identities(DynamicPropertyRegistry properties) {
        properties.add("gecko.api.tenants." + ApiIdentity.keyId("alice"), () -> "alpha");
        properties.add("gecko.api.tenants." + ApiIdentity.keyId("bob"), () -> "beta");
        properties.add("gecko.api.tenants." + ApiIdentity.keyId("admin"), () -> "alpha");
        properties.add("gecko.api.roles." + ApiIdentity.keyId("admin"), () -> "ADMIN");
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService tokens;
    @Autowired private SimulationService simulations;

    @Test
    void hidesForeignIdsAndEnforcesTenantQuotasAndAdminRole() throws Exception {
        String alpha = upload("alice");
        String beta = upload("bob");
        mvc.perform(get(CIRCUITS + "/{id}/info", alpha).header(KEY, "bob")).andExpect(status().isNotFound());
        mvc.perform(get(CIRCUITS).header(KEY, "bob"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.circuits[0].circuitId").value(beta));
        mvc.perform(uploadRequest("alice")).andExpect(status().isTooManyRequests());
        mvc.perform(submission(alpha, "bob")).andExpect(status().isNotFound());

        var worker = (Executor) ReflectionTestUtils.getField(simulations, "executorService");
        assertThat(worker).isNotNull();
        var occupied = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        worker.execute(() -> {
            occupied.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertThat(occupied.await(5, TimeUnit.SECONDS)).isTrue();
            var response = mvc.perform(submission(beta, "bob")).andExpect(status().isCreated()).andReturn();
            String job = json.readTree(response.getResponse().getContentAsString()).path("simulationId").asText();
            mvc.perform(submission(beta, "bob")).andExpect(status().isTooManyRequests());
            mvc.perform(get(SIMULATIONS + "/{id}", job).header(KEY, "alice")).andExpect(status().isNotFound());
            mvc.perform(get(SIMULATIONS + "/{id}/ws-info", job).header(KEY, "admin")).andExpect(status().isNotFound());
            mvc.perform(delete(SIMULATIONS + "/{id}", job).header(KEY, "admin")).andExpect(status().isNotFound());
            mvc.perform(get(SIMULATIONS).header(KEY, "alice"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
            mvc.perform(get(ADMIN).header(KEY, "alice")).andExpect(status().isForbidden());
            mvc.perform(get(ADMIN).header(KEY, "admin"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$[1].tenant").value("beta"))
                    .andExpect(jsonPath("$[1].activeSimulations").value(1));
        } finally {
            release.countDown();
        }

        String access = tokens.issue(ApiIdentity.keyId("bob"), List.of("USER")).accessToken();
        assertThat(tokens.decode(access, "access").getClaimAsString("tenant")).isEqualTo("beta");
        mvc.perform(get(CIRCUITS + "/{id}/info", beta).header("Authorization", "Bearer " + access))
                .andExpect(status().isOk());
        mvc.perform(get(CIRCUITS + "/{id}/info", alpha).header("Authorization", "Bearer " + access))
                .andExpect(status().isNotFound());
    }

    private String upload(String key) throws Exception {
        var result = mvc.perform(uploadRequest(key)).andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("circuitId").asText();
    }

    private org.springframework.test.web.servlet.RequestBuilder uploadRequest(String key) throws Exception {
        var buck = Path.of("../gecko-simulation-core/src/test/resources/ipes/buck_simple.ipes");
        var file = new MockMultipartFile("file", "buck_simple.ipes", "application/gzip", Files.readAllBytes(buck));
        return multipart(CIRCUITS + "/parse").file(file).header(KEY, key);
    }

    private org.springframework.test.web.servlet.RequestBuilder submission(String circuit, String key) {
        return post(SIMULATIONS).header(KEY, key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"circuitId\":\"" + circuit + "\",\"simulationTime\":0.002,\"timeStep\":0.000001}");
    }
}
