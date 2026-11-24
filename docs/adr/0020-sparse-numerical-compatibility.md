# Order the enabled LU like the dense reference or like EJML

ADR-0020 · Claude Opus 5.5 for tinix84 · 2026-11-24 · Status: Accepted · Version 1.1
Audience: maintainers who implement or review the sparse and dense LU in `gecko-simulation-core`.
Source: EJML 0.46.1, Java 21.

**Decision:** Forced-sparse small wired systems use dense-LU order, enabled dense pinned systems order node 0 last like EJML, and null settings stay legacy.

## Context

Issue [#60](https://github.com/tinix84/GeckoCIRCUITS/issues/60) requires dense and sparse results to agree to 1e-9 relative on RC ladders and the 21 regression circuits.
With the EJML LU, 4 of 21 wired circuits missed that bound on node potentials, by 5.8e-9 to 2.6e-6, while switch states and time grids agreed exactly [M, High].
Wired captures drop row and column 0 ([ADR-0011](0011-numerical-reference-compatibility.md)), so the two LUs differ only in rounding order, and EJML exposes neither the Crout dot products nor the lowest-row pivot tie [C from bytecode, High].
The pinned 300-node DC-seeded ladder missed by 32 % on component currents at its first sub-step, because the dense LU eliminates the 1e15-pinned node 0 first and 299 equal ground products round in one direction [M, High; mechanism E].
A sparse LU in that order fills fully, with 1.0e6 factor nonzeros against 7002 for EJML at 1000 nodes [M, High].

## Options

| Option | Verdict |
|---|---|
| Keep EJML everywhere, with per-circuit bounds or floors | Rejected: weakens the 1e-9 bound |
| Tune EJML, or refine iteratively | Rejected: cannot move sparse toward the dense trace [C] |
| Dense LU labelled sparse | Rejected: forbidden fallback and n×n storage |
| Legacy order in the sparse pinned LU | Rejected: full fill, a dense LU in sparse storage |
| **Legacy-order sparse LU for wired systems below the threshold** | Chosen for the 21 circuits |
| Node 0 last in every dense LU | Rejected: changes default results and goldens (ADR-0011) |
| **Node 0 last in the enabled dense LU of pinned systems** | Chosen for pinned ladders, one more n×n array |

## Decision and rationale

Small wired systems: the sparse LU reads the reduced CSC matrix in natural order. Each column forms `x(i) -= s`, where `s` sums `L(i,k)·x(k)` from 0.0 in ascending `k`. The pivot is the first strictly largest `|x|`, and the solve repeats `LUDecomposition.solve` loop for loop.
Only products with an exact zero factor are skipped, which changes at most the sign of a zero. Java 17+ floating point is strict, without FMA contraction (JEP 306) [C, High].
A prototype solved 3000 seeded wire-reference MNA matrices of order 3 to 66. All 2931 nonsingular solutions were numerically identical to `LUDecomposition` (signed zero ignored) and all 69 singular verdicts agreed. EJML matched in 79 [M, High].
This kernel runs only where the threshold exceeds the node count, so AUTO would choose dense. Larger wired systems and pinned sparse systems keep EJML with node 0 last, and pinned ladders agree to 6.7e-14 or better [M, Medium].

Pinned dense systems: with explicit settings the dense LU factors the same complete system with row and column 0 moved last. The mapping covers potentials and voltage-source branch currents, and `LUDecomposition`, the 1e15 pin and the equations do not change [C, High].
A prototype `MatrixSolver` gave 0.0 A and 8.5e-43 relative on the 300-node ladder for both integration methods, and the null path kept its 28 trace fingerprints [M, High]. Node 0 last is closer to the exact double solution, 1.17e-14 A against 1.31e-12 A of error at 300 nodes [M, High].
Wire-reference ladders and source-limit patches matched the null path and EJML [M, High]. The legacy order stays the compatibility reference, and with ground degree 2 (an MMC arm) both orders agree [M, High].
The evidence comes from prototypes, and the implementation tests and the root proofs decide the final result.

## Consequences

- The null path and the wire-reference dense path do not change. Enabled DENSE and AUTO-dense differ from them only on pinned systems.
- Enabled and null results differ by roundoff on pinned systems with high ground degree, 4.4e-10 at 100 nodes and 4.4e-9 at 1000 [M, High]. SPARSE already had this difference.
- The 300-node comparison uses explicit DENSE. The null reference still differs by 1.321e-9 on component currents, so that legacy comparison stays unfixed.
- The storage estimate counts the extra n×n array. It is an estimate, not heap.
- Wired systems at or above the threshold use EJML and can differ from dense by amplified rounding. The evidence covers the tested corpus and ladders, not every large nonlinear system.
- The order can change singular verdicts of floating circuits. The existing singular tests pass, and other cases are not verified.

## Status

v1.0 proposed by Claude Opus 5.5 (design pass for tinix84) and accepted by root review on 2026-11-24 for implementation. v1.1 adds the enabled pinned dense order, designed by Claude Opus 5.5 and accepted by root engineering review on 2026-11-24. No human approval is claimed. Refines #60.

Links: [LUDecomposition](https://github.com/tinix84/GeckoCIRCUITS/blob/main/src/modules/gecko-simulation-core/src/main/java/gecko/core/math/LUDecomposition.java) · [SparseSystem](https://github.com/tinix84/GeckoCIRCUITS/blob/main/src/modules/gecko-simulation-core/src/main/java/gecko/core/simulation/solver/SparseSystem.java) · [MatrixSolver](https://github.com/tinix84/GeckoCIRCUITS/blob/main/src/modules/gecko-simulation-core/src/main/java/gecko/core/simulation/solver/MatrixSolver.java) · [ADR-0011](0011-numerical-reference-compatibility.md) · [ADR-0014](0014-java-solver-with-optional-native-sparse.md)
