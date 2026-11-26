package gecko.rest.config;

import gecko.core.preparation.ExecutionOptions;
import gecko.core.preparation.PreparedCircuit;
import gecko.core.simulation.HeadlessSimulationEngine;
import gecko.core.simulation.SimulationResult;
import gecko.rest.GeckoRestApiApplication;
import gecko.rest.model.BatchSimulationRequest;
import gecko.rest.model.SimulationRequest;
import gecko.rest.model.SimulationResponse;
import gecko.rest.persistence.PostgresPersistence;
import gecko.rest.persistence.SimulationMetadata;
import gecko.rest.model.SimulationResponse.SimulationStatus;
import gecko.rest.service.CircuitFileService;
import gecko.rest.service.SimulationService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
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
            assertThat(lost.saveSimulation(job("completed", tenant, SimulationStatus.COMPLETED))).isTrue();
            assertThat(lost.saveSimulation(job("completed", tenant, SimulationStatus.RUNNING))).isFalse();
            assertThat(lost.simulation("completed", tenant).orElseThrow().status()).isEqualTo("COMPLETED");
            assertThat(jdbc.queryForObject("""
                    select count(*) from audit_log where entity_id = 'completed' and action = 'RUNNING'
                    """, Long.class)).isZero();

            lost.admitSimulations(List.of(job("lookup-first", "lookup")), "lookup", 1);
            lost.admitSimulations(List.of(job("list-first", "list")), "list", 1);
            lost.admitSimulations(List.of(job("usage-first", "usage")), "usage", 1);
            remaining.saveSimulation(job("live", tenant, SimulationStatus.RUNNING));
            assertThat(remaining.activeJobs(tenant)).isEqualTo(3);

            terminateOwner(jdbc, "abandoned-pending");

            assertThat(remaining.simulation("lookup-first", "lookup").orElseThrow().status()).isEqualTo("FAILED");
            assertThat(remaining.simulations("list")).extracting(SimulationMetadata::status).containsExactly("FAILED");
            assertThat(remaining.activeJobs("usage")).isZero();
            assertThat(jdbc.queryForObject("select status from simulation_job where id = 'usage-first'", String.class))
                    .isEqualTo("FAILED");

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

    @Test
    @EnabledIfEnvironmentVariable(named = "GECKO_TEST_POSTGRES", matches = "true")
    void fencesRunningAndQueuedJobsWhenOnlyTheirPostgresSessionIsLost() throws Exception {
        String[] options = postgresOptions();
        var victimEngine = new ControlledEngine();
        var peerEngine = new ControlledEngine();
        try (var victim = application("postgres", options); var peer = application("postgres", options);
             var http = HttpClient.newHttpClient()) {
            var first = victim.getBean(SimulationService.class);
            var second = peer.getBean(SimulationService.class);
            var jdbc = peer.getBean(JdbcTemplate.class);
            var request = buckRequest(victim);
            var batch = new BatchSimulationRequest();
            batch.setCircuitId(request.getCircuitId());
            batch.setParameterSets(List.of(Map.of()));
            var settings = new BatchSimulationRequest.SimulationSettings();
            settings.setSimulationTime(request.getSimulationTime());
            settings.setTimeStep(request.getTimeStep());
            batch.setSimulationSettings(settings);
            var previous = first.submitBatch(batch);
            String completed = previous.getSimulationIds().getFirst();
            await(() -> first.getSimulationStatus(completed).getStatus() == SimulationStatus.COMPLETED);
            assertThat(first.getSimulation(completed).getResults()).isNotEmpty();

            ReflectionTestUtils.setField(first, "engineFactory", (Supplier<HeadlessSimulationEngine>) () -> victimEngine);
            ReflectionTestUtils.setField(second, "engineFactory", (Supplier<HeadlessSimulationEngine>) () -> peerEngine);
            String running = first.submitSimulation(request).getSimulationId();
            assertThat(victimEngine.started.await(15, TimeUnit.SECONDS)).isTrue();
            String pending = first.submitSimulation(request).getSimulationId();
            String unaffected = second.submitSimulation(request).getSimulationId();
            assertThat(peerEngine.started.await(15, TimeUnit.SECONDS)).isTrue();
            assertThat(first.getSimulationStatus(running).getStatus()).isEqualTo(SimulationStatus.RUNNING);
            assertThat(first.getSimulationStatus(pending).getStatus()).isEqualTo(SimulationStatus.PENDING);
            assertThat(health(http, victim)).isEqualTo(200);

            terminateOwner(jdbc, running);

            // Observe engine cancellation before making any request to the affected API.
            assertThat(victimEngine.cancelled.await(15, TimeUnit.SECONDS)).isTrue();
            assertThat(health(http, victim)).isEqualTo(503);
            assertThat(health(http, peer)).isEqualTo(200);
            var blocked = HttpRequest.newBuilder(apiUri(victim, "/api/v1/simulations"))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"circuitId":"%s","simulationTime":0.002,"timeStep":0.000001}
                            """.formatted(request.getCircuitId()))).build();
            assertThat(http.send(blocked, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(503);
            for (String job : List.of(running, pending)) {
                assertThat(second.getSimulationStatus(job).getStatus()).isEqualTo(SimulationStatus.FAILED);
                assertThat(first.getSimulation(job).getStatus()).isEqualTo(SimulationStatus.FAILED);
            }
            assertThat(first.getSimulation(completed).getResults()).isNotEmpty();
            assertThat(first.getBatchStatus(previous.getBatchId()).getCompleted()).isEqualTo(1);
            assertThat(victimEngine.invocations.get()).isEqualTo(1);
            assertThat(peerEngine.cancelled.getCount()).isEqualTo(1);
            assertThat(second.getSimulationStatus(unaffected).getStatus()).isEqualTo(SimulationStatus.RUNNING);

            peerEngine.release.countDown();
            Instant deadline = Instant.now().plusSeconds(15);
            while (second.getSimulationStatus(unaffected).getStatus() == SimulationStatus.RUNNING
                    && Instant.now().isBefore(deadline)) {
                Thread.sleep(50);
            }
            assertThat(second.getSimulationStatus(unaffected).getStatus()).isEqualTo(SimulationStatus.COMPLETED);
        } finally {
            victimEngine.release.countDown();
            peerEngine.release.countDown();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "GECKO_TEST_POSTGRES", matches = "true")
    void rechecksOwnershipAfterWaitingForJobAndQuotaLocks() throws Exception {
        String[] options = postgresOptions();
        var engine = new ControlledEngine();
        try (var victim = application("postgres", options); var peer = application("postgres", options);
             var work = Executors.newVirtualThreadPerTaskExecutor(); var http = HttpClient.newHttpClient()) {
            var service = victim.getBean(SimulationService.class);
            var persistence = victim.getBean(PostgresPersistence.class);
            var jdbc = peer.getBean(JdbcTemplate.class);
            ReflectionTestUtils.setField(service, "engineFactory", (Supplier<HeadlessSimulationEngine>) () -> engine);
            String id = service.submitSimulation(buckRequest(victim)).getSimulationId();
            assertThat(engine.started.await(15, TimeUnit.SECONDS)).isTrue();
            var workers = (ThreadPoolExecutor) ReflectionTestUtils.getField(service, "executorService");

            try (var lock = peer.getBean(DataSource.class).getConnection()) {
                lock.setAutoCommit(false);
                int blocker;
                try (var statement = lock.createStatement(); var row = statement.executeQuery("select pg_backend_pid()")) {
                    assertThat(row.next()).isTrue();
                    blocker = row.getInt(1);
                }
                try (var rowLock = lock.prepareStatement("select id from simulation_job where id = ? for update");
                     var quotaLock = lock.prepareStatement("select pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                    rowLock.setString(1, id);
                    rowLock.execute();
                    quotaLock.setString(1, "simulation:contended-admission");
                    quotaLock.execute();
                }

                try {
                    engine.release.countDown();
                    var admission = work.submit(() -> persistence.admitSimulations(
                            List.of(job("blocked-admission", "contended-admission")), "contended-admission", 1));
                    await(() -> jdbc.queryForObject("""
                            select count(*) from pg_stat_activity where ? = any(pg_blocking_pids(pid))
                            """, Integer.class, blocker) >= 2);
                    terminateOwner(jdbc, id);
                    assertThat(health(http, victim)).isEqualTo(503);
                    lock.commit();
                    assertThatThrownBy(() -> admission.get(15, TimeUnit.SECONDS))
                            .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(ResponseStatusException.class);
                    await(() -> workers.getCompletedTaskCount() == 1);
                } finally {
                    lock.rollback();
                }
            }
            assertThat(jdbc.queryForObject("select status from simulation_job where id = ?", String.class, id))
                    .isEqualTo("RUNNING");
            assertThat(jdbc.queryForObject("select count(*) from simulation_job where id = 'blocked-admission'", Long.class))
                    .isZero();
            assertThat(peer.getBean(SimulationService.class).getSimulationStatus(id).getStatus())
                    .isEqualTo(SimulationStatus.FAILED);
        } finally {
            engine.release.countDown();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "GECKO_TEST_POSTGRES", matches = "true")
    void failedCompletionWriteFencesReplicaWithLiveOwnershipConnection() throws Exception {
        var engine = new ControlledEngine();
        try (var context = application("postgres", postgresOptions()); var http = HttpClient.newHttpClient()) {
            var service = context.getBean(SimulationService.class);
            var jdbc = context.getBean(JdbcTemplate.class);
            ReflectionTestUtils.setField(service, "engineFactory", (Supplier<HeadlessSimulationEngine>) () -> engine);
            String id = service.submitSimulation(buckRequest(context)).getSimulationId();
            assertThat(engine.started.await(15, TimeUnit.SECONDS)).isTrue();
            assertThat(health(http, context)).isEqualTo(200);
            jdbc.execute("alter table audit_log add constraint test_block_completion check (action <> 'COMPLETED')");
            var workers = (ThreadPoolExecutor) ReflectionTestUtils.getField(service, "executorService");

            engine.release.countDown();
            await(() -> workers.getCompletedTaskCount() == 1);
            assertThat(health(http, context)).isEqualTo(503);
            assertThat(service.getSimulation(id).getStatus()).isEqualTo(SimulationStatus.FAILED);
            assertThat(service.getSimulation(id).getResults()).isEmpty();
        } finally {
            engine.release.countDown();
        }
    }

    private static SimulationRequest buckRequest(ConfigurableApplicationContext context) throws Exception {
        var path = Path.of("../gecko-simulation-core/src/test/resources/ipes/buck_simple.ipes");
        var upload = context.getBean(CircuitFileService.class).loadCircuit(
                Base64.getEncoder().encodeToString(Files.readAllBytes(path)), "buck_simple.ipes");
        assertThat(upload.status()).isEqualTo("loaded");
        var request = new SimulationRequest(null, 0.002, 1e-6);
        request.setCircuitId(upload.circuitId());
        return request;
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(15);
        while (!condition.getAsBoolean() && Instant.now().isBefore(deadline)) Thread.sleep(25);
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static void terminateOwner(JdbcTemplate jdbc, String id) {
        Integer pid = jdbc.queryForObject("""
                select lock.pid from pg_locks lock
                join simulation_job job on job.replica_id = lock.objid::integer
                where job.id = ? and lock.locktype = 'advisory'
                  and lock.objsubid = 2 and lock.granted
                  and lock.classid = (select oid from pg_namespace where nspname = current_schema())
                  and lock.database = (select oid from pg_database where datname = current_database())
                """, Integer.class, id);
        assertThat(jdbc.queryForObject("select pg_terminate_backend(?, 5000)", Boolean.class, pid)).isTrue();
    }

    private static int health(HttpClient http, ConfigurableApplicationContext context) throws Exception {
        var request = HttpRequest.newBuilder(apiUri(context, "/api/health"))
                .timeout(Duration.ofSeconds(5)).build();
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static URI apiUri(ConfigurableApplicationContext context, String path) {
        int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        return URI.create("http://127.0.0.1:" + port + "/gecko" + path);
    }

    private static final class ControlledEngine extends HeadlessSimulationEngine {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch cancelled = new CountDownLatch(1);
        final AtomicInteger invocations = new AtomicInteger();

        @Override
        public SimulationResult runSimulation(PreparedCircuit circuit, ExecutionOptions options,
                                              BooleanSupplier cancellationSignal) {
            invocations.incrementAndGet();
            started.countDown();
            try {
                while (!release.await(25, TimeUnit.MILLISECONDS)) {
                    if (cancellationSignal.getAsBoolean()) {
                        cancelled.countDown();
                        return SimulationResult.cancelled();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return SimulationResult.cancelled();
            }
            return super.runSimulation(circuit, options, cancellationSignal);
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
            "--gecko.jobs.workers=1",
            "--gecko.jobs.retained=3",
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
