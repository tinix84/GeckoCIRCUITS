# Phase B: Golden Trace Generation Guide

## Overview

Generate per-step golden trace CSVs for 8 education circuits using GeckoCIRCUITS v2.02 (and optionally PLECS). These traces serve as reference data for Phase C application tests in `gecko-simulation-core`.

## Prerequisites

- **GeckoCIRCUITS v2.02** running on a machine with display (GUI required)
- The `.ipes` files are already in the repo at `src/test/resources/ipes/education/`
- Optional: **PLECS** installed for cross-tool validation

## Configuration

All circuit definitions live in:
```
src/modules/gecko-simulation-core/src/test/resources/golden/golden-traces.yaml
```

This YAML config is the single source of truth for which circuits need traces, what tool generated them, and where the output goes.

## Circuits to Process

| # | Circuit ID | .ipes file | Category |
|---|-----------|-----------|----------|
| 1 | `buckBoost_simple` | `buckBoost_simple.ipes` | PWM converter |
| 2 | `cuk_simple` | `cuk_simple.ipes` | PWM converter |
| 3 | `sepic_simple` | `sepic_simple.ipes` | PWM converter |
| 4 | `singlePhase_PWM_converter` | `singlePhase_PWM_converter.ipes` | Full-bridge |
| 5 | `thyristor_RL_single` | `thyristor_RL_single.ipes` | Thyristor |
| 6 | `thyristor_freeWheelingDiode` | `thyristor_freeWheelingDiode.ipes` | Thyristor |
| 7 | `2phaseDiodeBridge_AC-Inductor` | `2phaseDiodeBridge_AC-Inductor.ipes` | Diode bridge |
| 8 | `diode_RL_singlePH_trafo` | `diode_RL_singlePH_trafo.ipes` | Diode rectifier |

## Step-by-Step: GeckoCIRCUITS v2.02

### 1. Generate per-step trace CSV

For each circuit, the trace must capture **every simulation time step** with full state: node voltages, component currents, switch/diode resistances, and gate signals.

The existing traces (`buck_simple_trace.csv.gz`, `boost_simple_trace.csv.gz`) were generated using `TraceGeneratorTest.java` which runs the v2.02 `SimulationsKern` with a `StepTraceListener`. The same approach works for all 8 circuits.

**Option A — Use TraceGeneratorTest (recommended)**

If you have the `TraceGeneratorTest` infrastructure available, add test cases for each circuit. The test:
1. Loads the `.ipes` file via `SimulationsKern`
2. Attaches a `StepTraceListener` that records `(time, p[], i[], switchR, diodeR, gateSignals)` at every step
3. Writes the trace to gzipped CSV

**Option B — Manual export from GUI**

1. Open the `.ipes` file in GeckoCIRCUITS v2.02
2. Run the simulation (press Play)
3. Export scope data: **File → Export Data → CSV**
4. This exports scope-level data, NOT per-step trace data. You would need to configure the scope to capture all nodes.

> **Important**: Option A is strongly preferred because it captures internal solver state (node voltages, currents, switch states) at every time step. The GUI scope export only captures what's wired to scope inputs.

### 2. Convert to required format

The per-step trace CSV must follow this format:

**Header row**:
```
time,p[0],p[1],...,p[N],i[0],i[1],...,i[M],R_sw_<name>,gate_<name>,R_d_<name>
```

**Data rows** (scientific notation, 12 digits):
```
0.000000000000e+00,0.000000000000e+00,4.800000000000e+01,...
1.000000000000e-06,0.000000000000e+00,4.798256370943e+01,...
```

**Compression**: gzip the CSV → `.csv.gz`

### 3. Place output files

```
src/modules/gecko-simulation-core/src/test/resources/golden/
├── golden-traces.yaml                          # Config (already exists)
├── buckBoost_simple_v202.csv.gz
├── cuk_simple_v202.csv.gz
├── sepic_simple_v202.csv.gz
├── singlePhase_PWM_converter_v202.csv.gz
├── thyristor_RL_single_v202.csv.gz
├── thyristor_freeWheelingDiode_v202.csv.gz
├── 2phaseDiodeBridge_AC-Inductor_v202.csv.gz
└── diode_RL_singlePH_trafo_v202.csv.gz
```

