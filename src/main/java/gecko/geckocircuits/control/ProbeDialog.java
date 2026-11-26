package gecko.geckocircuits.control;

import gecko.core.simulation.ProbeReference;
import gecko.geckocircuits.circuit.SchematicEditor2;
import gecko.geckocircuits.circuit.circuitcomponents.AbstractCircuitBlockInterface;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;

final class ProbeDialog extends DialogElementCONTROL<ReglerProbe> {
    private final JTextField references = new JTextField(32);

    ProbeDialog(ReglerProbe element) {
        super(element, false);
        references.setText(Arrays.stream(element.getOutputNames()).map(ProbeReference::escapeText)
                .collect(Collectors.joining(", ")));
    }

    @Override
    public void setVisible(boolean visible) {
        if (visible && isShowing()) {
            toFront();
        } else {
            super.setVisible(visible);
        }
    }

    @Override
    void baueGuiIndividual() {
        JLabel label = new JLabel("References (L.1.i, C.1.v, D.1.state)");
        label.setLabelFor(references);
        JPanel dropArea = new JPanel();
        dropArea.add(new JLabel("Hold Ctrl and drag an electrical component here."));
        dropArea.setTransferHandler(new ProbeTransferHandler(location -> element));
        jpM.add(label, BorderLayout.NORTH);
        jpM.add(references, BorderLayout.CENTER);
        jpM.add(dropArea, BorderLayout.SOUTH);
    }

    @Override
    protected boolean canApplyChanges() {
        var editor = SchematicEditor2.Singleton;
        if (editor == null || !editor.canEditProbeComponent(element)) {
            JOptionPane.showMessageDialog(this, "The Probe is unavailable for editing.", "Probe", JOptionPane.WARNING_MESSAGE);
            return false;
        }
        try {
            ProbeReference.parseText(references.getText());
            return true;
        } catch (IllegalArgumentException error) {
            JOptionPane.showMessageDialog(this, error.getMessage(), "Probe", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    void chooseQuantities(AbstractCircuitBlockInterface source) {
        var quantities = ProbeTransferHandler.quantities(source);
        JPanel choices = new JPanel();
        choices.setLayout(new BoxLayout(choices, BoxLayout.Y_AXIS));
        var options = quantities.stream().map(quantity -> new JCheckBox(labelFor(quantity))).toList();
        options.forEach(choices::add);
        if (JOptionPane.showConfirmDialog(this, choices, "Probe outputs for " + source.getStringID(),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION
                || !element.acceptsDrop(source)) {
            return;
        }
        var selected = new ArrayList<ProbeReference>();
        for (int index = 0; index < options.size(); index++) {
            if (options.get(index).isSelected()) {
                selected.add(new ProbeReference(source.getStringID(), quantities.get(index)));
            }
        }
        if (selected.isEmpty()) {
            return;
        }
        try {
            references.setText(appendReferences(references.getText(), selected));
        } catch (IllegalArgumentException error) {
            JOptionPane.showMessageDialog(this, error.getMessage(), "Probe", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static String labelFor(ProbeReference.Quantity quantity) {
        return switch (quantity) {
            case CURRENT -> "Current (.i)";
            case VOLTAGE -> "Voltage (.v)";
            case STATE -> "Conduction state (.state)";
        };
    }

    ProbeReferenceRename captureRename(String previous, String current) {
        return ProbeReferenceRename.capture(ProbeReference.splitTextEntries(references.getText()),
                ProbeReference.escapeText(previous), ProbeReference.escapeText(current));
    }

    void applyRename(ProbeReferenceRename rename, boolean forward) {
        String[] entries = ProbeReference.splitTextEntries(references.getText());
        if (rename.apply(entries, forward)) {
            references.setText(String.join(",", entries));
        }
    }

    static String appendReferences(String text, List<ProbeReference> additions) {
        var existing = new HashSet<>(ProbeReference.parseText(text));
        String appended = additions.stream().filter(existing::add).map(ProbeReference::toString)
                .map(ProbeReference::escapeText)
                .collect(Collectors.joining(", "));
        if (appended.isEmpty()) {
            return text;
        }
        return text.isBlank() ? appended : text.stripTrailing() + ", " + appended;
    }

    @Override
    public void processInputIndividual() {
        element.setReferenceText(references.getText());
    }
}
