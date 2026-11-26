# Capacitor

A capacitor stores energy in its electric field.

## Terminals

Two electrical terminals. Voltage is positive at the first terminal relative to the second.

## Parameters

| Parameter | Unit | Function |
| --- | --- | --- |
| Capacitance | F | Sets the ratio of current to the rate of voltage change. |
| Initial voltage | V | Sets the voltage at the start of the simulation. |
| Non-Linear Behavior | — | Uses the capacitance characteristic in the desktop simulator. |
| Capacitance characteristic | V, F | Gives capacitance at each specified voltage magnitude. |

## Model

The default model uses constant capacitance. If **Non-Linear Behavior** is enabled, the desktop simulator
uses the voltage magnitude to find the capacitance on the characteristic.
