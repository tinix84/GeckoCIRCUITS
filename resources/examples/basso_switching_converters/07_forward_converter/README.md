# Forward Converter — Voltage Mode Control

> **Book reference:** Basso, *"Simulating Switching Converters with LTspice"*,
> Chapter 8 — *The Forward Converter*.

## Overview

The single-switch forward converter is an isolated step-down topology.
Unlike the flyback, it transfers energy to the output *while* the switch
conducts (like a buck) and requires a **core reset** mechanism to demagnetise
the transformer between switching cycles.

**Difficulty:** Advanced

**Estimated Time:** 60–75 minutes

## Learning Objectives

- Understand forward converter operation and core reset requirements
- Size the reset winding (or active clamp) to prevent transformer saturation
- Derive the voltage conversion ratio including the turns ratio
- Design a buck-like compensator (forward has no RHPZ)

## Circuit Parameters

| Parameter | Symbol | Value | Unit |
|-----------|--------|-------|------|
| Input voltage | Vin | 48 | V |
| Output voltage | Vout | 5 | V |
| Output power | Pout | 50 | W |
| Switching frequency | fs | 250 | kHz |
| Turns ratio power (Np:Ns) | n1 | 6:1 | — |
| Turns ratio reset (Np:Nr) | n2 | 1:1 | — |
| Output inductor | L | 10 | μH |
| Output capacitor | C | 330 | μF |
| Output cap ESR | Rc | 20 | mΩ |
| Load resistance | R | 0.5 | Ω |
| Max duty cycle | Dmax | ≤ 0.45 | — |

## Core Reset — Third Winding Method

The reset winding (Nr = Np, i.e. n2 = 1:1) allows the magnetising inductance
to reset during the switch-OFF period by clamping to Vin via a reset diode:

```
Reset time ≥ ton  ⟹  D ≤ Nr/(Nr + Np) = 0.5 for n2=1:1
```

**Transformer volt-second balance:**
```
Vin × ton = Vin × (Nr/Np) × toff_reset
```

With n2 = 1:1: `ton = toff_reset` → maximum D = 0.5 (typically Dmax = 0.45
to leave margin).

## Voltage Conversion Ratio

```
Vout = (Ns/Np) × D × Vin = D × Vin / n1
```

Checking n1 = 6 against the Dmax = 0.45 reset constraint:
```
Required D = n1 × Vout / Vin = 6 × 5 / 48 = 0.625  ← violates Dmax ≤ 0.45!
```

**Use n1 = 4:1** to satisfy the Dmax ≤ 0.45 constraint:
```
Required D = 4 × 5 / 48 = 0.417  ← satisfies Dmax ✓
```

| n1 (Np:Ns) | Required D for Vout=5V, Vin=48V | Satisfies Dmax≤0.45? |
|------------|----------------------------------|----------------------|
| 3:1 | 0.313 | ✓ (but low turns ratio → higher secondary current) |
| 4:1 | 0.417 | ✓ (recommended) |
| 6:1 | 0.625 | ✗ (common textbook value, but violates reset constraint!) |
| 8:1 | 0.833 | ✗ |

*(The parameter table at the top of this document uses n1 = 6 for reference
and to illustrate the constraint violation; the actual simulation must use
n1 = 4 to stay within the reset limit.)*

## Small-Signal Plant

The forward converter's small-signal plant is **identical to the buck**
(referred to the secondary side):

```
Gvd(s) = (Vin/n) × (1 + s × Rc × C) / (1 + s × L/R + s² × L × C)
```

DC gain: `G0 = Vin/n = 48/4 = 12 V/V`
LC pole: `f0 = 1/(2π√LC) = 1/(2π√(10μ × 330μ)) = 8.77 kHz`
ESR zero: `fz = 1/(2π × Rc × C) = 24.1 kHz`

A **Type 3** compensator is appropriate (same as for the buck).
Target fc = 30 kHz (fs/8).

## Circuit Files

