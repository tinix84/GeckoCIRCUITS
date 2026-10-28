package gecko.rest.service;

import gecko.core.allg.SolverType;
import gecko.core.simulation.HeadlessSimulationEngine;
import gecko.core.simulation.SimulationConfig;
import gecko.core.simulation.SimulationProgress;
import gecko.core.simulation.SimulationResult;
import gecko.rest.model.BatchJobStatus;
import gecko.rest.model.BatchSimulationRequest;
import gecko.rest.model.BatchSimulationResponse;
import gecko.rest.model.SimulationRequest;
import gecko.rest.model.SimulationResponse;
import gecko.rest.model.SimulationResponse.SimulationStatus;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.LongFunction;
import java.util.function.Supplier;

/**
 * Owns simulation scheduling, state transitions, and progress subscriptions.
 * Each job commits its state and notifications under the same monitor.
 */
@Service
public class SimulationService {
    private static final Logger logger = LoggerFactory.getLogger(SimulationService.class);
    static final String CANCELLED_BY_USER = "Cancelled by user";
    private static final int DEFAULT_BATCH_CONCURRENCY = 4;

    private final Map<String, SimulationJob> jobs = new ConcurrentHashMap<>();
    private final Map<String, BatchJob> batches = new ConcurrentHashMap<>();
    private final ExecutorService executorService;
    private final Supplier<HeadlessSimulationEngine> engineFactory;
    private final LongFunction<SseEmitter> emitterFactory;

    @Autowired(required = false)
    private WebSocketProgressService webSocketProgressService;

