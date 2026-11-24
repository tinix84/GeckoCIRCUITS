# FMI 2.0 co-simulation

Export a GeckoCIRCUITS circuit as a Functional Mock-up Unit (FMU).
The FMU runs one Java simulation session for each instance.
It uses private process pipes and the same external port exchange as GeckoCIRCUITS' Simulink integration.

## Build and verify

1. Install Java 21 and Maven.
2. Install the Python tool dependencies.

```bash
conda run -n ntbees2 pip install -r tools/fmu/requirements.txt
```

3. Run the acceptance check from the repository root.

```bash
conda run -n ntbees2 python tools/fmu/acceptance.py
```

4. To export a circuit, supply its `.ipes` file and a JSON port map.

```bash
conda run -n ntbees2 python tools/fmu/build.py \
  tools/fmu/fixtures/rc_external.ipes tools/fmu/fixtures/rc_ports.json --output fmu-output
```

The port map gives an FMU model identifier, the communication step in seconds, and the external port names.
List real inputs and outputs in saved port order, then channel order within each port.
Each input has a name and a finite start value.
Connect the input slots to signal-controlled sources and the probe signals to output slots in the circuit.

Set the master's start time to zero. Set its communication step to the circuit grid step.
The GeckoCIRCUITS solver holds the input values for each communication step.
The electrical source retains the same update order as the desktop solver.
The FMU can run beyond the circuit's saved duration.
Each reset starts a fresh circuit session. State save and rollback are unavailable.

The FMI host needs Java 21 and a Python runtime that its platform's PythonFMU binary supports.
Use `GECKO_FMU_JAVA` to specify the Java executable if it is outside `PATH`.
The circuit must contain its external resources. The FMU parser reads the circuit's packaged bytes.
