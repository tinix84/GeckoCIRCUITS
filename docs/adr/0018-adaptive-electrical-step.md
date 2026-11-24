# Adapt the electrical step between fixed CONTROL grid points

ADR-0018 · Claude Opus 5.5 for tinix84 · 2026-11-24 · Status: Accepted · Version 1.0
Audience: maintainers who implement or review solver features in `gecko-simulation-core`.

**Decision:** We adapt only the electrical sub-step width between fixed grid points, with a Backward Euler (BE) and trapezoidal (TRZ) companion error estimate. CONTROL and recordings stay on the configured grid.

## Context

Issue [#59](https://github.com/tinix84/GeckoCIRCUITS/issues/59) asks for steps controlled by the local truncation error (LTE), with the step controller and clamps of the original request. The feature is off by default.
CONTROL depends on its step:
- An unstaged step change resamples delay histories and plans the dependency order again.
- A delay becomes a feedthrough when the step exceeds its delay time.
- Carriers, derivative (PD) blocks and random blocks change their output with the step [C, High].

The recording capacity comes from the fixed step and the duration.
[ADR-0016](0016-zero-crossing-event-location.md) adds electrical sub-steps. Their rollback covers only the uncommitted trial scratch.
The `buck_simple` acceptance test needs its PWM CONTROL blocks, and it must also work with the sampled CONTROL of #61.

## Options

| Option | Pros | Cons | Cost / effort |
|---|---|---|---|
| CONTROL at each accepted step, variable-length recording | Largest step growth | Delay resampling and feedthrough changes. Carrier and random output changes. Unbounded recording. | High |
| Steps across grid points, with interpolated rows | Speed-up with a fixed output grid | CONTROL inside a step needs a CONTROL rollback | High |
| **Grid-anchored electrical sub-steps** | All CONTROL blocks and #61 work unchanged. Fixed recording. Small rollback. | The step never exceeds the grid. Speed-up only against a fixed minimum step. | Medium |
| Reject circuits with CONTROL blocks | Simplest | Fails the `buck_simple` acceptance test | Low |

## Decision and rationale

CONTROL runs only at grid points, with the grid width or the #61 sample period as its step. Adaptation never calls `prepareTimeStepChange` or `initWithNewDt` [C, High].
An explicit `extend` with a new step keeps the staged contract. If that change cannot be staged, `extend` fails before any state changes.
Recordings keep one row per grid step and their capacity does not change. The ADR-0016 event log stays bounded.
The companion solve uses the other one-step method, from the same committed history and with the converged topology. It does not run CONTROL, draw random numbers, or write traces or recordings.
`|x_TRZ - x_BE|` estimates the BE local error, so the estimate is not divided by 3. For TRZ it is an upper bound while `h |x'''| < 6 |x''|` [C, Medium].
The controller keeps the original form: `h (tol / lte)^(1/2) 0.9`, clamped to `[dt_min, dt_max]` and then to the next grid point.
A rejected trial rolls back with the ADR-0016 set. A forced step at `dt_min` is counted and reported.
The first sub-step after a topology change uses BE.
The setting validation rejects GS.

## Consequences

- Accuracy improves for a given grid.
- The step count falls only against a fixed `dt_min`.
- Each new `h` costs two LU factorizations. No wall-clock gain is claimed for PWM converters [E].
- The RC acceptance test uses TRZ. With BE, `tol` bounds the local error, not the global error.
- Upgrade paths are an order-consistent TRZ estimator, and steps across grid points after a CONTROL snapshot contract exists.

## Status

Proposed by Claude Opus 5.5 (design pass for tinix84) on 2026-11-24. Accepted by root review on 2026-11-24. No human approval is claimed. Depends on [#58](https://github.com/tinix84/GeckoCIRCUITS/issues/58).

Links: [ElectricalTimeStep](../../src/modules/gecko-simulation-core/src/main/java/gecko/core/simulation/ElectricalTimeStep.java) · [ControlNetlist](../../src/modules/gecko-simulation-core/src/main/java/gecko/core/simulation/ControlNetlist.java) · [ControlRuntime](../../src/modules/gecko-simulation-core/src/main/java/gecko/core/simulation/ControlRuntime.java) · [ADR-0005](0005-shared-control-delay-history.md) · [ADR-0011](0011-numerical-reference-compatibility.md)
