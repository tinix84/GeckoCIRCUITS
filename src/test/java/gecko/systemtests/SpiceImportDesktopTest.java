package gecko.systemtests;

import gecko.GeckoSim;
import gecko.core.allg.GeckoFile;
import gecko.core.io.importer.Main;
import gecko.core.simulation.SimulationSession;
import gecko.geckocircuits.allg.MainWindow;
import gecko.geckocircuits.circuit.AbstractBlockInterface;
import gecko.geckocircuits.circuit.ConnectorType;
import gecko.geckocircuits.circuit.SchematicEditor2;
import gecko.geckocircuits.circuit.Verbindung;
import gecko.geckocircuits.circuit.circuitcomponents.AbstractCircuitSource;
import gecko.geckocircuits.control.NetzlisteCONTROL;
import gecko.geckocircuits.control.ReglerOSZI;
import gecko.geckocircuits.control.ReglerSignalSource;
import gecko.geckocircuits.control.ReglerVOLT;
import gecko.geckocircuits.datacontainer.ContainerStatus;
import java.awt.Color;
import java.awt.Component;
import java.awt.Point;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Opens and runs the imported SPICE fixture on the regression suite's display. */
@Tag("regression")
class SpiceImportDesktopTest {
    private static final Path SOURCE = Path.of("src/modules/gecko-simulation-core/src/test/resources/importer/rc.cir");
    private static final double STEP = 1e-6;
    private static final double END = 6e-3;
    private static final double FULL_SCALE = 5;

