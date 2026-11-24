"""Read and validate a GeckoCIRCUITS optimization problem."""

from pathlib import Path
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, FiniteFloat, model_validator
import yaml


Positive = Annotated[FiniteFloat, Field(gt=0)]
Value = FiniteFloat | str
ANALYSIS_FIELDS = {
    "average", "rms", "thd", "min", "max", "peak_to_peak", "ripple", "klirr",
    "shape_factor",
}
LOSS_INPUTS = {
    "conduction": {"current", "on_resistance", "threshold_voltage"},
    "switching": {
        "current", "voltage", "reference_voltage", "temperature",
        "turn_on_energy", "turn_off_energy",
    },
}


class Config(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Parameter(Config):
    path: str
    min: FiniteFloat
    max: FiniteFloat

    @model_validator(mode="after")
    def bounds(self):
        if self.max < self.min:
            raise ValueError(f"Reversed bounds for {self.path}.")
        return self


class Metric(Config):
    kind: Literal["analysis", "conduction", "switching", "sum", "ratio"]
    signal: str | None = None
    field: str | None = None
    inputs: dict[str, Value] = Field(default_factory=dict)
    scale: Value = 1.0
    frequency: Value | None = None
    terms: list[Value] = Field(default_factory=list)

    @model_validator(mode="after")
    def check_kind(self):
        if self.kind == "analysis" and (not self.signal or self.field not in ANALYSIS_FIELDS):
            raise ValueError("Specify a valid characteristics signal and field.")
        if self.kind in LOSS_INPUTS and set(self.inputs) != LOSS_INPUTS[self.kind]:
            raise ValueError(f"{self.kind} requires {sorted(LOSS_INPUTS[self.kind])}.")
        if self.kind == "switching" and self.frequency is None:
            raise ValueError("Specify the switching frequency in hertz.")
        if self.kind == "sum" and not self.terms:
            raise ValueError("Specify at least one term for the sum.")
        if self.kind == "ratio" and len(self.terms) != 2:
            raise ValueError("Specify two terms for the ratio.")
        return self


class Objective(Config):
    metric: str
    direction: Literal["minimize", "maximize"] = "minimize"
    target: FiniteFloat | None = None

    @model_validator(mode="after")
    def check_target(self):
        if self.target is not None and self.direction != "minimize":
            raise ValueError("Use minimize for a target objective.")
        return self

    def score(self, values):
        value = values[self.metric]
        return value if self.target is None else abs(value - self.target)


class Constraint(Config):
    metric: str
    min: FiniteFloat | None = None
    max: FiniteFloat | None = None

    @model_validator(mode="after")
    def check_limits(self):
        if self.min is None and self.max is None:
            raise ValueError("Specify at least one constraint limit.")
        if self.min is not None and self.max is not None and self.min >= self.max:
            raise ValueError("The lower constraint limit must be less than the upper limit.")
        return self

    def violations(self, values):
        value = values[self.metric]
        if self.min is not None:
            yield self.min - value
        if self.max is not None:
            yield value - self.max


class Problem(Config):
    circuit_file: Path | None = None
    circuit_id: str | None = None
    parameters: dict[str, Parameter] = Field(min_length=1)
    metrics: dict[str, Metric] = Field(min_length=1)
    duration: Positive
    time_step: Positive
    measure_from: Annotated[FiniteFloat, Field(ge=0)]
    trials: int = Field(default=60, ge=1)
    batch_size: int = Field(default=4, ge=1, le=100)
    concurrency: int = Field(default=4, ge=1, le=100)
    timeout: Positive = 120
    seed: int = Field(default=42, ge=0)
    objectives: list[Objective] = Field(default_factory=list)
    constraints: list[Constraint] = Field(default_factory=list)

    @model_validator(mode="after")
    def check_problem(self):
        if bool(self.circuit_file) == bool(self.circuit_id):
            raise ValueError("Specify one circuit_file or circuit_id.")
        if self.measure_from + self.time_step > self.duration:
            raise ValueError("The measurement window must span at least one time step.")
        if len({p.path for p in self.parameters.values()}) != len(self.parameters):
            raise ValueError("Parameter paths must be unique.")
        names = set(self.parameters)
        for name, metric in self.metrics.items():
            inputs = [*metric.inputs.values(), *metric.terms, metric.scale, metric.frequency]
            unknown = {value for value in inputs if isinstance(value, str)} - names
            if unknown:
                raise ValueError(f"Unknown references for metric {name}: {sorted(unknown)}.")
            if name in names or name == "trial":
                raise ValueError(f"Metric name {name} is already used or reserved.")
            names.add(name)
        if "trial" in self.parameters:
            raise ValueError("The parameter name trial is reserved.")
        for entry in [*self.objectives, *self.constraints]:
            if entry.metric not in names:
                raise ValueError(f"Unknown objective or constraint metric {entry.metric}.")
        return self


def load_problem(path: Path) -> Problem:
    problem = Problem.model_validate(yaml.safe_load(path.read_text(encoding="utf-8")))
    if problem.circuit_file:
        problem.circuit_file = (path.parent / problem.circuit_file).resolve()
    return problem
