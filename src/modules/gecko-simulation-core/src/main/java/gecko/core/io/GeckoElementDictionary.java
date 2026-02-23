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

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Comprehensive dictionary of all GeckoCIRCUITS circuit element types and their
 * representation in the Gecko-dialect {@code .cir} netlist format.
 *
 * <p>The Gecko .cir format is a superset of standard SPICE syntax. It is
 * <b>not compatible with real SPICE simulators</b> but allows GeckoCIRCUITS-exclusive
 * components to be represented in a human-readable, version-control-friendly text format.
 * Compatibility with ngspice-in-gecko could be a future extension.</p>
 *
 * <h2>Format Overview</h2>
 *
 * <h3>Standard SPICE elements (2 terminals)</h3>
 * <pre>
 * R&lt;name&gt; &lt;n+&gt; &lt;n-&gt; &lt;resistance[Ω]&gt;
 * L&lt;name&gt; &lt;n+&gt; &lt;n-&gt; &lt;inductance[H]&gt;
 * C&lt;name&gt; &lt;n+&gt; &lt;n-&gt; &lt;capacitance[F]&gt;
 * V&lt;name&gt; &lt;n+&gt; &lt;n-&gt; DC &lt;voltage[V]&gt;
 * V&lt;name&gt; &lt;n+&gt; &lt;n-&gt; AC &lt;amplitude[V]&gt; [phase[°]]
 * I&lt;name&gt; &lt;n+&gt; &lt;n-&gt; DC &lt;current[A]&gt;
 * I&lt;name&gt; &lt;n+&gt; &lt;n-&gt; AC &lt;amplitude[A]&gt; [phase[°]]
 * D&lt;name&gt; &lt;anode&gt; &lt;cathode&gt; [model | vf[V]]
 * </pre>
 *
 * <h3>Gecko-exclusive elements (power semiconductors)</h3>
 * <pre>
 * S&lt;name&gt;     &lt;n+&gt; &lt;n-&gt; &lt;gate_label&gt; [ron[Ω]]
 * THYR&lt;name&gt;  &lt;anode&gt; &lt;cathode&gt; &lt;gate_label&gt; [vf[V]] [ron[Ω]]
 * IGBT&lt;name&gt;  &lt;collector&gt; &lt;emitter&gt; &lt;gate_label&gt; [vf[V]] [ron[Ω]]
 * MOSFET&lt;name&gt; &lt;drain&gt; &lt;source&gt; &lt;gate_label&gt; [vf[V]] [ron[Ω]]
 * BJT&lt;name&gt;   &lt;collector&gt; &lt;emitter&gt; &lt;base&gt; [NPN|PNP] [beta]
 * </pre>
 *
 * <h3>Gecko-exclusive elements (magnetics &amp; passive networks)</h3>
 * <pre>
 * Lc&lt;name&gt;    &lt;n+&gt; &lt;n-&gt; &lt;inductance[H]&gt;   ; coupable inductor
 * K&lt;name&gt;     &lt;Lc1&gt; &lt;Lc2&gt; &lt;k[-1..1]&gt;        ; mutual inductance coupling
 * TRANS&lt;name&gt; &lt;n1+&gt; &lt;n1-&gt; &lt;n2+&gt; &lt;n2-&gt; [ratio]  ; ideal transformer
 * </pre>
 *
 * <h3>Gecko-exclusive elements (analog)</h3>
 * <pre>
 * OPAMP&lt;name&gt; &lt;in+&gt; &lt;in-&gt; &lt;out&gt; &lt;ref&gt;
 * LISN&lt;name&gt;  &lt;a1&gt; &lt;a2&gt; &lt;a3&gt; &lt;b1&gt; &lt;b2&gt; &lt;b3&gt;  ; Line Impedance Stabilization Network
 * </pre>
 *
 * <h2>SI suffixes for values</h2>
 * <pre>
 * G=1e9, MEG=1e6, K=1e3, M=1e-3, U/µ=1e-6, N=1e-9, P=1e-12, F=1e-15
 * </pre>
 *
 * <h2>Example Gecko .cir file</h2>
 * <pre>
 * * Buck converter – Gecko .cir format
 * V1 in 0 DC 400
 * S1 in sw GATE.1 0.01
 * L1 sw out 300u
 * D1 0 sw
 * C1 out 0 10u
 * R1 out 0 10
 * .tran 1u 1m
 * .end
 * </pre>
 */
