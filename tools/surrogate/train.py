"""Train and evaluate a buck-converter surrogate on a fixed held-out split."""

import argparse
from pathlib import Path
import time

import joblib
import numpy as np
import pyarrow.parquet as pq
from sklearn.compose import TransformedTargetRegressor
from sklearn.metrics import r2_score
from sklearn.model_selection import train_test_split
from sklearn.neural_network import MLPRegressor
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

from .model import FEATURES, OUTPUTS, design_terms, predict


def train(dataset, output):
    table = pq.read_table(dataset)
    metadata = table.schema.metadata
    grid_step = float(metadata[b"grid_step"])
    elapsed = float(metadata[b"simulation_seconds"])
    inputs = np.column_stack([table[name].to_numpy() for name in FEATURES])
    targets = np.column_stack([table[name].to_numpy() for name in OUTPUTS])
    if not np.all(np.isfinite(targets)) or np.any(targets <= 0):
        raise ValueError("Train on finite, positive simulation outputs.")
    x_train, x_test, y_train, y_test = train_test_split(
        inputs, targets, test_size=0.2, random_state=47
    )
    estimator = TransformedTargetRegressor(
        regressor=make_pipeline(
            StandardScaler(),
            MLPRegressor(hidden_layer_sizes=(40, 24), solver="lbfgs",
                         max_iter=1600, max_fun=22000, random_state=52),
        ),
        transformer=StandardScaler(),
    )
    estimator.fit(design_terms(x_train, grid_step), y_train)
    model = {
        "estimator": estimator, "grid_step": grid_step, "inputs": FEATURES, "outputs": OUTPUTS,
        "minimum": x_train.min(axis=0), "maximum": x_train.max(axis=0),
    }
    start = time.perf_counter()
    prediction = predict(model, x_test)
    per_prediction = (time.perf_counter() - start) / len(x_test)
    r2 = r2_score(y_test, prediction, multioutput="raw_values")
    relative = np.median(np.abs(prediction - y_test) / y_test, axis=0)
    per_simulation = elapsed / len(inputs)
    for label, score, error in zip(OUTPUTS, r2, relative, strict=True):
        print(f"{label}: R² {score:.6f}, median relative error {error:.3%}")
    print(f"Simulation {per_simulation:.6g} s/sample. Prediction {per_prediction:.6g} s/sample.")
    print(f"Speedup {per_simulation / per_prediction:.0f}×.")
    joblib.dump(model, output, compress=3)
    print(f"Dataset {dataset.stat().st_size} bytes. Model {output.stat().st_size} bytes.")
    return r2, relative, per_simulation / per_prediction


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    train(args.dataset, args.output)


if __name__ == "__main__":
    main()
