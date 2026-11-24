"""Read vendor loss CSV files and use GeckoCIRCUITS curve interpolation."""

from collections import defaultdict
from contextlib import contextmanager
import csv
import math
import os


VENDOR_HEADER = [
    "curve", "tj_c", "current_a", "v_drop_v", "blocking_v", "e_on_j", "e_off_j",
]


@contextmanager
def loss_client():
    from gecko_client import ApiClient, Configuration
    from gecko_client.api.loss_calculation_api import LossCalculationApi

    url = os.getenv("GECKO_API_BASE_URL", "http://localhost:8080/gecko")
    config = Configuration(host=url.rstrip("/"))
    if key := os.getenv("GECKO_API_KEY"):
        config.api_key["ApiKey"] = key
    if token := os.getenv("GECKO_ACCESS_TOKEN"):
        config.access_token = token
    with ApiClient(config) as client:
        yield LossCalculationApi(client)


class VendorCurves:
    def __init__(self, path, voltage):
        from gecko_client.models.loss_curve_data import LossCurveData

        grouped = defaultdict(list)
        with path.open(newline="", encoding="utf-8-sig") as stream:
            reader = csv.DictReader(
                line for line in stream
                if line.strip() and not line.lstrip().startswith("#")
            )
            if reader.fieldnames != VENDOR_HEADER:
                raise ValueError(f"Use the vendor loss CSV header in {path}.")
            for row in reader:
                kind = row["curve"].strip()
                tj, current = float(row["tj_c"]), float(row["current_a"])
                if kind == "conduction":
                    point = (current, float(row["v_drop_v"]))
                elif kind == "switching":
                    ref = float(row["blocking_v"])
                    if not math.isfinite(ref) or ref <= 0:
                        raise ValueError(f"Use a positive blocking voltage in {path}.")
                    point = (current, float(row["e_on_j"]) / ref * voltage,
                             float(row["e_off_j"]) / ref * voltage)
                else:
                    raise ValueError(f"Unknown curve type {kind} in {path}.")
                if not math.isfinite(tj) or tj < -273.15 or not all(map(math.isfinite, point)):
                    raise ValueError(f"Use finite curve samples and a valid temperature in {path}.")
                if kind == "switching" and min(point[1:]) < 0:
                    raise ValueError(f"Use nonnegative switching energies in {path}.")
                if kind == "conduction" and point[0] * point[1] < 0:
                    raise ValueError(f"Use matching current and voltage signs in {path}.")
                grouped[kind, tj].append(point)
        self.conduction = []
        self.switching = []
        self.current_limits = []
        for (kind, temperature), points in sorted(grouped.items()):
            points.sort()
            if len(points) < 2 or len({point[0] for point in points}) != len(points):
                raise ValueError(f"Use at least two distinct current samples per curve in {path}.")
            self.current_limits.append((points[0][0], points[-1][0]))
            if kind == "conduction":
                if any(a[1] >= b[1] for a, b in zip(points, points[1:])):
                    raise ValueError(f"Increase conduction voltage as current increases in {path}.")
                self.conduction.append(LossCurveData(
                    temperature=temperature, data=[list(column) for column in
                                                   zip(*((v, i) for i, v in points))],
                ))
            else:
                self.switching.append(LossCurveData(
                    temperature=temperature, blocking_voltage=voltage,
                    data=[list(column) for column in zip(*points)],
                ))
        if not self.conduction or not self.switching:
            raise ValueError(f"Supply conduction and switching curves in {path}.")

    def covers_current(self, current):
        return all(low <= current <= high for low, high in self.current_limits)

    def estimate(self, api, spec, junction):
        from gecko_client.models.detailed_loss_request import DetailedLossRequest

        result = api.calculate_detailed_loss(DetailedLossRequest(
            current=spec.iout_a, temperature=junction,
            conduction_curves=self.conduction, switching_curves=self.switching,
        ), _request_timeout=20.0)
        losses = (
            result.conduction_loss * spec.conduction_fraction,
            result.switching_loss * spec.fsw_hz,
        )
        if not all(math.isfinite(value) and value >= 0 for value in losses):
            raise ValueError("The loss curves returned an invalid power estimate.")
        return losses
