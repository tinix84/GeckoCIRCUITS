# Buck Converter — Peak Current Mode Control (CMC)

> **Book reference:** Basso, *"Simulating Switching Converters with LTspice"*,
> Chapter 3 — *The Buck Converter in Current Mode*.

## Overview

Peak current mode control (CMC) adds an inner current control loop that
senses inductor current on a cycle-by-cycle basis. Compared to voltage mode
control, CMC simplifies compensator design (the LC double pole splits into
two single poles) and provides inherent cycle-by-cycle over-current protection.

**Difficulty:** Intermediate

**Estimated Time:** 40–50 minutes

## Learning Objectives

- Understand the CMC modulator and current sensing
- Recognise how the inner current loop modifies the plant transfer function
- Apply slope compensation to prevent sub-harmonic oscillation (D > 0.5)
- Design a simpler Type 2 compensator for the voltage loop
- Compare CMC vs VMC transient response at the same crossover frequency

## Circuit Parameters

| Parameter | Symbol | Value | Unit |
|-----------|--------|-------|------|
| Input voltage | Vin | 12 | V |
| Output voltage | Vout | 5 | V |
| Peak inductor current (set point) | Ipk | 2.8 | A |
| Switching frequency | fs | 200 | kHz |
| Inductor | L | 47 | μH |
| Output capacitor | C | 100 | μF |
| Capacitor ESR | Rc | 20 | mΩ |
| Current sense resistor | Rcs | 0.1 | Ω |
| Slope compensation ramp | Se | 0.5×Sn | — |
| Load resistance (full) | R | 2.5 | Ω |

## CMC Modulator Model

Peak CMC replaces the voltage-ramp modulator with an inductor-current ramp:

```
ton = (Ipk − Ival(t)) / Sn
```

Where the natural inductor current slope is:
```
Sn = (Vin − Vout) / L = (12 − 5) / 47μ = 148.9 kA/s
```

For D > 0.5 (not the case here, but shown for completeness), slope compensation
is added to prevent period-doubling instability:
```
Se ≥ Sn/2  (minimum)
Se = Sn    (recommended, removes sampling effect at fs/2)
```

## Simplified CMC Plant

With the inner current loop closed, the effective control-to-output plant
simplifies to a **single pole** response (low-frequency region):

```
Gvc(s) ≈ Vout / (Ipk × Rcs) × 1/(1 + s/ωp)
```

Where:
```
ωp ≈ 2/(R × C)   (dominant output pole)
```

The LC double pole is split: one pole moves to `fmod ≈ fs/2` (high frequency,
outside the loop bandwidth) and the other becomes the simple RC output pole.

This means a **Type 2** compensator (one zero, two poles) is sufficient.

## Type 2 Compensator Design

```
Gc(s) = K × (1 + s/ωz1) / [s(1 + s/ωp1)]
```

Target bandwidth: fc = 20 kHz (fs/10)

| Element | Frequency |
|---------|-----------|
| Zero (fz1) | 2 kHz |
| High-freq pole (fp1) | 80 kHz |

## Circuit Files

| File | Description | Status |
|------|-------------|--------|
| `buck_cmc_open_loop.ipes` | Open-loop with fixed peak current limit | 📝 to be added |
| `buck_cmc_closed_loop.ipes` | Closed-loop with Type 2 voltage compensator | 📝 to be added |
| `buck_cmc_slope_comp.ipes` | CMC with slope compensation (Vin=15V, D>0.5) | 📝 to be added |

## Building `buck_cmc_open_loop.ipes`

1. Place a **DC voltage source** (12 V, label `Vin`)
2. Add an **ideal switch** (or MOSFET) in series
3. Add inductor **L = 47 μH** with current probe
4. Add freewheeling diode (Vf = 0.5 V) to GND
5. Add output stage: **C = 100 μF** (ESR 20 mΩ) ‖ **R = 2.5 Ω**
6. Add a **current sense resistor** Rcs = 0.1 Ω in series with the switch
   (or use the current probe signal directly)
