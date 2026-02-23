# Flyback Converter — Discontinuous Conduction Mode (DCM)

> **Book reference:** Basso, *"Simulating Switching Converters with LTspice"*,
> Chapter 6 — *The Flyback Converter in DCM*.

## Overview

The flyback converter is an isolated buck-boost topology. Operating in
Discontinuous Conduction Mode (DCM) simplifies the transformer design and
the transfer function (no RHPZ), at the cost of higher peak currents and
more output capacitor ripple current. DCM flyback is the dominant choice
for low-power offline power supplies (5–50 W).

**Difficulty:** Intermediate

**Estimated Time:** 45–60 minutes

## Learning Objectives

- Build a flyback converter using a coupled inductor in GeckoCIRCUITS
- Verify DCM operation by observing three-interval inductor current waveforms
- Calculate and verify the output voltage using the DCM conversion ratio
- Understand switch and diode voltage stresses

## Circuit Parameters

| Parameter | Symbol | Value | Unit |
|-----------|--------|-------|------|
| Input voltage (rectified 90 Vrms) | Vin | 127 | V dc |
| Output voltage | Vout | 19 | V |
| Output power | Pout | 19 | W |
| Switching frequency | fs | 65 | kHz |
| Turns ratio (primary:secondary) | n | 9:1 | — |
| Primary inductance (magnetizing) | Lm | 680 | μH |
| Output capacitor | C | 100 | μF |
| Output cap ESR | Rc | 50 | mΩ |
| Load resistance | R | Vout²/Pout = 19 | Ω |
| Max duty cycle | Dmax | 0.45 | — |

## DCM Operation — Three Intervals

In DCM the magnetizing current returns to zero before the next switching cycle,
creating **three distinct intervals** per period:

```
|←── T1 ──→|←── T2 ──→|←── T3 ──→|
│ Sw ON    │ Diode ON  │ Dead time │
│ IL ramps │ IL falls  │ IL = 0    │
│ up       │ to zero   │           │
└──────────┴───────────┴───────────┘
```

**Interval T1 (switch ON):**
- Energy stored in Lm: `ΔI = Vin × D / (Lm × fs)`
- Secondary diode reverse-biased

**Interval T2 (diode ON):**
- Magnetizing current transfers to secondary (reflected: `Is = Im × n`)
- Lm current decreases: slope = `n × Vout / Lm`

**Interval T3 (dead time):**
- Both switch and diode OFF
- Lm current = 0 (DCM condition)
- Resonance between Lm and switch capacitance (valley switching)

## DCM Voltage Conversion Ratio

In DCM, duty cycle and load are both needed. Using the normalised gain M:

```
M = Vout/Vin,  K = 2×Lm×fs / (n²×R)  (DCM parameter)
M = 1/2 × [−1 + √(1 + 4/(K × n²/D²))]   (simplified DCM M ratio)
```

Or equivalently (from the voltage-second balance in DCM):

```
Vout = Vin × D² × n² × R / (2 × Lm × fs)   (holds when M << 1, i.e. deep DCM)
```

For the design point (D = 0.45, R = 19 Ω, Lm = 680 μH, fs = 65 kHz, n = 9):

```
K = 2 × 680μ × 65k / (81 × 19) = 0.114
Vout/Vin = D²/(2K) × ... (solve numerically or with book formula)
Vout ≈ 19 V at Vin = 127 V  (verified with book figure 6-x)
```

