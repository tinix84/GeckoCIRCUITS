# Find periodic steady state by brute-force period iteration

ADR-0017 · Claude Opus 5.5 for tinix84 · 2026-11-24 · Status: Accepted · Version 1.1
Audience: maintainers who implement or review solver features in `gecko-simulation-core` and the REST API.

**Decision:** We find the periodic steady state (PSS) with a brute-force period search before the main phase, seeded from the DC operating point. The convergence test uses the full periodic state.

## Context

Issue [#53](https://github.com/tinix84/GeckoCIRCUITS/issues/53) asks for PSS for a given period T. It must be a session option and a REST field that report the periods used and a converged flag.

The session warmup phase already passes all histories to the main phase without a reset.
CONTROL carriers advance per step and clamp. Their simulated period is therefore a whole number of steps: 30 µs, not 28.57 µs, for 35 kHz at 1 µs [C, High].

The full state includes solver histories, discrete switch states, and private CONTROL state such as delay buffers and carrier phase.
Opt-in settings reach sessions and REST jobs through the `SolverFeatures` record that `PreparedCircuit` keeps.

## Options

| Option | Pros | Cons | Cost / effort |
|---|---|---|---|
| Brute force, residual on outputs only | Simplest | Misses hidden CONTROL state. Accepts a slow drift. | Low |
| **Brute force, full-state residual with contraction correction** | A real periodic check. Uses only the session step. | Stateful calculators need a read-only state export. The iteration count follows the slowest decay mode. | Medium |
| Shooting with Newton iteration | Few periods for lightly damped circuits | Needs state sensitivities and a CONTROL state restore | High |
| Extrapolation of the period sequence | Faster without sensitivities | Unstable with switching | Medium |

## Decision and rationale

The search runs as a warmup variant with the main step, and it records nothing. The main phase starts at the converged boundary. Absolute time and all state continue into it.

The residual covers the full state:
- the solver histories that the next step reads
- discrete switch and source-limit states, which must match exactly
- CONTROL state through a read-only export on each stateful calculator
- the sample phase of #61

A circuit with a stateful calculator that has no export is rejected. A partial check could report a false convergence [C, High].
`SignalCalculatorRandom` draws from the global `Math.random()` stream. Its hidden generator state cannot be exported, so PSS rejects it. The last sample is not the whole state [C, High].

Each state is normalized inside its own unit class (volt, ampere, CONTROL), with a relative scale and an absolute floor. Volts and amperes are never compared.
The contraction-corrected estimate `D r / (1 - r)` rejects drift that a plain change test accepts [C, High]. Two consecutive periods must pass.

T must be a whole number of steps and of the #61 sample periods. Each absolute-time forcing must repeat in T. A setting issue reports each violation [C, High].
- A sine source with a fixed nonzero frequency must complete one or more whole cycles in T. A fixed frequency of exactly zero is constant, so it repeats in any T.
- An imported table must start at time zero or earlier, and T must be a whole multiple of its duration. A constant table repeats in any T.
- PSS rejects a sine source whose frequency comes from an input, and the matrix converter block. Both compute their phase from absolute time.
- PSS rejects a time block whose output goes to another CONTROL block. A time block that goes only to a scope is valid.

The residual does not contain absolute time. A flat forcing can look periodic for many periods, so these rules apply before the run [C, High].
These setting issues are reported by preparation, so REST rejects the request before it accepts a job [C, High].

If `maxPeriods` completes without convergence, the session ends with `converged = false` and no main phase. The engine maps this to a failed result. A cancellation stops at the next step.

## Consequences

- Users give the simulated period, not a nominal frequency. `buck_simple` needs T = 30 µs at 1 µs [C, High].
- Circuits with counters or incremental carriers that do not repeat in T fail with a residual report [C, Medium].
- Circuits with random sources are rejected before the run [C, High].
- v1 rejects circuits that use time in control logic, sine sources with external modulation, and matrix converter blocks. The setting issue names the block [C, High].
- Main-phase timestamps start after the search. Progress uses `maxPeriods` as its upper bound [C, High].
- REST gets a `periodicSteadyState` request field and a `steadyState` response field. The OpenAPI snapshot is regenerated. Batch requests are a follow-up [C, High].
- Period boundaries stay on the grid, so [ADR-0016](0016-zero-crossing-event-location.md) event location stays compatible [C, Medium].

## Status

Proposed by Claude Opus 5.5 (design pass for tinix84) on 2026-11-24. Accepted by root review on 2026-11-24 under tinix84 task authorization, with the Opus attribution retained. No human approval is claimed.
Amended to Version 1.1 by Claude Opus 5.5 on 2026-11-24 after the root audit found a false convergence with an imported table. Root accepted the amendment on 2026-11-24, with the clarification that a fixed zero-frequency sine is constant.
Depends on [#57](https://github.com/tinix84/GeckoCIRCUITS/issues/57).

Links: [SimulationSession](../../src/modules/gecko-simulation-core/src/main/java/gecko/core/simulation/SimulationSession.java) · [SolverFeatures](../../src/modules/gecko-simulation-core/src/main/java/gecko/core/preparation/SolverFeatures.java) · [SignalCalculatorRectangle](../../src/modules/gecko-simulation-core/src/main/java/gecko/core/control/calculators/SignalCalculatorRectangle.java) · [ADR-0007](0007-local-simulation-session.md)
