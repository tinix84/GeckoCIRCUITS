"""Check ranking, exclusions, and empty results through the selection CLI."""

from pathlib import Path
import subprocess
import sys


HERE = Path(__file__).resolve().parent
CATALOG = HERE / "fixtures" / "devices.csv"
SPEC = HERE / "example.yaml"


def run(spec):
    return subprocess.run(
        [sys.executable, "-m", "tools.select.rank", str(spec), str(CATALOG)],
        cwd=HERE.parents[1], check=True, capture_output=True, text=True,
    ).stdout


def test_catalog_ranks_by_loss_then_price_and_reports_derating():
    output = run(SPEC)
    ranks = [line.split()[1] for line in output.splitlines() if line[:1].isdigit()]
    assert ranks == ["A", "B", "D", "C", "E"]
    assert "Estimated loss 1.240 W from 1.000 W conduction and 0.240 W switching" in output
    assert "Estimated junction temperature 44.96 °C" in output
    assert "low_voltage. voltage derating" in output
    assert "low_current. current derating" in output
    assert "hot. thermal limit" in output
    assert "over_budget. efficiency budget" in output
    assert "low_both. voltage derating" in output


def test_empty_result_reports_the_constraint_that_eliminated_the_last_devices(tmp_path):
    spec = tmp_path / "empty.yaml"
    spec.write_text(SPEC.read_text().replace("iout_a: 10", "iout_a: 30"), encoding="utf-8")
    assert "No candidates. current derating eliminated the last candidates." in run(spec)
