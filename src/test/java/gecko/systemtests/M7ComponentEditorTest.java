package gecko.systemtests;

import gecko.GeckoExternal;
import gecko.GeckoSim;
import gecko.core.ac.AcDocumentSettings;
import gecko.core.ac.AcSweep;
import gecko.core.io.CircuitFileParser;
import gecko.core.simulation.ProbeReference;
import gecko.geckocircuits.allg.MainWindow;
import gecko.geckocircuits.circuit.AbstractBlockInterface;
import gecko.geckocircuits.circuit.DialogCircuitComponent;
import gecko.geckocircuits.circuit.Enabled;
import gecko.geckocircuits.control.ControlGlobalTerminal;
import gecko.geckocircuits.control.ReglerDemux;
import gecko.geckocircuits.control.ReglerFromEXTERNAL;
import gecko.geckocircuits.control.ReglerMUX;
import gecko.geckocircuits.control.ReglerProbe;
import gecko.geckocircuits.control.ReglerSaveData;
import gecko.geckocircuits.control.ReglerToEXTERNAL;
import gecko.i18n.resources.I18nKeys;
import gecko.modelviewcontrol.AbstractUndoGenericModel;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Tag("regression")
class M7ComponentEditorTest {
    private boolean previousTestingMode;

    @BeforeEach
    void openFixture() throws Exception {
        previousTestingMode = GeckoSim._isTestingMode;
        GeckoSim._isTestingMode = true;
        GeckoSim.main(new String[0]);
        GeckoExternal.openFile(ComponentProbeParityTest.writeProbeBuck(false).toAbsolutePath().toString());
    }

    @AfterEach
    void restoreTestingMode() {
        GeckoSim._isTestingMode = previousTestingMode;
    }

    @Test
    void editorRenameUpdatesSavedReferencesAndOpenDraftThroughUndoAndRedo() throws Exception {
        onEdt(() -> {
            var probe = (ReglerProbe) block("PROBE.1");
            var inductor = block("L.1");
            var output = probe.YOUT.getFirst();
            probe.doDoubleClickAction(probe.getSheetPosition());
            var dialog = probeDialog();
            try {
                referenceField(dialog).setText("L.1.i, C.1.v, D.1.state, L.1.v");
                inductor.getIDStringDialog().setNewNameCheckedUndoable("L.input");
                assertReference(dialog, probe, "L.input");
                AbstractUndoGenericModel.undoManager.undo();
                assertReference(dialog, probe, "L.1");
                AbstractUndoGenericModel.undoManager.redo();
                assertReference(dialog, probe, "L.input");
                assertSame(output, probe.YOUT.getFirst());
                assertEquals("probeI", output.getLabelObject().getLabelString());
            } finally {
                dialog.dispose();
            }
            return null;
        });
    }

    @Test
    void renamesValidReferencesWhenAnotherSavedReferenceIsInvalid() throws Exception {
        GeckoExternal.openFile(ComponentProbeParityTest.writeProbeBuck(false, "/L.1.i/C.1.v/D.1.invalid")
                .toAbsolutePath().toString());
        onEdt(() -> {
            var probe = (ReglerProbe) block("PROBE.1");
            var inductor = block("L.1");
            var output = probe.YOUT.getFirst();
            inductor.getIDStringDialog().setNewNameCheckedUndoable("L.input");
            assertArrayEquals(new String[]{"L.input.i", "C.1.v", "D.1.invalid"}, probe.getOutputNames());
            assertSame(output, probe.YOUT.getFirst());
            AbstractUndoGenericModel.undoManager.undo();
            assertEquals("L.1", inductor.getStringID());
            assertArrayEquals(new String[]{"L.1.i", "C.1.v", "D.1.invalid"}, probe.getOutputNames());
            return null;
        });
    }

    @Test
    void renameUndoLeavesEarlierUnboundReferencesAndDraftTextAlone() throws Exception {
        GeckoExternal.openFile(ComponentProbeParityTest.writeProbeBuck(false, "/L.1.i/L.input.i/D.1.state")
                .toAbsolutePath().toString());
        onEdt(() -> {
            var probe = (ReglerProbe) block("PROBE.1");
            var inductor = block("L.1");
            probe.doDoubleClickAction(probe.getSheetPosition());
            var dialog = probeDialog();
            try {
                referenceField(dialog).setText("L.1.i, L.input.i, D.1.state,  L.1.v");
                inductor.getIDStringDialog().setNewNameCheckedUndoable("L.input");
                assertArrayEquals(new String[]{"L.input.i", "L.input.i", "D.1.state"}, probe.getOutputNames());
                AbstractUndoGenericModel.undoManager.undo();
                assertArrayEquals(new String[]{"L.1.i", "L.input.i", "D.1.state"}, probe.getOutputNames());
                assertEquals("L.1.i, L.input.i, D.1.state,  L.1.v", referenceField(dialog).getText());
                AbstractUndoGenericModel.undoManager.redo();
                assertEquals("L.input.i, L.input.i, D.1.state,  L.input.v", referenceField(dialog).getText());
            } finally {
                dialog.dispose();
            }
            return null;
        });
    }

