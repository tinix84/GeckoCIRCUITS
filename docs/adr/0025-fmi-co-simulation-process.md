# Run FMI co-simulation in a private Java process

ADR-0025 · GeckoCIRCUITS · 2026-11-24 · Status: Proposed · Version 1.0

Audience: maintainers of the Functional Mock-up Interface (FMI) export.

**Decision.** Use PythonFMU to package an FMI 2.0 co-simulation Functional Mock-up Unit (FMU) with a private Java session process.

## Context

Issue [#47](https://github.com/tinix84/GeckoCIRCUITS/issues/47) requires an FMI master to set circuit inputs and get named probe outputs.
`SessionHost` already runs external steps for Simulink on the session owner thread.
Each FMU instance needs an independent circuit state.

## Options

| Option | Benefit | Cost |
| --- | --- | --- |
| PythonFMU and a Java child process | PythonFMU implements the FMI 2.0 interface. The Java process uses the existing core. | The host needs compatible Python and Java runtimes. Each step crosses a process boundary. |
| Native wrapper with an embedded Java virtual machine (JVM) | In-process calls remove pipe overhead. | The wrapper must locate the JVM and manage native builds and JVM lifetime in each FMI host. |

## Decision and rationale

Package the circuit file, port map, and Java worker with the FMU.
Use one child process per FMU instance. Exchange input and output values over standard input and output, as shown in Figure 1.
The worker uses `SessionHost.stepExternal` for all circuit steps. The pipe interface needs no listening socket.

```mermaid
flowchart LR
    A[FMI master] <-->|FMI 2.0| B[PythonFMU adapter]
    B <-->|Private process pipes| C[Java worker]
    C <-->|External steps and probes| D[SessionHost]
```

*Figure 1. The Java worker owns the GeckoCIRCUITS simulation session.*

## Consequences

- Configure named real variables in port order. Connect the external input slots to signal-controlled sources. Connect the probe signals to external output slots.
- Set the communication step to the circuit grid step. Hold the input values for each communication step. Publish the probe values at the end of each step.
- Use time zero for initialization. Reset starts a new session. FMI state serialization and rollback are unavailable.
- Verify the FMU with FMPy and compare its capacitor voltage with a standalone GeckoCIRCUITS resistor-capacitor run. Use a 1 % voltage tolerance.

## Status

Proposed on 2026-11-24. This decision applies to the first FMI export in issue #47.

Sources: [FMI 2.0.4](https://github.com/modelica/fmi-standard/releases/tag/v2.0.4) and [PythonFMU](https://github.com/NTNU-IHB/PythonFMU).
