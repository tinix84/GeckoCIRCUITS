package gecko.rest.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import gecko.rest.config.ApiIdentity;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "gecko.education.enabled=true", "gecko.api.auth-enabled=true",
    "gecko.api.keys=instructor,learner,other-instructor",
    "gecko.api.quota.course-a.max-concurrent=1", "gecko.api.quota.course-b.max-concurrent=0",
    "gecko.api.jwt.enabled=true", "gecko.api.jwt.secret=lab-grading-test-signing-key-123456"
})
@ActiveProfiles("tenant")
@AutoConfigureMockMvc
class LabAssignmentIntegrationTest {
    @TempDir static Path storage;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    @DynamicPropertySource
    static void identities(DynamicPropertyRegistry properties) {
        properties.add("gecko.education.directory", () -> storage.toString());
        properties.add("gecko.api.roles." + ApiIdentity.keyId("learner"), () -> "USER");
        properties.add("gecko.api.tenants." + ApiIdentity.keyId("instructor"), () -> "course-a");
        properties.add("gecko.api.tenants." + ApiIdentity.keyId("learner"), () -> "course-a");
        properties.add("gecko.api.tenants." + ApiIdentity.keyId("other-instructor"), () -> "course-b");
    }

    @Test
    void gradesReferenceAndChangedInductorAndScopesHistory() throws Exception {
        byte[] reference = Files.readAllBytes(
                Path.of("../gecko-simulation-core/src/test/resources/ipes/buck_simple.ipes"));
        String assignment = """
                {"title":"Buck","duration":0.004,"timeStep":0.000001,"checks":[
                  {"signal":"u2","metric":"mean","t0":0.003,"t1":0.004,"target":18.82,"tolerance":0.3764},
                  {"signal":"iL","metric":"ripple_pp","t0":0.003,"t1":0.004,"target":1.17,"tolerance":0.10}
                ]}
                """;
        var definition = new MockMultipartFile("assignment", "definition.json", "application/json",
                assignment.getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/v1/assignments").file(definition).file(circuit(reference))
                        .header("X-API-Key", "other-instructor"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Tenant quota exceeded."));
        var create = mvc.perform(multipart("/api/v1/assignments").file(definition).file(circuit(reference))
                        .header("X-API-Key", "instructor"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray();
        String id = json.readTree(create).path("id").asText();
        String endpoint = "/api/v1/assignments/" + id + "/submissions";

        mvc.perform(multipart(endpoint).file(circuit(reference)).header("X-API-Key", "learner"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.score").value(1.0))
                .andExpect(jsonPath("$.checks[0].passed").value(true))
                .andExpect(jsonPath("$.checks[1].passed").value(true))
                .andExpect(jsonPath("$.checks[1].score").value(1));

        byte[] changed;
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(reference))) {
            String original = new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
            String variant = original.replace("parameter[] 3.0E-4 ", "parameter[] 3.0E-5 ");
            assertNotEquals(original, variant);
            changed = variant.getBytes(StandardCharsets.UTF_8);
        }
        var pair = mvc.perform(post("/api/v1/auth/token").header("X-API-Key", "learner"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        String access = json.readTree(pair).path("accessToken").asText();
        mvc.perform(multipart(endpoint).file(circuit(changed)).header("Authorization", "Bearer " + access))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.checks[1].passed").value(false))
                .andExpect(jsonPath("$.checks[1].score").value(0));

        mvc.perform(get(endpoint).header("X-API-Key", "instructor"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].student").value(ApiIdentity.keyId("learner")));
        mvc.perform(get(endpoint).header("X-API-Key", "learner")).andExpect(status().isForbidden());
        mvc.perform(get(endpoint).header("X-API-Key", "other-instructor")).andExpect(status().isNotFound());
        mvc.perform(multipart(endpoint).file(circuit(reference)).header("X-API-Key", "other-instructor"))
                .andExpect(status().isNotFound());
    }

    private static MockMultipartFile circuit(byte[] circuit) {
        return new MockMultipartFile("file", "buck.ipes", MediaType.APPLICATION_OCTET_STREAM_VALUE, circuit);
    }
}
