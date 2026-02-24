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
package gecko.core.testutil;

import gecko.core.circuit.SourceType;
import gecko.core.circuit.circuitcomponents.CircuitTypCore;
import gecko.core.circuit.netlist.CircuitNetlist;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds synthetic circuit netlists programmatically for integration testing.
 *
 * <p>Each factory method creates a fully configured {@link CircuitNetlist} with the
 * correct parameter layout for the MatrixSolver (A-matrix stamping via StamperRegistry
 * and b-vector via MatrixSolver.buildVectorB).
 *
 * <p>Parameter index conventions (per component type):
 * <ul>
 *   <li>LK_R: [0]=R</li>
 *   <li>LK_L: [0]=L</li>
 *   <li>LK_C: [0]=C (for A-matrix stamper), [6]=C, [7]=C (linear), [10]=0 (correction)</li>
 *   <li>LK_U: [0]=sourceType (DC=0), [1]=voltage</li>
 *   <li>LK_D: [0]=rD (initial=rOff), [1]=Uf, [2]=rOn, [3]=rOff</li>
 *   <li>LK_S: [0]=R (initial=rOff), [1]=rOn, [2]=rOff, [8]=gate</li>
 *   <li>LK_MOSFET: [0]=R (initial=rOff), [2]=rOn, [3]=rOff, [8]=gate</li>
 * </ul>
 */
public final class SyntheticCircuitBuilder {

    private static final int MIN_PARAMS = 21;  // enough for all component types

    private SyntheticCircuitBuilder() {
    }

    // ========================================================================
    // C1: Resistor Divider — Vin → R1 → node1 → R2 → GND
    // ========================================================================

    /**
     * Creates a resistive voltage divider.
     *
     * <pre>
     * Topology:
     *   Vin (LK_U, DC) ──[node1]── R1 ──[node2]── R2 ──[GND]
     *   VoltSource: node1(+) to GND(−)
     * </pre>
     *
     * Elements: [0]=Vin, [1]=R1, [2]=R2
     * Nodes: 0=GND, 1=Vin+, 2=midpoint
     *
     * @param vin input voltage [V]
     * @param r1  upper resistor [Ω]
     * @param r2  lower resistor [Ω]
     * @return configured CircuitNetlist
     */
    public static CircuitNetlist resistorDivider(double vin, double r1, double r2) {
        int elementCount = 3;     // V_in, R1, R2
        int maxNode = 2;          // nodes: 0=GND, 1=Vin+, 2=midpoint
        int maxVSrc = 1;          // 1 voltage source

        CircuitTypCore[] types = {CircuitTypCore.LK_U, CircuitTypCore.LK_R, CircuitTypCore.LK_R};
        int[] nodeX = {1, 1, 2};  // Vin+: node1, R1: node1→node2, R2: node2→GND
        int[] nodeY = {0, 2, 0};
        int[] vSrcNr = {1, -1, -1};

        double[][] params = new double[elementCount][];
        // Voltage source: DC, value = vin
        params[0] = makeParams();
        params[0][0] = SourceType.QUELLE_DC_NEW;
        params[0][1] = vin;

        // R1
        params[1] = makeParams();
        params[1][0] = r1;

        // R2
        params[2] = makeParams();
        params[2][0] = r2;

        return buildNetlist(types, nodeX, nodeY, vSrcNr, params, maxNode, maxVSrc,
                new String[]{"V_in", "R1", "R2"});
    }

    // ========================================================================
    // C2: RL Step Response — Vin → R → L → GND
    // ========================================================================

    /**
     * Creates an RL series circuit driven by a DC voltage source.
     *
     * <pre>
     * Topology:
     *   Vin (LK_U, DC) ──[node1]── R ──[node2]── L ──[GND]
     * </pre>
     *
     * Elements: [0]=Vin, [1]=R, [2]=L
     * Nodes: 0=GND, 1=Vin+, 2=R-L junction
     *
     * @param vin input voltage [V]
     * @param r   resistance [Ω]
     * @param l   inductance [H]
     * @return configured CircuitNetlist
     */
    public static CircuitNetlist rlCircuit(double vin, double r, double l) {
        int elementCount = 3;
        int maxNode = 2;
        int maxVSrc = 1;

        CircuitTypCore[] types = {CircuitTypCore.LK_U, CircuitTypCore.LK_R, CircuitTypCore.LK_L};
        int[] nodeX = {1, 1, 2};
        int[] nodeY = {0, 2, 0};
        int[] vSrcNr = {1, -1, -1};

        double[][] params = new double[elementCount][];
        // Voltage source
        params[0] = makeParams();
        params[0][0] = SourceType.QUELLE_DC_NEW;
        params[0][1] = vin;

        // Resistor
        params[1] = makeParams();
        params[1][0] = r;

        // Inductor
        params[2] = makeParams();
        params[2][0] = l;

        return buildNetlist(types, nodeX, nodeY, vSrcNr, params, maxNode, maxVSrc,
                new String[]{"V_in", "R", "L"});
    }