public enum GeckoElementDictionary {

    // ==================== Standard SPICE (2-terminal) ====================

    /**
     * Resistor.
     * <br>Gecko type ID: 1 (LK_R)
     * <br>Format: {@code R<name> <n+> <n-> <value[Ω]>}
     * <br>Nodes: n+ (positive), n- (negative)
     */
    R("R", 1, 2, false, List.of("n+", "n-"), List.of("resistance[Ω]")),

    /**
     * Inductor (without coupling support).
     * <br>Gecko type ID: 2 (LK_L)
     * <br>Format: {@code L<name> <n+> <n-> <value[H]>}
     * <br>Nodes: n+ (positive), n- (negative)
     */
    L("L", 2, 2, false, List.of("n+", "n-"), List.of("inductance[H]")),

    /**
     * Capacitor.
     * <br>Gecko type ID: 3 (LK_C)
     * <br>Format: {@code C<name> <n+> <n-> <value[F]>}
     * <br>Nodes: n+ (positive), n- (negative)
     */
    C("C", 3, 2, false, List.of("n+", "n-"), List.of("capacitance[F]")),

    /**
     * Independent voltage source (DC or AC sinusoidal).
     * <br>Gecko type ID: 4 (LK_U)
     * <br>Format DC: {@code V<name> <n+> <n-> DC <voltage[V]>}
     * <br>Format AC: {@code V<name> <n+> <n-> AC <amplitude[V]> [phase[°]]}
     * <br>Nodes: n+ (positive), n- (negative)
     */
    V("V", 4, 2, false, List.of("n+", "n-"), List.of("amplitude[V]", "frequency[Hz]")),

    /**
     * Independent current source (DC or AC sinusoidal).
     * <br>Gecko type ID: 5 (LK_I)
     * <br>Format DC: {@code I<name> <n+> <n-> DC <current[A]>}
     * <br>Format AC: {@code I<name> <n+> <n-> AC <amplitude[A]> [phase[°]]}
     * <br>Nodes: n+ (positive), n- (negative)
     */
    I("I", 5, 2, false, List.of("n+", "n-"), List.of("amplitude[A]", "frequency[Hz]")),

    /**
     * Diode (ideal with on-resistance and forward voltage).
     * <br>Gecko type ID: 6 (LK_D)
     * <br>Format: {@code D<name> <anode> <cathode> [model | vf[V]]}
     * <br>Nodes: anode (positive/XIN), cathode (negative/YOUT)
     * <br>Default vf = 0.6 V, ron = 0.01 Ω, roff = 1e7 Ω
     */
    D("D", 6, 2, false, List.of("anode", "cathode"), List.of("vf[V]", "ron[Ω]")),

    // ==================== Gecko-exclusive (gate-controlled switches) ====================

    /**
     * Ideal switch (gate-signal-controlled).
     * <br>Gecko type ID: 7 (LK_S)
     * <br>Format: {@code S<name> <n+> <n-> <gate_label> [ron[Ω]]}
     * <br>Nodes: n+ (anode/XIN), n- (cathode/YOUT)
     * <br>Gate: control signal label from the control circuit (e.g. GATE.1)
     * <br>Default ron = 0.01 Ω, roff = 1e7 Ω
     * <p><b>Note:</b> Unlike standard SPICE voltage-controlled switch, the gecko S element
     * is driven by a boolean gate signal from the control circuit.</p>
     */
    S("S", 7, 2, true, List.of("n+", "n-"), List.of("ron[Ω]")),

