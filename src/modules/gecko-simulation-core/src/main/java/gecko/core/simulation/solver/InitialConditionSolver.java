/*  This file is part of GeckoCIRCUITS. Copyright (C) ETH Zurich, Gecko-Simulations AG
 *
 *  GeckoCIRCUITS is free software: you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation, either version 3 of the License, or (at your option) any later version.
 *
 *  GeckoCIRCUITS is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR
 *  PURPOSE.  See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  GeckoCIRCUITS.  If not, see <http://www.gnu.org/licenses/>.
 */
package gecko.core.simulation.solver;

import gecko.core.allg.SolverType;
import gecko.core.circuit.circuitcomponents.CircuitTypCore;
import gecko.core.circuit.netlist.INetList;
import gecko.core.circuit.netlist.CircuitNetlist;
import gecko.core.circuit.SourceType;
import java.util.Arrays;

/**
 * Solver for setting initial conditions in circuit simulation.
 *
 * This class extracts the initial condition logic from LKMatrices (lines 1290-1503)
 * to provide a GUI-free, testable initial condition solver for headless operation.
 *
 * Responsibilities:
 * 1. Setting initial node potentials (voltages) from capacitors and sources
 * 2. Setting initial element currents (inductors, current sources)
 * 3. Supporting different initialization modes (dialog vs. continue)
 *
 * Key simplifications for core module:
 * - Uses INetList interface instead of concrete NetListLK
 * - Works with MatrixSolver which provides getter/setter access
 * - Throws exceptions instead of showing GUI dialogs
 * - Seeds all integration histories from either configured states or saved states
 *
 * @author GeckoCIRCUITS Team
 */
public class InitialConditionSolver {

    private final SolverType solverType;

    /** Threshold for treating inductance as zero */
    private static final double FAST_NULL_L = 1e-10;

    /**
     * Constructs an InitialConditionSolver with the specified solver type.
     *
     * @param solverType the numerical integration method
     */
    public InitialConditionSolver(SolverType solverType) {
        if (solverType == null) {
            throw new IllegalArgumentException("Solver type cannot be null");
        }
        this.solverType = solverType;
    }

    /**
     * Restores saved node potentials and currents, then seeds all integration histories.
     * Configured dialog values remain separate from these saved runtime values.
     *
     * @param matrixSolver the matrix solver containing state arrays to initialize
     * @param netlist the circuit netlist interface
     * @param solverType the solver type for coefficient calculations
     * @throws IllegalStateException if initialization fails
     */
    public void setInitialConditions(
        MatrixSolver matrixSolver,
        INetList netlist,
        SolverType solverType
    ) {
        if (matrixSolver == null) {
            throw new IllegalStateException("MatrixSolver cannot be null");
        }
        if (netlist == null) {
            throw new IllegalStateException("INetList cannot be null");
        }

        validateState(matrixSolver, netlist);
        if (solverType != this.solverType) {
            throw new IllegalArgumentException("Initialization solver types must match");
        }
        validateSavedParameters(netlist);

        // Initialize all potential arrays to zero
        initializePotentials(matrixSolver);

        // Initialize all current arrays to zero
        initializeCurrents(matrixSolver, netlist);

        // Restore saved potentials from netlist parameters (for CONTINUE mode)
        restorePotentialsFromNetlist(matrixSolver, netlist);

        // Restore saved currents from netlist parameters
        restoreCurrentsFromNetlist(matrixSolver, netlist);
        double[] potentials = matrixSolver.getPALT();
        double reference = potentials[0];
        for (int node = 0; node <= netlist.getNodeMax(); node++) {
            potentials[node] -= reference;
        }
        seedHistories(matrixSolver, potentials, matrixSolver.getIALT());
    }

