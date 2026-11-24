"""Run, export, and replay buck_simple with the generated GeckoCIRCUITS SDK."""
from base64 import b64encode
from io import BytesIO
import os
from pathlib import Path
import time
from tempfile import NamedTemporaryFile
from zipfile import ZipFile

from gecko_client import ApiClient, Configuration
from gecko_client.api.circuit_files_api import CircuitFilesApi
from gecko_client.api.research_bundles_api import ResearchBundlesApi
from gecko_client.api.simulations_api import SimulationsApi
from gecko_client.exceptions import ApiException
from gecko_client.models.circuit_load_request import CircuitLoadRequest
from gecko_client.models.simulation_request import SimulationRequest


BUCK = (Path(__file__).resolve().parents[2]
        / "src/modules/gecko-simulation-core/src/test/resources/ipes/buck_simple.ipes")


def configuration():
    url = os.getenv("GECKO_API_BASE_URL", "http://localhost:8080/gecko")
    config = Configuration(host=url.rstrip("/"))
    if key := os.getenv("GECKO_API_KEY"):
        config.api_key["ApiKey"] = key
    if token := os.getenv("GECKO_ACCESS_TOKEN"):
        config.access_token = token
    return config


def run_buck(client):
    circuits = CircuitFilesApi(client)
    simulations = SimulationsApi(client)
    bundles = ResearchBundlesApi(client)
    circuit = circuits.load_circuit_from_file1(CircuitLoadRequest(
        content=b64encode(BUCK.read_bytes()).decode("ascii"), filename=BUCK.name),
        _content_type="application/json")
    job = simulations.submit_simulation(SimulationRequest(
        circuit_id=circuit.circuit_id, simulation_time=0.002, time_step=0.00001))
    deadline = time.monotonic() + 30
    while job.status not in {"COMPLETED", "FAILED"} and time.monotonic() < deadline:
        time.sleep(0.1)
        job = simulations.get_simulation(job.simulation_id)
    if job.status != "COMPLETED":
        raise RuntimeError(f"Buck simulation ended with {job.status}: {job.error_message}")

    archive = bundles.export_research_bundle(job.simulation_id)
    with NamedTemporaryFile(prefix="gecko-buck-", suffix=".zip", delete=False) as output:
        output.write(archive)
    return Path(output.name), bundles.verify_research_bundle(bytes(archive), _request_timeout=65.0)


def check_tamper_detection(client, archive):
    damaged = BytesIO()
    with ZipFile(archive) as source, ZipFile(damaged, "w") as target:
        for name in source.namelist():
            content = source.read(name)
            target.writestr(name, content + b"\0" if name == "circuit.ipes" else content)
    try:
        ResearchBundlesApi(client).verify_research_bundle(damaged.getvalue(), _request_timeout=65.0)
    except ApiException as error:
        if error.status == 400 and "Checksum mismatch for circuit.ipes" in str(error.body):
            return
        raise
    raise RuntimeError("Bundle verification accepted a circuit with an incorrect checksum")


def main():
    with ApiClient(configuration()) as client:
        archive, report = run_buck(client)
        deviations = report.max_relative_deviation
        if not report.passed or not deviations or any(value != 0 for value in deviations.values()):
            raise RuntimeError(f"Buck replay did not match: {report}")
        check_tamper_detection(client, archive)
        print(f"Verified {len(deviations)} signals with zero deviation. Bundle: {archive}")


if __name__ == "__main__":
    main()