    public SimulationService() {
        this(Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() - 1)),
                HeadlessSimulationEngine::new);
    }

    SimulationService(ExecutorService executorService, Supplier<HeadlessSimulationEngine> engineFactory) {
        this(executorService, engineFactory, SseEmitter::new, null);
    }

    SimulationService(ExecutorService executorService, Supplier<HeadlessSimulationEngine> engineFactory,
                      LongFunction<SseEmitter> emitterFactory, WebSocketProgressService webSocketProgressService) {
        this.executorService = executorService;
        this.engineFactory = engineFactory;
        this.emitterFactory = emitterFactory;
        this.webSocketProgressService = webSocketProgressService;
    }

    public SimulationResponse submitSimulation(SimulationRequest request) {
        SimulationJob job = createJob(request, null);
        dispatch(job);
        return snapshot(job);
    }

    private SimulationJob createJob(SimulationRequest request, BatchJob batch) {
        SimulationRequest ownedRequest = new SimulationRequest(
                request.getCircuitFile(), request.getSimulationTime(), request.getTimeStep());
        ownedRequest.setSolverType(request.getSolverType());
        if (request.getParameters() != null) {
            ownedRequest.setParameters(new HashMap<>(request.getParameters()));
        }
        SimulationJob job = new SimulationJob(ownedRequest, batch);
        jobs.put(job.response.getSimulationId(), job);
        return job;
    }

    private boolean dispatch(SimulationJob job) {
        try {
            executorService.execute(() -> runSimulation(job));
            return true;
        } catch (RejectedExecutionException e) {
            synchronized (job) {
                if (applyFailureResult(job.response, "Simulation executor is shut down")) {
                    publishTerminal(job);
                }
            }
            return false;
        }
    }

    private void runSimulation(SimulationJob job) {
        try {
            synchronized (job) {
                if (isTerminal(job.response)) {
                    return;
                }
            }
            SimulationConfig config = buildSimulationConfig(job.request);
            HeadlessSimulationEngine engine = engineFactory.get();
            engine.setProgressListener((currentTime, endTime, currentStep) -> {
                double progress = endTime > 0 ? currentTime / endTime : 0;
                broadcastProgress(job.response.getSimulationId(), progress, currentTime, endTime);
            });
            synchronized (job) {
                if (isTerminal(job.response)) {
                    return;
                }
                job.engine = engine;
                markRunning(job.response);
            }
            // The durable signal also covers cancellation before the engine starts.
            SimulationResult result = engine.runSimulation(config, job::isCancelled);
            synchronized (job) {
                boolean committed = result.isSuccess()
                        ? applySuccessfulResult(job.response, result)
                        : applyFailureResult(job.response, result.getErrorMessage());
                if (committed) {
                    publishTerminal(job);
                }
            }
        } catch (Exception e) {
            synchronized (job) {
                if (applyFailureResult(job.response, "Simulation error: " + e.getMessage())) {
                    publishTerminal(job);
                    logger.error("Simulation {} failed", job.response.getSimulationId(), e);
                }
            }
        } finally {
            synchronized (job) {
                job.engine = null;
            }
            releaseBatchSlot(job);
        }
    }

    public SimulationResponse getSimulation(String simulationId) {
        SimulationJob job = jobs.get(simulationId);
        return job == null ? null : snapshot(job);
    }

    private SimulationResponse snapshot(SimulationJob job) {
        synchronized (job) {
            refreshProgress(job);
            SimulationResponse source = job.response;
            SimulationResponse result = new SimulationResponse(source.getSimulationId());
            result.setStatus(source.getStatus());
            result.setStartTime(source.getStartTime());
            result.setEndTime(source.getEndTime());
            result.setErrorMessage(source.getErrorMessage());
            result.setProgressDetails(source.getProgressDetails());
            result.setResults(source.getResults());
            return result;
        }
    }

    private void refreshProgress(SimulationJob job) {
        if (job.engine != null && job.response.getStatus() == SimulationStatus.RUNNING) {
            SimulationProgress detailed = job.engine.getDetailedProgress();
            if (detailed != null) {
                job.response.setProgressDetails(new SimulationResponse.ProgressDetails(
                        detailed.getPreCalcProgress(), detailed.getMainSimProgress(), detailed.getCurrentTime(),
                        detailed.getEndTime(), detailed.getCurrentStep(), detailed.getTotalSteps(),
                        detailed.getEstimatedRemainingMs()));
            }
        }
    }

    public Map<String, SimulationResponse> getAllSimulations() {
        Map<String, SimulationResponse> result = new HashMap<>();
        jobs.forEach((id, job) -> result.put(id, snapshot(job)));
        return result;
    }

    public SimulationResponse cancelSimulation(String simulationId) {
        SimulationJob job = jobs.get(simulationId);
        if (job == null) {
            return null;
        }
        synchronized (job) {
            if (markCancelled(job.response)) {
                if (job.engine != null) {
                    job.engine.cancel();
                }
                publishTerminal(job);
            }
            return snapshot(job);
        }
    }

    public double getSimulationProgress(String simulationId) {
        SimulationJob job = jobs.get(simulationId);
        if (job == null) {
            return -1;
        }
        synchronized (job) {
            if (job.response.getStatus() == SimulationStatus.COMPLETED) {
                return 100.0;
            }
            if (job.response.getStatus() == SimulationStatus.RUNNING && job.engine != null) {
                return job.engine.getProgress() * 100.0;
            }
            return 0.0;
        }
    }

    public double[] getSignalData(String simulationId, String signalName) {
        SimulationJob job = jobs.get(simulationId);
        if (job == null) {
            return null;
        }
        synchronized (job) {
            return job.response.getResult(signalName);
        }
    }

    /** Updates a nonterminal job through the existing controller-facing interface. */
    public void updateStatus(String simulationId, SimulationStatus status) {
        SimulationJob job = jobs.get(simulationId);
        if (job != null) {
            synchronized (job) {
                if (!isTerminal(job.response)) {
                    job.response.setStatus(status);
                    if (isTerminal(job.response)) {
                        job.response.setEndTime(Instant.now());
                        publishTerminal(job);
                    }
                }
            }
        }
    }

    public void addResults(String simulationId, String signalName, double[] signalData) {
        SimulationJob job = jobs.get(simulationId);
        if (job != null) {
            synchronized (job) {
                job.response.addResult(signalName, signalData);
            }
        }
    }

    public Map<SimulationStatus, Long> getStatistics() {
        Map<SimulationStatus, Long> result = new EnumMap<>(SimulationStatus.class);
        for (SimulationStatus status : SimulationStatus.values()) {
            result.put(status, 0L);
        }
        for (SimulationJob job : jobs.values()) {
            synchronized (job) {
                result.merge(job.response.getStatus(), 1L, Long::sum);
            }
        }
        return result;
    }

    public boolean isRunning(String simulationId) {
        SimulationJob job = jobs.get(simulationId);
        if (job == null) {
            return false;
        }
        synchronized (job) {
            return job.engine != null && job.response.getStatus() == SimulationStatus.RUNNING;
        }
    }

    public SseEmitter registerProgressStream(String simulationId, long timeoutMs) {
        SimulationJob job = jobs.get(simulationId);
        if (job == null) {
            return null;
        }
        synchronized (job) {
            SseEmitter emitter = emitterFactory.apply(timeoutMs);
            if (isTerminal(job.response)) {
                try {
                    emitter.send(SseEmitter.event().name("status").data(
                            "{\"status\":\"" + job.response.getStatus()
                                    + "\",\"simulationId\":\"" + simulationId + "\"}"));
                    emitter.complete();
                } catch (Exception ignored) {
                    // The subscriber disconnected before receiving the terminal state.
                }
            } else {
                job.emitters.add(emitter);
                emitter.onCompletion(() -> removeEmitter(job, emitter));
                emitter.onTimeout(() -> removeEmitter(job, emitter));
                emitter.onError(error -> removeEmitter(job, emitter));
            }
            return emitter;
        }
    }

    private void removeEmitter(SimulationJob job, SseEmitter emitter) {
        synchronized (job) {
            job.emitters.remove(emitter);
        }
    }

    public void broadcastProgress(String simulationId, double progress, double currentTime, double endTime) {
        SimulationJob job = jobs.get(simulationId);
        if (job == null) {
            return;
        }
        synchronized (job) {
            if (isTerminal(job.response)) {
                return;
            }
            refreshProgress(job);
            String data = String.format(Locale.ROOT,
                    "{\"progress\":%.3f,\"currentTime\":%.6f,\"endTime\":%.6f,\"simulationId\":\"%s\"}",
                    progress, currentTime, endTime, simulationId);
            List<SseEmitter> dead = new ArrayList<>();
            for (SseEmitter emitter : new ArrayList<>(job.emitters)) {
                try {
                    emitter.send(SseEmitter.event().name("progress").data(data));
                } catch (Exception e) {
                    dead.add(emitter);
                }
            }
            job.emitters.removeAll(dead);
            if (webSocketProgressService != null) {
                SimulationResponse.ProgressDetails details = job.response.getProgressDetails();
                try {
                    webSocketProgressService.broadcastProgress(simulationId, progress, currentTime, endTime,
                            details == null ? 0 : details.currentStep(), details == null ? 0 : details.totalSteps(),
                            "RUNNING");
                } catch (Exception e) {
                    logger.debug("WebSocket progress delivery failed for {}", simulationId, e);
                }
            }
        }
    }

    /** Publishes committed terminal state at most once, including manual completions. */
    public void completeProgressStreams(String simulationId, boolean success) {
        SimulationJob job = jobs.get(simulationId);
        if (job != null) {
            synchronized (job) {
                publishTerminal(job);
            }
        }
    }

    private void publishTerminal(SimulationJob job) {
        if (!isTerminal(job.response) || job.terminalPublished) {
            return;
        }
        job.terminalPublished = true;
        boolean success = job.response.getStatus() == SimulationStatus.COMPLETED;
        String simulationId = job.response.getSimulationId();
        List<SseEmitter> emitters = new ArrayList<>(job.emitters);
        job.emitters.clear();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(success ? "complete" : "error")
                        .data("{\"simulationId\":\"" + simulationId + "\"}"));
                emitter.complete();
            } catch (Exception ignored) {
                // Delivery is attempted once for each subscribed transport.
            }
        }
        if (webSocketProgressService != null) {
            try {
                webSocketProgressService.broadcastCompletion(simulationId, success, job.response.getErrorMessage());
            } catch (Exception e) {
                logger.debug("WebSocket terminal delivery failed for {}", simulationId, e);
            }
        }
    }

    public void clearAll() {
        for (BatchJob batch : batches.values()) {
            synchronized (batch) {
                batch.cancelled = true;
            }
        }
        for (String simulationId : jobs.keySet()) {
            cancelSimulation(simulationId);
        }
        jobs.clear();
        batches.clear();
    }

    @PreDestroy
    public void shutdown() {
        clearAll();
        executorService.shutdownNow();
    }

    SimulationConfig buildSimulationConfig(SimulationRequest request) {
        SimulationConfig.Builder builder = SimulationConfig.builder()
                .circuitFile(request.getCircuitFile())
                .stepWidth(request.getTimeStep())
                .simulationDuration(request.getSimulationTime())
                .solverType(parseSolverType(request.getSolverType()));
        if (request.getParameters() != null) {
            builder.withParameters(request.getParameters());
        }
        return builder.build();
    }

    SolverType parseSolverType(String value) {
        if (value == null) {
            return SolverType.SOLVER_BE;
        }
        return switch (value.toLowerCase(Locale.ROOT).trim()) {
            case "trapezoidal", "trz" -> SolverType.SOLVER_TRZ;
            case "gear-shichman", "gear", "gs" -> SolverType.SOLVER_GS;
            default -> SolverType.SOLVER_BE;
        };
    }

    boolean markRunning(SimulationResponse response) {
        synchronized (response) {
            if (response.getStatus() != SimulationStatus.PENDING) {
                return false;
            }
            response.setStatus(SimulationStatus.RUNNING);
            return true;
        }
    }

    boolean markCancelled(SimulationResponse response) {
        synchronized (response) {
            if (isTerminal(response)) {
                return false;
            }
            response.setErrorMessage(CANCELLED_BY_USER);
            response.setEndTime(Instant.now());
            response.setStatus(SimulationStatus.FAILED);
            return true;
        }
    }

    boolean applySuccessfulResult(SimulationResponse response, SimulationResult result) {
        synchronized (response) {
            if (isTerminal(response)) {
                return false;
            }
            String[] names = result.getSignalNames();
            for (int i = 0; i < names.length; i++) {
                float[] values = result.getSignalData(i);
                if (values != null) {
                    double[] converted = new double[values.length];
                    for (int j = 0; j < values.length; j++) {
                        converted[j] = values[j];
                    }
                    response.addResult(names[i], converted);
                }
            }
            response.addResult("time", result.getTimeArray());
            response.setErrorMessage(null);
            response.setEndTime(Instant.now());
            response.setStatus(SimulationStatus.COMPLETED);
            return true;
        }
    }

    boolean applyFailureResult(SimulationResponse response, String errorMessage) {
        synchronized (response) {
            if (isTerminal(response)) {
                return false;
            }
            response.setErrorMessage(errorMessage);
            response.setEndTime(Instant.now());
            response.setStatus(SimulationStatus.FAILED);
            return true;
        }
    }

    private static boolean isTerminal(SimulationResponse response) {
        return response.getStatus() == SimulationStatus.COMPLETED || response.getStatus() == SimulationStatus.FAILED;
    }

    public BatchSimulationResponse submitBatch(BatchSimulationRequest request) {
        int requestedLimit = request.getMaxConcurrent() == null
                ? DEFAULT_BATCH_CONCURRENCY : request.getMaxConcurrent();
        BatchJob batch = new BatchJob(requestedLimit);
        BatchSimulationRequest.SimulationSettings settings = request.getSimulationSettings();
        double duration = settings != null && settings.getSimulationTime() != null ? settings.getSimulationTime() : 0.02;
        double dt = settings != null && settings.getTimeStep() != null ? settings.getTimeStep() : 1e-6;
        List<String> ids = new ArrayList<>();
        for (Map<String, Double> parameters : expandParameterSets(request)) {
            SimulationRequest child = new SimulationRequest(request.getCircuitFile(), duration, dt);
            child.setParameters(parameters);
            child.setSolverType(settings == null ? null : settings.getSolverType());
            SimulationJob job = createJob(child, batch);
            batch.pending.add(job);
            ids.add(job.response.getSimulationId());
        }
        batch.response = new BatchSimulationResponse(UUID.randomUUID().toString(), List.copyOf(ids), requestedLimit);
        batches.put(batch.response.getBatchId(), batch);
        dispatchBatch(batch);
        return batchSnapshot(batch);
    }

    private void dispatchBatch(BatchJob batch) {
        boolean retry;
        do {
            List<SimulationJob> admitted = new ArrayList<>();
            synchronized (batch) {
                while (!batch.cancelled && batch.active < batch.limit && !batch.pending.isEmpty()) {
                    SimulationJob job = batch.pending.remove();
                    if (!job.isCancelled()) {
                        batch.active++;
                        admitted.add(job);
                    }
                }
            }
            retry = false;
            for (SimulationJob job : admitted) {
                if (!dispatch(job)) {
                    synchronized (batch) {
                        batch.active--;
                    }
                    retry = true;
                }
            }
        } while (retry);
    }

    private void releaseBatchSlot(SimulationJob job) {
        if (job.batch != null) {
            synchronized (job.batch) {
                job.batch.active--;
            }
            dispatchBatch(job.batch);
        }
    }

    public BatchJobStatus getBatchStatus(String batchId) {
        BatchJob batch = batches.get(batchId);
        if (batch == null) {
            return null;
        }
        BatchJobStatus status = new BatchJobStatus();
        status.setBatchId(batchId);
        status.setTotal(batch.response.getTotalSimulations());
        status.setSubmittedAt(batch.response.getSubmittedAt());
        int completed = 0;
        int failed = 0;
        int running = 0;
        int pending = 0;
        List<String> failedIds = new ArrayList<>();
        Map<String, String> childStatuses = new HashMap<>();
        for (String id : batch.response.getSimulationIds()) {
            SimulationJob child = jobs.get(id);
            if (child == null) {
                continue;
            }
            SimulationStatus childStatus;
            synchronized (child) {
                childStatus = child.response.getStatus();
            }
            childStatuses.put(id, childStatus.toString());
            switch (childStatus) {
                case COMPLETED -> completed++;
                case FAILED -> { failed++; failedIds.add(id); }
                case RUNNING -> running++;
                case PENDING -> pending++;
            }
        }
        status.setCompleted(completed);
        status.setFailed(failed);
        status.setRunning(running);
        status.setPending(pending);
        status.setOverallProgress(status.getTotal() == 0 ? 0.0 : (completed + failed) * 100.0 / status.getTotal());
        status.setDone(completed + failed == status.getTotal());
        status.setSimulationStatuses(childStatuses);
        status.setFailedIds(failedIds.isEmpty() ? null : failedIds);
        return status;
    }

    public BatchSimulationResponse getBatch(String batchId) {
        BatchJob batch = batches.get(batchId);
        return batch == null ? null : batchSnapshot(batch);
    }

    private BatchSimulationResponse batchSnapshot(BatchJob batch) {
        BatchSimulationResponse source = batch.response;
        BatchSimulationResponse result = new BatchSimulationResponse();
        result.setBatchId(source.getBatchId());
        result.setTotalSimulations(source.getTotalSimulations());
        result.setSimulationIds(new ArrayList<>(source.getSimulationIds()));
        result.setSubmittedAt(source.getSubmittedAt());
        result.setMaxConcurrent(source.getMaxConcurrent());
        result.setMessage(source.getMessage());
        return result;
    }

    public boolean cancelBatch(String batchId) {
        BatchJob batch = batches.get(batchId);
        if (batch == null) {
            return false;
        }
        synchronized (batch) {
            batch.cancelled = true;
        }
        batch.response.getSimulationIds().forEach(this::cancelSimulation);
        return true;
    }

    private static final class SimulationJob {
        private final SimulationRequest request;
        private final BatchJob batch;
        private final SimulationResponse response;
        private final List<SseEmitter> emitters = new ArrayList<>();
        private HeadlessSimulationEngine engine;
        private boolean terminalPublished;

        private SimulationJob(SimulationRequest request, BatchJob batch) {
            this.request = request;
            this.batch = batch;
            response = new SimulationResponse(UUID.randomUUID().toString());
            response.setStartTime(Instant.now());
        }

        private synchronized boolean isCancelled() {
            return response.getStatus() == SimulationStatus.FAILED
                    && CANCELLED_BY_USER.equals(response.getErrorMessage());
        }
    }

    private static final class BatchJob {
        private final int limit;
        private final Queue<SimulationJob> pending = new ArrayDeque<>();
        private BatchSimulationResponse response;
        private int active;
        private boolean cancelled;

        private BatchJob(int requestedLimit) {
            limit = requestedLimit == 0 ? DEFAULT_BATCH_CONCURRENCY : Math.max(1, requestedLimit);
        }
    }
    private List<java.util.Map<String, Double>> expandParameterSets(BatchSimulationRequest req) {
        // Explicit list takes priority
        if (req.getParameterSets() != null && !req.getParameterSets().isEmpty()) {
            return req.getParameterSets();
        }
        // Linear sweep
        if (req.getLinearSweep() != null) {
            BatchSimulationRequest.LinearSweep sweep = req.getLinearSweep();
            List<java.util.Map<String, Double>> sets = new ArrayList<>();
            int n = sweep.getPoints();
            for (int i = 0; i < n; i++) {
                double val = sweep.getStartValue() + i * (sweep.getEndValue() - sweep.getStartValue()) / (n - 1);
                java.util.Map<String, Double> params = new java.util.HashMap<>();
                if (sweep.getFixedParameters() != null) {
                    params.putAll(sweep.getFixedParameters());
                }
                params.put(sweep.getParameterPath(), val);
                sets.add(params);
            }
            return sets;
        }
        // Log sweep
        if (req.getLogSweep() != null) {
            BatchSimulationRequest.LogSweep sweep = req.getLogSweep();
            List<java.util.Map<String, Double>> sets = new ArrayList<>();
            int n = sweep.getPoints();
            double logStart = Math.log10(sweep.getStartValue());
            double logEnd = Math.log10(sweep.getEndValue());
            for (int i = 0; i < n; i++) {
                double logVal = logStart + i * (logEnd - logStart) / (n - 1);
                double val = Math.pow(10, logVal);
                java.util.Map<String, Double> params = new java.util.HashMap<>();
                if (sweep.getFixedParameters() != null) {
                    params.putAll(sweep.getFixedParameters());
                }
                params.put(sweep.getParameterPath(), val);
                sets.add(params);
            }
            return sets;
        }
        return java.util.Collections.emptyList();
    }

}
