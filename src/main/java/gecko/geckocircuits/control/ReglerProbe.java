package gecko.geckocircuits.control;

import gecko.core.simulation.ProbeReference;
import gecko.geckocircuits.circuit.TerminalControlOutput;
import gecko.geckocircuits.control.calculators.AbstractControlCalculatable;
import gecko.geckocircuits.control.calculators.NothingToDoCalculator;
import gecko.i18n.resources.I18nKeys;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class ReglerProbe extends RegelBlock implements VariableTerminalNumber {
    private static final long serialVersionUID = 1L;
    static final ControlTypeInfo TYPE_INFO = new ControlTypeInfo(ReglerProbe.class, "PROBE", I18nKeys.COMPONENT_PROBE);

    public ReglerProbe() {
        parameterString = new String[0];
    }

    public List<ProbeReference> references() {
        return ProbeReference.parse(parameterString);
    }

    void setReferenceText(String text) {
        setReferences(ProbeReference.parseText(text));
    }

    public void renameTarget(String previous, String current) {
        remapTargets(Map.of(previous, current), true);
    }

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
        parameterString = references.stream().map(ProbeReference::toString).toArray(String[]::new);
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
    public int getBlockHeight() {
        return Math.max(dpix, super.getBlockHeight());
    }

    @Override
    public String[] getOutputNames() {
        return parameterString.clone();
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

    @Override
    protected Window openDialogWindow() {
        return new ProbeDialog(this);
    }
}
