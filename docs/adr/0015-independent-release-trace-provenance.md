# Preserve independent release provenance for trace parity

ADR-0015 · Codex · 2026-11-23 · Version 1.0
Audience: maintainers of numerical compatibility tests.

## Decision

We use identified v2.02 release captures as reference traces and keep current legacy baselines separate.

## Context

Trace parity needs evidence from the release whose behavior it preserves.
Agreement with summary measurements does not establish agreement at every committed step.
Development captures establish a development baseline, even when their engine descends from the release.
The domain terms belong in [CONTEXT.md](../../CONTEXT.md).
The acceptance requirements belong in [issue #62](https://github.com/tinix84/GeckoCIRCUITS/issues/62) and [issue #63](https://github.com/tinix84/GeckoCIRCUITS/issues/63).

## Options

| Option | Pros | Cons | Cost / effort |
|---|---|---|---|
| Instrument pinned release source | Exposes committed internal values and consumed commands | Requires evidence that observation preserves behavior | Capture patch, build records, and stock-run witnesses |
| Observe the distributed release binary | Preserves the distributed executable | Internal state needs external observation tools | Runtime observation and signal mapping |
| Capture the current Legacy backend | Uses development capture facilities | Does not establish direct release provenance | Baseline generation and version records |

## Decision and rationale

Observation-only instrumentation of pinned v2.02 source is accepted.
The capture identifies its source revision, observation patch, build environment, and artifact hashes.
The patch preserves numerical calculations, execution order, and saved settings.
Stock and instrumented runs must produce matching exported signals as an instrumentation check.
This check covers configured SCOPE observables at their exported precision.
Review of the observation patch also checks that unexposed numerical operations remain unchanged.
The artifact is identified as an instrumented source capture, rather than a capture from the distributed binary.

Signal identity preserves physical meaning across engines with different matrix orders.
A Gate command and a Conduction state remain separate quantities because a device can conduct after its command ends.
Comparison tolerances reflect signal semantics and reference-capture evidence.
They are established before Core-versus-reference comparison to preserve the reference's independence.
Repeated fresh-process captures must establish stable release behavior before reference acceptance.
Unexplained variation requires investigation rather than an increased allowance for Core differences.

## Consequences

Current legacy baselines remain useful for development comparisons.
They cannot substitute for v2.02 reference traces.
Capture validation, physical signal mapping, and provenance become part of reference acceptance.
Observed release compatibility does not independently establish physical correctness.

## Status

Accepted by Riccardo Tinivella on 2026-11-23 during the issue #62 design interview.
