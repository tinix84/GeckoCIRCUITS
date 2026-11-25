"""Verify buck surrogate accuracy and online batch-to-prediction speed."""

import argparse
from pathlib import Path
import sys
from tempfile import mkdtemp

if __package__ in ("", None):
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

import numpy as np
import pyarrow.parquet as pq

from tools.optimize.problem import load_problem
from tools.surrogate.sweep import DEFAULT_PROBLEM, SAMPLE_COUNT, sweep
from tools.surrogate.train import train


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--dataset", type=Path, help="Use a data set from the same sweep.")
    args = parser.parse_args()
    output = args.output or Path(mkdtemp(prefix="gecko-surrogate-"))
    output.mkdir(parents=True, exist_ok=True)
    dataset = args.dataset
    if dataset is None:
        dataset = output / "buck_sweep.parquet"
        sweep(load_problem(DEFAULT_PROBLEM), SAMPLE_COUNT, dataset)
    if pq.read_metadata(dataset).num_rows != SAMPLE_COUNT:
        raise ValueError(f"Acceptance requires {SAMPLE_COUNT} rows.")
    r2, relative, speedup = train(dataset, output / "buck_surrogate.joblib")
    if np.any(r2 < 0.98) or np.any(relative > 0.02) or speedup < 100:
        raise SystemExit("Surrogate acceptance failed.")


if __name__ == "__main__":
    main()
