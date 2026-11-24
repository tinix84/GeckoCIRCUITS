"""Validate and simulate the GeckoCIRCUITS RC FMU with FMPy."""

import argparse
import os
from pathlib import Path
import subprocess
from tempfile import mkdtemp

from fmpy import read_model_description, simulate_fmu
from fmpy.validation import validate_fmu
import numpy as np

from build import HERE, build, compile_runtime


STEP = 2e-6
STOP = 0.004


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    output = args.output.resolve() if args.output else Path(mkdtemp(prefix="gecko-fmu-"))
    output.mkdir(parents=True, exist_ok=True)
    work = Path(mkdtemp(prefix="build-", dir=output))
    runtime = compile_runtime(work)
    fixtures = HERE / "fixtures"
    fmu = build(fixtures / "rc_external.ipes", fixtures / "rc_ports.json", output, runtime)
    problems = validate_fmu(str(fmu))
    if problems:
        raise AssertionError("\n".join(problems))
    description = read_model_description(str(fmu))
    if (description.fmiVersion != "2.0" or not description.coSimulation
            or description.coSimulation.canGetAndSetFMUstate
            or description.coSimulation.canHandleVariableCommunicationStepSize):
        raise AssertionError("The FMU must use fixed-step FMI 2.0 co-simulation without rollback.")

    classpath = os.pathsep.join([str(runtime / "classes"), str(runtime / "lib" / "*")])
    subprocess.run([
        os.environ.get("GECKO_FMU_JAVAC", "javac"), "--release", "21",
        "-classpath", classpath, "-d", str(work), str(HERE / "RcReference.java"),
    ], check=True)
    reference_file = output / "standalone.csv"
    with reference_file.open("w", encoding="utf-8") as csv:
        subprocess.run([
            os.environ.get("GECKO_FMU_JAVA", "java"), "-classpath",
            os.pathsep.join([str(work), classpath]), "RcReference",
            str(fixtures / "rc_standalone.ipes"),
        ], check=True, stdout=csv)
    reference = np.genfromtxt(reference_file, names=True, delimiter=",")

    # Duplicate timestamps at discontinuities to hold the standalone source value.
    source = reference["Vs"]
    events = [(0, source[0])]
    for index in np.flatnonzero(np.diff(source)) + 1:
        time = reference["time"][index]
        events.extend([(time, source[index - 1]), (time, source[index])])
    events.append((STOP, source[-1]))
    if len(events) < 4:
        raise AssertionError("The standalone source must make at least one voltage step.")
    stimulus = np.array(events, dtype=[("time", float), ("Vs", float)])
    result = simulate_fmu(
        str(fmu), start_time=0, stop_time=STOP, step_size=STEP,
        output_interval=STEP, output=["Vc"], input=stimulus,
    )
    comparison = np.interp(result["time"], reference["time"], reference["Vc"])
    error = max(abs(result["Vc"] - comparison)) / 5
    if abs(result["time"][-1] - STOP) > STEP / 2 or max(result["Vc"]) < 4.7 or error > 0.01:
        raise AssertionError(f"RC step mismatch. Maximum error is {error:.3%} of 5 V.")
    np.savetxt(output / "fmu.csv", np.column_stack([result["time"], result["Vc"]]),
               delimiter=",", header="time,Vc", comments="")
    print(f"FMPy validated {fmu}. Maximum voltage error {error:.3%} of 5 V.")


if __name__ == "__main__":
    main()
