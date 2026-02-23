# Control Compensators — Type 1, Type 2, and Type 3

> **Book reference:** Basso, *"Simulating Switching Converters with LTspice"*,
> Chapter 10 — *Compensator Design*.

## Overview

Feedback compensators shape the loop gain to achieve a desired crossover
frequency and phase margin. This example compares the three standard op-amp
compensator types used in switching power supply control loops, and provides
ready-to-use component value tables and verification circuits.

**Difficulty:** Intermediate

**Estimated Time:** 40–50 minutes

## Learning Objectives

- Understand the phase boost each compensator type provides
- Select the appropriate compensator for a given plant
- Calculate component values from crossover frequency and phase margin
- Verify loop gain (Bode plot) in GeckoCIRCUITS

## Compensator Types Summary

| Type | Description | Phase Boost | Use Case |
|------|-------------|-------------|----------|
| Type 1 | Integrator only | 0° | Very simple plants (no zeros needed) |
| Type 2 | One zero, two poles | Up to ~90° | CMC buck/boost, single-pole plant |
| Type 3 | Two zeros, two poles + integrator | Up to ~180° | VMC buck/forward (LC double pole) |

## Type 1 Compensator

The simplest compensator — a pure integrator:
```
Gc(s) = ωc / s  ⟹  |Gc(fc)| = 1 at fc by design
```

**Circuit:** Single resistor R1 and capacitor C1 around an inverting op-amp.

```
         Cf
        ┌──┤├──┐
        │      │
Vin ──[R1]──┬──┤−│──── Vout
            │  │+│
           GND └──── GND
```

**Component values:**
```
Cf = 1 / (2π × R1 × fc)    (at crossover, |Gc| = 1)
```

**Phase:** −90° (pure lag integrator — used only when extra lag is acceptable)

## Type 2 Compensator

One zero at fz, integrator, and one high-frequency pole at fp:

```
Gc(s) = K × (1 + s/ωz) / [s × (1 + s/ωp)]
```

**Circuit:**

```
        Cf ‖ Rf
        ┌──┤├──┐
        │  └─┤├┘
        │      │
Vin ──[R1]──┬──┤−│──── Vout
            │  │+│
           [Rb] └── GND (Rb sets Vref divider)
```

Where:
- Zero: `fz = 1/(2π × Rf × Cf)`
- HF pole: `fp = 1/(2π × Rf × Cpf)` (Cpf in parallel with Rf)
- Gain at fc: `K = ωz / (ωc × R1 × Cf × ωp)` (adjusted by R1)

**Phase boost at fc:**
```
φboost = arctan(fc/fz) − arctan(fc/fp)
Maximum φboost ≈ 90° when fp = fc²/fz
```

**Design by k-factor method:**
```
k = tan(45° + φboost/2)
fz = fc / k
fp = fc × k
```

### Type 2 Component Value Table

For fc = 10 kHz, target phase boost = 60°:
```
k = tan(45° + 30°) = tan(75°) = 3.73
fz = 10k / 3.73 = 2.68 kHz
fp = 10k × 3.73 = 37.3 kHz
```

Choose R1 = 10 kΩ, set |Gc(fc)| = needed gain (adjust R1 or Cf):
| Component | Value |
|-----------|-------|
| R1 | 10 kΩ |
| Rf | 56 kΩ (adjusts zero via Cf) |
| Cf | 1.06 nF (for fz = 2.68 kHz) |
| Cpf | 72 pF (for fp = 37.3 kHz) |

## Type 3 Compensator

Two zeros, two poles, one integrator — used to compensate the LC double pole
in VMC buck/forward converters:

```
Gc(s) = K × (1 + s/ωz1)(1 + s/ωz2) / [s × (1 + s/ωp1)(1 + s/ωp2)]
```

**Circuit (most common op-amp implementation):**

```
        Rf1 ‖ Cf1                 Cf2
        ┌──┤├──┐           ┌──────┤├──────┐
        │  └─┤├┘           │              │
        │          Cf3     │              │
Vin ──[R1]──┬──[R2]──┬──┤−│──── Vout
            │         │   │+│
           [Rb]      [Cb] └── GND
```

Two zeros:
```
fz1 = 1/(2π × R2 × Cb)
fz2 = 1/(2π × Rf1 × Cf1)
```

Two HF poles:
```
fp1 = 1/(2π × R1 × Cf3)
fp2 = 1/(2π × Rf1 × Cf2)   (or uses Cpf in parallel with Rf1)
```

**Phase boost:** Up to ~180° theoretically; practically 120–150° at fc.

### Type 3 Component Values (from [01_buck_vmc](../01_buck_vmc/))

For fc = 10 kHz with LC double pole at f0 = 2.32 kHz:

| Pole/Zero | Frequency | k-factor |
|-----------|-----------|----------|
| fz1 = fz2 | ~1.5–2 kHz | at f0/√2 |
| fp1 = fp2 | ~50–100 kHz | at fs/4 or fc×k |

