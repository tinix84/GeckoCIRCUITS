package gecko.core.preparation;

import gecko.core.allg.SolverSettingsCore;
import gecko.core.io.CircuitModel;

/** Fixed configuration with no live solver or CONTROL state. */
public final class PreparedCircuit {
    private final CircuitSnapshot circuit;
    PreparedCircuit(CircuitSnapshot circuit) { this.circuit = circuit; }
    /**
     * Creates a detached model for one execution.
     * Each run must create its own netlist, solver histories, and CONTROL state from this model.
     *
     * @return an independently writable copy of the prepared configuration
     */
    public CircuitModel newRunModel() { return circuit.toModel(); }
    /**
     * Returns a new settings object with the resolved solver, time step, and duration.
     * Changes to this object cannot change the prepared circuit.
     *
     * @return detached settings for one execution
     */
    public SolverSettingsCore solverSettings() {
        var model = circuit.toModel();
        return new SolverSettingsCore(model.getSolverType(), model.getTimeStep(), model.getSimulationDuration());
    }
}
