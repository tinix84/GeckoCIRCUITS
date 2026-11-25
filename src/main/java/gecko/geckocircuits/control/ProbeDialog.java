package gecko.geckocircuits.control;

import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JTextField;
import java.awt.BorderLayout;

final class ProbeDialog extends DialogElementCONTROL<ReglerProbe> {
    private final JTextField references = new JTextField(32);

    ProbeDialog(ReglerProbe element) {
        super(element);
        references.setText(String.join(", ", element.getOutputNames()));
    }

    @Override
    void baueGuiIndividual() {
        JLabel label = new JLabel("References (L.1.i, C.1.v, D.1.state)");
        label.setLabelFor(references);
        jpM.add(label, BorderLayout.NORTH);
        jpM.add(references, BorderLayout.CENTER);
    }

    @Override
    public void processInputIndividual() {
        try {
            element.setReferenceText(references.getText());
        } catch (IllegalArgumentException error) {
            JOptionPane.showMessageDialog(this, error.getMessage(), "Probe", JOptionPane.ERROR_MESSAGE);
            throw error;
        }
    }
}
