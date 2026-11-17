# Watching Balance Lab 📈

Open **Technical monitoring** in Balance Lab (`http://localhost:8080/#monitoring`).
The backend reads fixed Prometheus queries; browsers never receive Docker
sockets, Kubernetes tokens, arbitrary query access, or internal credentials.

| Evidence | Docker Compose | Kubernetes |
| --- | --- | --- |
| HTTP rate, 5xx, p95; JVM heap; JDBC connections | Micrometer | Micrometer, per-pod discovery |
| Shared outbox backlog and publisher failures | Micrometer | Micrometer |
| Container last seen, CPU cores, working memory | cAdvisor | Not collected |
| Pod readiness, container restarts, desired/ready replicas | Not applicable | kube-state-metrics |
| History and charts | Prometheus + Grafana | Prometheus + Grafana |

## Docker Compose

Add a strong, separate `GRAFANA_ADMIN_PASSWORD` to your existing `.env` (see
`.env.example`), then run from the repository root:

```powershell
docker compose up --build -d --wait
```

- Balance Lab: http://localhost:8080/#monitoring
- Grafana: http://localhost:3000/d/balance-lab (user `admin`, password from `.env`)
- Prometheus: http://localhost:9090 (query UI and Status → Targets)

The datasource and 14-panel dashboard are provisioned automatically.
`GRAFANA_PORT` and `PROMETHEUS_PORT` change host ports; the app's Grafana link
follows `GRAFANA_PORT`. Named volumes retain monitoring data. Prometheus keeps
up to 7 days or 1 GB. Changing Grafana's initialization password does not rotate
an existing user's password; use Grafana's administration tools.

cAdvisor is a privileged exporter with read-only host mounts, including
`/var/run` for Docker discovery. Read-only socket mounts do **not** restrict
Docker API calls; treat the exporter as trusted host software. The app has no
socket mount, and cAdvisor has no published port. This requires a Linux Docker
engine. On Docker Desktop it observes the Linux VM; Enhanced Container
Isolation, containerd configurations, or unavailable mounts may prevent Docker
labels/resource observations. An empty list is shown explicitly as no evidence.

`prometheus/docker.yml` scopes container series to Compose project
`concurrent-balance-service`. Update this filter if using another `-p` name,
then recreate Prometheus. Last seen does not prove a Docker healthcheck passed;
Docker restart counts are not collected.

Docker Hub 403 still requires resolving registry access or preloading images.
Additional images are `quay.io/prometheus/prometheus:v3.16.0`, `grafana/grafana:12.4.13`, and
`ghcr.io/google/cadvisor:0.60.4`. App builds also need base images and package
downloads.

### Grafana without Docker Hub

