# PFC Boost Converter — Average Current Mode Control

> **Book reference:** Basso, *"Simulating Switching Converters with LTspice"*,
> Chapter 9 — *Power Factor Correction with the Boost Converter*.

## Overview

A Power Factor Correction (PFC) pre-converter shapes the AC line current
to follow the sinusoidal line voltage, achieving near-unity power factor
(PF ≈ 0.99+) and low total harmonic distortion (THD < 5%). The boost
topology operating in Continuous Conduction Mode (CCM) with **average current
mode control** is the dominant approach for universal-line PFC above 250 W.

**Difficulty:** Advanced

**Estimated Time:** 60–90 minutes

## Learning Objectives

- Understand the two-loop PFC control architecture (voltage loop + current loop)
- Implement average current mode control for sinusoidal current shaping
- Verify power factor and THD from simulation results
- Observe the effect of the voltage loop bandwidth on voltage/current distortion

## System Overview

```
             PFC Pre-Converter
AC Line ──► [EMI Filter] ──► [Bridge Rect] ──► [Boost] ──► DC Bus ──► Load
                                                  ↑
                                          [Two-Loop Controller]
                                          Voltage loop (slow, < 20 Hz)
                                          Current loop (fast, ~ 5 kHz)
```

## Circuit Parameters

| Parameter | Symbol | Value | Unit |
|-----------|--------|-------|------|
| Input AC voltage | Vac | 230 | Vrms |
| Input AC frequency | fline | 50 | Hz |
| Input voltage range | Vac | 85–265 | Vrms |
| DC bus output voltage | Vbus | 400 | V |
| Output power | Pout | 300 | W |
| Switching frequency | fs | 100 | kHz |
| Boost inductor | L | 500 | μH |
| Bus capacitor | Cbus | 470 | μF |
| Bus cap ESR | Rc | 20 | mΩ |
| Line frequency | fline | 50 | Hz |

## Two-Loop Control Architecture

### Outer Voltage Loop

The **voltage loop** regulates the DC bus voltage at 400 V. Its bandwidth
must be **much lower than 2 × fline** (100 Hz for 50 Hz mains) to prevent
the controller from reacting to the double-line-frequency ripple on Vbus:

```
fv < 2 × fline / 10 = 10 Hz  (typical bandwidth: 10–20 Hz)
```

The voltage error signal `Vea` is multiplied with the rectified line voltage
`|Vac|` to create the current reference `Iref`:
```
Iref(t) = Vea × |sin(2π × fline × t)| × Gff
```

Where `Gff` is a feed-forward gain that pre-compensates for input voltage
variation.

### Inner Current Loop

The **current loop** forces the boost inductor current to track `Iref(t)`
on a cycle-by-cycle basis. Average current mode control integrates the
current error over each switching cycle:

```
fc_current = 5–10 kHz  (well above fline, well below fs/10)
```

The inner loop uses a **Type 2 compensator** tuned for the boost current loop.

## Boost Converter Equations (PFC)

At the peak of the AC line:
```
Vpk = Vac × √2 = 230 × 1.414 = 325 V  (peak)
D_max = 1 − Vpk/Vbus = 1 − 325/400 = 0.188  (near zero crossing: D → 1)
```

Boost inductor sizing for strict CCM at full load, worst case near zero crossing:
```
L_ccm > Vpk × D_worst / (ΔIL_max × fs)
```

For ΔIL = 20% of peak inductor current:
```
Ipk = Pout×√2 / (η × Vac) = 300×1.414/(0.97×230) = 1.90 A  (peak inductor current)
L_ccm > 325 × 0.188 / (0.2 × 1.90 × 100k) ≈ 1.6 mH  (for full CCM)
```

The design here uses **L = 500 μH**, which is intentionally below L_ccm.
Near the AC zero crossing (where Vpk × D → 0), the converter naturally enters
DCM. This *boundary/CrCM* design reduces inductor size and improves efficiency
at light load, at the cost of higher peak currents near the zero crossing.
This is a well-accepted practical trade-off in commercial PFC designs.

## Power Factor and THD Measurement

After steady state is reached (several line cycles), measure:

```
PF = P / (Vrms × Irms) = active power / apparent power

THD = √(I₂² + I₃² + I₅² + ...) / I₁
```

In GeckoCIRCUITS:
1. Use the **Fourier analysis** block on the input current
2. Read I1 (fundamental), I3, I5 harmonics
3. Calculate THD from harmonic magnitudes

Target values: PF > 0.99, THD < 5%

## Circuit Files

| File | Description | Status |
|------|-------------|--------|
| `pfc_boost_open_loop.ipes` | Boost with fixed D (no PFC control) | 📝 to be added |
| `pfc_boost_closed_loop.ipes` | Full two-loop average CMC PFC | 📝 to be added |
| `pfc_boost_wide_range.ipes` | 85–265 Vrms universal input | 📝 to be added |

