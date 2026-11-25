package gecko.geckocircuits.control;

import gecko.core.circuit.circuitcomponents.CircuitTypCore;
import gecko.core.simulation.ProbeReference;
import gecko.geckocircuits.circuit.circuitcomponents.AbstractCircuitBlockInterface;
import gecko.geckocircuits.circuit.circuitcomponents.Diode;
import java.awt.Point;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;

/** Transfers a component reference inside the desktop editor. */
public final class ProbeTransferHandler extends TransferHandler {
    private static final long serialVersionUID = 1L;
    private static final DataFlavor COMPONENT = new DataFlavor(
            DataFlavor.javaJVMLocalObjectMimeType + ";class=" + AbstractCircuitBlockInterface.class.getName(),
            "GeckoCIRCUITS electrical component");
    private final transient Function<Point, ReglerProbe> targetAt;
    private transient AbstractCircuitBlockInterface dragSource;

    public ProbeTransferHandler(Function<Point, ReglerProbe> targetAt) {
        this.targetAt = targetAt;
    }

    public static List<ProbeReference.Quantity> quantities(AbstractCircuitBlockInterface source) {
        int type = source.getCircuitTyp().getTypeNumber();
        if (!CircuitTypCore.isValidTypeNumber(type)) {
            return List.of();
        }
        var circuitType = CircuitTypCore.fromTypeNumber(type);
        boolean nonlinearDiode = source instanceof Diode diode && diode.useNonlinearChar.getValue();
        return Arrays.stream(ProbeReference.Quantity.values())
                .filter(quantity -> quantity.supports(circuitType))
                .filter(quantity -> quantity != ProbeReference.Quantity.STATE || !nonlinearDiode)
                .toList();
    }

    public void drag(JComponent sheet, MouseEvent event, AbstractCircuitBlockInterface source) {
        dragSource = source;
        try {
            exportAsDrag(sheet, event, COPY);
        } finally {
            dragSource = null;
        }
    }

    @Override
    public int getSourceActions(JComponent component) {
        return COPY;
    }

    @Override
    protected Transferable createTransferable(JComponent component) {
        return dragSource == null ? null : new ComponentTransfer(dragSource);
    }

    @Override
    public boolean canImport(TransferSupport support) {
        return dropTarget(support) != null;
    }

    @Override
    public boolean importData(TransferSupport support) {
        var probe = dropTarget(support);
        if (probe == null) {
            return false;
        }
        var source = source(support);
        if (source == null) {
            return false;
        }
        SwingUtilities.invokeLater(() -> probe.editDroppedComponent(source));
        return true;
    }

    private ReglerProbe dropTarget(TransferSupport support) {
        if (!support.isDrop() || !support.isDataFlavorSupported(COMPONENT) || (support.getSourceDropActions() & COPY) == 0) {
            return null;
        }
        var probe = targetAt.apply(support.getDropLocation().getDropPoint());
        var source = source(support);
        if (probe == null || source == null || !probe.acceptsDrop(source)) {
            return null;
        }
        support.setDropAction(COPY);
        return probe;
    }

    private static AbstractCircuitBlockInterface source(TransferSupport support) {
        try {
            Object value = support.getTransferable().getTransferData(COMPONENT);
            return value instanceof AbstractCircuitBlockInterface source ? source : null;
        } catch (UnsupportedFlavorException | IOException error) {
            return null;
        }
    }

    private record ComponentTransfer(AbstractCircuitBlockInterface source) implements Transferable {
        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{COMPONENT};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return COMPONENT.equals(flavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) {
                throw new UnsupportedFlavorException(flavor);
            }
            return source;
        }
    }
}
