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
package gecko.core.io;

import java.util.List;

/**
 * Represents a single element from a SPICE or Gecko-dialect {@code .cir} netlist.
 *
 * <p>Instances are created by {@link SpiceNetlistParser} and consumed by
 * {@link IpesFileWriter} to generate GeckoCIRCUITS {@code .ipes} files.</p>
 *
 * <p>The element type is derived from {@link GeckoElementDictionary} which provides
 * the complete mapping from keyword to GeckoCIRCUITS type IDs and terminal layouts.</p>
 *
 * <h3>Standard SPICE elements (2-terminal)</h3>
 * <ul>
 *   <li>R – Resistor</li>
 *   <li>L – Inductor</li>
 *   <li>C – Capacitor</li>
 *   <li>V – Voltage source (DC or AC)</li>
 *   <li>I – Current source (DC or AC)</li>
 *   <li>D – Diode</li>
 * </ul>
 *
 * <h3>Gecko-exclusive elements</h3>
 * <ul>
 *   <li>S  – Ideal switch (gate-controlled, typ 7)</li>
 *   <li>THYR – Thyristor (gate-controlled, typ 8)</li>
 *   <li>K  – Mutual inductance coupling between two Lc inductors (typ 9)</li>
 *   <li>IGBT – Insulated Gate Bipolar Transistor (gate-controlled, typ 10)</li>
 *   <li>Lc – Coupled inductor (typ 12)</li>
 *   <li>MOSFET – MOSFET (gate-controlled, typ 28)</li>
 *   <li>TRANS – Ideal Transformer (4-terminal, typ 23)</li>
 *   <li>OPAMP – Operational Amplifier (4-terminal, typ 22)</li>
 *   <li>BJT – Bipolar Junction Transistor (3-terminal, typ 33)</li>
 *   <li>LISN – Line Impedance Stabilization Network (6-terminal, typ 13)</li>
 * </ul>
 *
 * @see GeckoElementDictionary
 */
public final class SpiceComponent {

    /**
     * Source excitation mode for V and I elements.
     */
    public enum SourceMode {
        /** DC (constant) source */
        DC,
        /** AC (sinusoidal) source */
        AC
    }

    private final GeckoElementDictionary elementType;
    private final String name;

    /** Primary positive node (anode, collector, drain, n1+ for transformer, in+ for opamp). */
    private final String positiveNode;
    /** Primary negative node (cathode, emitter, source, n1- for transformer, in- for opamp). */
    private final String negativeNode;

    /**
     * Additional nodes for multi-terminal elements:
     * <ul>
     *   <li>TRANS: [n2+, n2-]</li>
     *   <li>OPAMP: [out, ref]</li>
     *   <li>BJT:   [base]</li>
     *   <li>K:     positiveNode=Lc1_name, negativeNode=Lc2_name (reused), extraNodes=[]</li>
     *   <li>LISN:  [a2, a3, b1, b2, b3] (positiveNode=a1)</li>
     * </ul>
     */
    private final List<String> extraNodes;

    /**
     * Gate/control signal label for switch-type elements (S, THYR, IGBT, MOSFET).
     * Corresponds to the {@code parameterString[]} gate reference in the {@code .ipes} format.
     * {@code null} for non-switch elements.
     */
    private final String gateSignal;

    /** Primary value: resistance (Ω), inductance (H), capacitance (F), voltage (V),
     *  current (A), diode Vf (V), transformer ratio, or coupling coefficient. */
    private final double value;

    /** Secondary value: vf for semiconductors, initial voltage/current for R/L/C, beta for BJT. */
    private final double value2;

    private final SourceMode sourceMode;
    /** DC component for V/I sources; equals {@link #value} for passives. */
    private final double dcValue;
    /** AC peak amplitude for V/I sources; 0 for passives. */
    private final double acAmplitude;

    /**
     * Full constructor used by factory methods and the parser.
     */
    private SpiceComponent(GeckoElementDictionary elementType, String name,
                           String positiveNode, String negativeNode,
                           List<String> extraNodes, String gateSignal,
                           double value, double value2,
                           SourceMode sourceMode, double dcValue, double acAmplitude) {
        this.elementType = elementType;
        this.name = name;
        this.positiveNode = positiveNode;
        this.negativeNode = negativeNode;
        this.extraNodes = extraNodes != null ? extraNodes : List.of();
        this.gateSignal = gateSignal;
        this.value = value;
        this.value2 = value2;
        this.sourceMode = sourceMode;
        this.dcValue = dcValue;
        this.acAmplitude = acAmplitude;
    }

    // ==================== Factory methods ====================

    /**
     * Creates a two-terminal passive element (R, L, C, Lc, D).
     */
    static SpiceComponent passive(GeckoElementDictionary type, String name,
                                  String n1, String n2, double value) {
        return new SpiceComponent(type, name, n1, n2, List.of(), null,
                value, 0.0, SourceMode.DC, value, 0.0);
    }

    /**
     * Creates a voltage or current source (V, I).
     */
    static SpiceComponent source(GeckoElementDictionary type, String name,
                                 String n1, String n2,
                                 SourceMode mode, double dcValue, double acAmplitude) {
        double primaryValue = (mode == SourceMode.AC) ? acAmplitude : dcValue;
        return new SpiceComponent(type, name, n1, n2, List.of(), null,
                primaryValue, 0.0, mode, dcValue, acAmplitude);
    }

