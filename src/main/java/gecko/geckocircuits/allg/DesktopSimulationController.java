package gecko.geckocircuits.allg;

import gecko.core.preparation.CircuitPreparation;
import gecko.core.preparation.ExecutionOptions;
import gecko.core.preparation.PreparationRequest;
import gecko.core.preparation.PreparationResult;
import gecko.core.simulation.SessionHost;
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
import java.util.function.Supplier;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.swing.SwingUtilities;

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

    DesktopSimulationController(MainWindow window, SchematicEditor2 editor, Supplier<NetListContainer> preparePresentation) {
        this.window = window;
        this.editor = editor;
        this.preparePresentation = preparePresentation;
    }

    void reset() {
        coreExecution = false;
        completedTime = 0;
    }

    boolean hasCoreExecution() { return coreExecution; }

    double getCurrentTime() {
        SessionHost host = currentHost;
        return host == null ? completedTime : host.status().time();
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
