package gecko.geckocircuits.allg;

import gecko.core.preparation.CircuitPreparation;
import gecko.core.preparation.ExecutionOptions;
import gecko.core.preparation.PreparationRequest;
import gecko.core.preparation.PreparationResult;
import gecko.core.simulation.SessionHost;
import gecko.core.simulation.SimulationSession;
import gecko.geckocircuits.circuit.EditorCircuitCapture;
import gecko.geckocircuits.circuit.NetListContainer;
import gecko.geckocircuits.circuit.SchematicEditor2;
import gecko.geckocircuits.circuit.SolverSettings;
import gecko.geckocircuits.control.NetzlisteCONTROL;
import gecko.geckocircuits.control.ReglerOSZI;
import gecko.geckocircuits.control.ReglerSaveData;
import gecko.geckocircuits.datacontainer.ContainerStatus;
import java.io.File;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** Routes synchronous desktop starts to an independently owned core session. */
public final class DesktopSimulationController {
    public enum Backend { LEGACY, CORE, AUTO }

    private static final Logger FALLBACK = Logger.getLogger("gecko.backend.fallback");
    private final MainWindow window;
    private final SchematicEditor2 editor;
    private final Supplier<NetListContainer> preparePresentation;
    private volatile SessionHost currentHost;
    private volatile boolean coreExecution;
    private volatile double completedTime;
    private volatile CoreAsyncExecution asyncExecution;

    private static final class CoreAsyncExecution {
        private final SessionHost host;
        private final NetListContainer netlist;
        private final Consumer<ContainerStatus> origin;
        private final LegacySimulationFrontend presentation;
        private final int[] rows;
        private volatile Timer timer;
        private volatile long startedAtMillis;
        private volatile boolean awaitingRunAdmission;
        private volatile boolean ending;
        private volatile Future<SimulationSession.Status> runFuture;

        private CoreAsyncExecution(SessionHost host, NetListContainer netlist, Consumer<ContainerStatus> origin,
                LegacySimulationFrontend presentation, int[] rows) {
            this.host = host;
            this.netlist = netlist;
            this.origin = origin;
            this.presentation = presentation;
            this.rows = rows;
        }
    }

    DesktopSimulationController(MainWindow window, SchematicEditor2 editor, Supplier<NetListContainer> preparePresentation) {
        this.window = window;
        this.editor = editor;
        this.preparePresentation = preparePresentation;
    }

    void reset() {
        retireAsyncForReplacement();
        coreExecution = false;
        completedTime = 0;
        currentHost = null;
    }

    boolean hasCoreExecution() { return coreExecution; }

    double getCurrentTime() {
        SessionHost host = currentHost;
        return host == null ? completedTime : host.status().time();
    }

    boolean hasAsyncExecution() { return asyncExecution != null; }

    SimulationSession.Status getCoreStatus() {
        CoreAsyncExecution run = asyncExecution;
        return run == null ? null : run.host.status();
    }

