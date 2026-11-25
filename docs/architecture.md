# Where things live

This page only points to where things are. The code shows how they work, [ADRs](adr/README.md) explain why, and [CONTEXT.md](https://github.com/tinix84/GeckoCIRCUITS/blob/main/CONTEXT.md) defines the terms.

## Builds

| Path | What |
|------|------|
| `pom.xml` | Desktop application (main build) |
| `pom-reactor.xml` | Reactor build covering the desktop, simulation core and REST API |
| `src/modules/gecko-simulation-core/` | GUI-free simulation core ([ADR-0013](adr/0013-gui-free-simulation-core.md)) |
| `src/modules/gecko-rest-api/` | Spring Boot REST API |
| `.github/workflows/` | CI, docs deploy, release and upstream sync |

## Desktop (`src/main/java/gecko/`)

| Path | What |
|------|------|
| `GeckoSim.java` | Entry point and operating-mode selection |
| `GeckoRemote*.java`, `GeckoCustomMMF.java` | MATLAB/Octave RMI and memory-mapped-file integration |
| `geckocircuits/allg/DesktopSimulationController.java` | Picks the Core or Legacy backend (`-Dgecko.backend`) |
| `geckocircuits/circuit/` | Circuit components, editors, Legacy backend matrices |
| `geckocircuits/control/` | CONTROL blocks and their panels |
| `geckocircuits/newscope/`, `geckocircuits/scope/` | Oscilloscope |
| `expressionscripting/`, `geckoscript/` | GeckoSCRIPT |
| `i18n/` | Translations |

## Simulation core (`src/modules/gecko-simulation-core/src/main/java/gecko/core/`)

| Path | What |
|------|------|
| `io/` | Circuit file parsing, topology and import issues |
| `preparation/` | Circuit preparation and Headless readiness ([ADR-0001](adr/0001-circuit-preparation-admission.md), [ADR-0002](adr/0002-circuit-preparation-interface.md)) |
| `simulation/` | Engine, Simulation session, Electrical time step, CONTROL runtime, periodic steady-state monitor ([ADR-0017](adr/0017-periodic-steady-state.md)), adaptive electrical sub-steps ([ADR-0018](adr/0018-adaptive-electrical-step.md)) |
| `simulation/solver/` | Matrix solver and initial conditions |
| `circuit/` | Matrix stampers, netlist, loss calculation |
| `control/` | Delay history and the periodic state contracts (`PeriodicStateSource`, `StatelessCalculation`) |
| `control/calculators/` | CONTROL calculators |
| `signal/` | Signal analysis (characteristics, Fourier) |

## REST API (`src/modules/gecko-rest-api/src/main/java/gecko/rest/`)

| Path | What |
|------|------|
| `controller/` | HTTP endpoints. The full contract is served at `/api-docs`. |
| `service/` | Simulation jobs, Batches, progress publishing |
| `config/` | Security, WebSocket and rate-limit configuration |
| `model/` | Request and response types |

The OpenAPI snapshot is [`docs/api/openapi.json`](api/openapi.json) (`scripts/snapshot-openapi.sh`). Clients are generated into `clients/` (`scripts/generate-sdks.sh`). Docker files are `src/modules/gecko-rest-api/Dockerfile` and `docker/`.

## Tests

| Path | What |
|------|------|
| `src/test/java/gecko/systemtests/RegressionTest.java` | The 21 education circuits checked against their Golden references |
| `src/test/resources/ipes/education/`, `src/test/resources/golden/` | Regression circuits and Golden references |
| `src/test/java/gecko/systemtests/SimulinkCoreParityTest.java` | Core vs Legacy backend parity |
| `src/test/java/gecko/geckocircuits/core/CorePackageValidationTest.java` | No GUI imports in the core |

## Documentation site

| Path | What |
|------|------|
| `mkdocs.yml`, `docs/` | Site source |
| `resources/` | Tutorial, example and article sources, copied into `docs/` by `scripts/sync-docs.py` |
| `scripts/generate-api-docs.py` | Generates `docs/api/core/` from the core module |
| `docs/releases/` | Release notes, one page per tag |

## REST persistence profiles

The REST API stores circuits, jobs and request budgets in memory by default.
Enable the optional backends with the standard Spring Boot configuration.

| Profile | Configuration | Stored data |
|---------|---------------|-------------|
| `postgres` | `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | Circuit uploads, circuit metadata, simulation job status and audit entries |
| `redis` | `SPRING_DATA_REDIS_HOST`, `SPRING_DATA_REDIS_PORT`, and `SPRING_DATA_REDIS_PASSWORD` when required | General and simulation submission budgets shared by API instances |

Set `SPRING_PROFILES_ACTIVE=postgres,redis` to enable both profiles. Give the PostgreSQL role permission to run the Flyway migrations. API instances that share a Redis database and deployment should use the same `GECKO_API_RATE_LIMIT_NAMESPACE`. Assign a different namespace to each independent deployment.

Waveforms, active executors, progress subscriptions and research bundle inputs are local to the instance that runs the job. After restart, the API can read the last recorded job status. A restart does not resume an active simulation. Use the original instance to access live progress and results.

For native integration tests in WSL, start PostgreSQL and Redis in Ubuntu. Set the Spring connection variables above and use a PostgreSQL test role that can create schemas. The test creates a separate schema per run.

```bash
GECKO_TEST_POSTGRES=true GECKO_TEST_REDIS=true \
  mvn -f pom-reactor.xml -pl :gecko-rest-api -am test \
    -Dtest=NativePersistenceTest -Dsurefire.failIfNoSpecifiedTests=false
```

### Tenant isolation

Add `tenant` to `SPRING_PROFILES_ACTIVE` and enable API key authentication. Assign every configured key to a tenant by setting `gecko.api.tenants.<keyId>`. The key ID is the SHA-256 digest of the API key. Access and refresh tokens use the current assignment.

Set `gecko.api.quota.<tenant>.max-circuits` and `gecko.api.quota.<tenant>.max-concurrent` to limit circuit uploads and outstanding simulation jobs. Pending jobs count towards the simulation quota. A batch reserves capacity for each simulation when the API accepts it. The `ADMIN` role can read usage and limits at `GET /api/v1/admin/tenants`.

Upload a circuit and use its ID to submit simulations in tenant mode. The API returns 404 for circuit, simulation and batch IDs from another tenant. Circuit and simulation records created before tenant mode have no tenant assignment.

PostgreSQL serializes quota admission across API replicas. When PostgreSQL releases a stopped replica's session lock, the next admission, quota lookup, or matching job read marks its abandoned jobs failed. The same admission can reuse the released quota.

Drain and stop all older API replicas before the first startup that applies Flyway migration V3. The migration marks remaining pending and running jobs from older versions failed because those jobs have no replica owner. Jobs with a terminal status keep that status.

Use a direct PostgreSQL connection or session pooling. Each API replica reserves one pool connection for its session lock. Set the pool size to at least two. Restart a replica if it loses its ownership session.
