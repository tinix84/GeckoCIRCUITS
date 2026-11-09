# Basso — Switching Converter Examples

Circuit examples inspired by Christoph Basso's
*"Simulating Switching Converters with LTspice"* (1st edition, 2023).

All circuits have been re-created as GeckoCIRCUITS `.ipes` files and follow
the same component values, operating points, and design philosophy as the
book so that readers can cross-reference simulation results.

## Book Reference

| ISBN | Title | Author |
|------|-------|--------|
| 978-1-119-46566-7 | Simulating Switching Converters with LTspice | Christoph P. Basso |

Free resources from the author: <https://powersimtof.com/>

## Example Index

| # | Folder | Topology | Control | Difficulty |
|---|--------|----------|---------|------------|
| 1 | [01_buck_vmc](01_buck_vmc/) | Buck | Voltage Mode (VMC) | Beginner |
| 2 | [02_buck_cmc](02_buck_cmc/) | Buck | Peak Current Mode (CMC) | Intermediate |
| 3 | [03_boost_vmc](03_boost_vmc/) | Boost | Voltage Mode (VMC) | Intermediate |
| 4 | [04_boost_cmc](04_boost_cmc/) | Boost | Peak Current Mode (CMC) | Intermediate |
| 5 | [05_flyback_dcm](05_flyback_dcm/) | Flyback | Open-loop DCM | Intermediate |
| 6 | [06_flyback_ccm](06_flyback_ccm/) | Flyback | Open-loop CCM | Intermediate |
| 7 | [07_forward_converter](07_forward_converter/) | Forward | Voltage Mode (VMC) | Advanced |
| 8 | [08_pfc_boost](08_pfc_boost/) | PFC Boost | Average Current Mode | Advanced |
| 9 | [09_compensators](09_compensators/) | Type 1/2/3 Op-Amp | — | Intermediate |

## How the Examples Are Organised

Each sub-folder follows the same pattern:

```
NN_topology_name/
├── README.md               ← theory, equations, exercises (this book's notation)
├── topology_basic.ipes     ← open-loop circuit (no feedback)
├── topology_closed.ipes    ← complete closed-loop circuit
└── topology_ac.ipes        ← AC sweep / Bode plot variant (where applicable)
```

> **Note on `.ipes` files:** Circuit files marked *"to be added"* must be
> built interactively in GeckoCIRCUITS using the parameter tables in each
> README. Refer to [tutorials/2xx_dcdc_converters](../../tutorials/2xx_dcdc_converters/)
> for a step-by-step guide on assembling your first circuit.

## Learning Path

```
01_buck_vmc → 09_compensators → 02_buck_cmc
     ↓
03_boost_vmc → 04_boost_cmc
     ↓
05_flyback_dcm → 06_flyback_ccm → 07_forward_converter
     ↓
08_pfc_boost
```

## Key Differences from LTspice Originals

| Aspect | LTspice (.asc) | GeckoCIRCUITS (.ipes) |
|--------|---------------|----------------------|
| Switch model | MOSFET (SPICE level 1) | Ideal switch + Rdson |
| Diode model | SPICE behavioral | Ideal diode + Vf |
| Control PWM | Voltage comparator + ramp | Built-in PWM block |
| Magnetics | SPICE mutual inductance | Coupled inductor component |
| AC sweep | `.ac` statement | Built-in Bode analyser |
| Simulation speed | Moderate | Fast (event-driven) |

## Contributing Circuit Files

1. Follow the parameter table in each sub-folder README
2. Use the `.ipes` naming convention shown above
3. Test at the operating point listed in the README
4. Verify steady-state values match the table before committing
5. See [`../_templates/README_example.md`](../_templates/README_example.md)
   for documentation style

## Related Resources

- [Basic Topologies](../basic_topologies/) — non-isolated DC-DC fundamentals
- [Power Supplies](../power_supplies/) — isolated and regulated supplies
- [Tutorials 2xx](../../tutorials/2xx_dcdc_converters/) — step-by-step DC-DC guides
- [Tutorials 3xx](../../tutorials/3xx_acdc_rectifiers/) — PFC and rectifiers

---
*Last updated: 2026-02*
*GeckoCIRCUITS v1.0*
