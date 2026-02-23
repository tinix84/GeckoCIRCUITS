# Boost Converter — Voltage Mode Control (VMC)

> **Book reference:** Basso, *"Simulating Switching Converters with LTspice"*,
> Chapter 4 — *The Boost Converter in Voltage Mode*.

## Overview

The boost converter is a step-up DC-DC topology. Its small-signal transfer
function contains a **right-half-plane zero (RHPZ)** that limits achievable
closed-loop bandwidth, making compensator design more challenging than for the
buck converter.

**Difficulty:** Intermediate

**Estimated Time:** 40–50 minutes

## Learning Objectives

- Understand boost converter waveforms (non-pulsating input current)
- Identify the right-half-plane zero (RHPZ) and its effect on stability
- Limit the crossover frequency to below the RHPZ frequency
- Design a Type 2 or Type 3 compensator respecting the RHPZ constraint

## Circuit Parameters

| Parameter | Symbol | Value | Unit |
|-----------|--------|-------|------|
| Input voltage | Vin | 5 | V |
| Output voltage | Vout | 12 | V |
| Duty cycle (open-loop) | D | 0.583 | — |
| Switching frequency | fs | 200 | kHz |
| Inductor | L | 22 | μH |
| Output capacitor | C | 220 | μF |
| Capacitor ESR | Rc | 30 | mΩ |
| Load resistance | R | 14.4 | Ω |
| Output current | Iout | 0.833 | A |

## Right-Half-Plane Zero

The RHPZ is the defining challenge of the boost converter in VMC. It occurs at:

```
fRHPZ = R × (1 − D)² / (2π × L) = 14.4 × (0.417)² / (2π × 22μ) ≈ 45.6 kHz
```

**Physical interpretation:** When duty cycle increases to raise Vout,
the switch is ON longer → the diode is OFF longer → *less* energy is delivered
to the output in the short term, which initially causes Vout to *drop*.
This appears as a zero in the right half of the s-plane and causes a
−20 dB/dec gain *increase* but a −90° phase *lag*.

**Design rule:** Set crossover frequency `fc < fRHPZ / 5` to maintain stability.
For this example: `fc < 9 kHz` — target `fc = 6 kHz`.

## Small-Signal Plant (CCM, VMC)

```
Gvd(s) = [Vin/(1−D)²] × (1 − s×L/(R×(1−D)²)) × (1 + s×Rc×C)
         ────────────────────────────────────────────────────────
         (1 + s×L/(R×(1−D)²) + s²×L×C/(1−D)²)
```

Key frequencies:
```
f0  = (1−D)/(2π√(LC)) ≈ 5.9 kHz    (effective LC pole, shifted by (1−D))
fz  = 1/(2π×Rc×C)     ≈ 24.1 kHz   (ESR zero)
fRHPZ = R(1−D)²/(2πL) ≈ 45.6 kHz  (right-half-plane zero)
```

## Compensator Design (Type 2)

Because fc is set well below fRHPZ, a **Type 2** compensator is sufficient:

```
Gc(s) = K × (1 + s/ωz1) / [s × (1 + s/ωp1)]
```

| Element | Frequency |
|---------|-----------|
| Zero (fz1) | 3 kHz |
| High-freq pole (fp1) | 40 kHz |

## Circuit Files

| File | Description | Status |
|------|-------------|--------|
| `boost_vmc_open_loop.ipes` | Open-loop, fixed D = 0.583 | 📝 to be added |
| `boost_vmc_closed_loop.ipes` | Closed-loop with Type 2 compensator | 📝 to be added |
| `boost_vmc_bode.ipes` | AC sweep showing RHPZ in plant | 📝 to be added |

## Building `boost_vmc_open_loop.ipes`

1. Place **DC voltage source** (5 V)
2. Add inductor **L = 22 μH** in series with Vin (boost input current is
   continuous — L connects from Vin node, not after the switch)
3. Add **ideal switch** between inductor-output node and GND
4. Add **diode** from inductor-output node to output (anode toward switch node)
5. Add output stage: **C = 220 μF** (ESR 30 mΩ) ‖ **R = 14.4 Ω**
6. Add **PWM signal generator**: fs = 200 kHz, D = 0.583
7. Place **current probe** on L, **voltage probes** on input and output nodes

## Expected Steady-State Results

| Signal | Value |
|--------|-------|
| Vout (average) | 12.0 V |
| IL (average) | 2.0 A (= Iout/(1−D)) |
| ΔIL (ripple p-p) | 0.73 A |
| ΔVout (ripple p-p) | ~11 mV |
| Switch voltage stress | 12 V |
| Diode reverse voltage | 12 V |

## Exercises

### Exercise 1 — Identify the RHPZ in Time Domain

1. Open `boost_vmc_closed_loop.ipes`
2. Apply a positive step to the reference voltage (increase Vout by 10%)
3. Observe the output voltage immediately after the step
4. **Question:** Does Vout initially move in the expected direction?
   Explain the initial undershoot in terms of the RHPZ mechanism.

### Exercise 2 — Bandwidth vs Stability Trade-off

1. Open `boost_vmc_bode.ipes`
2. The plant Bode plot shows gain increasing at fRHPZ — identify this point
3. Increase the compensator gain until fc = fRHPZ/2, then fc = fRHPZ
4. **Question:** What happens to the phase margin at each crossover frequency?
   Why is `fc < fRHPZ/5` a conservative but safe design rule?

### Exercise 3 — Input Voltage Variation

1. Open `boost_vmc_closed_loop.ipes`
2. Change Vin from 5 V to 7 V (same Vout = 12 V → D changes to 0.417)
3. Note the change in fRHPZ (it increases with lower D)
4. **Question:** Is the converter more or less stable at higher Vin?
   Re-derive fRHPZ for D = 0.417.

### Exercise 4 — CCM vs DCM Transition

1. Increase R to 200 Ω (light load)
2. Observe whether the inductor current reaches zero (DCM)
3. **Question:** In DCM, the RHPZ moves to a much higher frequency.
   Does this allow a higher crossover frequency in DCM?

## Key Equations Summary

```
Vout = Vin / (1 − D)              (CCM steady state)
ΔIL = Vin × D / (L × fs)         (inductor current ripple)
fRHPZ = R(1−D)² / (2πL)          (right-half-plane zero)
f0 = (1−D) / (2π√(LC))           (effective resonant pole)
fc < fRHPZ/5                      (stability crossover guideline)
```

## Related Examples

- [Buck VMC (01)](../01_buck_vmc/) — simpler plant (no RHPZ)
- [Boost CMC (04)](../04_boost_cmc/) — CMC mitigates RHPZ somewhat
- [PFC Boost (08)](../08_pfc_boost/) — boost operating over full line cycle
- [Tutorial 202](../../../tutorials/2xx_dcdc_converters/202_boost_converter/) — step-by-step guide

## References

1. Basso, C. *Simulating Switching Converters with LTspice*, Ch. 4
2. Middlebrook, R.D., Cuk, S. "A General Unified Approach to Modelling
   Switching-Converter Power Stages", IEEE PESC, 1976
3. Ridley, R.B. "Analyzing the Sepic Converter", *Power Systems Design
   Europe*, 2006 (also applicable to boost)

---
*Last updated: 2026-02*
*GeckoCIRCUITS v1.0*
