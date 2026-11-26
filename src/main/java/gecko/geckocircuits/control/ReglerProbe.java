package gecko.geckocircuits.control;

import gecko.core.simulation.ProbeReference;
import gecko.geckocircuits.circuit.Enabled;
import gecko.geckocircuits.circuit.SchematicEditor2;
import gecko.geckocircuits.circuit.TerminalControlOutput;
import gecko.geckocircuits.circuit.circuitcomponents.AbstractCircuitBlockInterface;
import gecko.geckocircuits.control.calculators.AbstractControlCalculatable;
import gecko.geckocircuits.control.calculators.NothingToDoCalculator;
import gecko.i18n.resources.I18nKeys;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.swing.undo.AbstractUndoableEdit;
import javax.swing.undo.UndoableEdit;

public final class ReglerProbe extends RegelBlock implements VariableTerminalNumber {
    private static final long serialVersionUID = 1L;
    static final ControlTypeInfo TYPE_INFO = new ControlTypeInfo(ReglerProbe.class, "PROBE", I18nKeys.COMPONENT_PROBE);

    private transient ProbeDialog dialog;

    public ReglerProbe() {
        parameterString = new String[0];
    }

    public List<ProbeReference> references() {
        return ProbeReference.parse(parameterString);
    }

    void setReferenceText(String text) {
        setReferences(ProbeReference.parseText(text));
    }

    /** Renames matching Probe entries and returns an edit for the entries that changed. */
    public UndoableEdit renameTarget(String previous, String current) {
        var saved = ProbeReferenceRename.capture(getOutputNames(), previous, current);
        var originalDialog = dialog;
        var draft = originalDialog != null && originalDialog.isShowing()
                ? originalDialog.captureRename(previous, current) : saved.escapedForEditor();
        if (saved.isEmpty() && draft.isEmpty()) {
            return null;
        }
        var edit = new AbstractUndoableEdit() {
            void apply(boolean forward) {
                String[] entries = getOutputNames();
                if (saved.apply(entries, forward)) {
                    parameterString = Arrays.stream(entries).map(ProbeReference::encodeSavedEntry)
                            .toArray(String[]::new);
                }
                if (dialog != null && dialog.isShowing()) {
                    dialog.applyRename(Objects.equals(dialog, originalDialog) ? draft : saved.escapedForEditor(), forward);
                }
            }

            @Override
            public void undo() {
                super.undo();
                apply(false);
            }

            @Override
            public void redo() {
                super.redo();
                apply(true);
            }
        };
        edit.apply(true);
        return edit;
    }

    /** Remaps copied targets. The caller selects whether to keep references outside the copy. */
    public void remapTargets(Map<String, String> replacements, boolean keepUncopied) {
        List<ProbeReference> remapped = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        var original = references();
        for (int port = 0; port < original.size(); port++) {
            var reference = original.get(port);
            String copiedName = replacements.get(reference.component());
            if (copiedName != null || keepUncopied) {
                remapped.add(copiedName == null ? reference : new ProbeReference(copiedName, reference.quantity()));
                labels.add(YOUT.get(port).getLabelObject().getLabelString());
            }
        }
        setReferences(remapped);
        for (int port = 0; port < labels.size(); port++) {
            YOUT.get(port).getLabelObject().setLabelWithoutUndo(labels.get(port));
        }
    }

    private void setReferences(List<ProbeReference> references) {
        parameterString = references.stream().map(ProbeReference::toString).map(ProbeReference::encodeSavedEntry)
                .toArray(String[]::new);
        setOutputTerminalNumber(references.size());
    }

    @Override
    public void setInputTerminalNumber(int count) {
        // A Probe receives values directly from the electrical solver.
    }

    @Override
    public void setOutputTerminalNumber(int count) {
        while (YOUT.size() > count) {
            YOUT.pop();
        }
        while (YOUT.size() < count) {
            YOUT.add(new TerminalControlOutput(this, 2, YOUT.size()));
        }
    }

    @Override
    public int getYShift() {
        // Keep the saved terminal positions and grow the body toward the upper outputs.
        return -Math.max(0, YOUT.size() - 1);
    }

    @Override
    public int getBlockHeight() {
        return Math.max(dpix, super.getBlockHeight());
    }

    @Override
    public String[] getOutputNames() {
        return Arrays.stream(parameterString).map(ProbeReference::decodeSavedEntry).toArray(String[]::new);
    }

    @Override
    public I18nKeys[] getOutputDescription() {
        I18nKeys[] descriptions = new I18nKeys[YOUT.size()];
        Arrays.fill(descriptions, I18nKeys.COMPONENT_PROBE);
        return descriptions;
    }

    @Override
    public AbstractControlCalculatable getInternalControlCalculatableForSimulationStart() {
        return new NothingToDoCalculator(0, YOUT.size());
    }

    boolean acceptsDrop(AbstractCircuitBlockInterface source) {
        var editor = SchematicEditor2.Singleton;
        return editor != null && editor.canEditProbeComponent(this) && editor.canEditProbeComponent(source)
                && source.isCircuitEnabled() == Enabled.ENABLED && !ProbeTransferHandler.quantities(source).isEmpty();
    }

    void editDroppedComponent(AbstractCircuitBlockInterface source) {
        if (acceptsDrop(source)) {
            openDialogWindow().setVisible(true);
            dialog.chooseQuantities(source);
        }
    }

    @Override
    protected Window openDialogWindow() {
        if (dialog == null || !dialog.isDisplayable()) {
            dialog = new ProbeDialog(this);
        }
        return dialog;
    }
}
