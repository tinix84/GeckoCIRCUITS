package gecko.systemtests;

import gecko.GeckoExternal;
import gecko.GeckoSim;
import gecko.core.preparation.CircuitPreparation;
import gecko.core.preparation.PreparationRequest;
import gecko.core.preparation.PreparationResult;
import gecko.geckocircuits.allg.MainWindow;
import gecko.geckocircuits.circuit.EditorCircuitCapture;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** Inventories editor-captured readiness. This predicts routing without starting a simulation. */
@Tag("regression")
public class BackendCorpusProbeTest {
    private static final Set<String> READY_EDUCATION = Set.of(
            "buck_simple", "boost_simple", "buckBoost_simple", "cuk_simple", "sepic_simple",
            "boostPFC", "boostPFC_currentControl", "2phaseDiodeBridge_AC-Inductor",
            "2phaseDiodeBridge_DC-Inductor", "thyristor_RL_single", "thyristor_RL_2phBridge",
            "thyristor_RL_3phBridge", "thyristor_freeWheelingDiode", "thyristor_interface_trafo",
            "thyristor_Jakopovic");
    private static final Map<String, Set<String>> FALLBACK_EDUCATION = Map.of(
            "singlePhase_PWM_converter", Set.of("UNSUPPORTED_SOURCE_MODE", "UNCONNECTED_GROUND",
                    "MISSING_CONTROL_INPUT"),
            "diode_RL_singlePH_trafo", Set.of("UNCONNECTED_GROUND"),
            "thyristor_RL_singlePh_trafo", Set.of("UNCONNECTED_GROUND"),
            "thyristor_RL_3ph_trafo", Set.of("UNCONNECTED_GROUND"),
            "thyristor_commutation_3ph_trafo", Set.of("UNCONNECTED_GROUND"),
            "thyristor_lossOfCommutation", Set.of("UNCONNECTED_GROUND"));
    private static void startApplication() throws Exception {
        GeckoSim._isTestingMode = true;
        GeckoSim.applicationProps = new Properties();
        try (InputStream defaults = GeckoSim.class.getResourceAsStream("/defaultProperties.prp")) {
            assertNotNull(defaults, "default properties must be available");
            GeckoSim.applicationProps.load(defaults);
        }
        GeckoSim.applicationProps.setProperty("UPDATE_FREQ", "NEVER");
        GeckoSim.main(new String[0]);
        Thread.sleep(2000);
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void inventoriesPredictedReadinessRoutesForTheWholeCorpus() throws Exception {
        String classPath = System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path"));
        Process worker = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classPath, ProbeProcess.class.getName())
                .directory(Path.of("").toAbsolutePath().toFile())
                .redirectErrorStream(true).start();
        var transcript = CompletableFuture.supplyAsync(() -> {
            try (InputStream output = worker.getInputStream()) {
                return output.readAllBytes();
            } catch (java.io.IOException failure) {
                throw new UncheckedIOException(failure);
            }
        });
        try {
            boolean completed = worker.waitFor(150, TimeUnit.SECONDS);
            if (!completed) worker.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            System.out.print(new String(transcript.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8));
            assertTrue(completed, "The isolated corpus probe timed out");
            assertEquals(0, worker.exitValue(), "The isolated corpus probe failed");
        } finally {
            if (worker.isAlive()) worker.destroyForcibly();
            worker.getInputStream().close();
        }
    }

    /** Owns all desktop windows and globals in an isolated JVM. */
    public static final class ProbeProcess {
        public static void main(String[] arguments) {
            int exit = 0;
            try {
                startApplication();
                inventoryPredictedRoutes();
            } catch (Throwable failure) {
                failure.printStackTrace();
                exit = 1;
            }
            System.exit(exit);
        }
    }

