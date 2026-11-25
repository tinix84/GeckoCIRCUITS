package gecko.rest.config;

import gecko.rest.GeckoRestApiApplication;
import gecko.rest.model.SimulationRequest;
import gecko.rest.model.SimulationResponse;
import gecko.rest.persistence.PostgresPersistence;
import gecko.rest.persistence.SimulationMetadata;
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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativePersistenceTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "GECKO_TEST_POSTGRES", matches = "true")
    void migratesEmptySchemaAndPreservesCircuitsAndSimulationMetadata() throws Exception {
        String[] options = postgresOptions();
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
    @EnabledIfEnvironmentVariable(named = "GECKO_TEST_POSTGRES", matches = "true")
    void releasesQuotaOnlyForTheReplicaThatLostItsPostgresSession() {
        String[] options = postgresOptions();
        try (var first = application("postgres", options); var second = application("postgres", options)) {
            var lost = first.getBean(PostgresPersistence.class);
            var remaining = second.getBean(PostgresPersistence.class);
            var jdbc = second.getBean(JdbcTemplate.class);
            String tenant = "course";
            lost.admitSimulations(List.of(job("abandoned-pending", tenant), job("abandoned-running", tenant),
                    job("completed", tenant)), tenant, 4);
            remaining.admitSimulations(List.of(job("live", tenant)), tenant, 4);
            lost.saveSimulation(job("abandoned-running", tenant, SimulationStatus.RUNNING));
            lost.saveSimulation(job("completed", tenant, SimulationStatus.COMPLETED));
            remaining.saveSimulation(job("live", tenant, SimulationStatus.RUNNING));
            assertThat(remaining.activeJobs(tenant)).isEqualTo(3);

            Integer pid = jdbc.queryForObject("""
                    select lock.pid from pg_locks lock
                    join simulation_job job on job.replica_id = lock.objid::integer
                    where job.id = 'abandoned-pending' and lock.locktype = 'advisory'
                      and lock.objsubid = 2 and lock.granted
                      and lock.classid = (select oid from pg_namespace where nspname = current_schema())
                      and lock.database = (select oid from pg_database where datname = current_database())
                    """, Integer.class);
            assertThat(jdbc.queryForObject("select pg_terminate_backend(?, 5000)", Boolean.class, pid)).isTrue();

            // Use admission first to prove that quota recovers without a preliminary read.
            remaining.admitSimulations(List.of(job("replacement", tenant)), tenant, 2);
            assertThat(remaining.activeJobs(tenant)).isEqualTo(2);
            assertThatThrownBy(() -> remaining.admitSimulations(List.of(job("excess", tenant)), tenant, 2))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
            for (String id : List.of("abandoned-pending", "abandoned-running")) {
                var response = remaining.simulation(id, tenant).orElseThrow().response();
                assertThat(response.getStatus()).isEqualTo(SimulationStatus.FAILED);
                assertThat(response.getEndTime()).isNotNull();
                assertThat(response.getErrorMessage()).contains("replica stopped");
                assertThat(jdbc.queryForObject("""
                        select count(*) from audit_log where entity_id = ? and action = 'FAILED'
                        """, Long.class, id)).isEqualTo(1L);
            }
            assertThat(remaining.simulation("completed", tenant).orElseThrow().status()).isEqualTo("COMPLETED");
            assertThat(remaining.simulation("live", tenant).orElseThrow().status()).isEqualTo("RUNNING");
            remaining.saveSimulation(job("abandoned-running", tenant, SimulationStatus.COMPLETED));
            assertThat(remaining.simulation("abandoned-running", tenant).orElseThrow().status()).isEqualTo("FAILED");
            remaining.saveSimulation(job("live", tenant, SimulationStatus.COMPLETED));
            assertThat(remaining.activeJobs(tenant)).isEqualTo(1);
        }
    }

    private static SimulationMetadata job(String id, String tenant) {
        return job(id, tenant, SimulationStatus.PENDING);
    }

    private static SimulationMetadata job(String id, String tenant, SimulationStatus status) {
        var response = new SimulationResponse(id);
        response.setStartTime(Instant.now());
        response.setStatus(status);
        if (status == SimulationStatus.COMPLETED) response.setEndTime(Instant.now());
        return new SimulationMetadata(null, "owner", tenant, response);
    }

    private static String[] postgresOptions() {
        String schema = "gecko_test_" + UUID.randomUUID().toString().replace("-", "");
        return new String[] {
            "--spring.flyway.schemas=" + schema,
            "--spring.flyway.default-schema=" + schema,
            "--spring.jpa.properties.hibernate.default_schema=" + schema,
            "--spring.datasource.hikari.connection-init-sql=SET search_path TO " + schema
        };
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