    /** Returns false when GUI execution must use the legacy backend. */
    boolean startAsync(SolverSettings settings) throws Exception {
        Backend backend = Backend.valueOf(System.getProperty("gecko.backend", "legacy")
                .trim().toUpperCase(Locale.ROOT));
        if (backend == Backend.LEGACY) return false;

        String source = MainWindow.getOpenFileName();
        String circuit = new File(source).getName().replaceFirst("\\.ipes$", "");
        if (settings._T_pre.getValue() > 0) return fallback(backend, circuit, "PRECALC");
        if (settings._tPAUSE.getValue() >= 0) return fallback(backend, circuit, "PAUSE_TIME");

        var scripts = MainWindow._scripter;
        var sources = new EditorCircuitCapture.ScriptSources(scripts.getScriptCode(), scripts.getImportCode(),
                scripts.getDeclarationCode(), scripts.getExtraFilesHashes());
        var snapshot = new EditorCircuitCapture().capture(editor._circuitSheet.getAllElements(), settings,
                MainWindow.optimizerParameterData, MainWindow._fileManager, sources, source);
        var preparation = new CircuitPreparation().prepare(snapshot, PreparationRequest.defaults());
        if (!(preparation instanceof PreparationResult.Ready ready)) {
            String codes = preparation.report().diagnostics().stream().map(diagnostic -> diagnostic.code().toString())
                    .distinct().collect(Collectors.joining(","));
            return fallback(backend, circuit, codes);
        }

        SessionHost host = SessionHost.open(ready.execution(), ExecutionOptions.defaults());
        NetListContainer netlist = null;
        LegacySimulationFrontend presentation = null;
        Consumer<ContainerStatus> origin = null;
        boolean published = false;
        boolean statusStarted = false;
        try {
            netlist = preparePresentation.get();
            origin = NetzlisteCONTROL.globalData.captureStatusUpdater();
            String[] names = host.signalNames();
            int[] rows = new int[NetzlisteCONTROL.globalData.getRowLength()];
            String missingSignal = null;
            for (int row = 0; row < rows.length; row++) {
                rows[row] = Arrays.asList(names).indexOf(NetzlisteCONTROL.globalData.getSignalName(row));
                if (rows[row] < 0) {
                    missingSignal = NetzlisteCONTROL.globalData.getSignalName(row);
                    break;
                }
            }
            if (missingSignal != null) {
                cancelExporters(netlist);
                host.close();
                return fallback(backend, circuit, "SIGNAL_MAP:" + missingSignal);
            }

            netlist._nlControl.doMemorInits(settings.dt.getValue());
            presentation = new LegacySimulationFrontend(() -> true);
            presentation.prepare(netlist, () -> true, true).run();
            setScopeBoundaries(netlist, 0, settings._tDURATION.getValue());

            CoreAsyncExecution run = new CoreAsyncExecution(host, netlist, origin, presentation, rows);
            run.startedAtMillis = System.currentTimeMillis();
            asyncExecution = run;
            currentHost = host;
            coreExecution = true;
            completedTime = 0;
            LegacySimulationFrontend.onEventThread(() -> {
                run.origin.accept(ContainerStatus.RUNNING);
                window.setMenuDuringSimulation(true, false);
                run.timer = new Timer(40, event -> tick(run));
                run.runFuture = host.run();
                run.timer.start();
            });
            statusStarted = true;
            published = true;
            return true;
        } finally {
            if (!published) {
                if (host.status().state() != SimulationSession.State.CLOSED) host.close();
                if (netlist != null) cancelExporters(netlist);
                if (statusStarted && origin != null) {
                    Consumer<ContainerStatus> capturedOrigin = origin;
                    LegacySimulationFrontend.onEventThread(() -> capturedOrigin.accept(ContainerStatus.DELETED));
                }
                asyncExecution = null;
                currentHost = null;
                coreExecution = false;
            }
        }
    }

    boolean pauseAsync() {
        CoreAsyncExecution run = asyncExecution;
        if (run == null) return false;
        SimulationSession.State state = run.host.status().state();
        if (state == SimulationSession.State.RUNNING || state == SimulationSession.State.READY) run.host.pause();
        return true;
    }

    boolean continueAsync(SolverSettings settings) {
        CoreAsyncExecution run = asyncExecution;
        if (run == null) return false;
        SimulationSession.Status status = run.host.status();
        if (status.state() == SimulationSession.State.CLOSED || status.state() == SimulationSession.State.FAILED) {
            return true;
        }
        if (status.state() == SimulationSession.State.RUNNING) return true;

        double end = status.endTime();
        boolean reachedEnd = status.state() == SimulationSession.State.COMPLETED || status.time() >= end;
        if (reachedEnd) {
            end += settings._tDURATION.getValue();
            run.host.extend(end, settings.dt.getValue());
            setScopeBoundaries(run.netlist, status.time(), end);
        }
        completedTime = 0;
        run.startedAtMillis = System.currentTimeMillis();
        run.awaitingRunAdmission = true;
        // Queue the next run before restarting the EDT timer. In the extension case, the
        // previous COMPLETED status remains visible while extend() copies the recording;
        // a timer tick in that window would stop itself before the queued run starts.
        run.runFuture = run.host.run();
        LegacySimulationFrontend.onEventThread(() -> {
            run.origin.accept(ContainerStatus.RUNNING);
            run.presentation.accept(true);
            window.setMenuDuringSimulation(true, false);
            if (!run.timer.isRunning()) run.timer.start();
        });
        return true;
    }

    boolean endAsync() {
        CoreAsyncExecution run = asyncExecution;
        if (run == null) return false;
        run.ending = true;
        if (run.runFuture != null && !run.runFuture.isDone()) run.host.pause();
        LegacySimulationFrontend.onEventThread(() -> {
            if (run.runFuture == null || run.runFuture.isDone()) finishEnd(run);
            else if (run.timer != null && !run.timer.isRunning()) run.timer.start();
        });
        return true;
    }

    private void retireAsyncForReplacement() {
        CoreAsyncExecution run = asyncExecution;
        if (run == null) return;
        asyncExecution = null;
        run.host.close();
        LegacySimulationFrontend.onEventThread(() -> {
            if (run.timer != null) run.timer.stop();
            cancelExporters(run.netlist);
            run.origin.accept(ContainerStatus.DELETED);
            run.presentation.accept(false);
        });
    }

