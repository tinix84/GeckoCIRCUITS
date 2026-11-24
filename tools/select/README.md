# Semiconductor selection

This tool filters a semiconductor catalog at a specified electrical and thermal operating point.
It ranks the remaining devices by estimated loss and then price.
It gives the loss breakdown, estimated junction temperature, and closest limit for every candidate.

## Run the example

1. Install the tool dependencies.

```bash
conda run -n ntbees2 pip install -r tools/select/requirements.txt
```

2. Run the selection from the repository root.

```bash
conda run -n ntbees2 python -m tools.select.rank \
  tools/select/example.yaml tools/select/fixtures/devices.csv
```

3. Run the acceptance tests.

```bash
conda run -n ntbees2 python tools/select/acceptance.py
```

The example catalog has ten synthetic devices.
To use manufacturer curves, install the [generated SDK](../../clients/README.md) and start the REST API.
Set `GECKO_API_BASE_URL`, `GECKO_API_KEY`, or `GECKO_ACCESS_TOKEN` as applicable.
Set `GECKO_SELECT_TEST_API=1` to include the live curve check in acceptance.
Use the seeded catalog after installing the vendor loss library.

```bash
conda run -n ntbees2 python -m tools.select.rank \
  tools/select/vendor_example.yaml tools/select/vendor_catalog.csv
```

The [catalog sources](VENDOR_CATALOG.md) give the ratings and thermal assumptions.

## Operating point and catalog

Give voltage, current, switching frequency, and ambient temperature in the YAML file.
Specify output power and efficiency target to calculate the maximum device loss budget.
Specify the gate-drive current and, if known, the conduction fraction.
The budget applies to one device at this operating point. Converter efficiency also depends on the other loss sources.

Give the voltage and current ratings, on-resistance or saturation voltage, gate charge, package, price, thermal resistance, and maximum junction temperature in the catalog.
Use the same currency for all prices. Leave the price blank if no quote is available.
Use junction-to-ambient thermal resistance, including the cooling path, in `rth_c_per_w`.
The column names have the units. Use coulombs for `qg_c` and a fraction for `efficiency_target`.

The tool applies 80 % voltage derating and 70 % current derating.
It calculates junction temperature from ambient temperature, estimated loss, and thermal resistance.
The estimated junction temperature must remain below the catalog limit.

Set `curve_file` to a [vendor loss CSV](../../src/main/resources/devices/) relative to the catalog.
The tool scales switching energy to the operating voltage and calls the detailed-loss endpoint.
It updates the loss and junction temperature until the temperature converges or reaches the limit.
The tool uses each curve type independently when the data cover the operating current.
For a missing conduction curve, the tool uses the static on-state rating.
For a missing switching curve, it estimates the transition time from gate charge divided by gate-drive current.
If both switching data and gate charge are unavailable, the tool excludes the device.