For Linux amd64 (including this project's Windows Docker Desktop setup), build
Grafana locally from its official standalone release instead of pulling its
Docker Hub image:

```powershell
docker build --platform linux/amd64 -t balance-grafana:12.4.13 monitoring/grafana-local
docker pull ghcr.io/google/cadvisor:0.60.4
```

Set `GRAFANA_IMAGE=balance-grafana:12.4.13` in `.env`, then run the normal command:

```powershell
docker compose up -d --pull never
```

The fallback uses a digest-pinned Prometheus BusyBox/glibc base from Quay and
verifies Grafana's archive against its published SHA-256. It runs as UID/GID 472
and supports the same provisioning files, environment settings, and persistent
data paths. Quay and `dl.grafana.com` must be reachable to build it. This is a
project-maintained fallback image, not Grafana's Docker image. ARM64 hosts need
a matching archive and checksum; this fallback targets amd64 only.

An `Interrupted` cAdvisor pull after another image fails does not mean cAdvisor
is unavailable. Pull it separately as above. `--pull never` requires all images
to be cached; omit it when other reachable registries still need downloading.

If your cached app image predates monitoring, its `/api/monitoring` and
`/actuator/prometheus` endpoints will return 404. When Docker Hub also blocks
the normal app build, refresh the existing app runtime using a host-built JAR:

```powershell
# Build the current UI first (Node 22).
npm --prefix frontend ci
npm --prefix frontend run build
.\mvnw.cmd package
# Reuse the cached app image's Java runtime and healthcheck.
docker build --pull=false -f deploy/app-local/Dockerfile -t concurrent-balance-service-app:latest target
docker compose up -d --no-build --pull never
```

This fallback requires Java 21, a previously built
`concurrent-balance-service-app:latest` image, and Maven/npm dependencies
available locally or over the network. It updates application code and UI,
while retaining the cached runtime's OS/JRE; rebuild with the normal Dockerfile
when registry access is restored to update that runtime too. If using another
Compose project name, pass its cached app image via `--build-arg RUNTIME_IMAGE`
and set the output `-t` to the same project's app image tag.

For Kubernetes, load `balance-grafana:12.4.13` onto each node as described in the
[cluster guide](../k8s/README.md), then use the optional overlay:

```powershell
kubectl apply --dry-run=server -k deploy/local-grafana
kubectl apply -k deploy/local-grafana
```

Source: [official Grafana archive](https://dl.grafana.com/oss/release/grafana-12.4.13.linux-amd64.tar.gz)
and [published checksum](https://dl.grafana.com/oss/release/grafana-12.4.13.linux-amd64.tar.gz.sha256).

## Kubernetes

Follow [the cluster guide](../k8s/README.md) for namespace, images, and storage.
Add `GRAFANA_ADMIN_PASSWORD` to `.env`, then create the monitoring Secret once:

```powershell
kubectl -n concurrent-balance-service create secret generic monitoring-credentials --from-env-file=.env
kubectl apply --dry-run=server -k k8s
kubectl apply -k k8s
kubectl -n concurrent-balance-service rollout status deployment/prometheus --timeout=300s
kubectl -n concurrent-balance-service rollout status deployment/grafana --timeout=300s
kubectl -n concurrent-balance-service rollout status deployment/kube-state-metrics --timeout=300s
```

In separate terminals:

```powershell
kubectl -n concurrent-balance-service port-forward service/app 8080:8080
kubectl -n concurrent-balance-service port-forward service/grafana 3000:3000
kubectl -n concurrent-balance-service port-forward service/prometheus 9090:9090
```

Only kube-state-metrics and Prometheus receive Kubernetes service-account
access. Their Roles allow discovery within this namespace, with no cluster-wide
or mutation permissions. The app has no Kubernetes token. Prometheus discovers
individual app pods to support multiple replicas. The shared outbox gauge uses
**max**, avoiding double counting across app replicas.

Monitoring adds 3 GiB of PVC storage. Load Prometheus and Grafana images plus
`registry.k8s.io/kube-state-metrics/kube-state-metrics:v2.18.0` into each node's
runtime when registries are unavailable. No cAdvisor DaemonSet or cluster
CPU/memory scraping is installed. If changing namespace, update both the
Prometheus discovery namespace and kube-state-metrics `--namespaces` argument.

## Local Java

Monitoring is disabled by default outside the supplied deployment manifests:

```powershell
$env:MONITORING_ENABLED = 'true'
$env:MONITORING_PLATFORM = 'docker' # local, docker, or kubernetes
$env:PROMETHEUS_URL = 'http://localhost:9090'
$env:GRAFANA_PUBLIC_URL = 'http://localhost:3000/d/balance-lab'
```

Prometheus must also scrape that Java process. For a host Java process on Docker
Desktop, use `host.docker.internal:8080` in its `balance-app` scrape target.

## Meaning and limits

Scrapes/UI polling run every 15 seconds. Queries are cached for 5 seconds, use a
1-second Prometheus execution timeout and 2-second HTTP timeout, and return up
to 200 series per metric. Samples carry observation timestamps. Missing,
non-finite, failed, and stale values are not converted into healthy zeroes.
After 45 seconds without a fresh target observation, the UI marks it stale.
The refresh button and polling toggle control monitoring refreshes too.

Rates/p95 use a 2-minute window and need at least two scrapes. HTTP measurements
exclude actuator and monitoring polls; console snapshot polling is included.
5xx failures are distinct from expected 4xx business rejections. No traffic
can produce an undefined p95. Disabled Kafka metrics remain absent. Prometheus
`rate` handles counter resets. Outbox backlog does not prove consumer delivery.

Actuator exposes `/actuator/prometheus` alongside health. This is a local
development app without authentication: keep app/Prometheus/Grafana local or
protect access with authenticated network boundaries. Logs, traces, alerting,
Kafka consumer lag, database locks, and PostgreSQL/Redis/broker exporters are
not included. Container/pod readiness does not prove business behavior.

Validate before deploying:

```powershell
docker compose config --quiet
docker compose run --rm --no-deps --entrypoint promtool prometheus check config /etc/prometheus/prometheus.yml
kubectl kustomize k8s
```

For missing data, inspect Prometheus Targets and exporter logs. Recreate Compose
services after provisioning/scrape changes. Kubernetes hashed ConfigMaps roll
pods on `kubectl apply -k k8s`. Rebuilt local app images still need loading and
a Deployment restart. Namespace deletion also deletes monitoring PVCs.

References: [Spring Boot metrics](https://docs.spring.io/spring-boot/3.5/reference/actuator/metrics.html),
[Prometheus configuration](https://prometheus.io/docs/prometheus/latest/configuration/configuration/),
[cAdvisor](https://github.com/google/cadvisor),
[kube-state-metrics](https://github.com/kubernetes/kube-state-metrics),
[Grafana provisioning](https://grafana.com/docs/grafana/latest/administration/provisioning/).
