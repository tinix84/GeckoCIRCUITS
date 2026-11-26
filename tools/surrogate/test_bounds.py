import json
import sys

import joblib
import numpy as np
import pyarrow.parquet as pq
import pytest

from tools.optimize.problem import load_problem
from tools.surrogate import predict as cli
from tools.surrogate import sweep as sweep_module
from tools.surrogate.model import FEATURES
from tools.surrogate.train import train


class BuckResults:
    def __init__(self, _client, _problem):
        pass

    def evaluate(self, samples):
        return [
            {
                "vout": sample["Vin"] * sample["D"],
                "ripple": 0.08 + 0.15 * sample["D"],
                "conduction_loss": sample["D"] + 0.1,
                "switching_loss": sample["fsw"] * 1e-5,
            }
            for sample in samples
        ]


@pytest.fixture
def dataset(tmp_path, monkeypatch):
    monkeypatch.setattr(sweep_module, "BatchEvaluator", BuckResults)
    problem = load_problem(sweep_module.DEFAULT_PROBLEM)
    path = tmp_path / "sweep.parquet"
    sweep_module.sweep(problem, 32, path)
    return path


def test_cli_accepts_both_configured_voltage_bounds(dataset, tmp_path, monkeypatch, capsys):
    model = tmp_path / "model.joblib"
    train(dataset, model)

    for voltage in ("40", "56"):
        capsys.readouterr()
        monkeypatch.setattr(sys, "argv", [
            "predict", str(model), "--Vin", voltage, "--L", "0.00017", "--C", "0.00012",
            "--fsw", "25000", "--D", "0.35", "--Rload", "1.5",
        ])
        cli.main()
        assert json.loads(capsys.readouterr().out)["vout"] > 0


def test_legacy_dataset_uses_the_range_of_all_swept_points(dataset, tmp_path):
    table = pq.read_table(dataset)
    metadata = dict(table.schema.metadata)
    metadata.pop(b"parameter_bounds", None)
    legacy = tmp_path / "legacy.parquet"
    pq.write_table(table.replace_schema_metadata(metadata), legacy)
    path = tmp_path / "model.joblib"

    train(legacy, path)

    model = joblib.load(path)
    np.testing.assert_array_equal(model["minimum"], [min(table[name].to_pylist()) for name in FEATURES])
    np.testing.assert_array_equal(model["maximum"], [max(table[name].to_pylist()) for name in FEATURES])
