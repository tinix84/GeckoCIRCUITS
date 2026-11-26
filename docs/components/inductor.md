# Inductor

An inductor stores energy in its magnetic field.

## Terminals

Two electrical terminals. Current is positive from the first terminal to the second.

## Parameters

| Parameter | Unit | Function |
| --- | --- | --- |
| Inductance | H | Sets the ratio of voltage to the rate of current change. |
| Initial current | A | Sets the current at the start of the simulation. |
| Non-Linear Behavior | — | Uses the inductance characteristic in the desktop simulator. |
| Inductance characteristic | A, H | Gives inductance at each specified current magnitude. |

## Model

Use this model for a winding without magnetic coupling. The default model uses constant inductance.
If **Non-Linear Behavior** is enabled, the desktop simulator uses the current magnitude to find
the inductance on the characteristic.