    private static void inventoryPredictedRoutes() throws Exception {
        Path root = Path.of("").toAbsolutePath().normalize();
        List<Path> files = corpusFiles(root);
        assertFalse(files.isEmpty(), "The corpus must contain circuit files");
        int core = 0, fallback = 0, captureErrors = 0, education = 0;
        var preparation = new CircuitPreparation();
        System.out.println("CORPUS | Predicted readiness routes use the same editor capture and preparation as auto routing.");
        System.out.println("CORPUS | This does not call a controller route API or verify presentation signal mapping.");
        for (Path file : files) {
            String relative = root.relativize(file).toString().replace('\\', '/');
            boolean isEducation = relative.startsWith("src/test/resources/ipes/education/");
            String name = file.getFileName().toString().replaceFirst("\\.ipes$", "");
            System.out.println("CORPUS_OPEN | " + relative);
            try {
                openCircuit(file);
                var scripts = MainWindow._scripter;
                var snapshot = new EditorCircuitCapture().capture(MainWindow._se._circuitSheet.getAllElements(),
                        MainWindow._solverSettings, MainWindow.optimizerParameterData, MainWindow._fileManager,
                        new EditorCircuitCapture.ScriptSources(scripts.getScriptCode(), scripts.getImportCode(),
                                scripts.getDeclarationCode(), scripts.getExtraFilesHashes()), file.toString());
                var result = preparation.prepare(snapshot, PreparationRequest.defaults());
                Set<String> codes = result.report().diagnostics().stream().map(diagnostic -> diagnostic.code())
                        .collect(Collectors.toCollection(TreeSet::new));
                var reasons = new TreeSet<>(codes);
                if (MainWindow._solverSettings._T_pre.getValue() > 0) reasons.add("PRECALC");
                if (MainWindow._solverSettings._tPAUSE.getValue() >= 0) reasons.add("PAUSE_TIME");
                boolean policyFallback = MainWindow._solverSettings._T_pre.getValue() > 0
                        || MainWindow._solverSettings._tPAUSE.getValue() >= 0;
                boolean predictedCore = result instanceof PreparationResult.Ready && !policyFallback;
                if (predictedCore) core++; else fallback++;
                System.out.println("CORPUS | " + relative + " | predicted "
                        + (predictedCore ? "core" : "fallback") + " | " + String.join(",", reasons));
                if (isEducation) {
                    education++;
                    if (READY_EDUCATION.contains(name)) {
                        assertTrue(predictedCore, relative + ": " + reasons);
                    } else {
                        assertTrue(FALLBACK_EDUCATION.containsKey(name), "Unknown education circuit: " + name);
                        assertFalse(predictedCore, relative);
                        assertEquals(FALLBACK_EDUCATION.get(name), reasons, relative);
                    }
                }
            } catch (Exception failure) {
                captureErrors++;
                System.out.println("CORPUS | " + relative + " | unsupported capture | "
                        + failure.getClass().getSimpleName() + ": "
                        + String.valueOf(failure.getMessage()).replace('\n', ' ').replace('\r', ' '));
                if (isEducation) throw failure;
            }
        }
        assertEquals(21, education, "All education circuits must be inventoried");
        System.out.printf("CORPUS_SUMMARY | files=%d | predicted core=%d | predicted fallback=%d | unsupported capture=%d%n",
                files.size(), core, fallback, captureErrors);
    }

    private static List<Path> corpusFiles(Path root) throws Exception {
        var roots = new ArrayList<Path>();
        roots.add(root.resolve("src/test/resources"));
        roots.add(root.resolve("resources/tutorials"));
        try (var modules = Files.list(root.resolve("src/modules"))) {
            modules.filter(Files::isDirectory).map(module -> module.resolve("src/test/resources"))
                    .filter(Files::isDirectory).forEach(roots::add);
        }
        var files = new TreeSet<Path>();
        for (Path directory : roots) {
            assertTrue(Files.isDirectory(directory), "Missing corpus directory: " + directory);
            try (var paths = Files.walk(directory)) {
                paths.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".ipes"))
                        .filter(path -> !path.toString().contains("/target/"))
                        .map(Path::normalize).forEach(files::add);
            }
        }
        return List.copyOf(files);
    }

    private static void openCircuit(Path file) throws Exception {
        Timer notices = new Timer(100, event -> {
            for (Window window : Window.getWindows()) {
                if (!(window instanceof JDialog dialog) || !dialog.isShowing()) continue;
                // A closed dialog can remain in Window.getWindows after its root pane is removed.
                var rootPane = dialog.getRootPane();
                if (rootPane == null) continue;
                if (findOptionPane(rootPane.getContentPane()) instanceof JOptionPane pane) {
                    String message = String.valueOf(pane.getMessage());
                    if (("Info".equals(dialog.getTitle()) && message.startsWith(
                            "This Model file was created with an older version of GeckoCIRCUITS."))
                            || ("Error!".equals(dialog.getTitle()) && message.startsWith(
                                    "There are circuit components located outside the selected sheet size "))
                            || message.equals("Cannot resize due to bad component location.")) {
                        System.out.println("CORPUS_NOTICE | " + file.getFileName() + " | "
                                + message.replace('\n', ' '));
                        pane.setValue(JOptionPane.OK_OPTION);
                    }
                }
            }
        });
        SwingUtilities.invokeAndWait(notices::start);
        try {
            GeckoExternal.openFile(file.toString());
        } finally {
            SwingUtilities.invokeAndWait(notices::stop);
        }
    }

    private static Component findOptionPane(Container container) {
        if (container == null) return null;
        if (container instanceof JOptionPane) return container;
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) {
                Component found = findOptionPane(nested);
                if (found != null) return found;
            }
        }
        return null;
    }
}