7. Implement the **PWM comparator**:
   - Comparator positive input: Ipk reference (0.28 V ≡ 2.8 A × 0.1 Ω)
   - Comparator negative input: sensed inductor current × Rcs
   - A **Set-Reset flip-flop** (SR-FF) with clock at fs resets each cycle
8. The SR-FF output drives the switch gate

## Building `buck_cmc_closed_loop.ipes`

1. Start from `buck_cmc_open_loop.ipes`
2. Replace the fixed Ipk reference with the output of a Type 2 compensator:
   - Error amplifier: Vout ÷ resistor divider → compare with Vref = 2.5 V
   - Add RC network for Type 2 (one zero at 2 kHz, high-freq pole at 80 kHz)
3. Limit the compensator output to `[0, Ipk_max]` (anti-windup)

## Expected Steady-State Results

| Signal | Value |
|--------|-------|
| Vout (average) | 5.0 V |
| IL (average) | 2.0 A |
| IL peak | 2.53 A |
| ΔIL (ripple p-p) | 0.53 A |
| ΔVout (ripple p-p) | 2.7 mV |

## Exercises

### Exercise 1 — Sub-Harmonic Oscillation Demo

1. Open `buck_cmc_slope_comp.ipes`
2. Disable slope compensation (set Se = 0)
3. Set Vin = 12 V, Vout = 5 V → D = 5/12 = 0.417 < 0.5 — no oscillation expected
4. Reduce Vin to 9 V so that D = 5/9 = 0.556 > 0.5 — sub-harmonic oscillation expected
   *(For a buck converter D = Vout/Vin, so to push D above 0.5 you must lower Vin
   below 2 × Vout = 10 V.)*
5. **Question:** At what Vin does period doubling first appear?
6. Enable slope compensation (Se = Sn/2) and repeat — oscillation should cease

### Exercise 2 — CMC vs VMC Transient Comparison

1. Run the same 50% load step on `buck_cmc_closed_loop.ipes` as in
   Exercise 4 of [01_buck_vmc](../01_buck_vmc/)
2. Compare undershoot magnitude and recovery time at identical fc = 10 kHz
3. **Question:** Which control mode shows better transient performance?
   What physical mechanism explains the difference?

### Exercise 3 — Current Sense Resistor Power Loss

1. In `buck_cmc_open_loop.ipes`, note the power dissipated in Rcs
2. Replace Rcs with Rcs = 0.05 Ω and 0.2 Ω
3. **Question:** What is the trade-off in selecting the sense resistor value?

### Exercise 4 — CCM/DCM Transition in CMC

1. In CMC, the converter enters DCM when the inductor current naturally reaches
   zero before the next clock cycle
2. Increase R to 50 Ω and observe the inductor current
3. **Question:** Does CMC naturally limit peak current in DCM? How does Vout
   behave without a voltage feedback loop?

## Key Equations Summary

```
Sn = (Vin − Vout) / L            (natural current slope)
Sf = Vout / L                    (falling current slope)
D_crit = 1/(1 + Sf/Sn) = Vout/Vin  (CCM/DCM boundary for duty)
Se_min = Sn / 2                  (minimum slope compensation)
fp_cmc ≈ 1/(π × R × C)          (dominant output pole with CMC)
```

## Related Examples

- [Buck VMC (01)](../01_buck_vmc/) — voltage mode variant of the same converter
- [Boost CMC (04)](../04_boost_cmc/) — boost with current mode (RHPZ challenge)
- [Type 2/3 Compensators (09)](../09_compensators/) — compensator design guide

## References

1. Basso, C. *Simulating Switching Converters with LTspice*, Ch. 3
2. Ridley, R.B. "A New, Continuous-Time Model for Current-Mode Control",
   *IEEE Trans. Power Electronics*, 6(2), 1991
3. Dixon, L. "Average Current Mode Control of Switching Power Supplies",
   Unitrode Power Supply Design Seminar

---
*Last updated: 2026-02*
*GeckoCIRCUITS v1.0*
