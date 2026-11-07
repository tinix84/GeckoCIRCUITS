package gecko.rest.service;

import gecko.core.allg.SolverType;
import gecko.core.datacontainer.DataContainerGlobal;
import gecko.core.simulation.HeadlessSimulationEngine;
import gecko.core.preparation.PreparedCircuit;
import gecko.core.preparation.ExecutionOptions;
import gecko.core.simulation.SimulationResult;
import gecko.rest.model.BatchJobStatus;
import gecko.rest.model.BatchSimulationRequest;
import gecko.rest.model.BatchSimulationResponse;
import gecko.rest.model.SimulationRequest;
import gecko.rest.model.SimulationResponse;
import gecko.rest.model.SimulationResponse.SimulationStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Tests lifecycle behavior through the same interface used by controllers. */
class SimulationServiceTest {
    private static final String CIRCUIT = "../gecko-simulation-core/src/test/resources/ipes/buck_simple.ipes";
    private ManualExecutor executor;
    private SimulationService service;
    private WebSocketProgressService webSocket;
    private AtomicInteger enginesCreated;
    private AtomicReference<PreparedCircuit> executedConfig;
    private BiFunction<PreparedCircuit, BooleanSupplier, SimulationResult> execution;

    @BeforeEach
    void setUp() {
        executor = new ManualExecutor();
        webSocket = mock(WebSocketProgressService.class);
        enginesCreated = new AtomicInteger();
        executedConfig = new AtomicReference<>();
        execution = (config, cancelled) -> successfulResult();
        service = new SimulationService(executor, () -> {
            enginesCreated.incrementAndGet();
            return new HeadlessSimulationEngine() {
                @Override
                public SimulationResult runSimulation(PreparedCircuit config, ExecutionOptions options, BooleanSupplier cancelled) {
                    executedConfig.set(config);
                    return execution.apply(config, cancelled);
                }
            };
        }, RecordingEmitter::new, webSocket);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void submissionRemainsPendingUntilExecutionStarts() {
        SimulationResponse first = submit();
        SimulationResponse second = submit();
        assertNotEquals(first.getSimulationId(), second.getSimulationId());
        assertEquals(SimulationStatus.PENDING, first.getStatus());
        assertNotNull(first.getStartTime());
        assertFalse(service.isRunning(first.getSimulationId()));
        assertEquals(0.0, service.getSimulationProgress(first.getSimulationId()));
        assertEquals(0, enginesCreated.get());
        assertEquals(2, service.getAllSimulations().size());
    }

    @Test
    void completedSnapshotContainsCommittedSignalsAndTimes() {
        SimulationResponse submitted = submit();
        executor.runNext();
        SimulationResponse completed = service.getSimulation(submitted.getSimulationId());
        assertEquals(SimulationStatus.COMPLETED, completed.getStatus());
        assertNotNull(completed.getEndTime());
        assertNull(completed.getErrorMessage());
        assertArrayEquals(new double[]{12.0, 18.0}, completed.getResult("V_out"));
        assertArrayEquals(new double[]{0.0, 0.001}, completed.getResult("time"));
        assertEquals(100.0, service.getSimulationProgress(submitted.getSimulationId()));
        assertFalse(service.isRunning(submitted.getSimulationId()));
        assertEquals(SimulationStatus.PENDING, submitted.getStatus());
        verify(webSocket).broadcastCompletion(submitted.getSimulationId(), true, null);
    }

    @Test
    void returnedResponsesCannotChangeOwnedJobState() {
        String id = submit().getSimulationId();
        service.addResults(id, "manual", new double[]{1.0, 2.0});
        SimulationResponse detached = service.getSimulation(id);
        detached.setStatus(SimulationStatus.FAILED);
        detached.setErrorMessage("caller mutation");
        detached.addResult("manual", new double[]{999.0});
        service.getAllSimulations().get(id).setStatus(SimulationStatus.COMPLETED);
        assertEquals(SimulationStatus.PENDING, service.getSimulation(id).getStatus());
        assertNull(service.getSimulation(id).getErrorMessage());
        assertArrayEquals(new double[]{1.0, 2.0}, service.getSignalData(id, "manual"));
        double[] firstRead = service.getSignalData(id, "manual");
        firstRead[0] = 999.0;
        assertEquals(1.0, service.getSignalData(id, "manual")[0]);
    }

    @Test
    void requestParametersAreOwnedWhileQueued() {
        SimulationRequest request = request();
        Map<String, Double> parameters = new HashMap<>(Map.of("R.1.resistance", 12.0));
        request.setParameters(parameters);
        request.setSolverType("trz");
        service.submitSimulation(request);
        parameters.put("R.1.resistance", 999.0);
        request.setCircuitFile("changed.ipes");
        request.setSolverType("gs");
        executor.runNext();
        assertEquals(CIRCUIT, executedConfig.get().newRunModel().getFilePath());
        assertEquals(12.0, parameter(executedConfig.get(), "R.1", "resistance"));
        assertEquals(SolverType.SOLVER_TRZ, executedConfig.get().solverSettings().getSolverType());
    }

    @Test
    void queuedCancellationDoesNotCreateAnEngine() {
        String id = submit().getSimulationId();
        RecordingEmitter stream = stream(id);
        SimulationResponse cancelled = service.cancelSimulation(id);
        executor.runNext();
        assertCancelled(cancelled);
        assertCancelled(service.getSimulation(id));
        assertEquals(0, enginesCreated.get());
        assertEquals(1, stream.events.size());
        assertTrue(stream.events.get(0).contains("event:error"));
        assertEquals(1, stream.completions);
        verify(webSocket).broadcastCompletion(id, false, SimulationService.CANCELLED_BY_USER);
    }

    @Test
    void cancellationDuringEngineCreationPreventsRegistrationAndRun() throws Exception {
        CountDownLatch creating = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        service.shutdown();
        executor = new ManualExecutor();
        service = new SimulationService(executor, () -> {
            creating.countDown();
            await(release);
            return new HeadlessSimulationEngine() {
                @Override
                public SimulationResult runSimulation(PreparedCircuit config, ExecutionOptions options, BooleanSupplier cancelled) {
                    runs.incrementAndGet();
                    return successfulResult();
                }
            };
        }, RecordingEmitter::new, webSocket);
        String id = submit().getSimulationId();
        Thread worker = startNext();
        try {
            assertTrue(creating.await(10, TimeUnit.SECONDS));
            assertEquals(SimulationStatus.PENDING, service.getSimulation(id).getStatus());
            assertCancelled(service.cancelSimulation(id));
        } finally {
            release.countDown();
            join(worker);
        }
        assertEquals(0, runs.get());
        assertFalse(service.isRunning(id));
        verify(webSocket).broadcastCompletion(id, false, SimulationService.CANCELLED_BY_USER);
    }

    @Test
    void cancellationSignalCoversRegisteredEngineBeforeCoreStartup() throws Exception {
        CountDownLatch starting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<SimulationResult> actual = new AtomicReference<>();
        service.shutdown();
        executor = new ManualExecutor();
        service = new SimulationService(executor, () -> new HeadlessSimulationEngine() {
            @Override
            public SimulationResult runSimulation(PreparedCircuit config, ExecutionOptions options, BooleanSupplier cancelled) {
                starting.countDown();
                await(release);
                // The accepted prepared input still uses the durable cancellation signal.
                SimulationResult result = super.runSimulation(config, options, cancelled);
                actual.set(result);
                return result;
            }
        }, RecordingEmitter::new, webSocket);
        String id = submit().getSimulationId();
        Thread worker = startNext();
        try {
            assertTrue(starting.await(10, TimeUnit.SECONDS));
            assertTrue(service.isRunning(id));
            assertCancelled(service.cancelSimulation(id));
        } finally {
            release.countDown();
            join(worker);
        }
        assertEquals(SimulationResult.Status.CANCELLED, actual.get().getStatus());
        assertCancelled(service.getSimulation(id));
        verify(webSocket).broadcastCompletion(id, false, SimulationService.CANCELLED_BY_USER);
    }

    @Test
    void cancellationWinsAgainstLateSuccessfulResult() throws Exception {
        assertCancellationWins(successfulResult());
    }

    @Test
    void cancellationWinsAgainstLateFailure() throws Exception {
        assertCancellationWins(SimulationResult.failed("late failure"));
    }

    private void assertCancellationWins(SimulationResult lateResult) throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        execution = (config, cancelled) -> {
            running.countDown();
            await(release);
            assertTrue(cancelled.getAsBoolean());
            return lateResult;
        };
        String id = submit().getSimulationId();
        RecordingEmitter stream = stream(id);
        Thread worker = startNext();
        try {
            assertTrue(running.await(10, TimeUnit.SECONDS));
            assertCancelled(service.cancelSimulation(id));
        } finally {
            release.countDown();
            join(worker);
        }
        assertCancelled(service.getSimulation(id));
        assertTrue(service.getSimulation(id).getResults().isEmpty());
        assertEquals(1, stream.events.size());
        assertEquals(1, stream.completions);
        verify(webSocket).broadcastCompletion(id, false, SimulationService.CANCELLED_BY_USER);
        verifyNoMoreInteractions(webSocket);
    }

