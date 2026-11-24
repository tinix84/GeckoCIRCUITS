# Circuit parameter optimization

The optimizer runs a GeckoCIRCUITS batch for each group of Optuna trials.
It uses the Tree-structured Parzen Estimator for one objective and NSGA-II for multiple objectives.
The CSV contains feasible, non-dominated trials. The PNG plots the objective values.

## Run the examples

1. Start the GeckoCIRCUITS REST API.
2. Generate and install the Python SDK from [clients](../../clients/README.md).
3. Install the optimizer dependencies.

```bash
conda run -n ntbees2 pip install -r tools/optimize/requirements.txt
```

4. Run the 48 V to 12 V buck example from the repository root.

```bash
conda run -n ntbees2 python -m tools.optimize.optimize \
  tools/optimize/buck_48v_12v.yaml --output optimizer-output
```

5. Run the acceptance check.

```bash
conda run -n ntbees2 python tools/optimize/acceptance.py
```

Set `GECKO_API_BASE_URL` to override `http://localhost:8080/gecko`.
Set `GECKO_API_KEY` or `GECKO_ACCESS_TOKEN` if the API requires authentication.
For a local acceptance server, increase the API request and simulation submission limits to fit the trial count.

## Problem format

| YAML key | Value |
| --- | --- |
| `circuit_file` | Path to a circuit that the API server can read. Relative paths start at the YAML directory. |
| `circuit_id` | ID of a circuit already loaded on the API server. Specify one circuit source. |
| `parameters` | Names with API parameter `path`, `min`, and `max`. Equal bounds set a fixed value. |
| `duration`, `time_step`, `measure_from` | Simulation duration, grid step, and start of the measurement window in seconds. |
| `trials`, `batch_size`, `concurrency`, `seed`, `timeout` | Trial count, jobs per batch, concurrent jobs, random seed, and batch deadline in seconds. |
| `metrics` | Named measurements and calculations in evaluation order. |
| `objectives` | Each entry specifies a `metric` and an optional `direction` of `minimize` or `maximize`. A `target` minimizes absolute error. |
| `constraints` | Each entry specifies a `metric` and an exclusive `min`, `max`, or both. |

Metric inputs can refer to parameters or earlier metrics by name.

- `analysis` takes a recorded `signal` and a characteristics `field` such as `average`, `rms`, or `peak_to_peak`.
- `conduction` takes `current`, `on_resistance`, and `threshold_voltage` in `inputs`.
- `switching` takes the switching-loss API fields in `inputs` and a `frequency` in hertz. The optimizer converts joules per event to watts.
- `sum` takes a list of `terms`. `ratio` takes two terms and uses the magnitude of the denominator.
- Any metric can specify `scale`. For example, the duty cycle scales the estimated conduction loss of the active switch.

The 48 V example uses a 1.2 Ω load and a 12 V target with a ±1 % tolerance.
Its loss objective estimates active-switch loss. It uses the inductor current to estimate switch current.
The ripple limit uses peak-to-peak voltage divided by the average voltage.
