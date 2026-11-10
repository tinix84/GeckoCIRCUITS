# Controlled source compatibility

Date: 2026-11-09

## Decision

The shared backend preserves the desktop reported current for a voltage-controlled current source (VCCS).
The numerical equations still use the configured gain and the controlling voltage.
With a 2 V control voltage and 0.5 S gain, the source contributes 1 A to the circuit equations.
A 10 Ω load then has a voltage of -10 V and a reported load current of -1 A.
The reported VCCS current remains 0 A.

Source references identify the controlling element explicitly.
Each run owns its resolved target indexes.
The numerical implementation must not infer a target from adjacent matrix positions.
Matrix ordering can change when hidden components become part of the numerical circuit.

## Why

Probes and recorded signals can observe the reported component current.
Replacing 0 A with 1 A would change those values and could change CONTROL feedback.
This separation preserves the desktop observable behavior.
Correcting the legacy reported current requires a separate compatibility decision.

Returning the physical source current would make the reported source and load currents consistent.
We retain the reporting quirk because this task changes execution ownership while preserving desktop behavior.
Explicit source references preserve the intended control relationship when matrix ordering changes.

## Source limits at startup

Decision date: 2026-11-10.

For raw source mode 5, apply voltage limits at time zero.
For raw source mode 399, apply limits only when time is greater than zero.
Preserve this distinction when source identifiers reach the numerical netlist.
An active limit returns to the linear equation before the opposite limit can activate.
This preserves the desktop retry order before the electrical step commits its history.

The desktop limit condition is `mode == 5 || mode == 399 && time > 0`.
Java evaluates the time condition only for mode 399.
`UserParameter.getDoubleValue()` returns the old circuit-source identifier, and its model listener writes that value into the parameter array.
The desktop simulation runs its step before it increases time.
A run that starts at zero therefore reaches the mode 399 exception.

Unifying both startup behaviors would change the first voltage sample and capacitor history.
This separation retains the desktop timing quirk.

## References

- [Desktop matrix equations and current reporting](../../src/main/java/gecko/geckocircuits/circuit/LKMatrices.java)
- [Desktop controlling element resolution](../../src/main/java/gecko/geckocircuits/circuit/NetListLK.java)
- [Desktop source parameter representation](../../src/main/java/gecko/geckocircuits/allg/UserParameter.java)
- [Desktop step timing](../../src/main/java/gecko/geckocircuits/circuit/SimulationsKern.java)
- [Controlled source compatibility contract](../../src/test/java/gecko/geckocircuits/circuit/ControlledSourceParityContractTest.java)
