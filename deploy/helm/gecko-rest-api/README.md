# GeckoCIRCUITS REST API Helm chart

Build the image with the REST API Dockerfile. Push the image to a registry that the cluster can access,
or load it into a local kind cluster.

```bash
docker build -f src/modules/gecko-rest-api/Dockerfile -t geckocircuits/rest-api:local .
kind load docker-image geckocircuits/rest-api:local
helm upgrade --install gecko deploy/helm/gecko-rest-api --set image.tag=local --wait
kubectl port-forward service/gecko 8080:8080
curl --fail http://localhost:8080/gecko/api/health
```

The chart activates the `observability` profile and uses one replica. The readiness and liveness probes
use the application context path `/gecko`. The legacy health endpoint also works at `/gecko/api/health`.

## Configuration

- Use `config.profiles` to add the applicable PostgreSQL, Redis, or tenant profiles.
  Use `config.extraEnv` for non-secret settings such as a database URL.
- Set `auth.enabled=true` before exposing the API. Use `auth.existingSecret` for a Secret that provides
  `GECKO_API_KEYS` and, when JWT is enabled, `GECKO_API_JWT_SECRET`. The chart can also create the Secret
  from `auth.keys` and `auth.jwtSecret`.
- Enable `ingress.enabled` and set `ingress.host` to configure Ingress. The Service expects the `/gecko` prefix.
- Enable `autoscaling.enabled` to target CPU utilization. Configure the cluster Metrics Server, PostgreSQL and
  Redis for replicas. Simulation waveform data stays on the replica that computed it. Route requests for live
  progress and retained waveforms to that replica.
- PostgreSQL readiness checks that the replica still owns its session. If the Redis profile is active,
  readiness also checks Redis. Liveness only checks whether the API process can continue to run.

## Prometheus and Grafana

Scrape `/gecko/actuator/prometheus` with a configured API key or a valid access token. Set
`config.metricsPublic=true` to allow anonymous scrapes. Import the dashboard from
`deploy/grafana/gecko-rest-api.json` and select a Prometheus data source.

The duration histogram measures elapsed time from job creation to its terminal state. The queue depth
and active gauges measure the local simulation executor on each pod.
