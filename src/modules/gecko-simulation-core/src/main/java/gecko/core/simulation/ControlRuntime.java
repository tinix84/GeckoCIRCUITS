/*  This file is part of GeckoCIRCUITS. Copyright (C) ETH Zurich, Gecko-Simulations AG
 *
 *  GeckoCIRCUITS is free software: you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation, either version 3 of the License, or (at your option) any later version.
 *
 *  GeckoCIRCUITS is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR
 *  PURPOSE. See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  GeckoCIRCUITS. If not, see <http://www.gnu.org/licenses/>.
 */
package gecko.core.simulation;

import gecko.core.circuit.netlist.CircuitNetlist;
import gecko.core.control.calculators.AbstractControlCalculatable;
import gecko.core.control.calculators.InitializableAtSimulationStart;
import gecko.core.io.CircuitModel;

/**
 * Owns an assembled CONTROL chain and its electrical coupling phases.
 * Apply pending gates before the electrical solve. Advance CONTROL after
 * electrical results and the step trace have been published.
 */
final class ControlRuntime {

    private final ControlNetlist controlNetlist;
    private final DomainCoupler domainCoupler;
    private final ScopeDataExtractor scopeExtractor;
    private final double dt;

    private ControlRuntime(ControlCalculatorFactory.BuildResult build, double dt) {
        this.dt = dt;
        controlNetlist = build.getControlNetlist();
        domainCoupler = new DomainCoupler();
        domainCoupler.configureFromMapping(build.getMapping());
        scopeExtractor = ScopeDataExtractor.fromScopeInfo(build.getMapping().getScopeInfo());

        for (AbstractControlCalculatable calculator : controlNetlist.getSortedCalculators()) {
            if (calculator instanceof InitializableAtSimulationStart initializable) {
                initializable.initializeAtSimulationStart(dt);
            }
        }
    }

    static ControlRuntime from(CircuitModel model, double dt) {
        ControlCalculatorFactory.BuildResult build = new ControlCalculatorFactory()
                .build(model != null ? model.getControlComponents() : null);
        return new ControlRuntime(build, dt);
    }

    boolean hasScope() {
        return scopeExtractor != null;
    }

    String[] getSignalNames() {
        return hasScope() ? scopeExtractor.getSignalNames() : new String[0];
    }

    void applyPendingGates(CircuitNetlist circuitNetlist) {
        domainCoupler.applyGateSignals(circuitNetlist);
    }

    void advanceAfterElectricalStep(CircuitNetlist circuitNetlist, double time) {
        domainCoupler.transferLkToControlProbes(circuitNetlist);
        if (controlNetlist.hasCalculators()) {
            controlNetlist.executeTimeStep(dt, time);
        }
        domainCoupler.transferGateSignalsToLk(circuitNetlist);
    }

    float[] sampleScope() {
        return hasScope() ? scopeExtractor.extractCurrentValues() : new float[0];
    }
}
