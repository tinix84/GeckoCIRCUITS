package gecko.rest.config;

import gecko.rest.GeckoRestApiApplication;
import gecko.rest.model.SimulationRequest;
import gecko.rest.model.SimulationResponse.SimulationStatus;
import gecko.rest.service.CircuitFileService;
import gecko.rest.service.SimulationService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class NativePersistenceTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "GECKO_TEST_POSTGRES", matches = "true")
    void migratesEmptySchemaAndPreservesCircuitsAndSimulationMetadata() throws Exception {
        String schema = "gecko_test_" + UUID.randomUUID().toString().replace("-", "");
        String[] options = {
            "--spring.flyway.schemas=" + schema,
            "--spring.flyway.default-schema=" + schema,
            "--spring.jpa.properties.hibernate.default_schema=" + schema,
            "--spring.datasource.hikari.connection-init-sql=SET search_path TO " + schema
        };
        String circuitId;
        String jobId;
        try (var context = application("postgres", options)) {
            var jdbc = context.getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForObject("select count(*) from circuit_meta", Long.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from simulation_job", Long.class)).isZero();
            var path = Path.of("../gecko-simulation-core/src/test/resources/ipes/buck_simple.ipes");
            var upload = context.getBean(CircuitFileService.class).loadCircuit(
                    Base64.getEncoder().encodeToString(Files.readAllBytes(path)), "buck_simple.ipes");
            assertThat(upload.status()).isEqualTo("loaded");
            circuitId = upload.circuitId();
            var request = new SimulationRequest(null, 0.002, 1e-6);
            request.setCircuitId(circuitId);
            var service = context.getBean(SimulationService.class);
            jobId = service.submitSimulation(request).getSimulationId();
            Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
            while (!"COMPLETED".equals(jdbc.queryForObject(
                    "select status from simulation_job where id = ?", String.class, jobId))
                    && Instant.now().isBefore(deadline)) {
                Thread.sleep(50);
            }
            assertThat(service.getSimulationStatus(jobId).getStatus()).isEqualTo(SimulationStatus.COMPLETED);
            assertThat(service.getSimulation(jobId).getResults()).isNotEmpty();
            assertThat(jdbc.queryForObject(
                    "select count(*) from audit_log where entity_id = ? and action = 'COMPLETED'",
                    Long.class, jobId)).isEqualTo(1L);
        }
        try (var restarted = application("postgres", options)) {
            assertThat(restarted.getBean(CircuitFileService.class).getCircuitInfo(circuitId).filename())
                    .isEqualTo("buck_simple.ipes");
            var service = restarted.getBean(SimulationService.class);
            var previous = service.getSimulation(jobId);
            assertThat(previous.getStatus()).isEqualTo(SimulationStatus.COMPLETED);
            assertThat(previous.getEndTime()).isNotNull();
            assertThat(previous.getResults()).isEmpty();
            assertThat(service.getAllSimulations()).containsKey(jobId);
            assertThat(service.getSimulationStatuses()).extracting(result -> result.getSimulationId())
                    .contains(jobId);
            assertThat(service.getStatistics().get(SimulationStatus.COMPLETED)).isEqualTo(1L);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "GECKO_TEST_REDIS", matches = "true")
    void twoApplicationInstancesShareTheSubmissionBudget() throws Exception {
        String[] options = {
            "--gecko.api.rate-limit=5",
            "--gecko.api.rate-limit-simulation=2",
            "--gecko.api.rate-limit-namespace=test-" + UUID.randomUUID()
        };
        try (var first = application("redis", options); var second = application("redis", options)) {
            var left = first.getBean(RateLimitFilter.class);
            var right = second.getBean(RateLimitFilter.class);
            assertThat(submit(left).getStatus()).isEqualTo(200);
            assertThat(submit(right).getStatus()).isEqualTo(200);
            assertThat(submit(left).getStatus()).isEqualTo(429);
            assertThat(submit(right).getStatus()).isEqualTo(429);
        }
    }

    private static ConfigurableApplicationContext application(String profile, String... options) {
        String[] arguments = new String[options.length + 1];
        arguments[0] = "--server.port=0";
        System.arraycopy(options, 0, arguments, 1, options.length);
        return new SpringApplicationBuilder(GeckoRestApiApplication.class, FixedClock.class)
                .profiles(profile).run(arguments);
    }

    private static MockHttpServletResponse submit(RateLimitFilter filter) throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/simulations");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock persistenceTestClock() {
            return Clock.fixed(Instant.parse("2026-11-25T11:01:01Z"), ZoneOffset.UTC);
        }
    }
}
