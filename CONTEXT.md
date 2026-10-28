# GeckoCIRCUITS Simulation

This context defines electrical simulation, control signals, and tracked simulation work.

## Language

**Electrical time step**:
One simulation interval with converged electrical values and one committed history update.
_Avoid_: Solver iteration, CONTROL step

**CONTROL runtime**:
The assembled control calculation chain for one simulation run, including its electrical probes, gate signals, and SCOPE inputs.
_Avoid_: Control service, individual calculator

**SCOPE sample**:
The ordered signal values observed at one simulation time for a configured SCOPE.
_Avoid_: Electrical trace, complete simulation result

**Simulation job**:
A tracked request to run one circuit simulation, from submission to its final result or cancellation.
_Avoid_: Engine, batch

**Batch**:
A group of simulation jobs for one circuit with different parameter values and a shared concurrency limit.
_Avoid_: Simulation job, parameter set