### 4. Copy .ipes files to simulation-core resources

```
src/modules/gecko-simulation-core/src/test/resources/ipes/
├── buck_simple.ipes          # Already exists
├── boost_simple.ipes         # Already exists
├── buckBoost_simple.ipes     # NEW
├── cuk_simple.ipes           # NEW
├── sepic_simple.ipes         # NEW
├── singlePhase_PWM_converter.ipes  # NEW
├── thyristor_RL_single.ipes        # NEW
├── thyristor_freeWheelingDiode.ipes      # NEW
├── 2phaseDiodeBridge_AC-Inductor.ipes    # NEW
└── diode_RL_singlePH_trafo.ipes          # NEW
```

Copy from `src/test/resources/ipes/education/`:
```bash
SRC=src/test/resources/ipes/education
DST=src/modules/gecko-simulation-core/src/test/resources/ipes

cp $SRC/buckBoost_simple.ipes $DST/
cp $SRC/cuk_simple.ipes $DST/
cp $SRC/sepic_simple.ipes $DST/
cp $SRC/singlePhase_PWM_converter.ipes $DST/
cp $SRC/thyristor_RL_single.ipes $DST/
cp $SRC/thyristor_freeWheelingDiode.ipes $DST/
cp $SRC/2phaseDiodeBridge_AC-Inductor.ipes $DST/
cp $SRC/diode_RL_singlePH_trafo.ipes $DST/
```

## Step-by-Step: PLECS (cross-validation)

### 1. Create PLECS models

For each circuit, build a PLECS model matching the `.ipes` topology exactly:
- Same component values (R, L, C, Vin, duty cycle, switching frequency)
- Same simulation parameters (dt, duration)
- Same initial conditions (zero)

### 2. Export traces

1. Open model in PLECS
2. Add Scope blocks for all node voltages and branch currents
3. Run simulation
4. **Scope → right-click → Export Data → CSV**
5. Ensure time column + all signal columns are exported

### 3. Convert to matching format

PLECS CSV may use different column naming. Convert to match the v2.02 format:
- Time column first
- Node voltages as `p[0], p[1], ...`
- Currents as `i[0], i[1], ...`
- Map PLECS signal names to GeckoCIRCUITS node indices using the circuit topology

### 4. Place output files

```
src/modules/gecko-simulation-core/src/test/resources/golden/
├── buckBoost_simple_plecs.csv.gz
├── cuk_simple_plecs.csv.gz
├── ...
```

## Naming Convention Summary

```
<circuit_id>_<tool>.csv.gz
```

| Tool suffix | Source |
|------------|--------|
| `_v202` | GeckoCIRCUITS v2.02 (baseline reference) |
| `_plecs` | PLECS (cross-tool validation) |
| `_v300` | GeckoCIRCUITS v3.x headless (generated by CI) |

## Verification Checklist

For each circuit, verify before committing:

- [ ] `.ipes` file copied to `src/modules/gecko-simulation-core/src/test/resources/ipes/`
- [ ] Trace CSV is gzipped (`.csv.gz`)
- [ ] Header row present with `time,p[0],...,i[0],...` format
- [ ] At least 1000 data rows (full simulation)
- [ ] Time column starts at 0 or near 0
- [ ] Time column is monotonically increasing
- [ ] No NaN or Inf values
- [ ] File size is reasonable (typically 100KB–5MB compressed)

Quick validation:
```bash
# Check header
zcat <file>.csv.gz | head -1

# Check row count
zcat <file>.csv.gz | wc -l

# Check for NaN
zcat <file>.csv.gz | grep -c "NaN"

# Check time range
zcat <file>.csv.gz | tail -1 | cut -d',' -f1
```

## What Happens Next (Phase C)

Once golden traces are in place, Phase C creates 7 application test classes (one already exists as a template: `BuckSimpleApplicationTest.java`). Each test:

1. Loads the `.ipes` file via `CircuitFileParser`
2. Builds `HeadlessSimulationEngine`
3. Registers `StepTraceListener`
4. Runs simulation
5. Loads golden trace via `TraceComparisonHelper.loadTrace()`
6. Compares per-step traces within 1% tolerance

The test class names are defined in `golden-traces.yaml` under `test_class`.