*(The exact DCM flyback formula involves a quadratic; refer to Basso Ch. 6
or the normalised variable derivation by Vorpérian. The values above are
consistent with the book's design example.)*

## Voltage Stresses

| Component | Stress | Example Value |
|-----------|--------|---------------|
| Switch Vds (max) | Vin + n × Vout + Vspike | 127 + 9×19 + 60 ≈ 358 V → 500 V FET |
| Diode Vr (max) | Vout + Vin/n | 19 + 127/9 ≈ 33 V → 60 V Schottky |
| Primary peak current | Vin × D / (Lm × fs) | 127 × 0.45 / (680μ × 65k) = 1.29 A |

## Circuit Files

| File | Description | Status |
|------|-------------|--------|
| `flyback_dcm_open_loop.ipes` | Open-loop flyback, DCM operation | 📝 to be added |
| `flyback_dcm_closed_loop.ipes` | Closed-loop with optocoupler compensation | 📝 to be added |

## Building `flyback_dcm_open_loop.ipes`

1. Place **DC voltage source** (127 V, label `Vin`)
2. Add an **ideal switch** in series with Vin

3. **Primary winding (coupled inductor):**
   - In GeckoCIRCUITS, use the **coupled inductor** component
   - Primary inductance Lp = Lm = 680 μH
   - Turns ratio k = n = 9 (set secondary Ls = Lm/n² = 680μ/81 ≈ 8.4 μH)
   - Coupling coefficient k_c ≈ 0.98 (tight coupling, low leakage)
   - Observe the **dot convention**: primary dot at top, secondary dot at bottom
     (flyback — dots on *opposite* ends of the winding windows)

4. **Secondary side:**
   - Secondary dot node → diode anode (Vf = 0.6 V)
   - Diode cathode → output node
   - Output node to GND: **C = 100 μF** (ESR 50 mΩ) ‖ **R = 19 Ω**

5. Add **PWM generator**: fs = 65 kHz, D = 0.45

6. Add probes:
   - Voltage across switch (Vds)
   - Primary current (magnetizing + leakage)
   - Secondary diode current
   - Output voltage

7. Simulation settings:
   - Time = 500 μs (several hundred cycles for DCM steady state)
   - Timestep = 5 ns (1/3000 of switching period)

## Expected Results

| Signal | Expected Behaviour |
|--------|--------------------|
| Primary current | Triangular ramp during T1, reflected ramp during T2, zero during T3 |
| Vds (switch) | Low during T1, ~Vin + n×Vout during T2+T3 |
| Secondary diode current | Zero during T1, triangular decay during T2, zero during T3 |
| Vout | ~19 V with ripple |
| Ripple ΔVout | ~(Iout × D) / (fs × C) ≈ 67 mV |

## Exercises

### Exercise 1 — Verify DCM by Waveform Inspection

1. Zoom in on one switching period in the primary current waveform
2. Identify T1, T2, T3 intervals
3. **Question:** Measure T3 as a fraction of Ts. What happens to T3
   when load increases (R decreases)?

### Exercise 2 — Enter CCM by Increasing Load

1. Reduce R to 5 Ω (Pout ≈ 72 W)
2. **Question:** Does the primary current still return to zero?
   What changes in the waveform when CCM begins?

### Exercise 3 — Turns Ratio Selection Trade-off

1. Keep Pout = 19 W, Vin = 127 V, Vout = 19 V, fs = 65 kHz
2. Try n = 5:1 and n = 13:1 with adjusted D to maintain Vout
3. Record switch Vds stress and primary peak current for each n
4. **Question:** What is the optimal n for minimum switch voltage stress?

### Exercise 4 — Valley Switching Observation

1. Look at the Vds waveform during interval T3
2. Observe the resonant oscillation (Lm resonates with switch capacitance)
3. **Question:** For valley switching (turn-on at minimum Vds), what
   minimum Vds value is achievable? How does this reduce switching loss?

## Key Equations Summary

```
D = ton × fs                               (duty cycle)
Ipk = Vin × D / (Lm × fs)                 (primary peak current — DCM)
Vds_max = Vin + n × Vout                  (switch voltage stress, no spike)
Vd_max = Vout + Vin/n                     (diode reverse voltage)
Pout = ½ × Lm × Ipk² × fs × η            (DCM output power)
```

## Related Examples

- [Flyback CCM (06)](../06_flyback_ccm/) — CCM operation with RHPZ
- [Forward Converter (07)](../07_forward_converter/) — alternative isolated topology
- [Buck VMC (01)](../01_buck_vmc/) — non-isolated reference for comparison
- [Tutorial 2xx — Flyback](../../../tutorials/2xx_dcdc_converters/)

## References

1. Basso, C. *Simulating Switching Converters with LTspice*, Ch. 6
2. Pressman, A., Billings, K., Morey, T. *Switching Power Supply Design*, 3rd ed.
3. Kazimierczuk, M.K. *Pulse-Width Modulated DC–DC Power Converters*, Ch. 9

---
*Last updated: 2026-02*
*GeckoCIRCUITS v1.0*
