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

**Circuit snapshot**:
An independent circuit state captured for a Simulation job or as the common starting state for a Batch.
Later changes to the stored circuit do not change it.
_Avoid_: Live circuit, source filename

**Headless readiness**:
The result of checking whether a circuit has valid settings, supported simulation behavior, and the required topology for the headless engine.
Readiness does not guarantee numerical convergence.
_Avoid_: Successful simulation, parse success

**Circuit preparation**:
Resolution of a circuit's effective configuration and its Headless readiness.
_Avoid_: File parsing, metadata validation

**Prepared circuit**:
An execution-ready circuit description with fixed configuration values and solver settings.
Each run has independent electrical and CONTROL state.
_Avoid_: Live engine, stored circuit ID
**Simulation session**:
The independent numerical state, CONTROL state, and recordings retained while one prepared circuit executes.
A session can retain its state after a Simulation segment ends.
_Avoid_: Simulation job, frontend controller

**Simulation segment**:
A requested interval of simulation within a Simulation session.
A later segment can continue from the last committed state.
_Avoid_: Session lifetime, new simulation

**Committed step**:
One completed electrical and CONTROL calculation with its corresponding recorded sample, when logging is enabled.
_Avoid_: Partial solve, display update

**Core backend**:
The headless simulation engine that executes a prepared circuit.
_Avoid_: REST engine

**Legacy backend**:
The desktop simulation engine derived from the original implementation and developed after v2.02.
_Avoid_: v2.02 release

**Golden reference**:
Results recorded from GeckoCIRCUITS v2.02 for a circuit file, which current results must match within the agreed tolerance.
_Avoid_: Expected output, baseline snapshot

**v2.02 reference trace**:
An ordered record of committed steps from an identified GeckoCIRCUITS v2.02 release, with recorded capture provenance.
_Avoid_: Current legacy baseline, unversioned golden trace

**Current legacy baseline**:
An ordered record of committed steps from an identified development revision of the Legacy backend.
_Avoid_: v2.02 reference trace, release reference

**Gate command**:
The binary switching instruction that a controlled device consumes for an electrical time step.
_Avoid_: Conduction state, later CONTROL output

**Conduction state**:
The conducting or blocking state of a switching device or diode after the electrical time step converges.
_Avoid_: Gate command, resistance value

**Signal identity**:
The circuit quantity identified by its component or connected terminals, physical unit, reference, and polarity or current direction.
_Avoid_: Matrix index, array position, unscoped wire label
