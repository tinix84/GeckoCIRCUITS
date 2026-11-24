"""Predict buck-converter outputs from a trained local model."""

import argparse
import json
from pathlib import Path

import joblib
import numpy as np

from .model import FEATURES, predict


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("model", type=Path, help="Trusted local model from the training command.")
    for name in FEATURES:
        parser.add_argument(f"--{name}", type=float, required=True)
    args = parser.parse_args()
    model = joblib.load(args.model)
    values = np.array([[getattr(args, name) for name in model["inputs"]]])
    if np.any(values < model["minimum"]) or np.any(values > model["maximum"]):
        parser.error("Use inputs within the model training bounds.")
    result = predict(model, values)[0]
    print(json.dumps(dict(zip(model["outputs"], map(float, result), strict=True))))


if __name__ == "__main__":
    main()
