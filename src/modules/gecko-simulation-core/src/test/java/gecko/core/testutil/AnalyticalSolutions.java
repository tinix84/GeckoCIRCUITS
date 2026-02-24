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

/**
 * Exact analytical solutions for simple circuit transients.
 * Used to verify numerical simulation results against known closed-form answers.
 */
public final class AnalyticalSolutions {

    private AnalyticalSolutions() {
    }

    // ========================================================================
    // Resistor divider (DC steady-state)
    // ========================================================================

    /**
     * Voltage at midpoint of a resistive divider: Vin → R1 → node → R2 → GND.
     *
     * @param vin input voltage [V]
     * @param r1  upper resistor [Ω]
     * @param r2  lower resistor [Ω]
     * @return V_mid = Vin * R2 / (R1 + R2)
     */
    public static double resistorDividerVoltage(double vin, double r1, double r2) {
        return vin * r2 / (r1 + r2);
    }

    /**
     * Current through a resistive divider.
     *
     * @return I = Vin / (R1 + R2)
     */
    public static double resistorDividerCurrent(double vin, double r1, double r2) {
        return vin / (r1 + r2);
    }

    // ========================================================================
    // RL step response: Vin → R → L → GND  (voltage source applied at t=0)
    // ========================================================================

    /**
     * RL series current step response (DC source).
     * <pre>
     *   I(t) = (Vin/R) * (1 - exp(-R*t/L))
     * </pre>
     *
     * @param vin input voltage [V]
     * @param r   resistance [Ω]
     * @param l   inductance [H]
     * @param t   time [s]
     * @return inductor current at time t
     */
    public static double rlStepCurrent(double vin, double r, double l, double t) {
        double tau = l / r;
        return (vin / r) * (1.0 - Math.exp(-t / tau));
    }

    /**
     * RL series steady-state current.
     *
     * @return Iss = Vin / R
     */
    public static double rlSteadyStateCurrent(double vin, double r) {
        return vin / r;
    }

    /**
     * RL time constant.
     *
     * @return τ = L / R
     */
    public static double rlTimeConstant(double r, double l) {
        return l / r;
    }

    // ========================================================================
    // RC step response: Vin → R → node → C → GND  (voltage source applied at t=0)
    // ========================================================================

    /**
     * RC series capacitor voltage step response (DC source).
     * <pre>
     *   V_C(t) = Vin * (1 - exp(-t / (R*C)))
     * </pre>
     *
     * @param vin input voltage [V]
     * @param r   resistance [Ω]
     * @param c   capacitance [F]
     * @param t   time [s]
     * @return capacitor voltage at time t
     */
    public static double rcStepVoltage(double vin, double r, double c, double t) {
        double tau = r * c;
        return vin * (1.0 - Math.exp(-t / tau));
    }

    /**
     * RC series current step response (DC source).
     * <pre>
     *   I(t) = (Vin/R) * exp(-t / (R*C))
     * </pre>
     *
     * @param vin input voltage [V]
     * @param r   resistance [Ω]
     * @param c   capacitance [F]
     * @param t   time [s]
     * @return current at time t
     */
    public static double rcStepCurrent(double vin, double r, double c, double t) {
        double tau = r * c;
        return (vin / r) * Math.exp(-t / tau);
    }

    /**
     * RC time constant.
     *
     * @return τ = R * C
     */
    public static double rcTimeConstant(double r, double c) {
        return r * c;
    }

    // ========================================================================
    // Diode forward/reverse
    // ========================================================================

    /**
     * Forward current through R + ideal-diode (Vin > Uf).
     *
     * @param vin input voltage [V]
     * @param r   series resistance [Ω]
     * @param uf  diode forward voltage [V]
     * @return I = (Vin - Uf) / R  (clamped to 0 if Vin < Uf)
     */
    public static double diodeForwardCurrent(double vin, double r, double uf) {
        if (vin <= uf) {
            return 0.0;
        }
        return (vin - uf) / r;
    }

    // ========================================================================
    // Buck converter (CCM, ideal components)
    // ========================================================================

    /**
     * Ideal buck converter output voltage in CCM.
     *
     * @param vin  input voltage [V]
     * @param duty duty cycle (0..1)
     * @return Vo = D * Vin
     */
    public static double buckOutputVoltage(double vin, double duty) {
        return duty * vin;
    }

    /**
     * Buck converter output (load) current in CCM.
     *
     * @param vin   input voltage [V]
     * @param duty  duty cycle (0..1)
     * @param rLoad load resistance [Ω]
     * @return Io = D * Vin / R_load
     */
    public static double buckOutputCurrent(double vin, double duty, double rLoad) {
        return duty * vin / rLoad;
    }

    /**
     * Buck inductor peak-to-peak ripple current in CCM.
     *
     * @param vin  input voltage [V]
     * @param vout output voltage [V]
     * @param l    inductance [H]
     * @param freq switching frequency [Hz]
     * @param duty duty cycle (0..1)
     * @return ΔI = (Vin - Vout) * D / (L * f)
     */
    public static double buckInductorRipple(double vin, double vout, double l, double freq, double duty) {
        return (vin - vout) * duty / (l * freq);
    }
}
