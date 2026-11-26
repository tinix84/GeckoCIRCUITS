from pathlib import Path

from tools.fmu import build as fmu_builder


def test_runtime_includes_only_the_core_artifact_maven_built(tmp_path, monkeypatch):
    target = tmp_path / fmu_builder.CORE / "target"
    target.mkdir(parents=True)
    latest = target / "gecko-simulation-core-1.jar"
    old = target / "gecko-simulation-core-0.jar"
    latest.write_bytes(b"current artifact")
    old.write_bytes(b"older artifact")

    def run(command, *, check, cwd=None):
        if command[0] != "mvn":
            return
        properties = dict(arg[2:].split("=", 1) for arg in command if arg.startswith("-D") and "=" in arg)
        Path(properties["outputDirectory"]).mkdir(parents=True)
        if "output" in properties and properties["expression"] == "project.artifact.file":
            Path(properties["output"]).write_text(str(latest), encoding="utf-8")

    monkeypatch.setattr(fmu_builder, "ROOT", tmp_path)
    monkeypatch.setattr(fmu_builder.shutil, "which", lambda _name: "mvn")
    monkeypatch.setattr(fmu_builder.subprocess, "run", run)

    runtime = fmu_builder.compile_runtime(tmp_path / "export")

    assert {jar.name: jar.read_bytes() for jar in (runtime / "lib").glob("*.jar")} == {
        latest.name: b"current artifact",
    }
