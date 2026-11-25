package gecko.geckocircuits.circuit;

import gecko.geckocircuits.allg.AbstractComponentTyp;
import gecko.i18n.GuiFabric;
import gecko.i18n.resources.I18nKeys;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.RootPaneContainer;

public final class ComponentHelp {
    private static final String HELP_ACTION = "componentHelp";
    private static final String COMPONENTS_URL = "https://tinix84.github.io/GeckoCIRCUITS/components/";
    private static final Map<String, String> PAGES = Map.ofEntries(
            Map.entry("R", "resistor"),
            Map.entry("L", "inductor"),
            Map.entry("C", "capacitor"),
            Map.entry("U", "voltage-source"),
            Map.entry("I", "current-source"),
            Map.entry("D", "diode"),
            Map.entry("S", "ideal-switch"),
            Map.entry("MOSFET", "mosfet"),
            Map.entry("IGBT", "igbt"),
            Map.entry("THYR", "thyristor"),
            Map.entry("Trans", "transformer"),
            Map.entry("PI", "pi-controller"));

    private ComponentHelp() {
    }

    static void install(Window window, AbstractBlockInterface block) {
        if (!(window instanceof RootPaneContainer container)
                || container.getRootPane().getActionMap().get(HELP_ACTION) != null) {
            return;
        }

        var content = container.getContentPane();
        var panel = new JPanel(new BorderLayout());
        var footer = new JPanel();
        footer.add(createButton(window, block));
        container.setContentPane(panel);
        panel.add(content, BorderLayout.CENTER);
        panel.add(footer, BorderLayout.SOUTH);
        if (window.isDisplayable()) {
            window.pack();
        }
    }

    static JButton createButton(Window window, AbstractBlockInterface block) {
        var openHelp = new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                open(window, urlFor(block.getTypeEnum()));
            }
        };
        var root = ((RootPaneContainer) window).getRootPane();
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_F1, 0), HELP_ACTION);
        root.getActionMap().put(HELP_ACTION, openHelp);
        var button = GuiFabric.getJButton(I18nKeys.HELP);
        button.addActionListener(openHelp);
        return button;
    }

    private static void open(Window window, URI url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(url);
                return;
            }
        } catch (IOException | SecurityException ex) {
            // Show the address if the desktop cannot open it.
        }
        JOptionPane.showMessageDialog(window, "Open this address in a browser:\n" + url,
                I18nKeys.HELP.getTranslation(), JOptionPane.INFORMATION_MESSAGE);
    }

    public static URI urlFor(AbstractComponentTyp type) {
        String slug = PAGES.get(type.getTypeInfo()._fixedIDString);
        return URI.create(slug == null ? COMPONENTS_URL : COMPONENTS_URL + slug + "/");
    }
}
