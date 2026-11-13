package gecko.core.simulation;

import gecko.core.allg.SolverType;
import gecko.core.circuit.circuitcomponents.CircuitTypCore;
import gecko.core.circuit.netlist.CircuitNetlist;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NumericalReferenceContractTest {
    @Test
    void internalReferencePreservesPublicVoltagesAndRespondsToResistanceChanges() {
        var circuit = new CircuitNetlist();
        double[] source = new double[13];
        source[0] = 401;
        source[1] = 6;
        circuit.initNetlist(new CircuitTypCore[]{CircuitTypCore.LK_U, CircuitTypCore.LK_R},
                new int[]{1, 1}, new int[]{0, 0}, new int[]{1, -1},
                new double[][]{source, new double[]{10, 0, 0}}, 1, 1, 2);
        circuit.setNumericalNodeOrder(new int[]{1, 0});
        var step = new ElectricalTimeStep(circuit, SolverType.SOLVER_BE);
        step.advance(0.001, 0, null);
        assertArrayEquals(new double[]{0, 6}, java.util.Arrays.copyOf(circuit.getLastNodeVoltagesRef(), 2), 1e-12);
        assertEquals(0.6, circuit.getLastComponentCurrents()[1], 1e-12);

        circuit.getParameter(1)[0] = 20;
        step.advance(0.001, 0.001, null);
        assertEquals(6, circuit.getLastNodeVoltagesRef()[1], 1e-12);
        assertEquals(0.3, circuit.getLastComponentCurrents()[1], 1e-12);
        assertEquals(0, circuit.getNodeY(0));
    }

    @Test
    void changedStepWidthRebuildsTheCapacitorCompanionMatrix() {
        var circuit = new CircuitNetlist();
        double[] source = new double[13], capacitor = new double[13];
        source[0] = 401;
        source[1] = 6;
        capacitor[0] = capacitor[6] = capacitor[7] = 0.3;
        circuit.initNetlist(new CircuitTypCore[]{CircuitTypCore.LK_U, CircuitTypCore.LK_R, CircuitTypCore.LK_C},
                new int[]{1, 1, 2}, new int[]{0, 2, 0}, new int[]{1, -1, -1},
                new double[][]{source, new double[]{10, 0, 0}, capacitor}, 2, 1, 3);
        circuit.setNumericalNodeOrder(new int[]{1, 0, 2});
        var step = new ElectricalTimeStep(circuit, SolverType.SOLVER_BE);
        step.advance(1, 0, null);
        assertEquals(1.5, circuit.getLastNodeVoltagesRef()[2], 1e-12);
        step.advance(2, 1, null);
        assertEquals(3.3, circuit.getLastNodeVoltagesRef()[2], 1e-12);
    }
}
