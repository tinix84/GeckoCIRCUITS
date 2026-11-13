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
package gecko.core.simulation;

import gecko.core.allg.SolverType;
import gecko.core.circuit.circuitcomponents.CircuitTypCore;
import gecko.core.circuit.netlist.CircuitNetlist;
import gecko.core.circuit.netlist.INetList;
import gecko.core.simulation.solver.ComponentCurrentCalculator;
import gecko.core.simulation.solver.MatrixSolver;
import gecko.core.simulation.solver.InitialConditionSolver;

/** Owns one converged electrical step and commits its histories exactly once. */
final class ElectricalTimeStep {

    private final CircuitNetlist netlist;
    private final INetList numericalNetlist;
    private final int[] nodeOrder;
    private final double[] publicPotentials;
    private final MatrixSolver matrixSolver;
    private final ComponentCurrentCalculator componentCurrentCalculator = new ComponentCurrentCalculator();
    private final double[] computedCurrents;
    private final double[][] matrixInputs;
    private double matrixDt = Double.NaN;

    ElectricalTimeStep(CircuitNetlist netlist, SolverType solverType) {
        this.netlist = netlist;
        nodeOrder = netlist.getNumericalNodeOrder();
        numericalNetlist = nodeOrder.length == 0 ? netlist : new NumericalNetlist(netlist, nodeOrder);
        matrixSolver = new MatrixSolver(solverType);
        matrixSolver.initializeMatrices(netlist.getNodeMax(), netlist.getVoltageSourceMax(),
                netlist.getElementCount());
        computedCurrents = new double[netlist.getElementCount()];
        matrixInputs = new double[netlist.getElementCount()][4];
        publicPotentials = new double[matrixSolver.getMatrixSize()];
        new InitialConditionSolver(solverType).setDialogInitialConditions(matrixSolver, numericalNetlist);
    }

    void advance(double dt, double time, HeadlessSimulationEngine.StepTraceListener traceListener) {
        if (!Double.isFinite(dt) || dt <= 0 || !Double.isFinite(time) || time < 0) {
            throw new IllegalArgumentException("Time step must be finite and positive; time must be finite and non-negative");
        }
        if (!numericalNetlist.usesWireReference() || matrixInputsChanged(dt)) {
            matrixSolver.buildMatrixA(numericalNetlist, dt, time, false);
        }
        matrixSolver.buildVectorB(numericalNetlist, dt, time, false);
        matrixSolver.solve();
        doDiodeIteration(numericalNetlist, dt, time);

        // Compute against previous-step histories before committing the new state.
        componentCurrentCalculator.calculateComponentCurrents(
                matrixSolver, numericalNetlist, computedCurrents, 0.0, dt, time, true);
        matrixSolver.shiftHistory();
        double[] currents = matrixSolver.getIALT();
        System.arraycopy(computedCurrents, 0, currents, 0, computedCurrents.length);
        double[] potentials = matrixSolver.getPALT();
        System.arraycopy(potentials, 0, publicPotentials, 0, potentials.length);
        if (nodeOrder.length != 0) {
            double reference = potentials[nodeOrder[0]];
            for (int node = 0; node < nodeOrder.length; node++) publicPotentials[node] = potentials[nodeOrder[node]] - reference;
        }
        netlist.storeResults(publicPotentials, currents);
        rememberMatrixInputs(dt);

        if (traceListener != null) {
            traceListener.onStep(time, publicPotentials, currents, netlist);
        }
    }

    private boolean matrixInputsChanged(double dt) {
        if (dt != matrixDt) return true;
        for (int element = 0; element < matrixInputs.length; element++) {
            double[] parameters = netlist.getParameter(element);
            for (int slot = 0; slot < 4; slot++) {
                if (matrixInputs[element][slot] != matrixInput(parameters, slot)) return true;
            }
        }
        return false;
    }

    private void rememberMatrixInputs(double dt) {
        matrixDt = dt;
        for (int element = 0; element < matrixInputs.length; element++) {
            double[] parameters = netlist.getParameter(element);
            for (int slot = 0; slot < 4; slot++) matrixInputs[element][slot] = matrixInput(parameters, slot);
        }
    }

    private static double matrixInput(double[] parameters, int slot) {
        // Matrix stamps use resistance/inductance/mode, capacitance, source gain, and source limit state.
        int index = switch (slot) { case 0 -> 0; case 1 -> 6; case 2 -> 11; default -> 14; };
        return index < parameters.length ? parameters[index] : 0;
    }

    void copyNodeVoltagesTo(float[] values) {
        double[] nodeVoltages = netlist.getLastNodeVoltagesRef();
        for (int index = 0; index < values.length && index < nodeVoltages.length; index++) {
            values[index] = (float) nodeVoltages[index];
        }
    }

