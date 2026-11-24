"""An FMI 2.0 adapter for a private GeckoCIRCUITS simulation session."""

import json
import math
import os
from pathlib import Path
from queue import Empty, Queue
import subprocess
from threading import Thread
from weakref import finalize
from xml.etree.ElementTree import SubElement

from pythonfmu.default_experiment import DefaultExperiment
from pythonfmu import Fmi2Causality, Fmi2Initial, Fmi2Slave, Real


def worker_command(runtime):
    classpath = os.pathsep.join([str(runtime / "classes"), str(runtime / "lib" / "*")])
    return [
        os.environ.get("GECKO_FMU_JAVA", "java"), "-classpath", classpath,
        "GeckoFmiWorker", str(runtime / "circuit.ipes"),
    ]


def read_replies(stream, replies):
    for line in stream:
        replies.put(line)
    replies.put(None)


def close_process(process):
    process.stdin.close()
    try:
        process.wait(timeout=2)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait()
    process.stdout.close()


class GeckoFmu(Fmi2Slave):
    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.author = "GeckoCIRCUITS"
        self.description = "GeckoCIRCUITS circuit co-simulation"
        self.runtime = Path(self.resources) / "gecko_runtime"
        data = json.loads((self.runtime / "manifest.json").read_text(encoding="utf-8"))
        self.modelName = data["model"]
        self.step = data["step"]
        self.default_experiment = DefaultExperiment(0.0, data["stop"], self.step)
        self.time = 0.0
        self.inputs = [port["start"] for port in data["inputs"]]
        self.outputs = [0.0] * len(data["outputs"])
        for index, port in enumerate(data["inputs"]):
            self.register_variable(Real(
                port["name"], causality=Fmi2Causality.input, start=port["start"],
                getter=lambda index=index: self.inputs[index],
                setter=lambda value, index=index: self.inputs.__setitem__(index, value),
            ), nested=False)
        for index, name in enumerate(data["outputs"]):
            self.register_variable(Real(
                name, causality=Fmi2Causality.output, initial=Fmi2Initial.calculated,
                getter=lambda index=index: self.outputs[index],
            ), nested=False)
        self.process = None
        self.cleanup = None
        self.replies = Queue()

    def to_xml(self, model_options=None):
        root = super().to_xml(model_options or {})
        # PythonFMU emits Outputs but omits the required calculated InitialUnknowns.
        structure = root.find("ModelStructure")
        initial_unknowns = SubElement(structure, "InitialUnknowns")
        for index in range(len(self.inputs) + 1, len(self.inputs) + len(self.outputs) + 1):
            SubElement(initial_unknowns, "Unknown", index=str(index))
        return root

    def setup_experiment(self, start_time, stop_time, tolerance):
        if start_time != 0.0:
            raise ValueError("Start the GeckoCIRCUITS FMU at time zero.")

    def exit_initialization_mode(self):
        self.process = subprocess.Popen(
            worker_command(self.runtime), stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            text=True, encoding="utf-8", bufsize=1,
        )
        self.cleanup = finalize(self, close_process, self.process)
        Thread(target=read_replies, args=(self.process.stdout, self.replies), daemon=True).start()
        try:
            inputs, outputs, width, _ = self.read()
            if inputs != len(self.inputs) or outputs != len(self.outputs) or width != self.step:
                raise ValueError("The FMU port map or communication step does not match the circuit.")
            self.exchange(0.0)
        except Exception:
            self.terminate()
            raise

    def read(self):
        try:
            line = self.replies.get(timeout=25)
        except Empty as error:
            raise TimeoutError("The GeckoCIRCUITS worker did not respond.") from error
        if line is None:
            raise RuntimeError("The GeckoCIRCUITS worker stopped before replying.")
        return [float(value) for value in line.split()]

    def exchange(self, expected_time):
        if not all(map(math.isfinite, self.inputs)):
            raise ValueError("Set finite FMU input values.")
        self.process.stdin.write(" ".join(map(str, self.inputs)) + "\n")
        self.process.stdin.flush()
        time, *outputs = self.read()
        if not math.isclose(time, expected_time, rel_tol=1e-9, abs_tol=self.step * 1e-6):
            raise RuntimeError(f"Expected circuit time {expected_time}. Got {time}.")
        if len(outputs) != len(self.outputs):
            raise RuntimeError("The worker returned an incorrect number of outputs.")
        self.outputs = outputs

    def do_step(self, current_time, step_size):
        if not math.isclose(self.step, step_size, rel_tol=1e-9, abs_tol=0.0):
            self.log(f"Use a communication step of {self.step:g} s.")
            return False
        if not math.isclose(self.time, current_time, rel_tol=1e-9, abs_tol=self.step * 1e-6):
            self.log(f"Expected communication time {self.time:g} s.")
            return False
        try:
            self.exchange(current_time + self.step)
        except Exception:
            self.terminate()
            raise
        self.time = current_time + self.step
        return True

    def terminate(self):
        if self.cleanup is not None:
            self.cleanup()
