"""Rank semiconductor devices by estimated loss and catalog price."""

import argparse
from contextlib import ExitStack
from dataclasses import dataclass
import math
from pathlib import Path

from .catalog import Device, load_catalog, load_spec
from .curves import VendorCurves, detailed_losses, loss_client


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
    if device.qg_c is None:
        return conduction, None
    transition = device.qg_c / spec.gate_drive_current_a
    switching = 0.5 * spec.vin_max_v * current * transition * spec.fsw_hz
    return conduction, switching


class TemperatureEstimateError(ValueError):
    """The temperature iteration did not converge."""


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
    raise TemperatureEstimateError("The loss estimate did not converge to a junction temperature.")


def estimate_at(api, spec, temperature, curves, static):
    if not any(curves.values()):
        return static
    detailed = detailed_losses(api, spec, temperature, curves)
    return tuple(fallback if value is None else value
                 for value, fallback in zip(detailed, static, strict=True))


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
        loss_survivors = 0
        for part in survivors:
            static = fallback(part, spec)
            curves = (VendorCurves(part.curve_file, spec.vin_max_v).at_current(spec.iout_a)
                      if part.curve_file else {"conduction": [], "switching": []})
            if static[1] is None and not curves["switching"]:
                rejected.append((part.name, "loss data (no switching curve at this current)"))
                continue
            loss_survivors += 1
            sources = ", ".join(
                f"{kind} from {'vendor curves' if curves[kind] else fallback_method}"
                for kind, fallback_method in (
                    ("conduction", "the static catalog value"),
                    ("switching", "the gate charge estimate"),
                )
            )
            if api is None and any(curves.values()):
                api = stack.enter_context(loss_client())
            if spec.ambient_c >= part.tj_max_c:
                rejected.append((part.name, f"thermal limit (ambient reaches or exceeds {part.tj_max_c:g} deg C)"))
                continue
            try:
                junction, conduction, switching = temperature_and_losses(
                    part, spec, lambda temperature: estimate_at(api, spec, temperature, curves, static),
                )
            except TemperatureEstimateError as error:
                rejected.append((part.name, f"thermal limit ({error})"))
                continue
            if junction >= part.tj_max_c:
                rejected.append((part.name, f"thermal limit ({junction:.2f} deg C reaches or exceeds {part.tj_max_c:g} deg C)"))
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
            candidates.append(Candidate(part, conduction, switching, junction, sources, binding))
        if survivors and not candidates:
            if thermal_survivors:
                eliminated_by = "efficiency budget"
            else:
                eliminated_by = "thermal limit" if loss_survivors else "loss data"

    def sort_key(item):
        price = item.device.price
        return item.total_w, math.inf if price is None else price, item.device.name

    candidates.sort(key=sort_key)
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
        price = "unavailable" if device.price is None else f"{device.price:.2f}"
        print(f"{position}. {device.name} ({device.package}). Estimated loss {item.total_w:.3f} W "
              f"from {item.conduction_w:.3f} W conduction and {item.switching_w:.3f} W switching. "
              f"The estimate uses {item.method}. Estimated junction temperature "
              f"{item.junction_c:.2f} deg C with a {device.tj_max_c:g} deg C limit. "
              f"Closest constraint is {item.binding}. Catalog price {price}.")
    if not candidates:
        reason = f"{eliminated_by} eliminated the last candidates" if eliminated_by else "empty catalog"
        print(f"No candidates. {reason}.")
    if rejected:
        print("\nExcluded devices")
        for name, reason in rejected:
            print(f"- {name}. {reason}.")


if __name__ == "__main__":
    main()
