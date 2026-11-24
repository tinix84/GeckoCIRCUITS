# Repeat the dense LU arithmetic in small forced-sparse systems

ADR-0020 · Claude Opus 5.5 for tinix84 · 2026-11-24 · Status: Accepted · Version 1.0
Audience: maintainers who implement or review the sparse LU in `gecko-simulation-core`.
Source: EJML 0.46.1, Java 21.

**Decision:** Wire-reference systems below `thresholdNodes` use a sparse LU that repeats the dense LU's scalar operations; every other sparse system keeps EJML.

## Context

Issue [#60](https://github.com/tinix84/GeckoCIRCUITS/issues/60) requires dense and forced-sparse results to agree to 1e-9 relative on RC ladders and the 21 regression circuits.
With the EJML LU, 4 of 21 circuits miss that bound on node potentials, by 5.8e-9 to 2.6e-6, while switch states and time grids agree exactly [M, High].
These circuits are wired captures, so both backends drop row and column 0 and the 1e15 pin is not factored ([ADR-0011](0011-numerical-reference-compatibility.md)) [C, High].
Their switch conductances span 1e-7 S to 100 S, and conditioning can amplify a different rounding order (the condition number was not computed) [E, Medium].
EJML `LuUpLooking_DSCC` uses axpy updates and reach-order pivot ties; the dense LU uses Crout dot products and the lowest row, and EJML exposes neither [C from bytecode, High].

## Options

| Option | Pros | Cons | Cost / effort |
|---|---|---|---|
| A. EJML for all, per-circuit bounds | No new code | Misses 1e-9 on 4 circuits [M] | None |
| B. Tune EJML ordering or pivoting | Library code only | No setting controls summation order or ties [C] | Low |
| C. Iterative refinement or compensated sums | Closer to the exact solution | Does not move sparse toward dense [C] | Medium |
| D. Dense LU below threshold, labelled sparse | Exact | Forbidden: dense fallback, n×n storage | Low |
| E. Legacy order in both reference modes | Exact below threshold | Pinned mode puts node 0 first and fills the factors [C] | Medium |
| **F. Legacy order for wire-reference systems below threshold, EJML otherwise** | Targets exactness on the 21 circuits. Sparse storage. | About 150 lines of own LU, O(n² + n·nnz(L)) factor time. | Medium |

## Decision and rationale

Option F targets the unchanged 1e-9 bound by construction: identical A, b and operation order give identical potentials [C, High].

The LU reads the reduced CSC matrix in natural order. In each column, each row position gets `x(i) -= s`, where `s` sums `L(i,k)·x(k)` from 0.0 in ascending `k`. The pivot is the first position with the strictly largest `|x|`, and a swap exchanges L rows and the permutation. The solve repeats `LUDecomposition.solve` loop for loop.
Storage is L by rows, U by columns and one scratch column. No n×n array exists.

Only products with an exact zero factor are skipped, which changes at most the sign of a zero [C, High]. Java 17+ floating point is strict, without FMA contraction (JEP 306).

A prototype solved 3000 seeded wire-reference MNA matrices of order 3 to 66. All 2931 nonsingular solutions were bit-identical to `LUDecomposition` and all 69 singular verdicts agreed; EJML matched bit for bit in 79 [M, High]. Wired RC ladders up to 1000 nodes kept about 6 factor nonzeros per row [M, Medium].

Pinned systems keep EJML with node 0 last (option E fills them); pinned ladders agree to 6.7e-14 or better [M, Medium].
Legacy order runs only where AUTO would choose dense. Larger wired systems stay supported through EJML. The strict trace evidence covers the tested corpus, not every circuit.

## Consequences

- `LinearSolverReport` names the factorization (`LEGACY_ORDER` or `EJML`) of the main, companion and initialization solvers. The backend stays `SPARSE`.
- All 21 circuits use the 1e-9 bound, with no per-circuit exception.
- Wire-reference systems at or above the threshold use EJML and can differ from dense by amplified rounding. #60 does not cover them.
- The default and DENSE paths do not change.

## Status

Proposed by Claude Opus 5.5 (design pass for tinix84) on 2026-11-24. Accepted by root review for implementation. No human approval is claimed. Refines #60.

Links: [LUDecomposition](https://github.com/tinix84/GeckoCIRCUITS/blob/main/src/modules/gecko-simulation-core/src/main/java/gecko/core/math/LUDecomposition.java) · [SparseSystem](https://github.com/tinix84/GeckoCIRCUITS/blob/main/src/modules/gecko-simulation-core/src/main/java/gecko/core/simulation/solver/SparseSystem.java) · [MatrixSolver](https://github.com/tinix84/GeckoCIRCUITS/blob/main/src/modules/gecko-simulation-core/src/main/java/gecko/core/simulation/solver/MatrixSolver.java) · [ADR-0011](0011-numerical-reference-compatibility.md) · [ADR-0014](0014-java-solver-with-optional-native-sparse.md)
