# Flyback Converter — Continuous Conduction Mode (CCM)

> **Book reference:** Basso, *"Simulating Switching Converters with LTspice"*,
> Chapter 7 — *The Flyback Converter in CCM*.

## Overview

The flyback converter in CCM exhibits a **right-half-plane zero (RHPZ)**
analogous to the boost converter, making control design more challenging.
CCM operation reduces peak currents and is preferred for higher power levels
(30–150 W) and wide-line-range designs.

**Difficulty:** Intermediate → Advanced

**Estimated Time:** 50–60 minutes

## Learning Objectives

- Compare CCM vs DCM flyback waveforms
- Derive and locate the RHPZ for the CCM flyback
- Design a voltage-mode compensator that respects the RHPZ bandwidth limit
- Add an RCD snubber to clamp the leakage inductance spike

## Circuit Parameters

| Parameter | Symbol | Value | Unit |
|-----------|--------|-------|------|
| Input voltage | Vin | 127 | V dc |
| Output voltage | Vout | 19 | V |
| Output power | Pout | 57 | W |
| Switching frequency | fs | 65 | kHz |
| Turns ratio (primary:secondary) | n | 9:1 | — |
| Primary inductance (magnetizing) | Lm | 680 | μH |
| Output capacitor | C | 330 | μF |
| Output cap ESR | Rc | 30 | mΩ |
| Load resistance | R | 6.3 | Ω |
| Duty cycle | D | 0.35 | — |

## CCM Voltage Conversion Ratio

In CCM, the steady-state conversion ratio for the flyback is:

```
Vout/Vin = D / (n × (1 − D))
```

Solving for Vout at Vin = 127 V, D = 0.35, n = 9:
```
Vout = Vin × D / (n × (1 − D)) = 127 × 0.35 / (9 × 0.65) = 7.6 V
```

To achieve Vout = 19 V with n = 9 and Vin = 127 V:
```
D = (n × Vout) / (Vin + n × Vout) = (9 × 19) / (127 + 9 × 19) = 171/298 ≈ 0.574
```

*(The design in the circuit table uses D = 0.35 with a lower output voltage
of 7.6 V, which keeps D well below 0.5 to simplify CMC. For Vout = 19 V
you would need either D ≈ 0.574 with VMC or a lower turns ratio n = 4:1
which gives D = 0.374 for Vout = 19 V. The exercises explore these
trade-offs — use the values that produce your target Vout.)*

For the CCM operating point used in RHPZ and compensator calculations below,
assume: **Vin = 127 V, n = 4:1, D = 0.374, Vout = 19 V, R = 6.3 Ω**.

## RHPZ in CCM Flyback

The CCM flyback has a RHPZ similar to the boost, located at:
```
fRHPZ = R × (1 − D)² / (2π × Lm × n²)
```

Using the corrected operating point (D = 0.374, R = 6.3 Ω, Lm = 680 μH, n = 4):
```
fRHPZ = 6.3 × (0.626)² / (2π × 680μ × 16) ≈ 28.9 kHz
```

**Design impact:** The RHPZ at ~29 kHz allows a crossover of `fc < 5.8 kHz`.
For the original n = 9 case (D ≈ 0.574):
```
fRHPZ = 6.3 × (0.426)² / (2π × 680μ × 81) ≈ 3.3 kHz  (fc < 660 Hz — very restrictive)
```

This comparison shows the strong impact of turns ratio selection on control bandwidth.

## Leakage Inductance & RCD Snubber

Transformer leakage inductance (typically 1–3% of Lm) causes a voltage spike
on the switch when it turns off:
```
Vspike = Vin + n × Vout + ΔV_snubber
```

An RCD snubber (resistor + capacitor + diode) clamps this spike:
- **Cs:** Snubber capacitor (choose: `Cs × Vclamp² = Llk × Ipk²/2`)
- **Rs:** Discharges Cs between cycles
- **Ds:** Clamping diode (fast recovery)

## Circuit Files

| File | Description | Status |
|------|-------------|--------|
| `flyback_ccm_open_loop.ipes` | CCM flyback, no snubber | 📝 to be added |
| `flyback_ccm_snubber.ipes` | CCM flyback with RCD snubber on primary | 📝 to be added |
| `flyback_ccm_closed_loop.ipes` | Closed-loop CCM with low-bandwidth compensator | 📝 to be added |

