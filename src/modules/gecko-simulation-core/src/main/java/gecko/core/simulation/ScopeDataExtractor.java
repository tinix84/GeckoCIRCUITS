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

import gecko.core.control.calculators.AbstractControlCalculatable;

/**
 * Reads signal values from a wired SCOPE calculator's input signals.
 *
 * <p>Once the control chain is wired (via {@link ControlCalculatorFactory}),
 * the SCOPE calculator's {@code _inputSignal[i]} arrays are shared references
 * to the upstream calculators' output arrays. This extractor simply reads those
 * shared values — no copying or routing needed.</p>
 *
 * <p>This mirrors the GUI-side behavior in {@code NetzlisteCONTROL.writeData()},
 * which reads {@code _allSortedCalculatables[idx]._inputSignal[port][0]}.</p>
 */
public class ScopeDataExtractor {

    private final AbstractControlCalculatable scopeCalculator;
    private final String[] signalNames;
    private final int signalCount;

    /**
     * Creates a ScopeDataExtractor for the given SCOPE calculator.
     *
     * @param scopeCalculator the wired SCOPE calculator (NothingToDoCalculator with N inputs)
     * @param signalNames     the signal names corresponding to each input
     */
    public ScopeDataExtractor(AbstractControlCalculatable scopeCalculator, String[] signalNames) {
        this.scopeCalculator = scopeCalculator;
        this.signalNames = signalNames != null ? signalNames.clone() : new String[0];
        this.signalCount = this.signalNames.length;
    }

    /**
     * Creates a ScopeDataExtractor from a {@link ControlBlockMapping.ScopeInfo}.
     *
     * @param scopeInfo the scope info from the factory build result
     * @return a new extractor, or null if scopeInfo is null
     */
    public static ScopeDataExtractor fromScopeInfo(ControlBlockMapping.ScopeInfo scopeInfo) {
        if (scopeInfo == null) {
            return null;
        }
        return new ScopeDataExtractor(scopeInfo.getCalculator(), scopeInfo.getSignalNames());
    }

    /**
     * Extracts the current values of all SCOPE input signals.
     *
     * <p>Each value is read from {@code scopeCalculator._inputSignal[i][0]},
     * which is a shared reference to the upstream calculator's output signal.</p>
     *
     * @return array of float values, one per signal
     */
    public float[] extractCurrentValues() {
        float[] values = new float[signalCount];
        for (int i = 0; i < signalCount; i++) {
            if (i < scopeCalculator._inputSignal.length && scopeCalculator._inputSignal[i] != null) {
                values[i] = (float) scopeCalculator._inputSignal[i][0];
            }
        }
        return values;
    }

    /**
     * Returns the signal names corresponding to each SCOPE input.
     *
     * @return array of signal name strings
     */
    public String[] getSignalNames() {
        return signalNames.clone();
    }

    /**
     * Returns the number of signals in this SCOPE.
     *
     * @return the signal count
     */
    public int getSignalCount() {
        return signalCount;
    }
}