    /**
     * Initializes configured dynamic states. Zero-start circuits retain their existing startup convention.
     * A nonzero state is held fixed while the surrounding instantaneous resistive/source network is solved.
     */
    public void setDialogInitialConditions(MatrixSolver matrixSolver, INetList netlist) {
        validateState(matrixSolver, netlist);
        boolean hasInitialState = false;
        for (int i = 0; i < netlist.getElementCount(); i++) {
            if (isDynamic(netlist.getType(i))) {
                double[] parameters = netlist.getParameter(i);
                double value = parameters.length > 1 ? parameters[1] : 0;
                if (!Double.isFinite(value)) {
                    throw new IllegalArgumentException("Nonfinite initial state for element " + i);
                }
                hasInitialState |= value != 0;
            }
        }
        if (!hasInitialState) {
            seedHistories(matrixSolver, new double[matrixSolver.getMatrixSize()],
                    new double[netlist.getElementCount()]);
            return;
        }

        int count = netlist.getElementCount();
        CircuitTypCore[] types = new CircuitTypCore[count];
        int[] x = new int[count], y = new int[count], sources = new int[count];
        double[][] parameters = new double[count][];
        int sourceCount = 0;
        for (int i = 0; i < count; i++) {
            CircuitTypCore original = netlist.getType(i);
            types[i] = original;
            x[i] = netlist.getNodeX(i);
            y[i] = netlist.getNodeY(i);
            double[] values = netlist.getParameter(i);
            parameters[i] = Arrays.copyOf(values, Math.max(13, values.length));
            if (isDynamic(original)) {
                double initial = parameters[i][1];
                types[i] = original == CircuitTypCore.LK_C || original == CircuitTypCore.TH_CTH
                        ? CircuitTypCore.LK_U : CircuitTypCore.LK_I;
                parameters[i] = new double[13];
                parameters[i][0] = SourceType.QUELLE_DC_NEW;
                parameters[i][1] = initial;
            }
            boolean voltageSource = types[i] == CircuitTypCore.LK_U || types[i] == CircuitTypCore.TH_TEMP
                    || types[i] == CircuitTypCore.REL_MMF;
            sources[i] = voltageSource ? ++sourceCount : -1;
        }
        CircuitNetlist initialNetwork = new CircuitNetlist();
        initialNetwork.initNetlist(types, x, y, sources, parameters,
                netlist.getNodeMax(), sourceCount, count);
        MatrixSolver initialSolver = new MatrixSolver(SolverType.SOLVER_BE);
        initialSolver.initializeMatrices(netlist.getNodeMax(), sourceCount, count);
        initialSolver.buildMatrixA(initialNetwork, 1, 0, false);
        initialSolver.buildVectorB(initialNetwork, 1, 0, false);
        initialSolver.solve();
        double[] currents = new double[count];
        new ComponentCurrentCalculator().calculateComponentCurrents(
                initialSolver, initialNetwork, currents, 0, 1, 0, true);
        double[] potentials = new double[matrixSolver.getMatrixSize()];
        System.arraycopy(initialSolver.getP(), 0, potentials, 0, netlist.getNodeMax() + 1);
        for (int i = 0; i < count; i++) {
            if (!Double.isFinite(currents[i])) {
                throw new IllegalStateException("Nonfinite initial current for element " + i);
            }
            int source = netlist.getVoltageSourceNumber(i);
            if (source > 0) {
                potentials[netlist.getNodeMax() + source] = currents[i];
            }
        }
        seedHistories(matrixSolver, potentials, currents);
    }

    private static boolean isDynamic(CircuitTypCore type) {
        return type == CircuitTypCore.LK_C || type == CircuitTypCore.TH_CTH
                || type == CircuitTypCore.LK_L || type == CircuitTypCore.NONLIN_REL
                || type == CircuitTypCore.LK_LKOP2;
    }