    @Test
    void scriptedRenameFollowsTheProbeAndTheDialogCanSaveANameWithAComma() throws Exception {
        GeckoExternal.setComponentName("L.1", "L,coil");
        onEdt(() -> {
            var probe = (ReglerProbe) block("PROBE.1");
            assertArrayEquals(new String[]{"L,coil.i", "C.1.v", "D.1.state"}, probe.getOutputNames());
            probe.doDoubleClickAction(probe.getSheetPosition());
            return null;
        });
        var dialog = awaitDialog(candidate -> candidate.getClass().getSimpleName().equals("ProbeDialog"));
        onEdt(() -> {
            try {
                assertEquals("L\\,coil.i, C.1.v, D.1.state", referenceField(dialog).getText());
                ((DialogCircuitComponent<?>) dialog).jButtonOk.doClick();
                assertFalse(dialog.isShowing());
            } finally {
                dialog.dispose();
            }
            return null;
        });
    }

    @Test
    void slashNamedProbeTargetSurvivesTheDesktopSaveAndBothReaders() throws Exception {
        GeckoExternal.setComponentName("L.1", "L/coil");
        Path saved = Files.createTempDirectory(Path.of("target"), "m7-probe-save").resolve("buck.ipes");
        GeckoExternal.saveFileAs(saved.toAbsolutePath().toString());
        GeckoExternal.openFile(saved.toAbsolutePath().toString());
        var expected = List.of("L/coil.i", "C.1.v", "D.1.state");
        onEdt(() -> {
            var probe = (ReglerProbe) block("PROBE.1");
            assertEquals(expected, List.of(probe.getOutputNames()));
            assertEquals(3, probe.YOUT.size());
            assertNotNull(block("L/coil"));
            return null;
        });

        var model = new CircuitFileParser().parse(saved.toString());
        var probe = model.getControlComponents().stream().filter(component -> component.getType() == ProbeReference.TYPE)
                .findFirst().orElseThrow();
        assertEquals(expected, ProbeReference.parse(probe.getParameterStrings()).stream().map(Object::toString).toList());
    }

    @Test
    void invalidReferencesLeaveNameEnabledFlagAndPortsUnchangedAfterCancel() throws Exception {
        var dialog = onEdt(() -> {
            var probe = block("PROBE.1");
            probe.doDoubleClickAction(probe.getSheetPosition());
            return probeDialog();
        });

        try {
            onEdt(() -> {
                dialog.tfNam.setText("PROBE.edited");
                JCheckBox enabled = children(dialog, JCheckBox.class)
                        .filter(button -> "Enabled".equals(button.getText())).findFirst().orElseThrow();
                enabled.setSelected(false);
                referenceField(dialog).setText("D.1.invalid");
                SwingUtilities.invokeLater(dialog.jButtonOk::doClick);
                return null;
            });
            var error = awaitDialog(candidate -> optionPane(candidate) != null
                    && optionPane(candidate).getMessageType() == JOptionPane.ERROR_MESSAGE);
            onEdt(() -> {
                try {
                    assertTrue(dialog.isShowing());
                    assertOriginalProbe(dialog.element);
                } finally {
                    optionPane(error).setValue(JOptionPane.OK_OPTION);
                }
                return null;
            });
            onEdt(() -> {
                dialog.jButtonCancel.doClick();
                assertFalse(dialog.isShowing());
                assertOriginalProbe(dialog.element);
                return null;
            });
        } finally {
            onEdt(() -> {
                dialog.dispose();
                return null;
            });
        }
    }

    @Test
    void savedProbeBodyEnclosesEveryOutputAtItsSavedRow() throws Exception {
        onEdt(() -> {
            var probe = (ReglerProbe) block("PROBE.1");
            var graphics = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
            try {
                probe.drawBlockRectangle(graphics);
            } finally {
                graphics.dispose();
            }
            int[] bounds = probe.getAussenabmessungenRechteckEckpunkte();
            for (int port = 0; port < 3; port++) {
                var terminal = probe.YOUT.get(port).getPosition();
                assertEquals(probe.getSheetPosition().x + 2, terminal.x);
                assertEquals(probe.getSheetPosition().y - port, terminal.y);
                int y = terminal.y * ReglerProbe.dpix;
                assertTrue(bounds[1] < y && y < bounds[3], "output " + port + " must align with the body");
            }
            return null;
        });
    }

