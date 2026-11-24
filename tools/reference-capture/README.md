# Capture independent v2.02 reference traces

Use this tool to build and observe pinned v2.02 source.
It never invokes the Core backend.
The reference contract belongs to [#62](https://github.com/tinix84/GeckoCIRCUITS/issues/62).
Application comparison belongs to [#63](https://github.com/tinix84/GeckoCIRCUITS/issues/63).

## Prerequisites

- Linux with Java 8, Maven, Python 3.9 or later, and Xvfb.
- The v2.02 tag and its source objects in this repository.
- Two separate release worktrees at commit `b34b9b9d8110374097a1fc5839491e66cff5a39a`.
- A separate evidence directory with sufficient space.

The capture process uses an isolated home directory.
Its command records the Graal.js Nashorn compatibility option required by the release's editor library.

## Build the stock release and observer

Run these commands from the development worktree.
The example uses separate worktrees and evidence under the parent directory.
Choose new paths if those paths already contain work.

```bash
git branch codex/m4-v202-stock v2.02
git worktree add ../m4-v202-stock codex/m4-v202-stock
git branch codex/m4-v202-observer v2.02
git worktree add ../m4-v202-observer codex/m4-v202-observer
git -C ../m4-v202-observer apply "$PWD/tools/reference-capture/v202-observer.patch"
mkdir -p ../m4-reference-evidence-20261123
cd ../m4-v202-stock
mvn -B test dependency:build-classpath -Dmdep.outputFile=target/capture-classpath.txt
cd ../m4-trace-parity
```

Set `--jdk` to an external Java 8 installation.
Use `--stock`, `--observer`, and `--evidence` when the worktree paths differ from these defaults.
The following commands first preview each operation.

```bash
/usr/bin/python3 tools/reference-capture/capture.py prepare --build-group build-java8-2
/usr/bin/python3 tools/reference-capture/capture.py prepare --build-group build-java8-2 --execute
/usr/bin/python3 tools/reference-capture/capture.py run --build-group build-java8-2
/usr/bin/python3 tools/reference-capture/capture.py run --build-group build-java8-2 --execute
```

The observer changes the kernel at two locations.
One call records the command at consumption.
The other call records the completed electrical and CONTROL step.
The stock kernel receives neither call.
Both processes use the same release build and dependencies.

Existing incomplete outputs are retained and cause an error.
Use a new `--run-group` or `--build-group` after investigating a failed attempt.

## Validate the release evidence

```bash
/usr/bin/python3 tools/reference-capture/analyze.py --output ../m4-reference-evidence-20261123/reference-validation-1.json
cd tools/reference-capture
/usr/bin/python3 -m unittest -v test_analyze
cd ../..
```

Each circuit has one stock witness and three fresh observer processes.
Validation requires identical full-precision repeats and matching stock SCOPE witnesses.
Native timestamps, complete endpoints, physical units, binary states, and artifact hashes receive separate checks.
The pinned input file supplies an independent inventory of components, commands, device states, and auxiliary currents.
Every decimal value must satisfy its normalized twelve-place rounding interval before it contributes to tolerance evidence.
Each process records its JVM identity, start time, and Java version.

The decimal trace uses twelve decimal places in scientific notation.
The companion hexadecimal trace preserves the binary values for serialization-error measurements.
The proposed absolute floor uses measured serialization error plus two binary units in the last place, rounded upward.
These proposals describe capture resolution, not physical uncertainty or a proven allowance for every runtime environment.

## Publish fixtures for review

```bash
/usr/bin/python3 tools/reference-capture/publish.py
/usr/bin/python3 tools/reference-capture/publish.py --execute
```

This operation creates a new `traces/v202` namespace.
It preserves the existing development baseline files.
The evidence bundle records source and build hashes, physical schemas, process receipts, and tolerance proposals.

Use a new build group and run group after capture implementation changes.
Pass the same groups to the analysis and publication commands.
To preserve existing fixtures, use `--supplement --evidence-name reference-acceptance.json`.
Supplement mode requires exact decoded trace equality and matching physical schemas before it appends evidence.

Stop for the user's absolute-tolerance review before any Core comparison.
The manifest records approval before comparison becomes enabled.
The existing application, reactor, and regression gates remain necessary after the review.

## Compare Core with the accepted references

The user approved absolute floors of `6e-11 V`, `6e-11 A`, and `4e-9 ohm` on 2026-11-23.
The manifest records this decision.
The comparison retains the 1% relative limit, exact binary checks, and 1 ps timestamp limit.

```bash
mvn -f src/modules/gecko-simulation-core/pom.xml test -Papplication
bash scripts/verify-ci.sh
xvfb-run -a mvn -f pom-reactor.xml test -pl . -am -Pregression
```

`ReleaseTraceApplicationTest` runs all ten circuits and rejects a 5% in-memory reference mutation.
It also rejects command/state flips and shifted or missing steps during matching simulations.
`PhysicalTraceComparisonTest` checks binary flips, timestamp shifts, missing values, nonfinite values, and unit-specific floors.
`ReleaseTraceFixtureValidationTest` rejects malformed CSV headers, missing signals, truncated rows, duplicate times, and empty traces.
Accepted files remain unchanged during these controls.

The Core mapping resolves saved component identifiers and terminal connectivity before reading numerical values.
Node voltage comparisons use the release's explicit physical datum.
Auxiliary currents remain separate from component current reports.

Release compatibility includes two observable semiconductor conventions in `LKMatrices.java`:

- THYR and IGBT current reports subtract `Uf` even while the device blocks.
- Incremental switching corrections use `Uf/R` in both states. The next ordinary vector build omits the blocking source term.

These conventions can make a reported device current differ from the MNA branch current.
Focused tests check both quantities separately and retain source-to-resistor current balance.
The standalone stamper equations retain their existing behavior.
Saved anonymous CONTROL ports use the release terminal layouts to recover connections that touch on the schematic.
Explicit captured terminal positions take precedence over this recovery.
