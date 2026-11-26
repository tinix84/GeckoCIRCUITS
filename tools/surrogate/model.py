"""Build numerical inputs and predictions for the buck surrogate."""

import numpy as np


FEATURES = ("Vin", "L", "C", "fsw", "D", "Rload")
OUTPUTS = ("vout", "ripple", "conduction_loss", "switching_loss")


def design_terms(inputs, grid_step):
    vin, inductance, capacitance, frequency, duty, load = np.asarray(inputs, dtype=float).T
    if not np.all(np.isfinite(inputs)) or np.any(np.asarray(inputs) <= 0) or np.any(duty >= 1):
        raise ValueError("Use positive finite inputs and a duty cycle below one.")
    # The rectangular source uses a triangle stepped on the fixed simulation grid.
    half_steps = np.ceil(0.5 / (frequency * grid_step))
    on_steps = 2 * np.ceil(duty / (2 * frequency * grid_step)) - 1
    effective_duty = np.clip(on_steps / (2 * half_steps), 0, 1)
    effective_frequency = 1 / (2 * grid_step * half_steps)
    dc = (effective_duty * (vin + 0.6) - 0.6) / (1 + 0.01 / load)
    delta_i = vin * effective_duty * (1 - effective_duty) / (inductance * effective_frequency)
    delta_v = delta_i / (8 * effective_frequency * capacitance)
    power = 0.01 * (np.square(dc / load) + np.square(delta_i) / 12) * duty
    switch_power = 45e-6 * (vin / 400) * (dc / load + delta_i / 2) * frequency
    features = np.column_stack((
        inputs, effective_duty, effective_frequency, dc, delta_i, delta_v, power, switch_power,
    ))
    return features


def predict(model, inputs):
    features = design_terms(inputs, model["grid_step"])
    return model["estimator"].predict(features)
