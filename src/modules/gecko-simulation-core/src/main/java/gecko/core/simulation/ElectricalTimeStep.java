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
import gecko.core.simulation.solver.ComponentCurrentCalculator;
import gecko.core.simulation.solver.MatrixSolver;

/** Owns one converged electrical step and commits its histories exactly once. */
final class ElectricalTimeStep {

    private final CircuitNetlist netlist;
    private final MatrixSolver matrixSolver;
    private final ComponentCurrentCalculator componentCurrentCalculator = new ComponentCurrentCalculator();
    private final double[] computedCurrents;

    ElectricalTimeStep(CircuitNetlist netlist, SolverType solverType) {
        this.netlist = netlist;
        matrixSolver = new MatrixSolver(solverType);
        matrixSolver.initializeMatrices(netlist.getNodeMax(), netlist.getVoltageSourceMax(),
                netlist.getElementCount());
        computedCurrents = new double[netlist.getElementCount()];
    }

    void advance(double dt, double time, HeadlessSimulationEngine.StepTraceListener traceListener) {
        matrixSolver.buildMatrixA(netlist, dt, time, false);
        matrixSolver.buildVectorB(netlist, dt, time, false);
        matrixSolver.solve();
        doDiodeIteration(netlist, dt, time);

        // Compute against previous-step histories before committing the new state.
        componentCurrentCalculator.calculateComponentCurrents(
                matrixSolver, netlist, computedCurrents, 0.0, dt, time, true);
        matrixSolver.shiftHistory();
        double[] currents = matrixSolver.getIALT();
        System.arraycopy(computedCurrents, 0, currents, 0, computedCurrents.length);
        netlist.storeResults(matrixSolver.getPALT(), currents);

        if (traceListener != null) {
            traceListener.onStep(time, matrixSolver.getPALT(), currents, netlist);
        }
    }

    void copyNodeVoltagesTo(float[] values) {
        double[] nodeVoltages = matrixSolver.getPALT();
        for (int index = 0; index < values.length && index < nodeVoltages.length; index++) {
            values[index] = (float) nodeVoltages[index];
        }
    }

    /**
     * Performs diode iteration loop matching proven LKMatrices.berechneBauteilStroeme().
     *
     * <p>After the initial solve, checks each diode/thyristor/IGBT for voltage-based
     * state transitions. If any diode toggles, incrementally patches the A and b
     * matrices and re-solves until convergence (or max 10000 iterations).
     *
     * <p>Voltage-based criteria (matching proven code):
     * <ul>
     *   <li>ON→OFF: {@code V_diode < stoergroesse * Uf} AND currently ON (rD &lt; 10000)</li>
     *   <li>OFF→ON: {@code V_diode > stoergroesse * Uf} AND currently OFF (rD &gt; 10000)</li>
     * </ul>
     *
     * <p>For thyristors/IGBTs, OFF→ON also requires {@code params[8] > 0.5} (gate signal).
     *
     * @param netlist the circuit netlist
     * @param dt time step
     * @param time current simulation time
     * @return true if any diode state changed during this step
     */
    private boolean doDiodeIteration(CircuitNetlist netlist, double dt, double time) {
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

                // ON → OFF: diode voltage below threshold while currently ON
                if ((diodeVoltage < stoergroesse * uf) && (rD < 10000)) {
                    double aOLD = 1.0 / rD;
                    double bOLD = uf / rD;
                    params[0] = rOff; // Switch to OFF
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
                // OFF → ON: diode voltage above threshold while currently OFF
                else if ((diodeVoltage > stoergroesse * uf) && (rD > 10000)) {
                    // For thyristors/IGBTs, also require gate signal
                    if ((type == CircuitTypCore.LK_THYR || type == CircuitTypCore.LK_IGBT)
                            && params[8] < 0.5) {
                        continue; // Gate not active — cannot turn ON
                    }

                    double aOLD = 1.0 / rD;
                    double bOLD = uf / rD;
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

}