    private void validateState(MatrixSolver matrix, INetList netlist) {
        if (matrix == null || netlist == null || matrix.getP() == null || matrix.getIALT() == null) {
            throw new IllegalStateException("Initialized matrices and a netlist are required");
        }
        if (matrix.getSolverType() != solverType
                || (long) netlist.getNodeMax() + netlist.getVoltageSourceMax() + 1 != matrix.getMatrixSize()
                || netlist.getElementCount() != matrix.getIALT().length) {
            throw new IllegalArgumentException("Initialization dimensions and solver types must match");
        }
        for (int i = 0; i < netlist.getElementCount(); i++) {
            if (netlist.getType(i) == null || netlist.getParameter(i) == null
                    || netlist.getNodeX(i) < 0 || netlist.getNodeX(i) > netlist.getNodeMax()
                    || netlist.getNodeY(i) < 0 || netlist.getNodeY(i) > netlist.getNodeMax()) {
                throw new IllegalArgumentException("Invalid initialization element " + i);
            }
            int source = netlist.getVoltageSourceNumber(i);
            if (source > netlist.getVoltageSourceMax()) {
                throw new IllegalArgumentException("Invalid initialization auxiliary number for element " + i);
            }
        }
    }

    private static void validateSavedParameters(INetList netlist) {
        for (int i = 0; i < netlist.getElementCount(); i++) {
            int[] fields = switch (netlist.getType(i)) {
                case LK_C, TH_CTH -> new int[]{2, 4, 5};
                case REL_MMF, TH_TEMP -> new int[]{8, 9};
                case LK_L, NONLIN_REL, LK_LKOP2 -> new int[]{2};
                case LK_I, TH_FLOW -> new int[]{6};
                default -> new int[0];
            };
            double[] values = netlist.getParameter(i);
            for (int field : fields) {
                if (field >= values.length || !Double.isFinite(values[field])) {
                    throw new IllegalArgumentException("Invalid saved state for element " + i);
                }
            }
        }
    }

    private static void seedHistories(MatrixSolver matrix, double[] potentials, double[] currents) {
        for (double[] target : new double[][]{matrix.getP(), matrix.getPALT(),
                matrix.getPALTALT(), matrix.getPALTALTALT()}) {
            System.arraycopy(potentials, 0, target, 0, potentials.length);
        }
        for (double[] target : new double[][]{matrix.getIALT(), matrix.getIALTALT(), matrix.getIALTALTALT()}) {
            System.arraycopy(currents, 0, target, 0, currents.length);
        }
    }

    /**
     * Initializes all potential arrays to zero.
     *
     * @param matrixSolver the matrix solver
     */
    private void initializePotentials(MatrixSolver matrixSolver) {
        double[] pALT = matrixSolver.getPALT();
        double[] pALTALT = matrixSolver.getPALTALT();
        double[] pALTALTALT = matrixSolver.getPALTALTALT();

        for (int i = 0; i < pALT.length; i++) {
            pALT[i] = 0;
            pALTALT[i] = 0;
            pALTALTALT[i] = 0;
        }
    }

    /**
     * Initializes all current arrays to zero.
     *
     * @param matrixSolver the matrix solver
     * @param netlist the circuit netlist
     */
    private void initializeCurrents(MatrixSolver matrixSolver, INetList netlist) {
        double[] iALT = matrixSolver.getIALT();
        double[] iALTALT = matrixSolver.getIALTALT();
        double[] iALTALTALT = matrixSolver.getIALTALTALT();

        for (int i = 0; i < iALT.length; i++) {
            iALT[i] = 0;
            iALTALT[i] = 0;
            iALTALTALT[i] = 0;
        }
    }

    /**
     * Restores potentials from netlist parameters (saved initial conditions).
     *
     * Handles capacitor voltages and other potential sources.
     *
     * @param matrixSolver the matrix solver
     * @param netlist the circuit netlist
     */
    private void restorePotentialsFromNetlist(MatrixSolver matrixSolver, INetList netlist) {
        double[] pALT = matrixSolver.getPALT();

        for (int i = 0; i < netlist.getElementCount(); i++) {
            CircuitTypCore type = netlist.getType(i);
            int nodeX = netlist.getNodeX(i);
            int nodeY = netlist.getNodeY(i);
            double[] params = netlist.getParameter(i);

            if (type == CircuitTypCore.LK_C || type == CircuitTypCore.TH_CTH) {
                // Capacitor: restore initial voltage
                // param[4] = voltage at X node, param[5] = voltage at Y node
                pALT[nodeX] = params[4];
                pALT[nodeY] = params[5];
            } else if (type == CircuitTypCore.REL_MMF || type == CircuitTypCore.TH_TEMP) {
                // MMF/Temperature source: restore potentials
                // param[8] = voltage at X node, param[9] = voltage at Y node
                pALT[nodeX] = params[8];
                pALT[nodeY] = params[9];
            } else if (type == CircuitTypCore.LK_LKOP2) {
                // Coupled inductor: restore source current in potential vector
                // param[2] = source current
                int voltageSourceNumber = netlist.getVoltageSourceNumber(i);
                if (voltageSourceNumber > 0) {
                    pALT[netlist.getNodeMax() + voltageSourceNumber] = params[2];
                }
            }
        }
    }