    /**
     * Creates a gate-controlled switch element (S, THYR, IGBT, MOSFET).
     *
     * @param type        element type from dictionary
     * @param name        element name
     * @param n1          positive node (collector/drain/anode)
     * @param n2          negative node (emitter/source/cathode)
     * @param gateSignal  gate/control label (may be null if not connected)
     * @param ron         on-state resistance (Ω)
     * @param vf          forward voltage drop (V); 0 for ideal switch
     */
    static SpiceComponent gatedSwitch(GeckoElementDictionary type, String name,
                                      String n1, String n2,
                                      String gateSignal, double ron, double vf) {
        return new SpiceComponent(type, name, n1, n2, List.of(), gateSignal,
                ron, vf, SourceMode.DC, ron, 0.0);
    }

    /**
     * Creates a mutual inductance coupling element (K).
     *
     * @param name             coupling element name (e.g. "K1")
     * @param lc1Name          name of the first coupled Lc inductor
     * @param lc2Name          name of the second coupled Lc inductor
     * @param couplingCoeff    coupling coefficient k (−1 … +1)
     */
    static SpiceComponent coupling(String name, String lc1Name, String lc2Name,
                                   double couplingCoeff) {
        return new SpiceComponent(GeckoElementDictionary.K, name,
                lc1Name, lc2Name, List.of(), null,
                couplingCoeff, 0.0, SourceMode.DC, couplingCoeff, 0.0);
    }

    /**
     * Creates an ideal transformer (4-terminal).
     *
     * @param name  element name
     * @param n1p   primary positive node
     * @param n1n   primary negative node
     * @param n2p   secondary positive node
     * @param n2n   secondary negative node
     * @param ratio turns ratio (primary:secondary)
     */
    static SpiceComponent transformer(String name, String n1p, String n1n,
                                      String n2p, String n2n, double ratio) {
        return new SpiceComponent(GeckoElementDictionary.TRANS, name,
                n1p, n1n, List.of(n2p, n2n), null,
                ratio, 0.0, SourceMode.DC, ratio, 0.0);
    }

    /**
     * Creates an operational amplifier (4-terminal).
     *
     * @param name  element name
     * @param inp   non-inverting input node
     * @param inn   inverting input node
     * @param out   output node
     * @param ref   reference/ground node
     */
    static SpiceComponent opamp(String name, String inp, String inn,
                                String out, String ref) {
        return new SpiceComponent(GeckoElementDictionary.OPAMP, name,
                inp, inn, List.of(out, ref), null,
                1e6, 0.0, SourceMode.DC, 1e6, 0.0);
    }

    /**
     * Creates a BJT (3-terminal: collector, emitter, base).
     *
     * @param name      element name
     * @param collector collector node
     * @param emitter   emitter node
     * @param base      base node
     * @param isNpn     true for NPN, false for PNP
     * @param beta      forward current gain (h_FE)
     */
    static SpiceComponent bjt(String name, String collector, String emitter,
                              String base, boolean isNpn, double beta) {
        return new SpiceComponent(GeckoElementDictionary.BJT, name,
                collector, emitter, List.of(base), null,
                beta, isNpn ? 1.0 : 0.0, SourceMode.DC, beta, 0.0);
    }

    /**
     * Creates a LISN (Line Impedance Stabilization Network, 6-terminal).
     *
     * @param name  element name
     * @param nodes list of 6 node names: [a1, a2, a3, b1, b2, b3]
     */
    static SpiceComponent lisn(String name, List<String> nodes) {
        if (nodes.size() < 6) {
            throw new IllegalArgumentException("LISN requires 6 nodes");
        }
        return new SpiceComponent(GeckoElementDictionary.LISN, name,
                nodes.get(0), nodes.get(1),
                nodes.subList(2, 6), null,
                0.0, 0.0, SourceMode.DC, 0.0, 0.0);
    }

    // ==================== Accessors ====================

    /** Returns the dictionary entry describing this element type. */
    public GeckoElementDictionary getElementType() { return elementType; }

    /** Returns the element name (e.g., "R1", "IGBT.1"). */
    public String getName() { return name; }

    /** Returns the primary positive node (anode, collector, drain, n1+). */
    public String getPositiveNode() { return positiveNode; }

    /** Returns the primary negative node (cathode, emitter, source, n1-). */
    public String getNegativeNode() { return negativeNode; }

    /**
     * Returns additional nodes for multi-terminal elements.
     * Never null; empty for 2-terminal elements.
     */
    public List<String> getExtraNodes() { return extraNodes; }

    /**
     * Returns the gate/control signal label for switch-type elements (S, THYR, IGBT, MOSFET),
     * or {@code null} if not gate-controlled.
     */
    public String getGateSignal() { return gateSignal; }

    /** Returns the primary value (resistance, inductance, capacitance, amplitude, vf, ratio…). */
    public double getValue() { return value; }

    /** Returns the secondary value (vf for semiconductors, beta for BJT, 0 otherwise). */
    public double getValue2() { return value2; }

    public SourceMode getSourceMode() { return sourceMode; }
    public double getDcValue() { return dcValue; }
    public double getAcAmplitude() { return acAmplitude; }

    /**
     * Returns the GeckoCIRCUITS type ID as used in the {@code typ} field of
     * {@code <ElementLK>} blocks in {@code .ipes} files.
     * Delegates to {@link GeckoElementDictionary#geckoTypeId}.
     */
    public int getGeckoTypeId() { return elementType.geckoTypeId; }

    /**
     * Returns true if this element has a gate/control signal.
     */
    public boolean hasGateSignal() { return gateSignal != null && !gateSignal.isBlank(); }

    @Override
    public String toString() {
        return elementType.keyword + " " + name + " " + positiveNode + " " + negativeNode
                + " value=" + value
                + (gateSignal != null ? " gate=" + gateSignal : "");
    }
}

