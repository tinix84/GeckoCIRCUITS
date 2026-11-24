# Buck-converter surrogate

The sweep runs 2,000 Latin-hypercube samples through the GeckoCIRCUITS batch API.
It varies input voltage, inductance, capacitance, switching frequency, duty cycle, and load resistance.
The multilayer perceptron predicts average output voltage, peak-to-peak ripple, switch conduction loss, and switch switching loss.

## Generate, train, and predict

1. Install the [generated Python SDK](../../clients/README.md).
2. Install the dependencies.

```bash
conda run -n ntbees2 pip install -r tools/surrogate/requirements.txt
```

3. Start a local API with capacity for the full sweep.

```bash
java -Xmx5500m -jar src/modules/gecko-rest-api/target/gecko-rest-api-1.0.0.jar \
  --server.address=127.0.0.1 --gecko.jobs.retained=2250 --gecko.jobs.batches=220 \
  --gecko.api.rate-limit=24000 --gecko.api.rate-limit-simulation=12000
```

4. Run the acceptance script from the repository root.

```bash
conda run -n ntbees2 python tools/surrogate/acceptance.py --output surrogate-output
```

5. Use the model with an operating point inside the training bounds.

```bash
conda run -n ntbees2 python -m tools.surrogate.predict surrogate-output/buck_surrogate.joblib \
  --Vin 48 --L 0.00017 --C 0.00012 --fsw 25000 --D 0.35 --Rload 1.5
```

Set `GECKO_API_BASE_URL`, `GECKO_API_KEY`, or `GECKO_ACCESS_TOKEN` as applicable.
The sweep uses [buck_sweep.yaml](buck_sweep.yaml) for the bounds, sample seed, and measurement window.
The Parquet file stores the six inputs and four outputs, with the seed, simulation grid step, and sweep duration in its metadata.
The training command prints the data set and model sizes and the error and speed measurements for the fixed 20 % holdout.

Use `python -m tools.surrogate.sweep` and `python -m tools.surrogate.train` to run the steps independently.
Load only a trusted local Joblib file.
Model inputs use SI units. The model estimates the same active-switch losses as the sweep.
Prediction timing uses batch inference on the holdout. Simulation timing includes submission, polling, and the metric requests.