    // ========================================================================
    // C3: RC Step Response — Vin → R → node → C → GND
    // ========================================================================

    /**
     * Creates an RC series circuit driven by a DC voltage source.
     *
     * <pre>
     * Topology:
     *   Vin (LK_U, DC) ──[node1]── R ──[node2]── C ──[GND]
     * </pre>
     *
     * Elements: [0]=Vin, [1]=R, [2]=C
     * Nodes: 0=GND, 1=Vin+, 2=R-C junction (capacitor voltage)
     *
     * @param vin input voltage [V]
     * @param r   resistance [Ω]
     * @param c   capacitance [F]
     * @return configured CircuitNetlist
     */
    public static CircuitNetlist rcCircuit(double vin, double r, double c) {
        int elementCount = 3;
        int maxNode = 2;
        int maxVSrc = 1;

        CircuitTypCore[] types = {CircuitTypCore.LK_U, CircuitTypCore.LK_R, CircuitTypCore.LK_C};
        int[] nodeX = {1, 1, 2};
        int[] nodeY = {0, 2, 0};
        int[] vSrcNr = {1, -1, -1};

        double[][] params = new double[elementCount][];
        // Voltage source
        params[0] = makeParams();
        params[0][0] = SourceType.QUELLE_DC_NEW;
        params[0][1] = vin;

        // Resistor
        params[1] = makeParams();
        params[1][0] = r;

        // Capacitor: params[0]=C (stamper), params[6]=C, params[7]=C (linear), params[10]=0
        params[2] = makeParams();
        params[2][0] = c;
        params[2][6] = c;
        params[2][7] = c;
        params[2][10] = 0.0;

        return buildNetlist(types, nodeX, nodeY, vSrcNr, params, maxNode, maxVSrc,
                new String[]{"V_in", "R", "C"});
    }

    // ========================================================================
    // C4: Resistor + Diode — Vin → R → D → GND
    // ========================================================================

    /**
     * Creates a resistor-diode circuit.
     *
     * <pre>
     * Topology:
     *   Vin (LK_U, DC) ──[node1]── R ──[node2]── D ──[GND]
     *   Diode anode = node2, cathode = GND
     * </pre>
     *
     * Elements: [0]=Vin, [1]=R, [2]=D
     * Nodes: 0=GND, 1=Vin+, 2=R-D junction
     *
     * @param vin  input voltage [V]
     * @param r    series resistance [Ω]
     * @param uf   diode forward voltage [V]
     * @param rOn  diode ON resistance [Ω]
     * @param rOff diode OFF resistance [Ω]
     * @return configured CircuitNetlist
     */
    public static CircuitNetlist diodeCircuit(double vin, double r, double uf, double rOn, double rOff) {
        int elementCount = 3;
        int maxNode = 2;
        int maxVSrc = 1;

        CircuitTypCore[] types = {CircuitTypCore.LK_U, CircuitTypCore.LK_R, CircuitTypCore.LK_D};
        int[] nodeX = {1, 1, 2};
        int[] nodeY = {0, 2, 0};
        int[] vSrcNr = {1, -1, -1};

        double[][] params = new double[elementCount][];
        // Voltage source
        params[0] = makeParams();
        params[0][0] = SourceType.QUELLE_DC_NEW;
        params[0][1] = vin;

        // Resistor
        params[1] = makeParams();
        params[1][0] = r;

        // Diode: [0]=rD (start OFF), [1]=Uf, [2]=rOn, [3]=rOff
        params[2] = makeParams();
        params[2][0] = rOff;   // start in OFF state
        params[2][1] = uf;
        params[2][2] = rOn;
        params[2][3] = rOff;

        return buildNetlist(types, nodeX, nodeY, vSrcNr, params, maxNode, maxVSrc,
                new String[]{"V_in", "R", "D"});
    }

    // ========================================================================
    // C5: Resistor + Switch — Vin → S → R → GND
    // ========================================================================

    /**
     * Creates a switch + resistor circuit. Gate signal must be set externally
     * via params[8] on element index 1 (the switch).
     *
     * <pre>
     * Topology:
     *   Vin (LK_U, DC) ──[node1]── S ──[node2]── R ──[GND]
     * </pre>
     *
     * Elements: [0]=Vin, [1]=S, [2]=R
     * Nodes: 0=GND, 1=Vin+, 2=S-R junction
     *
     * @param vin  input voltage [V]
     * @param r    load resistance [Ω]
     * @param rOn  switch ON resistance [Ω]
     * @param rOff switch OFF resistance [Ω]
     * @return configured CircuitNetlist
     */
    public static CircuitNetlist switchCircuit(double vin, double r, double rOn, double rOff) {
        int elementCount = 3;
        int maxNode = 2;
        int maxVSrc = 1;

        CircuitTypCore[] types = {CircuitTypCore.LK_U, CircuitTypCore.LK_S, CircuitTypCore.LK_R};
        int[] nodeX = {1, 1, 2};
        int[] nodeY = {0, 2, 0};
        int[] vSrcNr = {1, -1, -1};

        double[][] params = new double[elementCount][];
        // Voltage source
        params[0] = makeParams();
        params[0][0] = SourceType.QUELLE_DC_NEW;
        params[0][1] = vin;

        // Switch (LK_S): [0]=current R (start OFF), [1]=rOn, [2]=rOff, [8]=gate
        params[1] = makeParams();
        params[1][0] = rOff;   // start OFF
        params[1][1] = rOn;
        params[1][2] = rOff;
        params[1][8] = 0.0;    // gate off

        // Resistor
        params[2] = makeParams();
        params[2][0] = r;

        return buildNetlist(types, nodeX, nodeY, vSrcNr, params, maxNode, maxVSrc,
                new String[]{"V_in", "S", "R"});
    }

