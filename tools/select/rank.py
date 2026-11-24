"""Rank semiconductor devices by estimated loss and catalog price."""

import argparse
from contextlib import ExitStack
from dataclasses import dataclass
from pathlib import Path

from .catalog import Device, load_catalog, load_spec
from .curves import VendorCurves, loss_client


VOLTAGE_FRACTION = 0.80
CURRENT_FRACTION = 0.70


@dataclass(frozen=True)
class Candidate:
    device: Device
    conduction_w: float
    switching_w: float
    junction_c: float
    method: str
    binding: str

    @property
    def total_w(self):
        return self.conduction_w + self.switching_w


def fallback(device, spec):
    current = spec.iout_a
    drop = device.vce_sat_v if device.vce_sat_v is not None else current * device.rds_on_ohm
    conduction = spec.conduction_fraction * current * drop
    transition = device.qg_c / spec.gate_drive_current_a
    switching = 0.5 * spec.vin_max_v * current * transition * spec.fsw_hz
    return conduction, switching


def temperature_and_losses(device, spec, estimate):
    junction = spec.ambient_c
    for _ in range(30):
        conduction, switching = estimate(junction)
        result = spec.ambient_c + device.rth_c_per_w * (conduction + switching)
        if abs(result - junction) < 0.01:
            return result, conduction, switching
        if result >= device.tj_max_c:
            return result, conduction, switching
        junction = result
    raise ValueError("The loss estimate did not converge to a junction temperature.")


def rank(spec, devices):
    survivors = list(devices)
    rejected = []
    eliminated_by = None
    for constraint, asks, limit in (
        ("voltage derating", spec.vin_max_v, lambda part: part.voltage_rating_v * VOLTAGE_FRACTION),
        ("current derating", spec.iout_a, lambda part: part.current_rating_a * CURRENT_FRACTION),
    ):
        if not survivors:
            break
        previous = survivors
        survivors = []
        for part in previous:
            if asks > limit(part):
                rejected.append((part.name, f"{constraint} ({asks:g} exceeds {limit(part):g})"))
            else:
                survivors.append(part)
        if not survivors:
            eliminated_by = constraint

    candidates = []
    with ExitStack() as stack:
        api = None
        thermal_survivors = 0
        for part in survivors:
            estimate = lambda _temperature, device=part: fallback(device, spec)
            method = "static catalog values and the gate charge estimate"
            if part.curve_file:
                curves = VendorCurves(part.curve_file, spec.vin_max_v)
                if curves.covers_current(spec.iout_a):
                    if api is None:
                        api = stack.enter_context(loss_client())
                    estimate = lambda junction: curves.estimate(api, spec, junction)
                    method = "vendor loss curves"
                else:
                    method += " because the current is outside the vendor curves"
            junction, conduction, switching = temperature_and_losses(part, spec, estimate)
            if junction >= part.tj_max_c:
                rejected.append((part.name, f"thermal limit ({junction:.2f} °C ≥ {part.tj_max_c:g} °C)"))
                continue
            thermal_survivors += 1
            total = conduction + switching
            if total > spec.loss_budget_w:
                rejected.append((part.name,
                                 f"efficiency budget ({total:.3f} W > {spec.loss_budget_w:.3f} W)"))
                continue
            margins = {
                "voltage derating": 1 - spec.vin_max_v / (VOLTAGE_FRACTION * part.voltage_rating_v),
                "current derating": 1 - spec.iout_a / (CURRENT_FRACTION * part.current_rating_a),
                "thermal limit": (part.tj_max_c - junction) / (part.tj_max_c - spec.ambient_c),
                "efficiency budget": 1 - total / spec.loss_budget_w,
            }
            binding = min(margins, key=margins.get)
            candidates.append(Candidate(part, conduction, switching, junction, method, binding))
        if survivors and not candidates:
            eliminated_by = "efficiency budget" if thermal_survivors else "thermal limit"

    candidates.sort(key=lambda item: (item.total_w, item.device.price, item.device.name))
    return candidates, rejected, eliminated_by


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("spec", type=Path)
    parser.add_argument("catalog", type=Path)
    args = parser.parse_args()
    spec = load_spec(args.spec)
    candidates, rejected, eliminated_by = rank(spec, load_catalog(args.catalog))
    for position, item in enumerate(candidates, 1):
        device = item.device
        print(f"{position}. {device.name} ({device.package}). Estimated loss {item.total_w:.3f} W "
              f"from {item.conduction_w:.3f} W conduction and {item.switching_w:.3f} W switching. "
              f"The calculation uses {item.method}. Estimated junction temperature "
              f"{item.junction_c:.2f} °C with a {device.tj_max_c:g} °C limit. "
              f"Closest constraint is {item.binding}. Catalog price {device.price:.2f}.")
    if not candidates:
        reason = f"{eliminated_by} eliminated the last candidates" if eliminated_by else "empty catalog"
        print(f"No candidates. {reason}.")
    if rejected:
        print("\nExcluded devices")
        for name, reason in rejected:
            print(f"- {name}. {reason}.")


if __name__ == "__main__":
    main()