## Building `flyback_ccm_open_loop.ipes`

Start from the DCM design in [05_flyback_dcm](../05_flyback_dcm/), changing:

1. Reduce R to **6.3 Ω** (heavier load → CCM)
2. Keep Lm = 680 μH, fs = 65 kHz, n = 9:1
3. Adjust D to **0.35** (check that CCM is achieved: inductor current never
   reaches zero)
4. Add leakage inductance **Llk = 7 μH** in series with the primary winding
   (1% of Lm — add this as a separate inductor in series)
5. Observe the voltage spike on the switch when leakage is included

**Adding the RCD snubber (`flyback_ccm_snubber.ipes`):**

1. Add snubber diode Ds from the switch drain to snubber capacitor Cs
2. Cs = 4.7 nF, Vclamp ≈ Vin + n × Vout + 50 V margin
3. Add discharge resistor Rs = Vclamp² / (Pspike = Llk × Ipk² × fs / 2)
4. Observe reduction in Vds spike

## Expected Results

### Without Snubber

| Signal | Value |
|--------|-------|
| Vout | ~19 V |
| Vds spike | Vin + n×Vout + ~200 V spike = severe overstress |
| Primary peak current | ~3 A |

### With RCD Snubber

| Signal | Value |
|--------|-------|
| Vds spike | Clamped to Vin + n×Vout + 50 V ≈ 298 V |
| Snubber loss | Llk × Ipk² × fs / 2 ≈ 0.6 W |
| Switch | 500 V rated MOSFET with adequate margin |

## Exercises

### Exercise 1 — CCM vs DCM Boundary

1. Open `flyback_ccm_open_loop.ipes`
2. Gradually increase R from 6.3 Ω to 20 Ω
3. **Question:** At what R does DCM begin (primary current reaches zero)?
   Verify using the CCM/DCM boundary condition:
   `Lm_crit = (R × D × (1−D)²) / (2 × fs × n²)`

### Exercise 2 — Leakage Spike Energy

1. Compare Vds with and without the leakage inductor (Llk = 7 μH)
2. Estimate the peak spike voltage from the waveform
3. Calculate energy in the spike: `E = ½ × Llk × Ipk²`
4. **Question:** If fs doubles, does the spike energy increase?
   Does the snubber dissipation increase?

### Exercise 3 — Verify RHPZ Bandwidth Limit

1. Open `flyback_ccm_closed_loop.ipes`
2. Set fc = 5 kHz (above RHPZ/5 = 1.2 kHz) and run a load step
3. **Question:** Is the converter stable? Now reduce fc to 1 kHz and repeat.

### Exercise 4 — Optocoupler Compensation

Many offline flybacks use an **optocoupler** + **TL431** shunt regulator
for isolated feedback. Sketch the signal path and identify the poles and
zeros introduced by:
- TL431 internal amplifier (~1 MHz bandwidth)
- Optocoupler current transfer ratio (CTR) and bandwidth
- Compensation network around the TL431

## Key Equations Summary

```
Vout = Vin × D / (n × (1 − D))      (CCM steady state)
fRHPZ = R(1−D)² / (2π × Lm × n²)   (CCM flyback RHPZ)
Vds_max = Vin + n×Vout + Vspike      (switch stress with leakage)
E_snubber = Llk × Ipk² × fs / 2     (snubber dissipation)
```

## Related Examples

- [Flyback DCM (05)](../05_flyback_dcm/) — easier DCM case (no RHPZ)
- [Forward Converter (07)](../07_forward_converter/) — unidirectional isolated
- [Boost VMC (03)](../03_boost_vmc/) — RHPZ analogy

## References

1. Basso, C. *Simulating Switching Converters with LTspice*, Ch. 7
2. Andreycak, B. "Active Clamp and Reset Technique Enhances Forward Converter
   Performance", Unitrode Application Note U-128
3. Texas Instruments SLUA122: "Designing an Optocoupler Isolated Feedback for
   Flyback Converters"

---
*Last updated: 2026-02*
*GeckoCIRCUITS v1.0*