Using k-factor for Type 3 with desired total phase boost of 120° (PM = 60°):
```
Two identical pairs → each pair must provide 120°/2 = 60°
k3 = tan(45° + 60°/2) = tan(45° + 30°) = tan(75°) ≈ 3.73

Place zeros: fz1 = fz2 = fc / k3 = 10k / 3.73 ≈ 2.68 kHz  → use 2.5 kHz
Place poles: fp1 = fp2 = fc × k3 = 10k × 3.73 ≈ 37.3 kHz  → use 40 kHz
```

## Circuit Files

| File | Description | Status |
|------|-------------|--------|
| `type1_compensator.ipes` | Type 1 integrator verification | 📝 to be added |
| `type2_compensator.ipes` | Type 2 Bode plot and transient test | 📝 to be added |
| `type3_compensator.ipes` | Type 3 Bode plot and transient test | 📝 to be added |

## Building `type2_compensator.ipes`

1. Place an **ideal op-amp** (or a finite-gain op-amp with GBW = 10 MHz)
2. Add the R/C network for Type 2 from the table above:
   - R1 between input source and inverting terminal
   - Rf + Cf in series (zero network) in parallel with Cpf, all in the feedback
3. Connect a **sinusoidal input voltage sweep** from 100 Hz to 500 kHz
4. Use the **Bode analyser** in GeckoCIRCUITS to measure gain and phase vs
   frequency
5. Verify: gain = 0 dB at fc = 10 kHz, phase ≈ +60° boost (−30° net)

## Expected Bode Plot Results

### Type 2 at fc = 10 kHz, φboost = 60°

| Frequency | Gain | Phase |
|-----------|------|-------|
| 100 Hz | High (integrator) | ≈ −90° |
| fz = 2.68 kHz | Starts rising | −90° + boost |
| fc = 10 kHz | 0 dB | −30° (= −90° + 60°) |
| fp = 37.3 kHz | Starts falling | Decreasing |
| > 100 kHz | −40 dB/dec | ≈ −90° |

## Exercises

### Exercise 1 — Type 2 Phase Boost vs Component Values

1. Build `type2_compensator.ipes`
2. Move the zero from fz = 2.68 kHz to fz = 10 kHz (equal to fc)
3. **Question:** What happens to the phase at fc? What is the maximum
   achievable phase boost from a single zero?

### Exercise 2 — Type 3 vs Type 2 for Buck Converter

1. Connect each compensator type to the buck plant from [01_buck_vmc](../01_buck_vmc/)
2. Target fc = 10 kHz for both
3. **Question:** Which achieves higher phase margin? What is the phase margin
   of Type 2 applied to the LC double pole plant?

### Exercise 3 — Op-Amp Gain-Bandwidth Limitation

1. In `type2_compensator.ipes`, change the op-amp GBW from 10 MHz to 1 MHz
2. Run the Bode plot again
3. **Question:** At what frequency does the finite GBW begin to affect the
   compensator response? What is the rule of thumb for minimum op-amp GBW?

### Exercise 4 — k-Factor Method Verification

1. For the Type 3 compensator with fc = 20 kHz and target phase margin = 55°:
   - Calculate the required phase boost
   - Apply the k-factor method to find fz1 = fz2 and fp1 = fp2
   - Set the component values accordingly
   - Verify in `type3_compensator.ipes`
2. **Question:** Does the measured phase margin match the target?

## Key Equations Summary

```
k = tan(45° + φboost/2)          (k-factor for Type 2: one zero-pole pair)
fz = fc / k                       (zero frequency)
fp = fc × k                       (pole frequency)

For Type 3 (two identical zero-pole pairs, total boost = φtotal):
  Each pair contributes φtotal/2, so k per pair:
k3 = tan(45° + φtotal/4)         (k-factor per pair for Type 3)
fz1 = fz2 = fc / k3
fp1 = fp2 = fc × k3

Example: fc=10kHz, φtotal=120°  → k3 = tan(45°+30°) = tan(75°) ≈ 3.73
  fz1=fz2 = 10k/3.73 ≈ 2.68 kHz
  fp1=fp2 = 10k×3.73 ≈ 37.3 kHz
```

## Related Examples

- [Buck VMC (01)](../01_buck_vmc/) — Type 3 applied to LC plant
- [Buck CMC (02)](../02_buck_cmc/) — Type 2 applied to CMC plant
- [Boost VMC (03)](../03_boost_vmc/) — Type 2 with RHPZ constraint
- [PFC Boost (08)](../08_pfc_boost/) — dual-loop compensators

## References

1. Basso, C. *Simulating Switching Converters with LTspice*, Ch. 10
2. Venable, H.D. "The k-Factor: A New Mathematical Tool", Proc. Powercon 10, 1983
3. Ridley, R.B. *Power Supply Design*, Vol. 1, Ch. on Compensator Design

---
*Last updated: 2026-02*
*GeckoCIRCUITS v1.0*
