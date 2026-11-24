"""Run parameter batches and read their metrics through the generated client."""

from contextlib import contextmanager
import math
import os
import time

from gecko_client import ApiClient, Configuration
from gecko_client.api.loss_calculation_api import LossCalculationApi
from gecko_client.api.signal_analysis_api import SignalAnalysisApi
from gecko_client.api.simulations_api import SimulationsApi
from gecko_client.models.batch_simulation_request import BatchSimulationRequest
from gecko_client.models.conduction_loss_request import ConductionLossRequest
from gecko_client.models.signal_analysis_request import SignalAnalysisRequest
from gecko_client.models.simulation_settings import SimulationSettings
from gecko_client.models.switching_loss_request import SwitchingLossRequest


class SimulationFailed(RuntimeError):
    """A simulation job finished without results."""


@contextmanager
def connect():
    url = os.getenv("GECKO_API_BASE_URL", "http://localhost:8080/gecko")
    config = Configuration(host=url.rstrip("/"))
    if key := os.getenv("GECKO_API_KEY"):
        config.api_key["ApiKey"] = key
    if token := os.getenv("GECKO_ACCESS_TOKEN"):
        config.access_token = token
    with ApiClient(config) as client:
        yield client


class BatchEvaluator:
    def __init__(self, client, problem):
        self.simulations = SimulationsApi(client)
        self.analysis = SignalAnalysisApi(client)
        self.loss = LossCalculationApi(client)
        self.problem = problem

    def evaluate(self, samples):
        """Return one metric dictionary or job failure per sample, in input order."""
        p = self.problem
        batch = self.simulations.submit_batch(
            BatchSimulationRequest(
                circuit_file=str(p.circuit_file) if p.circuit_file else None,
                circuit_id=p.circuit_id,
                parameter_sets=[
                    {p.parameters[name].path: value for name, value in sample.items()}
                    for sample in samples
                ],
                simulation_settings=SimulationSettings(
                    simulation_time=p.duration, time_step=p.time_step
                ),
                max_concurrent=p.concurrency,
            ),
            _request_timeout=p.timeout,
        )
        deadline = time.monotonic() + p.timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError(f"Batch {batch.batch_id} exceeded {p.timeout} s.")
            status = self.simulations.get_batch_status(
                batch.batch_id, _request_timeout=remaining
            )
            if status.done:
                break
            time.sleep(min(0.08, remaining))
        if len(batch.simulation_ids) != len(samples):
            raise RuntimeError("Batch did not return one job per sample.")
        results = []
        for job_id, sample in zip(batch.simulation_ids, samples, strict=True):
            if status.simulation_statuses.get(job_id) != "COMPLETED":
                job = self.simulations.get_simulation(job_id, _request_timeout=p.timeout)
                results.append(SimulationFailed(f"{job_id}: {job.status}. {job.error_message}"))
            else:
                results.append(self.metrics(job_id, sample))
        return results

    def metrics(self, job_id, sample):
        p = self.problem
        values = dict(sample)
        signals = {}

        def resolve(value):
            return values[value] if isinstance(value, str) else value

        for name, metric in p.metrics.items():
            match metric.kind:
                case "analysis":
                    if metric.signal not in signals:
                        signals[metric.signal] = self.analysis.compute_characteristics(
                            SignalAnalysisRequest(
                                simulation_id=job_id,
                                signal_name=metric.signal,
                                start_time=p.measure_from or None,
                            ),
                            _request_timeout=p.timeout,
                        )
                    value = getattr(signals[metric.signal], metric.field)
                case "conduction":
                    request = ConductionLossRequest(
                        **{key: resolve(val) for key, val in metric.inputs.items()}
                    )
                    value = self.loss.calculate_conduction_loss(
                        request, _request_timeout=p.timeout
                    ).conduction_loss
                case "switching":
                    request = SwitchingLossRequest(
                        **{key: resolve(val) for key, val in metric.inputs.items()}
                    )
                    energy = self.loss.calculate_switching_loss(
                        request, _request_timeout=p.timeout
                    ).switching_loss
                    value = energy * resolve(metric.frequency)
                case "sum":
                    value = sum(map(resolve, metric.terms))
                case "ratio":
                    value = resolve(metric.terms[0]) / abs(resolve(metric.terms[1]))
            value *= resolve(metric.scale)
            if not math.isfinite(value):
                raise ValueError(f"Metric {name} is not finite for simulation {job_id}.")
            values[name] = value
        return {name: values[name] for name in p.metrics}
