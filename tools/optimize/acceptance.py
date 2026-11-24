"""Verify the optimizer through its CLI and a live GeckoCIRCUITS API."""

import argparse
import csv
from pathlib import Path
import subprocess
import sys
from tempfile import mkdtemp


HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]


def run(problem: Path, output: Path) -> list[dict[str, str]]:
    subprocess.run(
        [sys.executable, "-m", "tools.optimize.optimize", str(problem), "--output", str(output)],
        cwd=ROOT,
        check=True,
    )
    image = (output / "pareto.png").read_bytes()
    if not image.startswith(b"\x89PNG\r\n\x1a\n"):
        raise AssertionError("Missing Pareto plot.")
    with (output / "pareto.csv").open(newline="", encoding="utf-8") as stream:
        return list(csv.DictReader(stream))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, help="Directory for acceptance results.")
    output = parser.parse_args().output
    output = output.resolve() if output else Path(mkdtemp(prefix="gecko-optimize-"))
    single = run(HERE / "buck_target.yaml", output / "single")
    best = min(single, key=lambda row: abs(float(row["vout"]) - 12))
    if abs(float(best["vout"]) - 12) >= 0.12:
        raise AssertionError(f'Output voltage {best["vout"]} V is outside 1%.')
    if int(best["trial"]) >= 60:
        raise AssertionError("The target requires more than 60 trials.")
    if abs(float(best["D"]) - 0.26) >= 0.0026:
        raise AssertionError(f'Duty cycle {best["D"]} is outside 1% of 0.26.')

    front = run(HERE / "buck_48v_12v.yaml", output / "multi")
    points = {(float(row["L"]), float(row["total_loss"])) for row in front}
    if len(points) < 5:
        raise AssertionError(f"Expected at least five distinct Pareto points. Got {len(points)}.")
    for row in front:
        if float(row["ripple_ratio"]) >= 0.01 or abs(float(row["vout"]) - 12) >= 0.12:
            raise AssertionError(f"Infeasible point in the Pareto front: {row}")
    for inductance, loss in points:
        if any(a <= inductance and b <= loss and (a, b) != (inductance, loss) for a, b in points):
            raise AssertionError(f"Dominated point in the Pareto front: {(inductance, loss)}")
    print(f'Vout {best["vout"]} V at duty {best["D"]}. {len(points)} Pareto points.')
    print(f"Acceptance results: {output}")


if __name__ == "__main__":
    main()