    // ========================================================================
    // C6: Synthetic Buck Converter
    // ========================================================================

    /**
     * Creates a simplified buck converter topology.
     *
     * <pre>
     * Topology:
     *   Vin ──[node1]── S ──[node2]── L ──[node3]── R_load ──[GND]
     *                      D (cathode=node2, anode=GND)
     *                                       C (node3 to GND)
     *
     * Switch S: controlled by gate signal (params[8] on element 1)
     * Diode D: freewheeling diode
     * </pre>
     *
     * Elements: [0]=Vin, [1]=S, [2]=D, [3]=L, [4]=C, [5]=R_load
     * Nodes: 0=GND, 1=Vin+, 2=switch node, 3=output
     *
     * @param vin   input voltage [V]
     * @param l     inductance [H]
     * @param c     output capacitance [F]
     * @param rLoad load resistance [Ω]
     * @param rSwOn switch ON resistance [Ω]
     * @param rSwOff switch OFF resistance [Ω]
     * @param uf    diode forward voltage [V]
     * @param rDOn  diode ON resistance [Ω]
     * @param rDOff diode OFF resistance [Ω]
     * @return configured CircuitNetlist
     */
    public static CircuitNetlist buckConverter(double vin, double l, double c, double rLoad,
                                               double rSwOn, double rSwOff,
                                               double uf, double rDOn, double rDOff) {
        int elementCount = 6;
        int maxNode = 3;          // 0=GND, 1=Vin+, 2=sw node, 3=output
        int maxVSrc = 1;

        CircuitTypCore[] types = {
            CircuitTypCore.LK_U,    // [0] Vin
            CircuitTypCore.LK_S,    // [1] S (high-side switch)
            CircuitTypCore.LK_D,    // [2] D (freewheeling diode)
            CircuitTypCore.LK_L,    // [3] L (inductor)
            CircuitTypCore.LK_C,    // [4] C (output capacitor)
            CircuitTypCore.LK_R     // [5] R_load
        };
        int[] nodeX = {1, 1, 0, 2, 3, 3};  // D: GND(anode)→node2(cathode)
        int[] nodeY = {0, 2, 2, 3, 0, 0};
        int[] vSrcNr = {1, -1, -1, -1, -1, -1};

        double[][] params = new double[elementCount][];

        // [0] Voltage source
        params[0] = makeParams();
        params[0][0] = SourceType.QUELLE_DC_NEW;
        params[0][1] = vin;

        // [1] Switch (LK_S): start OFF
        params[1] = makeParams();
        params[1][0] = rSwOff;
        params[1][1] = rSwOn;
        params[1][2] = rSwOff;
        params[1][8] = 0.0;

        // [2] Diode (LK_D): start OFF
        params[2] = makeParams();
        params[2][0] = rDOff;
        params[2][1] = uf;
        params[2][2] = rDOn;
        params[2][3] = rDOff;

        // [3] Inductor
        params[3] = makeParams();
        params[3][0] = l;

        // [4] Capacitor
        params[4] = makeParams();
        params[4][0] = c;
        params[4][6] = c;
        params[4][7] = c;
        params[4][10] = 0.0;

        // [5] R_load
        params[5] = makeParams();
        params[5][0] = rLoad;

        return buildNetlist(types, nodeX, nodeY, vSrcNr, params, maxNode, maxVSrc,
                new String[]{"V_in", "S", "D", "L", "C", "R_load"});
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private static double[] makeParams() {
        return new double[MIN_PARAMS];
    }

    private static CircuitNetlist buildNetlist(
            CircuitTypCore[] types, int[] nodeX, int[] nodeY,
            int[] vSrcNr, double[][] params,
            int maxNode, int maxVSrc,
            String[] componentNames) {

        CircuitNetlist netlist = new CircuitNetlist();
        netlist.initNetlist(types, nodeX, nodeY, vSrcNr, params, maxNode, maxVSrc, types.length);

        if (componentNames != null) {
            Map<String, Integer> nameMap = new LinkedHashMap<>();
            for (int i = 0; i < componentNames.length; i++) {
                nameMap.put(componentNames[i], i);
            }
            netlist.setComponentNameToIndex(nameMap);
        }

        return netlist;
    }
}