    @Test
    void completionWinsAgainstLateCancellationAndFailureUpdates() {
        String id = submit().getSimulationId();
        executor.runNext();
        SimulationResponse completed = service.getSimulation(id);
        service.cancelSimulation(id);
        service.updateStatus(id, SimulationStatus.FAILED);
        service.completeProgressStreams(id, false);
        SimulationResponse retained = service.getSimulation(id);
        assertEquals(SimulationStatus.COMPLETED, retained.getStatus());
        assertEquals(completed.getEndTime(), retained.getEndTime());
        assertArrayEquals(completed.getResult("V_out"), retained.getResult("V_out"));
        verify(webSocket).broadcastCompletion(id, true, null);
        verifyNoMoreInteractions(webSocket);
    }

    @Test
    void failureCommitsErrorAndPublishesOnce() {
        execution = (config, cancelled) -> SimulationResult.failed("invalid circuit");
        String id = submit().getSimulationId();
        RecordingEmitter stream = stream(id);
        executor.runNext();
        SimulationResponse failed = service.getSimulation(id);
        assertEquals(SimulationStatus.FAILED, failed.getStatus());
        assertEquals("invalid circuit", failed.getErrorMessage());
        assertNotNull(failed.getEndTime());
        assertEquals(0.0, service.getSimulationProgress(id));
        service.cancelSimulation(id);
        assertEquals("invalid circuit", service.getSimulation(id).getErrorMessage());
        assertEquals(1, stream.events.size());
        verify(webSocket).broadcastCompletion(id, false, "invalid circuit");
    }

