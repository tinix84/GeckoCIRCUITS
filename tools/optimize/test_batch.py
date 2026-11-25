from types import SimpleNamespace

import pytest

from tools.optimize.batch import BatchEvaluator, SimulationFailed
from tools.optimize.problem import Problem


def test_undefined_ratio_fails_only_its_sample():
    problem = Problem.model_validate({
        "circuit_id": "test-circuit",
        "duration": 0.008,
        "time_step": 1e-7,
        "measure_from": 0.006,
        "parameters": {"duty": {"path": "SIGNAL.1.duty", "min": 0, "max": 0.3}},
        "metrics": {
            "vout": {"kind": "analysis", "signal": "u2", "field": "average"},
            "ripple": {"kind": "analysis", "signal": "u2", "field": "peak_to_peak"},
            "ratio": {"kind": "ratio", "terms": ["ripple", "vout"]},
        },
    })
    evaluator = BatchEvaluator(None, problem)
    evaluator.simulations = SimpleNamespace(
        submit_batch=lambda *_args, **_kwargs: SimpleNamespace(
            batch_id="test-batch", simulation_ids=["off", "normal"],
        ),
        get_batch_status=lambda *_args, **_kwargs: SimpleNamespace(
            done=True, simulation_statuses={"off": "COMPLETED", "normal": "COMPLETED"},
        ),
    )
    traces = {
        "off": SimpleNamespace(average=0, peak_to_peak=0),
        "normal": SimpleNamespace(average=12, peak_to_peak=0.1),
    }
    evaluator.analysis = SimpleNamespace(
        compute_characteristics=lambda request, **_kwargs: traces[request.simulation_id],
    )

    failed, normal = evaluator.evaluate([{"duty": 0}, {"duty": 0.26}])

    assert isinstance(failed, SimulationFailed)
    assert "ratio" in str(failed) and "zero" in str(failed)
    assert normal == {"vout": 12, "ripple": 0.1, "ratio": pytest.approx(1 / 120)}
