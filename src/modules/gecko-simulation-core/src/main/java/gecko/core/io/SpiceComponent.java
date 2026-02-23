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

/**
 * Represents a single element from a SPICE netlist.
 *
 * <p>Instances are created by {@link SpiceNetlistParser} and consumed by
 * {@link IpesFileWriter} to generate GeckoCIRCUITS .ipes files.</p>
 */
public final class SpiceComponent {

    /**
     * SPICE element type.
     */
    public enum Type {
        /** Resistor (R) */
        R,
        /** Inductor (L) */
        L,
        /** Capacitor (C) */
        C,
        /** Independent voltage source (V) */
        V,
        /** Independent current source (I) */
        I,
        /** Diode (D) */
        D
    }

    /**
     * Source excitation mode for V and I elements.
     */
    public enum SourceMode {
        /** DC (constant) source */
        DC,
        /** AC (sinusoidal) source */
        AC
    }

    private final Type type;
    private final String name;
    private final String positiveNode;
    private final String negativeNode;
    /** Primary value: resistance (Ω), inductance (H), capacitance (F), voltage (V), current (A), or diode Vf (V). */
    private final double value;
    private final SourceMode sourceMode;
    /** DC value for V/I sources; equals {@link #value} for passives. */
    private final double dcValue;
    /** AC peak amplitude for V/I sources; 0 for passives. */
    private final double acAmplitude;

    /**
     * Creates a new SPICE component.
     *
     * @param type         element type
     * @param name         element name (e.g., "R1", "L2")
     * @param positiveNode positive (or anode) node name
     * @param negativeNode negative (or cathode) node name
     * @param value        primary value
     * @param sourceMode   DC or AC (only meaningful for V and I)
     * @param dcValue      DC component of V/I source
     * @param acAmplitude  AC peak amplitude (0 for DC-only or passive elements)
     */
    public SpiceComponent(Type type, String name, String positiveNode, String negativeNode,
                          double value, SourceMode sourceMode, double dcValue, double acAmplitude) {
        this.type = type;
        this.name = name;
        this.positiveNode = positiveNode;
        this.negativeNode = negativeNode;
        this.value = value;
        this.sourceMode = sourceMode;
        this.dcValue = dcValue;
        this.acAmplitude = acAmplitude;
    }

    public Type getType() { return type; }
    public String getName() { return name; }
    public String getPositiveNode() { return positiveNode; }
    public String getNegativeNode() { return negativeNode; }
    public double getValue() { return value; }
    public SourceMode getSourceMode() { return sourceMode; }
    public double getDcValue() { return dcValue; }
    public double getAcAmplitude() { return acAmplitude; }

    /**
     * Returns the GeckoCIRCUITS component type integer (as used in the {@code typ} field of
     * {@code <ElementLK>} blocks in .ipes files).
     *
     * <p>Type mapping (from {@code CircuitTyp} enum in the main application):</p>
     * <ul>
     *   <li>1 – LK_R (resistor)</li>
     *   <li>2 – LK_L (inductor)</li>
     *   <li>3 – LK_C (capacitor)</li>
     *   <li>4 – LK_U (voltage source)</li>
     *   <li>5 – LK_I (current source)</li>
     *   <li>6 – LK_D (diode)</li>
     * </ul>
     */
    public int getGeckoTypeId() {
        return switch (type) {
            case R -> 1;
            case L -> 2;
            case C -> 3;
            case V -> 4;
            case I -> 5;
            case D -> 6;
        };
    }

    @Override
    public String toString() {
        return type + " " + name + " " + positiveNode + " " + negativeNode + " " + value;
    }
}
