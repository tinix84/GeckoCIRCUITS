# Boost Converter — Peak Current Mode Control (CMC)

> **Book reference:** Basso, *"Simulating Switching Converters with LTspice"*,
> Chapter 5 — *The Boost Converter in Current Mode*.

## Overview

Peak current mode control applied to the boost converter retains the
non-minimum-phase challenge (RHPZ still present) but provides a simpler
voltage-loop plant and faster transient response. This example also
demonstrates **average current mode control** as an alternative, which is
commonly used in PFC applications.

**Difficulty:** Intermediate

**Estimated Time:** 40–50 minutes

## Learning Objectives

- Apply CMC to a boost converter and compare plant to VMC boost
- Understand that CMC does **not** eliminate the RHPZ
- Implement average current mode control and compare with peak CMC
- Observe the benefits of CMC for inherent current limiting

## Circuit Parameters

| Parameter | Symbol | Value | Unit |
|-----------|--------|-------|------|
| Input voltage | Vin | 5 | V |
| Output voltage | Vout | 12 | V |
| Switching frequency | fs | 200 | kHz |
| Inductor | L | 22 | μH |
| Output capacitor | C | 220 | μF |
| Capacitor ESR | Rc | 30 | mΩ |
| Current sense resistor | Rcs | 0.05 | Ω |
| Load resistance | R | 14.4 | Ω |

## CMC Plant for Boost (Simplified)

In peak CMC, the inner current loop modifies the boost plant. The effective
output voltage-to-current-reference transfer function is approximately:

```
Gvc(s) ≈ Vout / [(1−D) × Ipk_ref] × (1 − s/ωRHPZ) / (1 + s/ωp)
```

Note: **the RHPZ is still present** at the same frequency as in VMC:
```
fRHPZ = R × (1 − D)² / (2π × L)
```

The dominant output pole is now at:
```
fp ≈ (1−D)² / (2π × R × C)
```

**Design rule remains:** `fc < fRHPZ / 5`

## Circuit Files

| File | Description | Status |
|------|-------------|--------|
| `boost_cmc_peak.ipes` | Peak CMC boost, Type 2 voltage loop | 📝 to be added |
| `boost_cmc_average.ipes` | Average CMC boost (suited for PFC) | 📝 to be added |

## Building `boost_cmc_peak.ipes`

The power stage is identical to [03_boost_vmc](../03_boost_vmc/).
Replace the voltage-mode PWM block with a CMC scheme:

1. Add **current sense resistor** Rcs = 0.05 Ω in series with the switch
   (between switch drain/emitter and GND)
2. Add a **comparator** that compares `Vsense = IL × Rcs` against a peak
   current reference `Vpk_ref`
3. Use an **SR flip-flop** clocked at fs: clock sets, comparator resets
4. Implement a **Type 2 voltage loop** (see [09_compensators](../09_compensators/)):
   - Error amplifier drives `Vpk_ref`
   - fc = 5 kHz (conservative, RHPZ ≈ 45 kHz → fc < 9 kHz)

## Expected Results

| Signal | Peak CMC | Average CMC |
|--------|----------|-------------|
| Vout | 12 V | 12 V |
| IL waveform | Triangular with peak clamp | Sinusoidal envelope (PFC) |
| Transient response | Fast (inner loop) | Moderate |
| Current distortion | Moderate | Very low |

## Exercises

### Exercise 1 — Peak vs Average CMC

1. Run `boost_cmc_peak.ipes` and `boost_cmc_average.ipes` side by side
2. Apply a 25% load step (R: 14.4 Ω → 19.2 Ω) in both
3. **Question:** Which scheme shows faster recovery? Which scheme shows
   lower output voltage undershoot?

### Exercise 2 — Over-Current Protection

1. In `boost_cmc_peak.ipes`, reduce R to 1 Ω (severe overload)
2. Observe the peak inductor current — it should be clamped by the CMC limit
3. **Question:** What is the maximum output current the CMC scheme allows?
   How would you implement a current foldback mechanism?

### Exercise 3 — Confirming RHPZ with CMC

1. Open `boost_cmc_peak.ipes` with AC sweep enabled
2. Locate the RHPZ in the Bode plot of the open-loop plant
3. **Question:** Compare the RHPZ frequency in CMC vs VMC. Are they equal?
   (They should be — CMC does not remove the RHPZ.)

## Key Equations Summary

```
fRHPZ_cmc ≈ R(1−D)² / (2πL)    (same as VMC — RHPZ is topology-inherent)
fp_cmc ≈ (1−D)² / (2πRC)       (output pole with CMC inner loop)
Vpk_ref = Ipk × Rcs             (peak current reference voltage)
Se_min = Sn/2 = (Vin−Vout)/2L  (minimum slope compensation for D>0.5)
```

## Related Examples

- [Boost VMC (03)](../03_boost_vmc/) — voltage mode variant
- [Buck CMC (02)](../02_buck_cmc/) — CMC without RHPZ complication
- [PFC Boost (08)](../08_pfc_boost/) — average CMC for mains rectification

## References

1. Basso, C. *Simulating Switching Converters with LTspice*, Ch. 5
2. Wester, G., Middlebrook, R.D. "Low-Frequency Characterization of Switched
   dc-dc Converters", IEEE Trans. AES, 1973

---
*Last updated: 2026-02*
*GeckoCIRCUITS v1.0*
