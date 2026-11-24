# Run CONTROL subsystems on their own sample clocks

ADR-0024 · Claude Opus 5.5 for tinix84 · 2026-11-24 · Status: Proposed · Version 1.1
Audience: maintainers of the CONTROL runtime in `gecko-simulation-core`.

**Decision:** A run request gives a sample period per subcircuit sheet path, and the runtime executes the due blocks of all groups in one dependency order, each with its own controller step and absolute sample clock.

## Context

Issue [#111](https://github.com/tinix84/GeckoCIRCUITS/issues/111) asks for different sample periods for separate CONTROL subsystems, for example a fast current loop and a slow voltage loop.
Issue #61 added one global period. Blocks without a subsystem period must keep it.
CONTROL blocks run in one topological order ([ADR-0003](0003-control-dependency-ordering.md)). Delay histories follow the controller step ([ADR-0005](0005-shared-control-delay-history.md)).
Subcircuit sheets are the existing grouping in saved circuits [C, High]. [ADR-0022](0022-native-asc-hierarchy.md) adds native occurrence sheets with instance names, but a native sheet cannot hold CONTROL blocks and a transient run rejects every native hierarchy document [C, High].
Legacy sheets have no name uniqueness [C, High]. No tracked example circuit uses sheets [M, High].
Gates and signal-controlled sources read producer arrays directly, so they bypass a calculator hold [C, High]. Thermal sources use the same seam ([ADR-0023](0023-core-thermal-network.md)).

## Options

| Option | Pros | Cons | Cost / effort |
|---|---|---|---|
| **Sheet path in the run request, one filtered order** | Existing identity. File meaning unchanged. One order for all rates. | Users must place a loop on its own sheet. | Medium |
| Block-name lists in the request | Works on flat circuits | New grouping concept. Arbitrary rate boundaries. | Medium |
| Period token on the sheet block | Saved with the circuit | The legacy fallback ignores it, so backends differ | Medium |
| One netlist per group | Simple per-group state | A group order may not exist for cross-group signals | High |

## Decision and rationale

`SolverFeatures.controlSubsystemPeriods` maps a sheet path to a period in seconds, finite and not negative. A path joins the segments from the root with `/`. A native sheet contributes its occurrence instance and a legacy sheet its saved name. Segments compare without regard to case.
A block takes the period of its nearest configured enclosing sheet, found by walking sheet identifiers, else the global period. Nested keys are allowed: with `outer` and `outer/inner` configured, a block below `outer/inner` takes the second period.
Each group runs at the first grid step at or after each multiple of its period, on the absolute clock, with the step `max(period, dt)`. A period of zero runs the group at every grid step.
At a grid step the runtime executes the due blocks in the global dependency order, with the ADR-0003 publication and capture phases. Delays and dependency planning use the step of their own group.
A block output changes only when its group runs, so faster consumers read a held value.
A gate publishes at a sample of its own group. A signal-controlled source, electrical or thermal, publishes after a sample of its producer's group. Probes stay live. A gate of a configured subsystem also applies its published value to the switch, not its live input. A gate of the default group keeps the #61 behaviour.
A step change restages only groups whose controller step changes. Adaptive and event sub-steps never run CONTROL ([ADR-0018](0018-adaptive-electrical-step.md)).
The periodic steady-state search needs a period that is a whole multiple of every positive group period, and it compares each group's sample phase, flag and held gate values.
Preparation rejects, at `controlSubsystemPeriods.<path>`, an invalid key or period, an unknown path, a path that matches more than one sheet, two spellings of one sheet, and a sheet without CONTROL blocks. It rejects, at `controlSubsystemPeriods`, a parent sheet chain that is missing or circular, and an instantaneous cycle at the group steps.
An empty map keeps the single-clock path of #61 unchanged, including its checks.

## Consequences

- Fast and slow loops run in one circuit without extra blocks [C, High].
- Default and global-only results stay bit-identical [C, High].
- A rate boundary adds no delay at a common sample instant. Algebraic loops across rates stay rejected [C, High].
- A native hierarchy path resolves in the plan, but a native sheet cannot run CONTROL yet [C, High].
- REST, the desktop and the circuit file do not carry the periods yet [C, High].
- The #61 global period alone still checks cycles at the electrical step only [C, High].
- Upgrades: REST fields for the global and per-subsystem periods, rate-transition delays, a definition-wide key once native sheets can hold CONTROL [E, Low].

## Status

Proposed by Claude Opus 5.5 (design pass for tinix84) on 2026-11-24. No human approval is claimed.

Links: [ControlRuntime](https://github.com/tinix84/GeckoCIRCUITS/blob/main/src/modules/gecko-simulation-core/src/main/java/gecko/core/simulation/ControlRuntime.java) · [ControlSubsystems](https://github.com/tinix84/GeckoCIRCUITS/blob/main/src/modules/gecko-simulation-core/src/main/java/gecko/core/simulation/ControlSubsystems.java) · [ADR-0017](0017-periodic-steady-state.md)
