"""Validate operating conditions and semiconductor catalog data."""

import csv
from pathlib import Path
from typing import Annotated

from pydantic import BaseModel, ConfigDict, Field, FiniteFloat, model_validator
import yaml


Positive = Annotated[FiniteFloat, Field(gt=0)]
Nonnegative = Annotated[FiniteFloat, Field(ge=0)]
Temperature = Annotated[FiniteFloat, Field(ge=-273.15)]


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


class OperatingPoint(StrictModel):
    vin_max_v: Positive
    iout_a: Positive
    fsw_hz: Positive
    ambient_c: Temperature
    output_power_w: Positive
    efficiency_target: Annotated[FiniteFloat, Field(gt=0, lt=1)]
    gate_drive_current_a: Positive
    conduction_fraction: Annotated[FiniteFloat, Field(gt=0, le=1)] = 1

    @property
    def loss_budget_w(self):
        return self.output_power_w * (1 / self.efficiency_target - 1)


class Device(StrictModel):
    name: str = Field(min_length=1)
    voltage_rating_v: Positive
    current_rating_a: Positive
    rds_on_ohm: Positive | None = None
    vce_sat_v: Positive | None = None
    qg_c: Positive | None = None
    package: str = Field(min_length=1)
    price: Nonnegative | None = None
    rth_c_per_w: Positive
    tj_max_c: Temperature
    curve_file: Path | None = None

    @model_validator(mode="after")
    def require_one_conduction_model(self):
        if (self.rds_on_ohm is None) == (self.vce_sat_v is None):
            raise ValueError("Enter either rds_on_ohm or vce_sat_v.")
        if self.qg_c is None and self.curve_file is None:
            raise ValueError("Enter gate charge or a vendor loss file.")
        return self


def load_spec(path):
    return OperatingPoint.model_validate(yaml.safe_load(path.read_text(encoding="utf-8")))


def load_catalog(path):
    with path.open(newline="", encoding="utf-8-sig") as stream:
        reader = csv.DictReader(stream)
        if not reader.fieldnames or len(reader.fieldnames) != len(set(reader.fieldnames)):
            raise ValueError("Specify unique catalog column names.")
        devices = []
        names = set()
        for row in reader:
            try:
                device = Device.model_validate(
                    {key: value for key, value in row.items() if value not in (None, "")}
                )
                if device.name in names:
                    raise ValueError(f"Duplicate device {device.name}.")
                if device.curve_file:
                    device.curve_file = (path.parent / device.curve_file).resolve()
            except ValueError as error:
                raise ValueError(f"Catalog row {reader.line_num}. {error}") from error
            devices.append(device)
            names.add(device.name)
    return devices
