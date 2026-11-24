
# Research bundles

A research bundle stores the inputs and results of a GeckoCIRCUITS simulation.
Use it to repeat a completed simulation on another installation.

## Run the API

1. Check out the source revision for the study.
2. Open a shell in the repository root.
3. Start the local container.

   ```bash
   export GECKO_GIT_SHA="$(git rev-parse HEAD)"
   export GECKO_RESEARCH_TAG="$GECKO_GIT_SHA"
   docker compose -f docker/research/compose.yaml up -d
   ```

The Compose file builds the image from the checkout if the specified image tag is absent.
Use a clean checkout and keep the image tag or image digest with the study.
The API listens on `http://localhost:8080/gecko`.

## Export and verify a bundle

1. Submit a simulation.
2. Poll the simulation until its status is `COMPLETED`.
3. Download its bundle with `GET /api/v1/simulations/{simulationId}/bundle`.
4. Send the ZIP as the body of `POST /api/v1/bundles/verify`. Set `Content-Type` to `application/zip`.
5. Check that the response contains `passed: true`.

Use [the buck notebook](https://github.com/tinix84/GeckoCIRCUITS/blob/dev/tools/notebooks/reproduce_buck.ipynb)
for a complete example. Generate and install the Python client first.

```bash
bash scripts/generate-sdks.sh
conda run -n ntbees2 python -m pip install ./clients/python
conda run -n ntbees2 python -m pip install -r tools/notebooks/requirements.txt
conda run -n ntbees2 python tools/notebooks/acceptance.py
```

## Bundle contents and comparison

- `circuit.ipes` contains the captured circuit with its embedded resources.
- `request.json` contains the submitted simulation settings.
- `results/<index>.csv` contains the samples for one signal.
- `manifest.json` lists the signals in file-index order. It records the GeckoCIRCUITS version, Git commit, Java version, creation time, and the SHA-256 of each payload file.

The verifier checks all payload hashes before it runs the circuit. It uses the bundled circuit even if the original request used a server path or a registry ID.

For each signal, divide the maximum absolute sample difference by the peak magnitude of the reference signal. The signal passes at or below 1%. A zero reference passes only at zero error. A missing signal, sample-count mismatch, or different sample grid fails verification.

## Publish to Zenodo

1. Sign in to Zenodo.
2. Select **New upload**.
3. Add the verified ZIP.
4. Select **Dataset** as the resource type.
5. Enter the title, creators, license, and description.
6. Record the image tag or digest in the description.
7. Preview the deposit.
8. Publish it when it is ready.
9. Copy the digital object identifier (DOI) from the published record.

See the [Zenodo upload instructions](https://help.zenodo.org/docs/deposit/create-new-upload/) for the deposit form and DOI options.
