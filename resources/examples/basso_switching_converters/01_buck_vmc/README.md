# Buck Converter — Voltage Mode Control (VMC)

> **Book reference:** Basso, *"Simulating Switching Converters with LTspice"*,
> Chapter 2 — *The Buck Converter in Voltage Mode*.

## Overview

This example implements an open-loop and closed-loop buck converter controlled
by a voltage-mode PWM scheme. The compensated design uses a Type 3 op-amp
network to achieve a 10 kHz crossover frequency with 60° phase margin.

**Difficulty:** Beginner → Intermediate

**Estimated Time:** 30–40 minutes

## Learning Objectives

- Understand open-loop buck converter waveforms (Vout, IL, Vsw)
- Design a Type 3 compensator for the voltage control loop
- Verify the closed-loop Bode plot (crossover, phase margin)
- Compare CCM and DCM operation by changing load resistance

## Circuit Parameters

| Parameter | Symbol | Value | Unit |
|-----------|--------|-------|------|
| Input voltage | Vin | 12 | V |
| Output voltage | Vout | 5 | V |
| Duty cycle (open-loop) | D | 0.417 | — |
| Switching frequency | fs | 200 | kHz |
| Inductor | L | 47 | μH |
| Output capacitor | C | 100 | μF |
| Capacitor ESR | Rc | 20 | mΩ |
| Load resistance (full) | R | 2.5 | Ω |
| Load resistance (light) | R | 25 | Ω |

## Small-Signal Plant Transfer Function

The control-to-output transfer function in CCM (voltage mode):

```
Gvd(s) = Vin / (1 + s*Rc*C) / (1 + s*L/R + s²*L*C*(1 + Rc/R))
```

DC gain:
```
G0 = Vin = 12 V  (with 1 V/V modulator)
```

Double pole (LC resonance):
```
f0 = 1 / (2π √(LC)) = 1 / (2π √(47μ × 100μ)) ≈ 2.32 kHz
```

ESR zero:
```
fz_esr = 1 / (2π × Rc × C) = 1 / (2π × 20m × 100μ) ≈ 79.6 kHz
```

## Type 3 Compensator Design

A Type 3 (two zeros, two poles + one pole at origin) compensator cancels the
LC double pole and extends phase margin:

```
Gc(s) = K × (1 + s/ωz1)(1 + s/ωz2) / [s(1 + s/ωp1)(1 + s/ωp2)]
```

Target bandwidth: fc = 10 kHz (fs/20)

| Compensator pole/zero | Frequency |
|-----------------------|-----------|
| Zero 1 (fz1) | 1.5 kHz |
| Zero 2 (fz2) | 2.0 kHz |
| Pole 1 (fp1) | 50 kHz |
| Pole 2 (fp2) | 100 kHz |

## Circuit Files

| File | Description | Status |
|------|-------------|--------|
| `buck_vmc_open_loop.ipes` | Open-loop, fixed duty cycle D=0.417 | 📝 to be added |
| `buck_vmc_closed_loop.ipes` | Closed-loop with Type 3 compensator | 📝 to be added |
| `buck_vmc_bode.ipes` | AC sweep — open-loop plant Bode plot | 📝 to be added |

## Building `buck_vmc_open_loop.ipes`

1. Place a **DC voltage source** (12 V, label `Vin`)
2. Add an **ideal switch** in series with `Vin`
3. Add a **PWM signal generator**: fs = 200 kHz, D = 0.417
4. Connect the switch output to inductor **L = 47 μH**
5. Add a **freewheeling diode** (Vf = 0.5 V) from switch node to GND
6. Connect inductor to parallel **C = 100 μF** (ESR = 20 mΩ) and **R = 2.5 Ω**
7. Place **current probe** on inductor, **voltage probe** on output node
8. Set simulation time = 500 μs, timestep = 10 ns

## Building `buck_vmc_closed_loop.ipes`

1. Start from `buck_vmc_open_loop.ipes`
2. Remove the fixed-duty-cycle PWM generator
3. Add an **error amplifier** (op-amp or `GAIN` block):
   - Reference voltage Vref = 2.5 V (or use a voltage divider)
   - Implement Type 3 network with R/C components around the op-amp
4. Feed the error amplifier output to a **voltage-controlled PWM block**
5. Run for 2 ms to observe startup and settling

## Expected Steady-State Results

### Open-Loop (D = 0.417, R = 2.5 Ω)

| Signal | Value |
|--------|-------|
| Vout (average) | 5.0 V |
| IL (average) | 2.0 A |
| ΔIL (ripple p-p) | 0.53 A |
| ΔVout (ripple p-p) | 2.7 mV (ESR limited) |
| Efficiency (ideal) | ~100% |

### Closed-Loop (fc = 10 kHz, PM ≈ 60°)

| Signal | Value |
|--------|-------|
| Vout regulation | 5.0 V ± 1% |
| Transient undershoot (50% load step) | < 200 mV |
| Recovery time | < 100 μs |

## Exercises

### Exercise 1 — CCM/DCM Boundary

1. Open `buck_vmc_open_loop.ipes`
2. Keep D = 0.417, change R from 2.5 Ω to 25 Ω
3. Observe inductor current waveform
4. **Question:** At what load resistance does the converter enter DCM?
   Use the formula: `Rload_crit = 2 × L × fs / (1 - D)`

### Exercise 2 — Effect of ESR on Output Ripple

1. Change Rc from 20 mΩ to 200 mΩ
2. Observe change in output voltage ripple
3. **Question:** Does a higher ESR increase or decrease ripple?
   Why is there a zero in the transfer function at `fz = 1/(2π Rc C)`?

### Exercise 3 — Loop Gain Measurement

1. Open `buck_vmc_bode.ipes`
2. Identify the crossover frequency and phase margin from the Bode plot
3. Change Type 3 zero frequencies and re-run the AC sweep
4. **Question:** What happens to phase margin as fz moves away from f0?

### Exercise 4 — Load Transient Response

1. Use `buck_vmc_closed_loop.ipes`
2. Add a **step load**: R switches from 2.5 Ω to 5 Ω at t = 1 ms
3. Measure output voltage undershoot and recovery time
4. **Question:** How does increasing C affect recovery time? How does
   increasing fc affect undershoot?

## Key Equations Summary

```
Vout = D × Vin                         (CCM steady state)
ΔIL = Vin × D × (1-D) / (L × fs)      (inductor current ripple)
ΔVout ≈ ΔIL × Rc                       (ESR-dominated ripple)
f0 = 1/(2π √LC)                        (LC resonant frequency)
fz_esr = 1/(2π × Rc × C)              (ESR zero frequency)
```

## Related Examples

- [Buck CMC (02)](../02_buck_cmc/) — same converter with current mode control
- [Type 2/3 Compensators (09)](../09_compensators/) — compensator design details
- [Boost VMC (03)](../03_boost_vmc/) — boost variant with RHPZ challenge
- [Tutorial 201](../../../tutorials/2xx_dcdc_converters/201_buck_converter/) — step-by-step guide

## References

1. Basso, C. *Simulating Switching Converters with LTspice*, Ch. 2
2. Erickson, R., Maksimovic, D. *Fundamentals of Power Electronics*, Ch. 7–9
3. Venable, H.D. "The k-Factor: A New Mathematical Tool for Stability Analysis"

---
*Last updated: 2026-02*
*GeckoCIRCUITS v1.0*
