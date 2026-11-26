"""Package a GeckoCIRCUITS circuit and its external ports as an FMI 2.0 FMU."""

import argparse
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
from tempfile import mkdtemp

from pythonfmu.builder import FmuBuilder

from GeckoFmu import worker_command


HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
CORE = "src/modules/gecko-simulation-core"


def compile_runtime(work):
    runtime = work / "gecko_runtime"
    classes = runtime / "classes"
    libraries = runtime / "lib"
    classes.mkdir(parents=True)
    mvn = shutil.which("mvn")
    if mvn is None:
        raise RuntimeError("Install Maven before building the FMU.")
    artifact_file = work / "core-artifact.txt"
    subprocess.run([
        mvn, "-B", "-f", str(ROOT / "pom-reactor.xml"), "-pl", CORE, "package",
        "dependency:copy-dependencies", "org.apache.maven.plugins:maven-help-plugin:3.5.2:evaluate",
        "-Dmaven.test.skip=true", "-DincludeScope=runtime", f"-DoutputDirectory={libraries}",
        "-Dexpression=project.artifact.file", f"-Doutput={artifact_file}",
    ], cwd=ROOT, check=True)
    jar = Path(artifact_file.read_text(encoding="utf-8").strip())
    shutil.copyfile(jar, libraries / jar.name)
    subprocess.run([
        os.environ.get("GECKO_FMU_JAVAC", "javac"), "--release", "21",
        "-classpath", str(libraries / "*"), "-d", str(classes), str(HERE / "GeckoFmiWorker.java"),
    ], check=True)
    return runtime


def build(circuit, manifest, destination, runtime):
    config = json.loads(manifest.read_text(encoding="utf-8"))
    if set(config) != {"model", "step", "inputs", "outputs"}:
        raise ValueError("Specify model, step, inputs, and outputs in the port map.")
    if not re.fullmatch(r"[A-Za-z_][A-Za-z_0-9]*", config["model"]):
        raise ValueError("Use a valid model identifier.")
    names = [item["name"] for item in config["inputs"]] + list(config["outputs"])
    if not config["outputs"] or len(set(names)) != len(names) or any(not name.strip() for name in names):
        raise ValueError("Use unique port names and specify at least one output.")
    if any(not math.isfinite(item["start"]) for item in config["inputs"]):
        raise ValueError("Set finite input start values.")
    if not math.isfinite(config["step"]) or config["step"] <= 0:
        raise ValueError("Set a positive finite communication step.")
    shutil.copyfile(circuit, runtime / "circuit.ipes")
    worker = subprocess.run(
        worker_command(runtime), input="", capture_output=True, text=True, timeout=25,
    )
    if worker.returncode:
        raise RuntimeError(worker.stderr)
    inputs, outputs, step, end = map(float, worker.stdout.split())
    if (inputs, outputs, step) != (len(config["inputs"]), len(config["outputs"]), config["step"]):
        raise ValueError("The port map or communication step does not match the circuit.")

    config["stop"] = end
    (runtime / "manifest.json").write_text(json.dumps(config), encoding="utf-8")
    shutil.copyfile(ROOT / "LICENSE", runtime / "LICENSE")

    destination.mkdir(parents=True, exist_ok=True)
    return FmuBuilder.build_FMU(
        HERE / "GeckoFmu.py", dest=destination, project_files={runtime},
        canHandleVariableCommunicationStepSize=False, canGetAndSetFMUstate=False,
        canSerializeFMUstate=False,
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("circuit", type=Path)
    parser.add_argument("ports", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    work = Path(mkdtemp(prefix="gecko-fmu-build-", dir=args.output))
    runtime = compile_runtime(work)
    print(build(args.circuit, args.ports, args.output, runtime))


if __name__ == "__main__":
    main()
