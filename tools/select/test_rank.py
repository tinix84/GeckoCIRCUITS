"""Check ranking, exclusions, and empty results through the selection CLI."""

import os
from pathlib import Path
import subprocess
import sys

import pytest


HERE = Path(__file__).resolve().parent
CATALOG = HERE / "fixtures" / "devices.csv"
SPEC = HERE / "example.yaml"


def run(spec, catalog=CATALOG):
    return subprocess.run(
        [sys.executable, "-m", "tools.select.rank", str(spec), str(catalog)],
        cwd=HERE.parents[1], check=True, capture_output=True, text=True,
    ).stdout


def test_catalog_ranks_by_loss_then_price_and_reports_derating():
    output = run(SPEC)
    ranks = [line.split()[1] for line in output.splitlines() if line[:1].isdigit()]
    assert ranks == ["A", "B", "D", "C", "E"]
    assert "Estimated loss 1.240 W from 1.000 W conduction and 0.240 W switching" in output
    assert "Estimated junction temperature 44.96 deg C" in output
    assert "low_voltage. voltage derating" in output
    assert "low_current. current derating" in output
    assert "hot. thermal limit" in output
    assert "over_budget. efficiency budget" in output
    assert "low_both. voltage derating" in output


def test_empty_result_reports_the_constraint_that_eliminated_the_last_devices(tmp_path):
    spec = tmp_path / "empty.yaml"
    spec.write_text(SPEC.read_text().replace("iout_a: 10", "iout_a: 30"), encoding="utf-8")
    assert "No candidates. current derating eliminated the last candidates." in run(spec)


@pytest.mark.skipif(not os.getenv("GECKO_SELECT_TEST_API"), reason="Requires the GeckoCIRCUITS API")
def test_preserves_the_vendor_conduction_curve_when_switching_points_are_out_of_range(tmp_path):
    loss_file = tmp_path / "curve.csv"
    loss_file.write_text(
        "curve,tj_c,current_a,v_drop_v,blocking_v,e_on_j,e_off_j\n"
        "conduction,25,0,0,,,\n"
        "conduction,25,20,0.4,,,\n"
        "conduction,100,0,0,,,\n"
        "conduction,100,20,0.4,,,\n"
        "switching,25,20,,400,0.00005,0.00003\n"
        "switching,25,40,,400,0.0001,0.00006\n",
        encoding="utf-8",
    )
    catalog = tmp_path / "devices.csv"
    header = CATALOG.read_text(encoding="utf-8").splitlines()[0]
    catalog.write_text(header + "\nvendor,100,40,0.01,,3e-8,TO-247,1.5,4,150,curve.csv\n",
                       encoding="utf-8")
    output = run(SPEC, catalog)
    assert "2.240 W from 2.000 W conduction and 0.240 W switching" in output
    assert "conduction from vendor curves, switching from the gate charge estimate" in output
