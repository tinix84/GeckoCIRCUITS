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

import java.util.*;

/**
 * Holds the probe-to-node mappings, gate-to-switch mappings, and SCOPE signal info
 * produced by {@link ControlCalculatorFactory}.
 *
 * <p>Used by {@link DomainCoupler} to transfer data between the LK and CONTROL domains
 * and by {@link ScopeDataExtractor} to read SCOPE input signals.</p>
 */
public class ControlBlockMapping {

    /** VOLT probe: calculator index → {nodeA, nodeB} MNA node indices */
    private final List<VoltProbeMapping> voltProbeMappings = new ArrayList<>();

    /** AMP probe: calculator index → component name */
    private final List<AmpProbeMapping> ampProbeMappings = new ArrayList<>();

    /** GATE: calculator index → switch component name */
    private final List<GateMapping> gateMappings = new ArrayList<>();

    /** SCOPE: calculator + input signal names */
    private ScopeInfo scopeInfo;

    // --- VOLT probe ---

    public void addVoltProbe(AbstractControlCalculatable calculator, int nodeA, int nodeB, String name) {
        voltProbeMappings.add(new VoltProbeMapping(calculator, nodeA, nodeB, name));
    }

    public List<VoltProbeMapping> getVoltProbeMappings() {
        return Collections.unmodifiableList(voltProbeMappings);
    }

    // --- AMP probe ---

    public void addAmpProbe(AbstractControlCalculatable calculator, String componentName, String probeName) {
        ampProbeMappings.add(new AmpProbeMapping(calculator, componentName, probeName));
    }

    public List<AmpProbeMapping> getAmpProbeMappings() {
        return Collections.unmodifiableList(ampProbeMappings);
    }

    // --- GATE ---

    public void addGateMapping(AbstractControlCalculatable calculator, String switchName) {
        gateMappings.add(new GateMapping(calculator, switchName));
    }

    public List<GateMapping> getGateMappings() {
        return Collections.unmodifiableList(gateMappings);
    }

    // --- SCOPE ---

    public void setScopeInfo(AbstractControlCalculatable scopeCalculator, String[] signalNames) {
        this.scopeInfo = new ScopeInfo(scopeCalculator, signalNames);
    }

    public ScopeInfo getScopeInfo() {
        return scopeInfo;
    }

    // --- Record-like inner classes ---

    public static class VoltProbeMapping {
        private final AbstractControlCalculatable calculator;
        private final int nodeA;
        private final int nodeB;
        private final String name;

        public VoltProbeMapping(AbstractControlCalculatable calculator, int nodeA, int nodeB, String name) {
            this.calculator = calculator;
            this.nodeA = nodeA;
            this.nodeB = nodeB;
            this.name = name;
        }

        public AbstractControlCalculatable getCalculator() { return calculator; }
        public int getNodeA() { return nodeA; }
        public int getNodeB() { return nodeB; }
        public String getName() { return name; }
    }

    public static class AmpProbeMapping {
        private final AbstractControlCalculatable calculator;
        private final String componentName;
        private final String probeName;

        public AmpProbeMapping(AbstractControlCalculatable calculator, String componentName, String probeName) {
            this.calculator = calculator;
            this.componentName = componentName;
            this.probeName = probeName;
        }

        public AbstractControlCalculatable getCalculator() { return calculator; }
        public String getComponentName() { return componentName; }
        public String getProbeName() { return probeName; }
    }

    public static class GateMapping {
        private final AbstractControlCalculatable calculator;
        private final String switchName;

        public GateMapping(AbstractControlCalculatable calculator, String switchName) {
            this.calculator = calculator;
            this.switchName = switchName;
        }

        public AbstractControlCalculatable getCalculator() { return calculator; }
        public String getSwitchName() { return switchName; }
    }

    public static class ScopeInfo {
        private final AbstractControlCalculatable calculator;
        private final String[] signalNames;

        public ScopeInfo(AbstractControlCalculatable calculator, String[] signalNames) {
            this.calculator = calculator;
            this.signalNames = signalNames;
        }

        public AbstractControlCalculatable getCalculator() { return calculator; }
        public String[] getSignalNames() { return signalNames; }
        public int getSignalCount() { return signalNames.length; }
    }
}