    /**
     * Resolves diode states and controlled source voltage limits before one history commit.
     *
     * <p>After the initial solve, checks each diode/thyristor/IGBT for voltage-based
     * state transitions. If any diode toggles, incrementally patches the A and b
     * matrices and re-solves until convergence (or max 10000 iterations).
     * Controlled voltage sources update their limits in this same retry loop.
     *
     * <p>Voltage-based criteria (matching proven code):
     * <ul>
     *   <li>ON→OFF: voltage below threshold or IGBT gate OFF, while currently ON (rD &lt; 10000)</li>
     *   <li>OFF→ON: {@code V_diode > stoergroesse * Uf} AND currently OFF (rD &gt; 10000)</li>
     * </ul>
     *
     * <p>For thyristors/IGBTs, OFF→ON also requires {@code params[8] > 0.5} (gate signal).
     *
     * @param netlist the circuit netlist
     * @param dt time step
     * @param time current simulation time
     * @return true if a diode or source limit changes during this step
     */
    private boolean doDiodeIteration(INetList netlist, double dt, double time) {
        double stoergroesse = 1.0;
        int iterCount = 0;
        boolean anyChangedOverall = false;
        boolean changed = true;

        double[] p = matrixSolver.getP();
        double[][] a = matrixSolver.getSystemMatrix();
        double[] bVector = matrixSolver.getb();

        while (changed) {
            changed = false;

            for (int i = 0; i < netlist.getElementCount(); i++) {
                CircuitTypCore type = netlist.getType(i);
                if (type != CircuitTypCore.LK_D && type != CircuitTypCore.LK_THYR
                        && type != CircuitTypCore.LK_IGBT) {
                    continue;
                }

                double[] params = netlist.getParameter(i);
                int x = netlist.getNodeX(i);
                int y = netlist.getNodeY(i);
                double diodeVoltage = p[x] - p[y];
                double rD = params[0];
                double uf = params[1]; // Forward voltage — never overwritten
                double rOn = params[2];
                double rOff = params[3];

                // An IGBT also turns off when its gate is removed. A thyristor remains latched.
                boolean gateRemoved = type == CircuitTypCore.LK_IGBT && params[8] < 0.5;
                if ((diodeVoltage < stoergroesse * uf || gateRemoved) && (rD < 10000)) {
                    double aOLD = 1.0 / rD;
                    double bOLD = uf / rD;
                    params[0] = rOff; // Switch to OFF
                    double aNEW = 1.0 / params[0];
                    // Retain diode trace compatibility; controlled devices block without a source.
                    double bNEW = type == CircuitTypCore.LK_D ? uf / params[0] : 0;

                    // Incremental patch A matrix
                    a[x][x] += (-aOLD + aNEW);
                    a[y][y] += (-aOLD + aNEW);
                    a[x][y] += (aOLD - aNEW);
                    a[y][x] += (aOLD - aNEW);
                    // Incremental patch b vector
                    bVector[x] += (-bOLD + bNEW);
                    bVector[y] += (bOLD - bNEW);

                    changed = true;
                }
                // OFF → ON: diode voltage above threshold while currently OFF
                else if ((diodeVoltage > stoergroesse * uf) && (rD > 10000)) {
                    // For thyristors/IGBTs, also require gate signal
                    if ((type == CircuitTypCore.LK_THYR || type == CircuitTypCore.LK_IGBT)
                            && params[8] < 0.5) {
                        continue; // Gate not active — cannot turn ON
                    }

                    double aOLD = 1.0 / rD;
                    double bOLD = type == CircuitTypCore.LK_D ? uf / rD : 0;
                    params[0] = rOn; // Switch to ON
                    double aNEW = 1.0 / params[0];
                    double bNEW = uf / params[0];

                    // Incremental patch A matrix
                    a[x][x] += (-aOLD + aNEW);
                    a[y][y] += (-aOLD + aNEW);
                    a[x][y] += (aOLD - aNEW);
                    a[y][x] += (aOLD - aNEW);
                    // Incremental patch b vector
                    bVector[x] += (-bOLD + bNEW);
                    bVector[y] += (bOLD - bNEW);

                    changed = true;
                }
            }

            changed |= matrixSolver.updateControlledSourceLimits(netlist, time);
            if (changed) {
                anyChangedOverall = true;
                matrixSolver.setMatrixChanged();
                matrixSolver.solve();
                iterCount++;

                if (iterCount > 2) {
                    stoergroesse *= 0.99; // Perturbation damping
                }
                if (iterCount > 10000) {
                    throw new RuntimeException(
                            "Diode iteration did not converge after 10000 iterations at t=" + time);
                }
            }
        }

        return anyChangedOverall;
    }

    private record NumericalNetlist(CircuitNetlist source, int[] order) implements INetList {
        @Override public boolean usesWireReference() { return true; }
        @Override public int getNodeMax() { return source.getNodeMax(); }
        @Override public int getVoltageSourceMax() { return source.getVoltageSourceMax(); }
        @Override public int getElementCount() { return source.getElementCount(); }
        @Override public CircuitTypCore getType(int index) { return source.getType(index); }
        @Override public int getNodeX(int index) { return order[source.getNodeX(index)]; }
        @Override public int getNodeY(int index) { return order[source.getNodeY(index)]; }
        @Override public double[] getParameter(int index) { return source.getParameter(index); }
        @Override public int getVoltageSourceNumber(int index) { return source.getVoltageSourceNumber(index); }
        @Override public double getSimulationTime() { return source.getSimulationTime(); }
        @Override public int getControlElementIndex(int index) { return source.getControlElementIndex(index); }
        @Override public java.util.List<gecko.core.circuit.netlist.MutualCouplingRegistry.Coupling> getAllCouplings() {
            return source.getAllCouplings();
        }
    }

}