    /**
     * Thyristor (SCR – silicon-controlled rectifier).
     * <br>Gecko type ID: 8 (LK_THYR)
     * <br>Format: {@code THYR<name> <anode> <cathode> <gate_label> [vf[V]] [ron[Ω]]}
     * <br>Nodes: anode (XIN), cathode (YOUT)
     * <br>Gate: control signal label from the control circuit
     * <br>Default vf = 0.6 V, ron = 0.01 Ω, roff = 1e7 Ω
     */
    THYR("THYR", 8, 2, true, List.of("anode", "cathode"), List.of("vf[V]", "ron[Ω]")),

    /**
     * Mutual inductance coupling (between two Lc inductors).
     * <br>Gecko type ID: 9 (LK_M)
     * <br>Format: {@code K<name> <Lc1_name> <Lc2_name> <k[-1..1]>}
     * <br>Nodes: none (coupling defined by referenced inductor names)
     * <p>Both referenced inductors must be {@code Lc} elements (type 12).</p>
     */
    K("K", 9, 0, false, List.of("Lc1_ref", "Lc2_ref"), List.of("k[-1..1]")),

    /**
     * IGBT (Insulated Gate Bipolar Transistor).
     * <br>Gecko type ID: 10 (LK_IGBT)
     * <br>Format: {@code IGBT<name> <collector> <emitter> <gate_label> [vf[V]] [ron[Ω]]}
     * <br>Nodes: collector (XIN), emitter (YOUT)
     * <br>Gate: control signal label from the control circuit
     * <br>Default vf = 0.6 V, ron = 0.01 Ω, roff = 1e7 Ω
     */
    IGBT("IGBT", 10, 2, true, List.of("collector", "emitter"), List.of("vf[V]", "ron[Ω]")),

    /**
     * Coupled inductor (can participate in mutual inductance via K elements).
     * <br>Gecko type ID: 12 (LK_LKOP2)
     * <br>Format: {@code Lc<name> <n+> <n-> <inductance[H]>}
     * <br>Nodes: n+ (positive/XIN), n- (negative/YOUT)
     * <p>Use together with {@link #K} elements to define mutual coupling.</p>
     */
    LC("Lc", 12, 2, false, List.of("n+", "n-"), List.of("inductance[H]")),

    /**
     * MOSFET (idealized metal-oxide field-effect transistor with anti-parallel diode).
     * <br>Gecko type ID: 28 (LK_MOSFET)
     * <br>Format: {@code MOSFET<name> <drain> <source> <gate_label> [vf[V]] [ron[Ω]]}
     * <br>Nodes: drain (XIN), source (YOUT)
     * <br>Gate: control signal label from the control circuit
     * <br>Default vf = 0.6 V, ron = 0.01 Ω, roff = 1e7 Ω
     */
    MOSFET("MOSFET", 28, 2, true, List.of("drain", "source"), List.of("vf[V]", "ron[Ω]")),

    /**
     * Ideal transformer (4-terminal: primary + secondary).
     * <br>Gecko type ID: 23 (LK_TRANS)
     * <br>Format: {@code TRANS<name> <n1+> <n1-> <n2+> <n2-> [ratio]}
     * <br>Nodes: n1+ n1- (primary XIN), n2+ n2- (secondary YOUT)
     * <br>Default ratio = 1.0 (1:1)
     */
    TRANS("TRANS", 23, 4, false,
            List.of("n1+", "n1-", "n2+", "n2-"), List.of("ratio")),

    /**
     * Operational amplifier (idealized 4-terminal).
     * <br>Gecko type ID: 22 (LK_OPV1)
     * <br>Format: {@code OPAMP<name> <in+> <in-> <out> <ref>}
     * <br>Nodes: in+ in- (input XIN), out ref (output YOUT)
     * <br>Default gain = 1e6, Rin = 1e6 Ω, Rout = 1 Ω
     */
    OPAMP("OPAMP", 22, 4, false,
            List.of("in+", "in-", "out", "ref"), List.of("gain", "Rin[Ω]", "Rout[Ω]")),