## Building `pfc_boost_closed_loop.ipes`

### Power Stage

1. **Input rectifier:** Four diodes in full-bridge configuration
   - AC source: 230 Vrms, 50 Hz sinusoidal voltage source
   - Bridge output is `|Vac(t)|`
2. **Boost inductor:** L = 500 μH (current probe here)
3. **Boost switch:** Ideal MOSFET, drain at L-diode node, source to GND
4. **Boost diode:** Anode at L output, cathode to Cbus
5. **Bus capacitor:** Cbus = 470 μF ‖ Rload = Vbus²/Pout = 400²/300 = 533 Ω

### Control (two-loop)

6. **Voltage error amplifier:**
   - Vbus sense → resistor divider → compare with Vref = 2.5 V
   - Type 2 compensator: fz = 5 Hz, fp = 30 Hz, fc = 10 Hz
   - Output = `Vea`

7. **Multiplier / current reference:**
   - `Iref = Vea × |Vac(t)| / Vac_rms²`  (use GeckoCIRCUITS MULTIPLY block)
   - This creates a sinusoidal current reference in phase with `|Vac|`

8. **Current error amplifier:**
   - Sensed inductor current (via current probe × Rcs factor) − Iref
   - Type 2 compensator: fz = 1 kHz, fp = 30 kHz, fc = 5 kHz
   - Output feeds the PWM comparator (duty cycle command)

9. **PWM block:** fs = 100 kHz

### Simulation Settings

- **Time:** 200 ms (10 line cycles to reach steady state)
- **Timestep:** 100 ns
- **Solver:** Trapezoidal

## Expected Results

| Signal | Target |
|--------|--------|
| Vbus (steady state) | 400 V ± 2% |
| Vbus ripple (100 Hz) | < 10 V p-p (2.5%) |
| Input current shape | Sinusoidal (in phase with Vac) |
| Power factor | > 0.99 |
| Input current THD | < 5% |
| Efficiency | > 96% |

## Exercises

### Exercise 1 — Single-Loop vs Dual-Loop Control

1. Run `pfc_boost_open_loop.ipes` (sinusoidal input, fixed duty cycle)
2. Observe the input current waveform — it is **not** sinusoidal
3. Enable the inner current loop only (no voltage loop)
4. **Question:** Does the current become sinusoidal with only the inner loop?
   What is still missing?

### Exercise 2 — Voltage Loop Bandwidth Effect

1. In `pfc_boost_closed_loop.ipes`, increase the voltage loop bandwidth to
   50 Hz (fp = 100 Hz, fz = 25 Hz)
2. **Question:** What happens to the input current waveform?
   Observe the 2nd harmonic (100 Hz) distortion in the current.

### Exercise 3 — Universal Input Range

1. Open `pfc_boost_wide_range.ipes`
2. Run at Vac = 85 Vrms, then 230 Vrms, then 265 Vrms
3. **Question:** Does the duty cycle change as expected? Is the power factor
   maintained across the entire range? What is the peak switch current at
   Vac = 85 Vrms?

### Exercise 4 — Holdup Time Calculation

1. After steady state (Vbus = 400 V), disconnect the AC source at t = t0
2. How long does Vbus remain above 360 V (10% droop)?
3. **Question:** For a 20 ms holdup time with Pout = 300 W, what Cbus is needed?
   `Cbus = 2 × Pout × tholdup / (Vbus² − Vmin²)`

## Key Equations Summary

```
Vbus = 400 V                        (regulated DC bus)
Vpk = Vac × √2                     (peak AC voltage)
Ipk = Pout × √2 / (η × Vac)       (peak inductor current)
D(t) = 1 − |Vac(t)| / Vbus        (instantaneous duty cycle)
fv < 2 × fline / 10               (voltage loop bandwidth limit)
Cbus = 2×Pout×tholdup/(Vbus²-Vmin²) (holdup capacitor sizing)
```

## Related Examples

- [Boost VMC (03)](../03_boost_vmc/) — basic boost (DC operation)
- [Boost CMC (04)](../04_boost_cmc/) — current mode boost
- [Tutorial 302 — PFC Basics](../../../tutorials/3xx_acdc_rectifiers/302_pfc_basics/)
- [Education Examples — boostPFC](../../../application_examples/education/education_www.ipes.ethz.ch/boostPFC.ipes)

## References

1. Basso, C. *Simulating Switching Converters with LTspice*, Ch. 9
2. Mohan, N. et al. *Power Electronics*, Ch. 17 (Harmonic standards and PFC)
3. Orfanidis, S.J. "IEC 61000-3-2: Limits for harmonic current emissions"
4. Texas Instruments SLUP325: "Average Current Mode Control"

---
*Last updated: 2026-02*
*GeckoCIRCUITS v1.0*