    /**
     * Restores currents from netlist parameters (saved initial conditions).
     *
     * Handles inductor currents, current sources, and coupled inductors.
     *
     * @param matrixSolver the matrix solver
     * @param netlist the circuit netlist
     */
    private void restoreCurrentsFromNetlist(MatrixSolver matrixSolver, INetList netlist) {
        double[] iALT = matrixSolver.getIALT();
        double[] iALTALT = matrixSolver.getIALTALT();

        for (int i = 0; i < netlist.getElementCount(); i++) {
            CircuitTypCore type = netlist.getType(i);
            double[] params = netlist.getParameter(i);

            if (type == CircuitTypCore.LK_C || type == CircuitTypCore.TH_CTH) {
                // Capacitor: restore initial current
                // param[2] = initial current
                iALT[i] = params[2];
            } else if (type == CircuitTypCore.LK_L || type == CircuitTypCore.NONLIN_REL) {
                // Inductor: restore initial current
                // param[1] = initial current (from dialog), param[2] = saved current
                iALT[i] = params[2];
                iALTALT[i] = iALT[i];
            } else if (type == CircuitTypCore.LK_LKOP2) {
                // Coupled inductor: restore initial current
                iALT[i] = params[2];
                iALTALT[i] = iALT[i];
            } else if (type == CircuitTypCore.LK_I || type == CircuitTypCore.TH_FLOW) {
                // Current source: restore initial current
                // param[6] = saved current value
                iALT[i] = params[6];
            }
        }
    }

    /**
     * Calculates the inductance coefficient for matrix stamping.
     *
     * Extracted from LKMatrices.getAWForInductance (lines 1504-1527).
     * Calculates the coefficient that relates inductance to matrix elements
     * for different numerical integration methods.
     *
     * @param inductance the inductance value
     * @param timeStep the simulation time step (dt)
     * @return the calculated coefficient aW for matrix stamping
     */
    public double getAWForInductance(double inductance, double timeStep) {
        if (!Double.isFinite(inductance) || inductance < 0
                || !Double.isFinite(timeStep) || timeStep <= 0) {
            throw new IllegalArgumentException("Inductance must be finite and non-negative; time step must be finite and positive");
        }
        double aW = 0;

        if (inductance < FAST_NULL_L) {
            // Inductance is near zero - use threshold value
            if (solverType == SolverType.SOLVER_BE) {
                aW = timeStep / FAST_NULL_L;
            } else if (solverType == SolverType.SOLVER_TRZ) {
                aW = 0.5 * timeStep / FAST_NULL_L;
            } else if (solverType == SolverType.SOLVER_GS) {
                aW = 2.0 / 3.0 * (timeStep / FAST_NULL_L);
            }
        } else {
            // Normal inductance value
            if (solverType == SolverType.SOLVER_BE) {
                aW = timeStep / inductance;
            } else if (solverType == SolverType.SOLVER_TRZ) {
                aW = 0.5 * timeStep / inductance;
            } else if (solverType == SolverType.SOLVER_GS) {
                aW = 2.0 / 3.0 * (timeStep / inductance);
            }
        }

        if (!Double.isFinite(aW)) {
            throw new IllegalArgumentException("Inductance coefficient is not finite");
        }
        return aW;
    }
}
