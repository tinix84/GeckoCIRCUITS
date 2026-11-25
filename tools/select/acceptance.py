"""Run the component-selection acceptance tests."""

from pathlib import Path
import subprocess
import sys


if __name__ == "__main__":
    here = Path(__file__).resolve().parent
    raise SystemExit(subprocess.run(
        [sys.executable, "-m", "pytest", str(here)], cwd=here.parents[1], check=False,
    ).returncode)