| File | Description | Status |
|------|-------------|--------|
| `forward_open_loop.ipes` | Open-loop forward, fixed D | 📝 to be added |
| `forward_closed_loop.ipes` | Closed-loop with Type 3 compensator | 📝 to be added |
| `forward_reset_check.ipes` | Shows magnetising current and core reset | 📝 to be added |

## Building `forward_open_loop.ipes`

1. Place **DC voltage source** (48 V)
2. Add **ideal switch** in series with primary

3. **Transformer (three-winding):**
   - Primary winding (Np) — in series with switch
   - Secondary winding (Ns) — forward-coupled to primary (dot same side)
   - Reset winding (Nr = Np) — connected between Vin and a reset diode
   - Model as **three coupled inductors** or use an ideal transformer + Lm

4. **Secondary side:**
   - Secondary dot node → forward diode D1 (forward current flows)
   - D1 cathode → output inductor L = 10 μH
   - Freewheeling diode D2: anode to GND, cathode to L-output node
   - Output: C = 330 μF (ESR 20 mΩ) ‖ R = 0.5 Ω

5. **Reset path:**
   - Reset winding start → reset diode Dr (cathode to Vin)
   - This allows magnetising energy to return to the source during reset

6. Add **PWM generator**: fs = 250 kHz, D = 0.417

7. Probes: Vout, primary magnetising current, switch Vds

## Expected Results

| Signal | Expected Value |
|--------|----------------|
| Vout | 5 V |
| IL (average) | 10 A |
| ΔIL | 0.5 A p-p |
| Switch Vds stress | 2 × Vin = 96 V (with n2=1:1 reset) |
| Magnetising current resets to zero | Yes (verify in waveform) |

## Exercises

### Exercise 1 — Core Reset Verification

1. Place a current probe on the magnetising inductance
2. Verify the magnetising current returns to zero during toff
3. Increase D to 0.6 (above Dmax for n2=1:1 reset)
4. **Question:** What happens to the magnetising current over successive cycles?
   Why does this lead to transformer saturation?

### Exercise 2 — Shoot-Through Prevention

1. The forward diode D1 and freewheeling diode D2 must never conduct
   simultaneously (they shouldn't — verify with waveforms)
2. **Question:** What circuit element prevents simultaneous conduction?
   Compare with the synchronous buck which requires dead-time management.

### Exercise 3 — Duty Cycle Limit Impact on Regulation

1. Set Vin = 36 V (low input) and maintain Vout = 5 V
2. Required D = 4 × 5/36 = 0.556 — exceeds Dmax = 0.45!
3. **Question:** What must be changed in the design to handle wide input
   range (36–72 V) while maintaining Dmax ≤ 0.45?

### Exercise 4 — Two-Switch Forward

1. Research the two-switch forward topology
2. Identify the advantage over the single-switch design for switch stress
3. **Question:** What is Vds_max for each switch in the two-switch forward?
   (Answer: Vin, not 2×Vin — significant advantage for 400 V bus)

## Key Equations Summary

```
Vout = D × Vin / n1               (CCM, with n1 = Np/Ns)
Dmax = Nr / (Nr + Np)             (reset constraint, n2=1:1 → Dmax=0.5)
Vds_max = Vin × (1 + Np/Nr)      (switch voltage stress)
f0 = 1/(2π√(LC))                 (LC resonance, same as buck)
G0 = Vin/n1                      (DC plant gain, secondary-referred)
```

## Related Examples

- [Flyback DCM (05)](../05_flyback_dcm/) — flyback (isolated buck-boost)
- [Flyback CCM (06)](../06_flyback_ccm/) — flyback with RHPZ
- [Buck VMC (01)](../01_buck_vmc/) — buck topology (same plant as forward)
- [Power Supplies — LLC](../../power_supplies/) — higher-efficiency alternative

## References

1. Basso, C. *Simulating Switching Converters with LTspice*, Ch. 8
2. Chryssis, G. *High-Frequency Switching Power Supplies*, Ch. 4
3. Texas Instruments SLUP075: "Single-Ended Forward Converter Design
   Considerations"

---
*Last updated: 2026-02*
*GeckoCIRCUITS v1.0*