    /**
     * BJT – Bipolar Junction Transistor (NPN or PNP).
     * <br>Gecko type ID: 33 (LK_BJT)
     * <br>Format: {@code BJT<name> <collector> <emitter> <base> [NPN|PNP] [beta]}
     * <br>Nodes: collector (XIN[0]), emitter (YOUT[0]), base (XIN[1])
     * <br>Default type NPN, beta = 100
     */
    BJT("BJT", 33, 3, false,
            List.of("collector", "emitter", "base"), List.of("NPN|PNP", "beta")),

    /**
     * LISN – Line Impedance Stabilization Network (6-terminal).
     * <br>Gecko type ID: 13 (LK_LISN)
     * <br>Format: {@code LISN<name> <a1> <a2> <a3> <b1> <b2> <b3>}
     * <br>Nodes: a1 a2 a3 (input XIN), b1 b2 b3 + internal nodes (output YOUT)
     */
    LISN("LISN", 13, 6, false,
            List.of("a1", "a2", "a3", "b1", "b2", "b3"), List.of());

    // ==================== Fields ====================

    /** Gecko .cir keyword prefix (case-insensitive match). */
    public final String keyword;

    /** GeckoCIRCUITS type ID (matches {@code CircuitTyp} int values). */
    public final int geckoTypeId;

    /** Number of power-circuit terminals (0 for coupling-only elements like K). */
    public final int terminalCount;

    /**
     * True if this element has a gate/control signal connected via the
     * GeckoCIRCUITS control circuit (IGBT, MOSFET, Thyristor, Switch).
     */
    public final boolean hasGateSignal;

    /** Ordered list of terminal/node names for documentation. */
    public final List<String> nodeNames;

    /** Ordered list of main parameter names for documentation. */
    public final List<String> parameterNames;

    GeckoElementDictionary(String keyword, int geckoTypeId, int terminalCount,
                           boolean hasGateSignal,
                           List<String> nodeNames, List<String> parameterNames) {
        this.keyword = keyword;
        this.geckoTypeId = geckoTypeId;
        this.terminalCount = terminalCount;
        this.hasGateSignal = hasGateSignal;
        this.nodeNames = nodeNames;
        this.parameterNames = parameterNames;
    }

    // ==================== Lookup helpers ====================

    /**
     * Finds the dictionary entry whose keyword is a case-insensitive prefix of the
     * given element name token.
     *
     * <p>Longer keywords are checked first to prevent short prefixes from shadowing
     * longer ones (e.g., {@code "Lc"} checked before {@code "L"}).</p>
     *
     * @param elementName the first token of a netlist element line (e.g. "IGBT1", "R10")
     * @return matching entry, or {@code null} if not recognized
     */
    public static GeckoElementDictionary fromElementName(String elementName) {
        if (elementName == null || elementName.isBlank()) {
            return null;
        }
        String upper = elementName.toUpperCase(Locale.ROOT);

        // Sort by keyword length descending so longer prefixes win
        return Arrays.stream(values())
                .filter(e -> upper.startsWith(e.keyword.toUpperCase(Locale.ROOT)))
                .max(java.util.Comparator.comparingInt(e -> e.keyword.length()))
                .orElse(null);
    }

    /**
     * Returns the entry for the given GeckoCIRCUITS type ID, or {@code null}.
     *
     * @param geckoTypeId the integer type ID from a {@code <ElementLK>} block
     */
    public static GeckoElementDictionary fromGeckoTypeId(int geckoTypeId) {
        for (GeckoElementDictionary entry : values()) {
            if (entry.geckoTypeId == geckoTypeId) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Returns a concise human-readable description of the .cir format for this element.
     */
    public String formatDescription() {
        StringBuilder sb = new StringBuilder(keyword).append("<name>");
        nodeNames.forEach(n -> sb.append(' ').append('<').append(n).append('>'));
        if (hasGateSignal) {
            sb.append(" <gate_label>");
        }
        parameterNames.forEach(p -> sb.append(" [").append(p).append(']'));
        return sb.toString();
    }

    @Override
    public String toString() {
        return keyword + " (geckoType=" + geckoTypeId + ", terminals=" + terminalCount
                + (hasGateSignal ? ", gated" : "") + ")";
    }
}