    @Test
    void standardAndDedicatedParameterDialogsExposeHelpAndF1() throws Exception {
        for (var component : onEdt(() -> List.of(block("R.1"), new ReglerMUX(), new ReglerDemux(),
                new ReglerFromEXTERNAL(), new ReglerToEXTERNAL(), new ReglerSaveData(), new ControlGlobalTerminal()))) {
            checkHelpInDialog(component, dialog -> true);
        }
        var resistor = onEdt(() -> {
            MainWindow._se.setAcSettings(new AcDocumentSettings(new AcSweep(10, 10, 1_000), List.of(), ".ac dec 10 10 1000"));
            return block("R.1");
        });
        checkHelpInDialog(resistor, dialog -> dialog.getTitle().equals("AC component R.1") && optionPane(dialog) != null);
    }

    private static void checkHelpInDialog(AbstractBlockInterface component, Predicate<JDialog> condition) throws Exception {
        SwingUtilities.invokeLater(() -> component.doDoubleClickAction(component.getSheetPosition()));
        var dialog = awaitDialog(condition);
        onEdt(() -> {
            try {
                var helpButtons = children(dialog, JButton.class)
                        .filter(button -> I18nKeys.HELP.getTranslation().equals(button.getText())).toList();
                assertEquals(1, helpButtons.size(), dialog.getTitle());
                var root = dialog.getRootPane();
                var key = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                        .get(KeyStroke.getKeyStroke(KeyEvent.VK_F1, 0));
                assertNotNull(key, "Missing F1 in " + dialog.getTitle());
                assertSame(helpButtons.getFirst().getActionListeners()[0], root.getActionMap().get(key));
            } finally {
                var pane = optionPane(dialog);
                if (pane != null) {
                    pane.setValue(JOptionPane.CANCEL_OPTION);
                }
                dialog.dispose();
            }
            return null;
        });
    }

    private static void assertReference(DialogCircuitComponent<?> dialog, ReglerProbe probe, String target) {
        assertArrayEquals(new String[]{target + ".i", "C.1.v", "D.1.state"}, probe.getOutputNames());
        assertEquals(target + ".i, C.1.v, D.1.state, " + target + ".v", referenceField(dialog).getText());
    }

    private static void assertOriginalProbe(AbstractBlockInterface component) {
        var probe = (ReglerProbe) component;
        assertEquals("PROBE.1", probe.getStringID());
        assertEquals(Enabled.ENABLED, probe.isCircuitEnabled());
        assertArrayEquals(new String[]{"L.1.i", "C.1.v", "D.1.state"}, probe.getOutputNames());
        assertEquals(3, probe.YOUT.size());
    }

    private static AbstractBlockInterface block(String name) {
        return MainWindow._se.getBlockInterfaceComponents().stream()
                .filter(component -> name.equals(component.getStringID())).findFirst().orElseThrow();
    }

    private static DialogCircuitComponent<?> probeDialog() {
        return (DialogCircuitComponent<?>) Arrays.stream(Window.getWindows())
                .filter(Window::isShowing).filter(window -> window.getClass().getSimpleName().equals("ProbeDialog"))
                .findFirst().orElseThrow();
    }

    private static JTextField referenceField(JDialog dialog) {
        return children(dialog, JTextField.class).filter(field -> field.getColumns() == 32).findFirst().orElseThrow();
    }

    private static JOptionPane optionPane(JDialog dialog) {
        return children(dialog, JOptionPane.class).findFirst().orElse(null);
    }

    private static <T extends Component> Stream<T> children(Component parent, Class<T> type) {
        Stream<T> self = type.isInstance(parent) ? Stream.of(type.cast(parent)) : Stream.empty();
        if (parent instanceof Container container) {
            return Stream.concat(self, Arrays.stream(container.getComponents()).flatMap(child -> children(child, type)));
        }
        return self;
    }

    private static JDialog awaitDialog(Predicate<JDialog> condition) throws Exception {
        for (int attempt = 0; attempt < 80; attempt++) {
            var dialog = onEdt(() -> Arrays.stream(Window.getWindows()).filter(Window::isShowing)
                    .filter(JDialog.class::isInstance).map(JDialog.class::cast).filter(condition).findFirst().orElse(null));
            if (dialog != null) {
                return dialog;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Expected component dialog did not open.");
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        SwingUtilities.invokeLater(task);
        return task.get(10, TimeUnit.SECONDS);
    }
}
