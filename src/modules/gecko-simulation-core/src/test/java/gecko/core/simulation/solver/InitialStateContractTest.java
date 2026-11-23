package gecko.core.simulation.solver;

import gecko.core.allg.SolverType;
import gecko.core.circuit.circuitcomponents.CircuitTypCore;
import gecko.core.circuit.netlist.CircuitNetlist;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.junit.jupiter.api.Assertions.*;

class InitialStateContractTest {
    static CircuitNetlist saved(CircuitTypCore type, int x, int y, double[] values) {
        var netlist = new CircuitNetlist();
        netlist.initNetlist(new CircuitTypCore[]{type}, new int[]{x}, new int[]{y},
                new int[]{-1}, new double[][]{values}, 1, 0, 1);
        return netlist;
    }

    static MatrixSolver matrix(SolverType method) {
        var matrix = new MatrixSolver(method);
        matrix.initializeMatrices(1, 0, 1);
        return matrix;
    }

    @ParameterizedTest
    @EnumSource(SolverType.class)
    void savedGroundReferenceAndEveryHistoryAreRestoredTogether(SolverType method) {
        double[] values = new double[13];
        values[2] = 0.25;
        values[4] = 12;
        values[5] = 7;
        var matrix = matrix(method);
        new InitialConditionSolver(method).setInitialConditions(
                matrix, saved(CircuitTypCore.LK_C, 1, 0, values), method);
        for (double[] history : new double[][]{matrix.getP(), matrix.getPALT(),
                matrix.getPALTALT(), matrix.getPALTALTALT()}) {
            assertArrayEquals(new double[]{0, 5}, history);
        }
        for (double[] history : new double[][]{matrix.getIALT(), matrix.getIALTALT(),
                matrix.getIALTALTALT()}) {
            assertArrayEquals(new double[]{0.25}, history);
        }
    }

    @ParameterizedTest
    @EnumSource(value = CircuitTypCore.class, names = {
            "LK_C", "TH_CTH", "REL_MMF", "TH_TEMP", "LK_L",
            "NONLIN_REL", "LK_LKOP2", "LK_I", "TH_FLOW"})
    void malformedSavedStateIsRejectedBeforeExistingHistoryChanges(CircuitTypCore type) {
        var solver = new InitialConditionSolver(SolverType.SOLVER_BE);
        var matrix = matrix(SolverType.SOLVER_BE);
        for (double[] values : new double[][]{new double[0], new double[13]}) {
            Arrays.fill(values, Double.NaN);
            Arrays.fill(matrix.getPALT(), 23);
            Arrays.fill(matrix.getIALT(), 31);
            assertThrows(IllegalArgumentException.class, () -> solver.setInitialConditions(
                    matrix, saved(type, 1, 0, values), SolverType.SOLVER_BE));
            assertArrayEquals(new double[]{23, 23}, matrix.getPALT());
            assertArrayEquals(new double[]{31}, matrix.getIALT());
        }
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void invalidConfiguredStateCannotSeedHistories(double value) {
        double[] values = new double[13];
        values[1] = value;
        var matrix = matrix(SolverType.SOLVER_BE);
        matrix.getPALT()[1] = 23;
        assertThrows(IllegalArgumentException.class, () -> new InitialConditionSolver(SolverType.SOLVER_BE)
                .setDialogInitialConditions(matrix, saved(CircuitTypCore.LK_C, 1, 0, values)));
        assertEquals(23, matrix.getPALT()[1]);
    }

    @Test
    void initializationRequiresMatchingDimensionsAndMethods() {
        assertThrows(IllegalArgumentException.class, () -> new InitialConditionSolver(null));
        var netlist = saved(CircuitTypCore.LK_R, 1, 0, new double[]{10});
        var solver = new InitialConditionSolver(SolverType.SOLVER_BE);
        assertThrows(IllegalStateException.class, () -> solver.setDialogInitialConditions(null, netlist));
        assertThrows(IllegalStateException.class, () -> solver.setDialogInitialConditions(
                new MatrixSolver(SolverType.SOLVER_BE), netlist));
        assertThrows(IllegalArgumentException.class, () -> solver.setDialogInitialConditions(
                matrix(SolverType.SOLVER_TRZ), netlist));
        var wrongSize = new MatrixSolver(SolverType.SOLVER_BE);
        wrongSize.initializeMatrices(2, 0, 1);
        assertThrows(IllegalArgumentException.class, () -> solver.setDialogInitialConditions(wrongSize, netlist));
        assertThrows(IllegalArgumentException.class, () -> solver.setInitialConditions(
                matrix(SolverType.SOLVER_BE), netlist, SolverType.SOLVER_TRZ));
    }

    @ParameterizedTest
    @CsvSource({"-1,1", "NaN,1", "Infinity,1", "1,0", "1,-1", "1,NaN", "1,Infinity"})
    void inductanceWeightsRejectInvalidPhysicalInputs(double inductance, double dt) {
        for (SolverType method : SolverType.values()) {
            assertThrows(IllegalArgumentException.class,
                    () -> new InitialConditionSolver(method).getAWForInductance(inductance, dt));
        }
    }

    @ParameterizedTest
    @EnumSource(SolverType.class)
    void overflowedInductanceWeightIsRejected(SolverType method) {
        assertThrows(IllegalArgumentException.class,
                () -> new InitialConditionSolver(method).getAWForInductance(0, Double.MAX_VALUE));
    }
    @Test
    void invalidConnectivityAndElementStorageAreRejected() {
        var solver = new InitialConditionSolver(SolverType.SOLVER_BE);
        var matrix = matrix(SolverType.SOLVER_BE);
        assertThrows(IllegalStateException.class, () -> solver.setDialogInitialConditions(matrix, null));
        for (int[] nodes : new int[][]{{-1, 0}, {2, 0}, {1, -1}, {1, 2}}) {
            assertThrows(IllegalArgumentException.class, () -> solver.setDialogInitialConditions(
                    matrix, saved(CircuitTypCore.LK_R, nodes[0], nodes[1], new double[]{10})));
        }
        assertThrows(IllegalArgumentException.class, () -> solver.setDialogInitialConditions(
                matrix, saved(null, 1, 0, new double[]{10})));
        assertThrows(IllegalArgumentException.class, () -> solver.setDialogInitialConditions(
                matrix, saved(CircuitTypCore.LK_R, 1, 0, null)));
        var wrongCount = new MatrixSolver(SolverType.SOLVER_BE);
        wrongCount.initializeMatrices(1, 0, 2);
        assertThrows(IllegalArgumentException.class, () -> solver.setDialogInitialConditions(
                wrongCount, saved(CircuitTypCore.LK_R, 1, 0, new double[]{10})));
        var source = new CircuitNetlist();
        source.initNetlist(new CircuitTypCore[]{CircuitTypCore.LK_U},
                new int[]{1}, new int[]{0}, new int[]{2}, new double[][]{new double[13]}, 1, 1, 1);
        var sourceMatrix = new MatrixSolver(SolverType.SOLVER_BE);
        sourceMatrix.initializeMatrices(1, 1, 1);
        assertThrows(IllegalArgumentException.class, () -> solver.setDialogInitialConditions(sourceMatrix, source));
    }

}