    private void finishEnd(CoreAsyncExecution run) {
        if (asyncExecution != run) return;
        if (run.runFuture != null && !run.runFuture.isDone()) return;
        if (run.timer != null) run.timer.stop();
        SimulationSession.Status terminalStatus = run.host.status();
        double finalTime = terminalStatus.time();
        run.host.close();
        drainSamples(run);
        completedTime = finalTime;
        currentHost = null;
        asyncExecution = null;
        if (terminalStatus.state() == SimulationSession.State.FAILED) {
            cancelExporters(run.netlist);
            run.origin.accept(ContainerStatus.DELETED);
            run.presentation.accept(false);
            window.setMenusAfterSimulationFailure();
            window.jtfStatus.setText("Simulation aborted.");
            return;
        }
        run.origin.accept(ContainerStatus.PAUSED);
        run.presentation.accept(false);
        window.setMenusAfterSimulationFailure();
        window.jtfStatus.setText("Stopped after  ");
    }

    private void tick(CoreAsyncExecution run) {
        if (asyncExecution != run) {
            run.timer.stop();
            return;
        }
        SimulationSession.Status status = run.host.status();
        if (run.ending) {
            if (run.runFuture == null || run.runFuture.isDone()) finishEnd(run);
            return;
        }
        if (run.awaitingRunAdmission) {
            if (run.runFuture != null && !run.runFuture.isDone()
                    && status.state() != SimulationSession.State.RUNNING) return;
            run.awaitingRunAdmission = false;
        }
        drainSamples(run);
        SimulationSession.State state = status.state();
        if (state != SimulationSession.State.PAUSED && state != SimulationSession.State.COMPLETED
                && state != SimulationSession.State.FAILED) return;

        drainSamples(run);
        run.timer.stop();
        completedTime = status.time();
        if (state == SimulationSession.State.FAILED) {
            // A PAUSED status can trigger DATA_EXPORT. Cancel first and invalidate the
            // captured recording so a failed partial core run cannot be saved as complete.
            cancelExporters(run.netlist);
            run.origin.accept(ContainerStatus.DELETED);
            run.presentation.accept(false);
            window.setMenusAfterSimulationFailure();
        } else {
            run.origin.accept(ContainerStatus.PAUSED);
            run.presentation.accept(false);
            // Paused and completed executions can both be continued in the legacy UI.
            window.setMenuDuringSimulation(false, true);
            if (state == SimulationSession.State.COMPLETED) {
                window.jtfStatus.setzeStatusRechenzeit(System.currentTimeMillis() - run.startedAtMillis);
            }
        }
    }

    private static void drainSamples(CoreAsyncExecution run) {
        run.host.drainSamples(sample -> insertSample(run, sample));
    }

    private static void insertSample(CoreAsyncExecution run, SimulationSession.Sample sample) {
        float[] sourceValues = sample.values();
        float[] values = new float[run.rows.length];
        for (int row = 0; row < run.rows.length; row++) values[row] = sourceValues[run.rows[row]];
        NetzlisteCONTROL.globalData.insertValuesAtEnd(values, sample.time());
    }

    private static void setScopeBoundaries(NetListContainer netlist, double start, double end) {
        LegacySimulationFrontend.onEventThread(() -> {
            for (var block : netlist._nlControl._orderedControlBlocks) {
                if (block instanceof ReglerOSZI scope) scope.setSimulationTimeBoundaries(start, end);
            }
        });
    }

    private static void cancelExporters(NetListContainer netlist) {
        LegacySimulationFrontend.onEventThread(() -> {
            for (var block : netlist._nlControl._orderedControlBlocks) {
                if (block instanceof ReglerSaveData exporter) exporter.cancelSimulationExport();
            }
        });
    }

    /** Returns false when the caller must use the existing legacy start path. */
    boolean start(SolverSettings settings) throws Exception {
        Backend backend = Backend.valueOf(System.getProperty("gecko.backend", "legacy")
                .trim().toUpperCase(Locale.ROOT));
        if (backend == Backend.LEGACY) return false;
        String source = MainWindow.getOpenFileName();
        String circuit = new File(source).getName().replaceFirst("\\.ipes$", "");
        if (settings._T_pre.getValue() > 0) return fallback(backend, circuit, "PRECALC");
        if (settings._tPAUSE.getValue() >= 0) return fallback(backend, circuit, "PAUSE_TIME");

        var scripts = MainWindow._scripter;
        var sources = new EditorCircuitCapture.ScriptSources(scripts.getScriptCode(), scripts.getImportCode(),
                scripts.getDeclarationCode(), scripts.getExtraFilesHashes());
        var snapshot = new EditorCircuitCapture().capture(editor._circuitSheet.getAllElements(), settings,
                MainWindow.optimizerParameterData, MainWindow._fileManager, sources, source);
        var preparation = new CircuitPreparation().prepare(snapshot, PreparationRequest.defaults());
        if (!(preparation instanceof PreparationResult.Ready ready)) {
            String codes = preparation.report().diagnostics().stream().map(diagnostic -> diagnostic.code().toString())
                    .distinct().collect(Collectors.joining(","));
            return fallback(backend, circuit, codes);
        }
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Run synchronous core calculations outside the Swing event thread");
        }

