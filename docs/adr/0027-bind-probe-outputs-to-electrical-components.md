# ADR-0027 — Bind Probe outputs to electrical components

Date: 2026-11-25

## Decision

Use an ordered list of electrical component references to bind the scalar outputs of a Probe block.

## Context

A schematic needs one meter block per measured quantity. A Probe can collect several quantities without adding electrical terminals. The core and legacy backends must give each output the same meaning. Its settings must survive a circuit save and load.

## Options

| Option | Pros | Cons | Effort |
| --- | --- | --- | --- |
| Bind each output by name at preparation | Uses existing circuit names and preserves output order | Requires rename and copy rules | Medium |
| Insert hidden meter blocks | Reuses meter wiring | Adds implicit control producers and does not represent diode state | Medium |
| Store internal solver indices | Reads values directly | Indices can change when the schematic changes | Low |

## Rationale

The editor shows a comma-separated list such as `L.1.i, C.1.v, D.1.state`. Split each reference at its last dot so names can contain dots. In editor text, use a backslash before a comma or a backslash in a component name.
The saved Probe encodes references that contain a slash so each remains one entry in the saved list.
Each backend resolves a unique electrical name when it prepares the circuit. The existing meter sign convention sets the direction of current and voltage.

| Quantity | Supported components | Value |
| --- | --- | --- |
| `.i` | Resistor, inductor, capacitor, current source, diode, ideal switch, IGBT, thyristor | Branch current in amperes |
| `.v` | The same components and voltage sources | First terminal potential minus second terminal potential, in volts |
| `.state` | Diode with a linear on-state model | 1 in conduction and 0 in blocking, from the committed resistance state |

## Consequences

An invalid name or quantity gives a preparation diagnostic. An empty list has no output terminals. A circuit rename follows the target through undo and redo. A copy follows the copied target when the selection contains both blocks. A copy within the same open circuit keeps other targets. A paste into a different circuit clears references whose targets are absent from the copied selection.

Loss signals stay in the scope of [issue #95](https://github.com/tinix84/GeckoCIRCUITS/issues/95). Drag-and-drop is separate from the text interface.

## Status

Accepted for [issue #38](https://github.com/tinix84/GeckoCIRCUITS/issues/38) on 2026-11-25.