    @Test
    void exceptionCommitsErrorAndPublishesOnce() {
        execution = (config, cancelled) -> { throw new IllegalStateException("broken calculation"); };
        String id = submit().getSimulationId();
        executor.runNext();
        assertEquals("Simulation error: broken calculation", service.getSimulation(id).getErrorMessage());
        verify(webSocket).broadcastCompletion(id, false, "Simulation error: broken calculation");
    }

    @Test
    void subscribersReceiveExistingProgressAndTerminalFormatsOnce() {
        String id = submit().getSimulationId();
        RecordingEmitter stream = stream(id);
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.FRANCE);
            service.broadcastProgress(id, 0.5, 5.0, 10.0);
        } finally {
            Locale.setDefault(original);
        }
        assertEquals(1, stream.events.size());
        assertTrue(stream.events.get(0).contains("event:progress"));
        assertTrue(stream.events.get(0).contains("\"progress\":0.500"));
        assertTrue(stream.events.get(0).contains("\"currentTime\":5.000000"));
        executor.runNext();
        assertEquals(2, stream.events.size());
        assertTrue(stream.events.get(1).contains("event:complete"));
        assertTrue(stream.events.get(1).contains("{\"simulationId\":\"" + id + "\"}"));
        assertEquals(1, stream.completions);
        service.completeProgressStreams(id, true);
        service.broadcastProgress(id, 1.0, 10.0, 10.0);
        assertEquals(2, stream.events.size());
        verify(webSocket).broadcastProgress(id, 0.5, 5.0, 10.0, 0, 0, "RUNNING");
        verify(webSocket).broadcastCompletion(id, true, null);
        verifyNoMoreInteractions(webSocket);
    }

    @Test
    void lateSubscriberGetsOneStatusAndNoRepeatedTerminalEvent() {
        String id = submit().getSimulationId();
        executor.runNext();
        RecordingEmitter late = stream(id);
        assertEquals(1, late.events.size());
        assertTrue(late.events.get(0).contains("event:status"));
        assertTrue(late.events.get(0).contains("\"status\":\"COMPLETED\""));
        assertEquals(1, late.completions);
        service.completeProgressStreams(id, true);
        service.cancelSimulation(id);
        assertEquals(1, late.events.size());
    }

    @Test
    void batchLimitControlsAdmissionAndReleasesOneSlotAfterEachResult() {
        BatchSimulationResponse batch = service.submitBatch(batchRequest(5, 2));
        assertEquals(2, executor.size());
        assertEquals(5, service.getBatchStatus(batch.getBatchId()).getPending());
        executor.runNext();
        assertEquals(2, executor.size());
        BatchJobStatus status = service.getBatchStatus(batch.getBatchId());
        assertEquals(1, status.getCompleted());
        assertEquals(4, status.getPending());
        assertEquals(20.0, status.getOverallProgress());
        executor.runAll();
        assertTrue(service.getBatchStatus(batch.getBatchId()).isDone());
        assertEquals(5, enginesCreated.get());
        assertEquals(100.0, service.getBatchStatus(batch.getBatchId()).getOverallProgress());
    }

    @Test
    void cancellationDoesNotReleaseRunningBatchSlotBeforeWorkerStops() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        execution = (config, cancelled) -> {
            running.countDown();
            await(release);
            return successfulResult();
        };
        BatchSimulationResponse batch = service.submitBatch(batchRequest(2, 1));
        Thread worker = startNext();
        try {
            assertTrue(running.await(10, TimeUnit.SECONDS));
            service.cancelSimulation(batch.getSimulationIds().get(0));
            assertEquals(0, executor.size());
            assertEquals(1, service.getBatchStatus(batch.getBatchId()).getPending());
        } finally {
            release.countDown();
            join(worker);
        }
        assertEquals(1, executor.size());
        executor.runNext();
        assertEquals(1, service.getBatchStatus(batch.getBatchId()).getCompleted());
        assertEquals(1, service.getBatchStatus(batch.getBatchId()).getFailed());
    }

    @Test
    void eachBatchHasItsOwnAdmissionLimit() {
        service.submitBatch(batchRequest(5, 1));
        service.submitBatch(batchRequest(5, 2));
        assertEquals(3, executor.size());
    }

    @Test
    void zeroBatchLimitUsesFourSlotsAndPreservesResponseValue() {
        BatchSimulationResponse batch = service.submitBatch(batchRequest(6, 0));
        assertEquals(0, batch.getMaxConcurrent());
        assertEquals(4, executor.size());
        executor.runAll();
        assertEquals(6, service.getBatchStatus(batch.getBatchId()).getCompleted());
    }

    @Test
    void batchIsRegisteredBeforeItsFirstChildRuns() {
        AtomicReference<String> batchId = new AtomicReference<>();
        BatchSimulationResponse batch = service.submitBatch(batchRequest(2, 1));
        batchId.set(batch.getBatchId());
        execution = (config, cancelled) -> {
            assertNotNull(service.getBatch(batchId.get()));
            assertEquals(2, service.getBatchStatus(batchId.get()).getTotal());
            assertEquals(2, service.getAllSimulations().size());
            return successfulResult();
        };
        executor.runNext();
    }

    @Test
    void batchSnapshotsCannotChangeChildrenOrMetadata() {
        BatchSimulationResponse batch = service.submitBatch(batchRequest(3, 1));
        String id = batch.getBatchId();
        batch.getSimulationIds().clear();
        batch.setMaxConcurrent(99);
        BatchSimulationResponse later = service.getBatch(id);
        assertEquals(3, later.getSimulationIds().size());
        assertEquals(1, later.getMaxConcurrent());
    }

    @Test
    void cancelBatchStopsPendingChildrenAndPublishesEachFailureOnce() {
        BatchSimulationResponse batch = service.submitBatch(batchRequest(5, 1));
        assertTrue(service.cancelBatch(batch.getBatchId()));
        executor.runAll();
        BatchJobStatus status = service.getBatchStatus(batch.getBatchId());
        assertTrue(status.isDone());
        assertEquals(5, status.getFailed());
        assertEquals(0, enginesCreated.get());
        assertEquals(5, status.getFailedIds().size());
        for (String id : batch.getSimulationIds()) {
            assertCancelled(service.getSimulation(id));
            verify(webSocket).broadcastCompletion(id, false, SimulationService.CANCELLED_BY_USER);
        }
        verifyNoMoreInteractions(webSocket);
    }

    @Test
    void explicitAndSweepSettingsReachOwnedChildRequests() {
        BatchSimulationRequest request = new BatchSimulationRequest();
        request.setCircuitFile(CIRCUIT);
        BatchSimulationRequest.LinearSweep sweep = new BatchSimulationRequest.LinearSweep();
        sweep.setParameterPath("R.1.resistance");
        sweep.setStartValue(1.0);
        sweep.setEndValue(3.0);
        sweep.setPoints(3);
        sweep.setFixedParameters(Map.of("C.1.capacitance", 2e-6));
        request.setLinearSweep(sweep);
        BatchSimulationRequest.SimulationSettings settings = new BatchSimulationRequest.SimulationSettings();
        settings.setSimulationTime(0.01);
        settings.setTimeStep(1e-5);
        settings.setSolverType("gs");
        request.setSimulationSettings(settings);
        service.submitBatch(request);
        executor.runAll();
        assertEquals(3.0, parameter(executedConfig.get(), "R.1", "resistance"));
        assertEquals(2e-6, parameter(executedConfig.get(), "C.1", "capacitance"));
        assertEquals(1e-5, executedConfig.get().solverSettings().getStepWidth());
        assertEquals(SolverType.SOLVER_GS, executedConfig.get().solverSettings().getSolverType());
    }

    @Test
    void clearAllCancelsQueuedWorkWithoutRecreatingJobs() {
        service.submitBatch(batchRequest(5, 1));
        submit();
        service.clearAll();
        executor.runAll();
        assertTrue(service.getAllSimulations().isEmpty());
        assertEquals(0, enginesCreated.get());
    }

    @Test
    void unknownJobsAndBatchesKeepTheirExistingResults() {
        assertNull(service.getSimulation("missing"));
        assertNull(service.cancelSimulation("missing"));
        assertNull(service.getSignalData("missing", "signal"));
        assertNull(service.registerProgressStream("missing", 100L));
        assertEquals(-1.0, service.getSimulationProgress("missing"));
        assertFalse(service.isRunning("missing"));
        assertNull(service.getBatch("missing"));
        assertNull(service.getBatchStatus("missing"));
        assertFalse(service.cancelBatch("missing"));
        assertDoesNotThrow(() -> service.completeProgressStreams("missing", true));
    }

    @Test
    void statisticsUseCapturedJobStatesWithoutPrivateStoreAccess() {
        String completed = submit().getSimulationId();
        String failed = submit().getSimulationId();
        submit();
        service.updateStatus(completed, SimulationStatus.COMPLETED);
        service.cancelSimulation(failed);
        assertEquals(1L, service.getStatistics().get(SimulationStatus.COMPLETED));
        assertEquals(1L, service.getStatistics().get(SimulationStatus.FAILED));
        assertEquals(1L, service.getStatistics().get(SimulationStatus.PENDING));
        assertEquals(0L, service.getStatistics().get(SimulationStatus.RUNNING));
    }

    @Test
    void rejectedExecutionProducesTrackedFailure() {
        executor.shutdown();
        SimulationResponse failed = submit();
        assertEquals(SimulationStatus.FAILED, failed.getStatus());
        assertEquals("Simulation executor is shut down", failed.getErrorMessage());
        assertNotNull(failed.getEndTime());
        assertEquals(0, enginesCreated.get());
    }

    @Test
    void configurationPreservesOverridesAndSolverAliases() {
        SimulationRequest request = request();
        request.setParameters(Map.of("R.1.resistance", 12.0, "C.1.capacitance", 8.5e-6));
        request.setSolverType(" GEAR ");
        service.submitSimulation(request);
        executor.runNext();
        assertEquals(12.0, parameter(executedConfig.get(), "R.1", "resistance"));
        assertEquals(8.5e-6, parameter(executedConfig.get(), "C.1", "capacitance"));
        assertEquals(SolverType.SOLVER_GS, executedConfig.get().solverSettings().getSolverType());
    }

    @Test
    void transportFailuresCannotChangeResultsOrStallBatchAdmission() {
        doThrow(new IllegalStateException("disconnected"))
                .when(webSocket).broadcastProgress(anyString(), anyDouble(), anyDouble(), anyDouble(),
                        anyInt(), anyInt(), anyString());
        doThrow(new IllegalStateException("disconnected"))
                .when(webSocket).broadcastCompletion(anyString(), anyBoolean(), nullable(String.class));
        BatchSimulationResponse batch = service.submitBatch(batchRequest(3, 1));
        String firstId = batch.getSimulationIds().get(0);
        assertDoesNotThrow(() -> service.broadcastProgress(firstId, 0.5, 0.001, 0.002));
        assertDoesNotThrow(executor::runAll);
        assertEquals(3, service.getBatchStatus(batch.getBatchId()).getCompleted());
        assertEquals(3, enginesCreated.get());
        for (String id : batch.getSimulationIds()) {
            assertEquals(SimulationStatus.COMPLETED, service.getSimulation(id).getStatus());
            verify(webSocket).broadcastCompletion(id, true, null);
        }
    }

    @Test
    void cancellationReturnsCommittedStateWhenTerminalTransportThrows() {
        doThrow(new IllegalStateException("disconnected"))
                .when(webSocket).broadcastCompletion(anyString(), anyBoolean(), nullable(String.class));
        String id = submit().getSimulationId();
        assertDoesNotThrow(() -> assertCancelled(service.cancelSimulation(id)));
        executor.runNext();
        assertCancelled(service.getSimulation(id));
        verify(webSocket).broadcastCompletion(id, false, SimulationService.CANCELLED_BY_USER);
    }

    @Test
    void rejectedSweepCompletesWithoutRecursiveAdmissionOrTransportFailure() {
        doThrow(new IllegalStateException("disconnected"))
                .when(webSocket).broadcastCompletion(anyString(), anyBoolean(), nullable(String.class));
        executor.shutdown();
        BatchSimulationRequest request = new BatchSimulationRequest();
        request.setCircuitFile(CIRCUIT);
        request.setMaxConcurrent(1);
        BatchSimulationRequest.LinearSweep sweep = new BatchSimulationRequest.LinearSweep();
        sweep.setParameterPath("R.1.resistance");
        sweep.setStartValue(1.0);
        sweep.setEndValue(1000.0);
        sweep.setPoints(1000);
        request.setLinearSweep(sweep);
        BatchSimulationResponse batch = assertDoesNotThrow(() -> service.submitBatch(request));
        assertTrue(service.getBatchStatus(batch.getBatchId()).isDone());
        assertEquals(1000, service.getBatchStatus(batch.getBatchId()).getFailed());
        assertEquals(0, enginesCreated.get());
    }

    @Test
    void terminalNotificationObservesCommittedResults() {
        doAnswer(invocation -> {
            String id = invocation.getArgument(0);
            SimulationResponse response = service.getSimulation(id);
            assertEquals(SimulationStatus.COMPLETED, response.getStatus());
            assertNotNull(response.getEndTime());
            assertArrayEquals(new double[]{12.0, 18.0}, response.getResult("V_out"));
            assertArrayEquals(new double[]{0.0, 0.001}, response.getResult("time"));
            return null;
        }).when(webSocket).broadcastCompletion(anyString(), eq(true), isNull());
        submit();
        executor.runNext();
    }
    private static double parameter(PreparedCircuit circuit, String component, String name) {
        return ((Number) circuit.newRunModel().getCircuitComponents().stream()
                .filter(item -> item.getName().equals(component)).findFirst().orElseThrow()
                .getParameters().get(name)).doubleValue();
    }
    private SimulationResponse submit() { return service.submitSimulation(request()); }
    private SimulationRequest request() { return new SimulationRequest(CIRCUIT, 0.02, 1e-6); }
    private RecordingEmitter stream(String id) {
        return (RecordingEmitter) service.registerProgressStream(id, 300000L);
    }
    private static void assertCancelled(SimulationResponse response) {
        assertEquals(SimulationStatus.FAILED, response.getStatus());
        assertEquals(SimulationService.CANCELLED_BY_USER, response.getErrorMessage());
        assertNotNull(response.getEndTime());
    }
    private static SimulationResult successfulResult() {
        DataContainerGlobal data = new DataContainerGlobal();
        data.init(1, 2, new String[]{"V_out"}, "time [s]");
        data.insertValuesAtEnd(new float[]{12.0f}, 0.0);
        data.insertValuesAtEnd(new float[]{18.0f}, 0.001);
        return SimulationResult.builder().dataContainer(data).totalTimeSteps(2).build();
    }
    private static BatchSimulationRequest batchRequest(int count, int limit) {
        BatchSimulationRequest request = new BatchSimulationRequest();
        request.setCircuitFile(CIRCUIT);
        request.setMaxConcurrent(limit);
        List<Map<String, Double>> parameters = new ArrayList<>();
        for (int i = 0; i < count; i++) { parameters.add(Map.of("R.1.resistance", (double) i + 1)); }
        request.setParameterSets(parameters);
        return request;
    }
    private Thread startNext() {
        Thread worker = new Thread(executor.take(), "controlled-simulation");
        worker.start();
        return worker;
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS), "Timed out waiting for controlled execution"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    private static void join(Thread worker) throws InterruptedException {
        worker.join(10000);
        assertFalse(worker.isAlive(), "Controlled worker did not terminate");
    }
    private static final class RecordingEmitter extends SseEmitter {
        private final List<String> events = new ArrayList<>();
        private int completions;
        private RecordingEmitter(long timeout) { super(timeout); }
        @Override
        public void send(SseEventBuilder event) {
            StringBuilder text = new StringBuilder();
            event.build().forEach(part -> text.append(part.getData()));
            events.add(text.toString());
        }
        @Override
        public void complete() { completions++; }
    }
    private static final class ManualExecutor extends AbstractExecutorService {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        private boolean shutdown;
        @Override
        public synchronized void execute(Runnable command) {
            if (shutdown) { throw new RejectedExecutionException(); }
            tasks.add(command);
        }
        private synchronized int size() { return tasks.size(); }
        private synchronized Runnable take() { return tasks.remove(); }
        private void runNext() { take().run(); }
        private void runAll() { while (size() > 0) { runNext(); } }
        @Override
        public synchronized void shutdown() { shutdown = true; }
        @Override
        public synchronized List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> pending = new ArrayList<>(tasks);
            tasks.clear();
            return pending;
        }
        @Override
        public synchronized boolean isShutdown() { return shutdown; }
        @Override
        public synchronized boolean isTerminated() { return shutdown && tasks.isEmpty(); }
        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
    }
}