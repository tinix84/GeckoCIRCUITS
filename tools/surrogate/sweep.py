"""Sample buck-converter designs through the GeckoCIRCUITS batch API."""

import argparse
from pathlib import Path
import time

import pyarrow as pa
import pyarrow.parquet as pq
from scipy.stats import qmc

from tools.optimize.batch import BatchEvaluator, SimulationFailed, connect
from tools.optimize.problem import load_problem


FEATURES = ("Vin", "L", "C", "fsw", "D", "Rload")
OUTPUTS = ("vout", "ripple", "conduction_loss", "switching_loss")
DEFAULT_PROBLEM = Path(__file__).with_name("buck_sweep.yaml")
SAMPLE_COUNT = 2000


def sweep(problem, count, output):
    if count < 1:
        raise ValueError("Set a positive sample count.")
    if set(problem.parameters) != set(FEATURES) or not set(OUTPUTS) <= problem.metrics.keys():
        raise ValueError("The sweep requires all six buck inputs and four outputs.")
    bounds = [problem.parameters[name] for name in FEATURES]
    samples = qmc.scale(
        qmc.LatinHypercube(d=len(bounds), seed=problem.seed).random(n=count),
        [bound.min for bound in bounds], [bound.max for bound in bounds],
    )
    rows = []
    start = time.perf_counter()
    with connect() as client:
        evaluator = BatchEvaluator(client, problem)
        for offset in range(0, count, problem.batch_size):
            inputs = [
                dict(zip(FEATURES, map(float, row), strict=True))
                for row in samples[offset:offset + problem.batch_size]
            ]
            for sample, result in zip(inputs, evaluator.evaluate(inputs), strict=True):
                if isinstance(result, SimulationFailed):
                    raise result
                rows.append(sample | {metric: result[metric] for metric in OUTPUTS})
            if len(rows) % (10 * problem.batch_size) == 0 or len(rows) == count:
                print(f"Completed {len(rows)} of {count} simulations.", flush=True)
    elapsed = time.perf_counter() - start
    table = pa.Table.from_pylist(rows).replace_schema_metadata({
        "simulation_seconds": repr(elapsed),
        "grid_step": repr(problem.time_step),
        "seed": str(problem.seed),
    })
    pq.write_table(table, output)
    print(f"Wrote {output} ({output.stat().st_size} bytes) in {elapsed:.3f} s.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--problem", type=Path, default=DEFAULT_PROBLEM)
    parser.add_argument("--count", type=int, default=SAMPLE_COUNT)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    sweep(load_problem(args.problem), args.count, args.output)


if __name__ == "__main__":
    main()