        try (var host = SessionHost.open(ready.execution(), ExecutionOptions.defaults())) {
            var netlist = preparePresentation.get();
            var origin = NetzlisteCONTROL.globalData.captureStatusUpdater();
            var presentation = new LegacySimulationFrontend(() -> true);
            String[] names = host.signalNames();
            int[] rows = new int[NetzlisteCONTROL.globalData.getRowLength()];
            String missingSignal = null;
            for (int row = 0; row < rows.length; row++) {
                String name = NetzlisteCONTROL.globalData.getSignalName(row);
                rows[row] = Arrays.asList(names).indexOf(name);
                if (rows[row] < 0) {
                    missingSignal = name;
                    break;
                }
            }
            if (missingSignal != null && backend == Backend.AUTO) {
                // Retire unused preparation resources before the legacy factory creates their replacements.
                LegacySimulationFrontend.onEventThread(() -> {
                    for (var block : netlist._nlControl._orderedControlBlocks) {
                        if (block instanceof ReglerSaveData exporter) exporter.cancelSimulationExport();
                    }
                });
                return fallback(backend, circuit, "SIGNAL_MAP:" + missingSignal);
            }
            boolean completed = false;
            Throwable failure = null;
            try {
                if (missingSignal != null) return fallback(backend, circuit, "SIGNAL_MAP:" + missingSignal);
                netlist._nlControl.doMemorInits(settings.dt.getValue());
                presentation.prepare(netlist, () -> true, true).run();
                LegacySimulationFrontend.onEventThread(() -> {
                    for (var block : netlist._nlControl._orderedControlBlocks) {
                        if (block instanceof ReglerOSZI scope) {
                            scope.setSimulationTimeBoundaries(0, settings._tDURATION.getValue());
                        }
                    }
                    window.setMenuDuringSimulation(true, false);
                });
                coreExecution = true;
                currentHost = host;
                try {
                    host.run().get();
                } catch (ExecutionException error) {
                    if (error.getCause() instanceof Error cause) throw cause;
                    if (error.getCause() instanceof RuntimeException cause) throw cause;
                    throw new IllegalStateException("Core calculation failed", error.getCause());
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw error;
                }
                host.drainSamples(sample -> {
                    float[] sourceValues = sample.values();
                    float[] values = new float[rows.length];
                    for (int row = 0; row < rows.length; row++) values[row] = sourceValues[rows[row]];
                    NetzlisteCONTROL.globalData.insertValuesAtEnd(values, sample.time());
                });
                completed = true;
                return true;
            } catch (Exception | Error error) {
                failure = error;
                throw error;
            } finally {
                try {
                    completedTime = completed ? host.status().time() : 0;
                    currentHost = null;
                    if (completed) {
                        LegacySimulationFrontend.onEventThread(() -> origin.accept(ContainerStatus.PAUSED));
                    } else {
                        coreExecution = false;
                        host.close();
                        LegacySimulationFrontend.onEventThread(() -> {
                            for (var block : netlist._nlControl._orderedControlBlocks) {
                                if (block instanceof ReglerSaveData exporter) exporter.cancelSimulationExport();
                            }
                            origin.accept(ContainerStatus.DELETED);
                        });
                    }
                    presentation.accept(false);
                    boolean successful = completed;
                    LegacySimulationFrontend.onEventThread(() -> {
                        if (successful) window.setMenuDuringSimulation(false, true);
                        else window.setMenusAfterSimulationFailure();
                    });
                } catch (RuntimeException | Error cleanupError) {
                    if (failure == null) throw cleanupError;
                    if (failure != cleanupError) failure.addSuppressed(cleanupError);
                }
            }
        }
    }

    private static boolean fallback(Backend backend, String circuit, String reason) {
        if (backend == Backend.CORE) throw new IllegalStateException("Core cannot run " + circuit + ": " + reason);
        FALLBACK.info(circuit + ": " + reason);
        return false;
    }
}