    @Test
    void opensAndRunsImportedRcWithCoreDesktopBackend() throws Exception {
        Path target = Path.of("target").toAbsolutePath();
        Path reports = Files.createDirectories(target.resolve("surefire-reports"));
        Path evidence = Files.createTempDirectory(reports, "m6-spice-rc-");
        Path appHome = Files.createTempDirectory(target, "m6-spice-rc-home-");
        Path log = evidence.resolve("desktop.log");
        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var worker = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Duser.home=" + appHome, "-Djava.io.tmpdir=" + appHome, "-Dgecko.backend=core", "-cp", classPath,
                DesktopRun.class.getName(), evidence.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile());
        worker.environment().put("LOCALAPPDATA", appHome.toString());
        Process process = worker.start();
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "The desktop test timed out. See " + log);
            assertEquals(0, process.exitValue(), Files.readString(log));
            assertTrue(Files.isRegularFile(evidence.resolve("receipt.txt")), "Desktop evidence is missing. See " + log);
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        }
    }

    /** Runs in a separate JVM so the desktop owns its settings, windows, and global state. */
    public static final class DesktopRun {
        public static void main(String[] args) {
            try {
                Path evidence = Path.of(args[0]);
                Path circuit = evidence.resolve("imported-rc.ipes");
                assertEquals(0, Main.run(new String[]{SOURCE.toString(), circuit.toString()}, System.out, System.err));
                openRunAndCapture(circuit, evidence);
                System.exit(0);
            } catch (Throwable error) {
                error.printStackTrace();
                System.exit(1);
            }
        }
    }

    private static void openRunAndCapture(Path circuit, Path evidence) throws Exception {
        var dialogTitle = new AtomicReference<String>();
        Timer dialogMonitor = new Timer(50, event -> {
            for (Window window : Window.getWindows()) {
                if (window instanceof JDialog dialog && dialog.isShowing() && dialog.isModal()) {
                    dialogTitle.compareAndSet(null, Objects.toString(dialog.getTitle(), "<untitled modal dialog>"));
                    dialog.dispose();
                }
            }
        });
        dialogMonitor.start();
        try {
            ReglerOSZI scope = onEventThread(() -> {
                GeckoSim._isTestingMode = true;
                GeckoSim.main(new String[]{circuit.toString()});
                assertNull(dialogTitle.get(), "Opening the circuit showed a modal dialog");
                assertTrue(GeckoSim._win.isShowing(), "The desktop window is hidden");
                assertEquals(circuit.toString(), MainWindow.getOpenFileName());
                ReglerOSZI importedScope = checkLoadedCircuit();
                GeckoSim._win.initStartWithErrorDialogMessage();
                assertNull(dialogTitle.get(), "Starting the circuit showed a modal dialog");
                return importedScope;
            });

            var runner = GeckoSim._win._simRunner;
            var status = assertNotNullStatus(runner.getCoreStatus());
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < deadline && status.state() != SimulationSession.State.FAILED
                    && (status.state() != SimulationSession.State.COMPLETED
                    || onEventThread(NetzlisteCONTROL.globalData::getContainerStatus) != ContainerStatus.PAUSED)) {
                Thread.sleep(20);
                status = assertNotNullStatus(runner.getCoreStatus());
            }
            assertEquals(SimulationSession.State.COMPLETED, status.state(), "Core simulation status " + status);
            assertEquals(END, status.time(), STEP + 1e-12);
            int steps = status.committedSteps();
            onEventThread(() -> {
                assertEquals(ContainerStatus.PAUSED, NetzlisteCONTROL.globalData.getContainerStatus());
                assertNull(dialogTitle.get(), "The simulation showed a modal dialog");
                captureEvidence(scope, steps, circuit, evidence);
                return null;
            });
        } finally {
            dialogMonitor.stop();
            onEventThread(() -> {
                if (GeckoSim._win != null) GeckoSim._win._simRunner.endSimulation();
                for (Window window : Window.getWindows()) window.dispose();
                return null;
            });
        }
    }

    private static ReglerOSZI checkLoadedCircuit() {
        var editor = MainWindow._se;
        var electrical = editor.getElementLK().stream().collect(
                Collectors.toMap(AbstractBlockInterface::getStringID, Function.identity()));
        var control = editor.getElementCONTROL().stream().collect(
                Collectors.toMap(AbstractBlockInterface::getStringID, Function.identity()));
        assertEquals(Set.of("V1", "R1", "C1"), electrical.keySet());
        assertEquals(Set.of("SIGNAL.V1", "VOLT.N1", "VOLT.N2", "SCOPE.IMPORT"), control.keySet());
        var wires = editor.getConnection(ConnectorType.LK_AND_RELUCTANCE).stream().collect(
                Collectors.groupingBy(Verbindung::getLabel, Collectors.counting()));
        assertEquals(Map.of("0", 2L, "N1", 2L, "N2", 2L), wires);
        assertTrue(editor.getConnection(ConnectorType.CONTROL).isEmpty());
        assertEquals(STEP, MainWindow._solverSettings.dt.getValue(), 1e-12);
        assertEquals(END, MainWindow._solverSettings._tDURATION.getValue(), 1e-12);
        var worksheet = editor._circuitSheet._worksheetSize;
        assertEquals(54, worksheet.getSizeX());
        assertEquals(35, worksheet.getSizeY());

        var source = assertInstanceOf(AbstractCircuitSource.class, electrical.get("V1"));
        var signal = assertInstanceOf(ReglerSignalSource.class, control.get("SIGNAL.V1"));
        assertEquals("control_0", signal.YOUT.getFirst().getLabelObject().getLabelString());
        assertEquals("control_0", source.getPotentialCoupling().getLabels()[0]);
        for (String node : List.of("N1", "N2")) {
            var probe = assertInstanceOf(ReglerVOLT.class, control.get("VOLT." + node));
            assertArrayEquals(new String[]{node, "0"}, probe.getPotentialCoupling().getLabels());
        }
        assertEquals(1, MainWindow._fileManager._allAvailableFiles.size());
        var resource = MainWindow._fileManager._allAvailableFiles.iterator().next();
        assertEquals(List.of(resource), signal.getFiles());
        assertEquals(GeckoFile.StorageType.INTERNAL, resource.getStorageType());
        assertEquals(1, resource.noOfUsers());
        var resourceBlock = new StringBuffer();
        resource.exportASCII(resourceBlock);
        assertTrue(resourceBlock.toString().contains("\n<usageList>\n" + signal.getUniqueObjectIdentifier()
                + "\n<\\usageList>\n"), "The embedded waveform owner must be SIGNAL.V1");
        return assertInstanceOf(ReglerOSZI.class, control.get("SCOPE.IMPORT"));
    }

    private static void captureEvidence(ReglerOSZI scope, int steps, Path circuit, Path evidence) throws Exception {
        var data = scope.getZVDatenImRAM();
        assertEquals(2, data.getRowLength());
        assertEquals("V_N1", data.getSignalName(0));
        assertEquals("V_N2", data.getSignalName(1));
        int last = data.getMaximumTimeIndex(1);
        assertTrue(steps >= 6000, "The RC run must cover six milliseconds");
        assertEquals(steps, last + 1, "SCOPE must record every committed step");
        assertEquals(last, data.getMaximumTimeIndex(0));
        assertEquals(0, data.getValue(0, 0), 1e-6);
        assertEquals(FULL_SCALE, data.getValue(0, last), 1e-6);
        double maxError = 0;
        for (int i = 0; i <= last; i++) {
            double time = data.getTimeValue(i, 1);
            assertEquals(i * STEP, time, 1e-10, "SCOPE time at sample " + i);
            maxError = Math.max(maxError, Math.abs(data.getValue(1, i) - analyticRc(time)));
        }
        assertTrue(maxError <= 0.01 * FULL_SCALE, "Maximum capacitor voltage error in V " + maxError);

        SchematicEditor2._controlDisplayMode.showName = true;
        GeckoSim._win.setSize(1000, 730);
        GeckoSim._win.validate();
        var viewport = MainWindow.seScroll.getViewport();
        viewport.setViewPosition(new Point());
        writePng(viewport, evidence.resolve("m6-spice-rc-schematic.png"));

        scope._scopeFrame.setVisible(true);
        scope._scopeFrame.getGrafer().setAxisPositions();
        scope._scopeFrame.getGrafer().doZoomAutoFit();
        writePng(scope._scopeFrame._scope, evidence.resolve("m6-spice-rc-scope.png"));
        Files.writeString(evidence.resolve("receipt.txt"), String.format(Locale.ROOT, """
                source=%s
                imported_circuit=%s
                backend=core
                state=COMPLETED
                worksheet_grid=54x35
                electrical=V1,R1,C1
                electrical_wires=6
                control=SIGNAL.V1,VOLT.N1,VOLT.N2,SCOPE.IMPORT
                embedded_waveform_owner=SIGNAL.V1
                scope=SCOPE.IMPORT
                channels=V_N1,V_N2
                samples=%d
                step_seconds=%.9f
                last_time_seconds=%.9f
                last_v_in_volts=%.9f
                last_v_out_volts=%.9f
                max_error_volts=%.9f
                full_scale_error_limit_volts=0.05
                """, SOURCE, circuit.getFileName(), steps, STEP, data.getTimeValue(last, 1),
                data.getValue(0, last), data.getValue(1, last), maxError));
    }

    private static double analyticRc(double time) {
        double elapsed = Math.max(0, time - 100e-6);
        double tau = 1e-3;
        double rise = 1e-6;
        double ramp = Math.min(elapsed, rise);
        double voltage = FULL_SCALE / rise * (ramp + tau * Math.expm1(-ramp / tau));
        return elapsed <= rise ? voltage : FULL_SCALE + (voltage - FULL_SCALE) * Math.exp(-(elapsed - rise) / tau);
    }

    private static SimulationSession.Status assertNotNullStatus(SimulationSession.Status status) {
        assertNotNull(status, "The desktop runner must use a core session");
        return status;
    }

    private static void writePng(Component component, Path file) throws Exception {
        assertTrue(component.isShowing(), "The component to capture must be visible");
        var image = new BufferedImage(component.getWidth(), component.getHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setClip(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            component.paint(graphics);
        } finally {
            graphics.dispose();
        }
        int[] pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        assertTrue(Arrays.stream(pixels).anyMatch(pixel -> pixel != pixels[0]), "The capture is blank " + file);
        assertTrue(ImageIO.write(image, "png", file.toFile()), "No PNG writer is available");
    }

    private static <T> T onEventThread(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
