# Kubernetes (local development)

This Kustomize bundle runs the UI/API, PostgreSQL 17, Redis 8.2, and a single-node
Kafka 4.0 KRaft broker in the `concurrent-balance-service` namespace. It mirrors
the Compose development stack and enables the demo endpoints. It requires a
Linux Kubernetes cluster, kubectl with Kustomize support, a default StorageClass
that provisions ReadWriteOnce volumes, and roughly 6 GiB of available memory.
Prometheus, Grafana, and namespace-scoped kube-state-metrics are included.
The stack uses 8 GiB total PersistentVolumeClaims.

## 1. Build and make images available

From the repository root, run the tests and build the application image:

```powershell
.\mvnw.cmd clean verify
docker build -t concurrent-balance-service:local .
```

The existing Dockerfile builds the frontend and backend. The app manifest uses
`imagePullPolicy: Never` so Kubernetes uses your local image instead of trying
to pull it from Docker Hub. The exact image tag must exist on every node that
can run the app.

- Docker Desktop: enable Kubernetes and select its context with
  `kubectl config use-context docker-desktop`. Confirm the Kubernetes runtime
  can see the image; some Docker Desktop configurations use a separate image
  store. If it cannot, use a registry reachable by your cluster and change the
  app image and pull policy in `app.yaml`.
- kind: `kind load docker-image concurrent-balance-service:local` (add
  `--name <cluster-name>` for a named cluster).
- minikube: `minikube image load concurrent-balance-service:local`.
- Remote clusters: push a versioned image to your registry and update the app
  image and `imagePullPolicy` to `IfNotPresent` before deploying. Configure
  `imagePullSecrets` if that registry requires authentication.

Dependency images are `postgres:17-alpine`, `redis:8.2-alpine`, and
`apache/kafka:4.0.2`; these use `IfNotPresent`. If registry access is blocked,
load them into **each node's** image store as well. For kind/minikube, use the
same image-load commands for each dependency. Host `docker load` alone does
not populate a separate Kubernetes runtime's image store. Building the app
also requires the Dockerfile base images and npm/Maven/Ubuntu package access;
Kubernetes does not bypass the Docker Hub 403 encountered during image builds.

## 2. Create the namespace and database Secret

Check the selected cluster first:

```powershell
kubectl config current-context
kubectl apply -f k8s/namespace.yaml
```

If you already have a local `.env` with a strong `POSTGRES_PASSWORD`, use it to
create the Secret without placing credentials in a tracked manifest:

```powershell
kubectl -n concurrent-balance-service create secret generic postgres-credentials --from-env-file=.env
kubectl -n concurrent-balance-service create secret generic monitoring-credentials --from-env-file=.env
```

If `.env` does not exist, copy `.env.example` to `.env` and replace the password
first. Also set a separate `GRAFANA_ADMIN_PASSWORD` in `.env` for the monitoring
Secret. The database Secret requires a nonempty `POSTGRES_PASSWORD` key. Run Secret
creation once; an existing Secret is kept when you reapply the bundle.

PostgreSQL reads the initialization password only when its data directory is
empty. Changing the Secret on an existing installation does not change the
database role password. Rotate the database password and Secret together,
then restart the app. Keep `.env` out of version control.

## 3. Deploy and open the app

```powershell
# Preview and validate against your cluster before applying.
kubectl kustomize k8s
kubectl apply --dry-run=server -k k8s
kubectl apply -k k8s
kubectl -n concurrent-balance-service rollout status statefulset/postgres --timeout=300s
kubectl -n concurrent-balance-service rollout status statefulset/redis --timeout=300s
kubectl -n concurrent-balance-service rollout status statefulset/kafka --timeout=300s
kubectl -n concurrent-balance-service rollout status deployment/app --timeout=300s
kubectl -n concurrent-balance-service rollout status deployment/prometheus --timeout=300s
kubectl -n concurrent-balance-service rollout status deployment/grafana --timeout=300s
kubectl -n concurrent-balance-service rollout status deployment/kube-state-metrics --timeout=300s
kubectl -n concurrent-balance-service port-forward service/app 8080:8080
```

Open http://localhost:8080 or http://localhost:8080/swagger-ui/index.html.
Port forwarding binds to localhost by default. All Services are internal;
there is no public LoadBalancer or Ingress. The Technical monitoring page shows
app metrics and pod/workload observations. See the
[monitoring guide](../monitoring/README.md) for Grafana, port forwarding,
additional images, and metric scope.

The app's init containers wait for PostgreSQL and Kafka before Spring starts.
Flyway initializes the database and the app creates its development Kafka
topic. Startup and liveness probes use `/actuator/health/liveness`; readiness
uses `/actuator/health/readiness`, including database connectivity. Redis is
optional for application readiness, as in Compose.

## Updates and troubleshooting

After rebuilding and loading a replacement `:local` app image, restart the
Deployment (the unchanged tag alone does not trigger a rollout):

```powershell
kubectl -n concurrent-balance-service rollout restart deployment/app
kubectl -n concurrent-balance-service rollout status deployment/app --timeout=300s
kubectl -n concurrent-balance-service get pods,pvc,services
kubectl -n concurrent-balance-service logs deployment/app -c app --tail=100
kubectl -n concurrent-balance-service get events --sort-by=.metadata.creationTimestamp
```

`ErrImageNeverPull` means the app image is absent from the scheduled node.
`ImagePullBackOff` on a dependency means that node cannot fetch its image.
For Pending PVCs, check `kubectl get storageclass` and configure a provisioner
or set a suitable `storageClassName` on the volume claim templates before first
deployment. Inspect init-container logs with `-c wait-for-postgres` or
`-c wait-for-kafka` when the app remains in Init state.

## Stop and retain data

```powershell
kubectl -n concurrent-balance-service delete deployment app prometheus grafana kube-state-metrics
kubectl -n concurrent-balance-service delete statefulset postgres redis kafka
```

These commands stop workloads while retaining Services, Secrets, and PVCs.
Apply the bundle again to restart using the retained data. Compose volumes
are separate from Kubernetes PVCs; existing Compose balances are not imported.
Deleting the namespace deletes its PVCs and may permanently delete their data.
`kubectl delete -k k8s` also deletes the namespace and therefore its data.

## Deployment scope

This is a development stack: one database, one Redis instance, one Kafka broker,
PLAINTEXT internal messaging, and enabled demo endpoints. Do not scale Kafka by
changing `replicas`; node IDs and quorum membership require a different broker
configuration. For production, use managed services or operators, backups,
TLS/authentication, network policies, an external secret manager, and versioned
registry images, and disable `BALANCE_DEMO_ENABLED`. The app runs as a non-root
user with a read-only root filesystem, bounded resources, and writable `/tmp`.

References: [Kustomize](https://kubernetes.io/docs/tasks/manage-kubernetes-objects/kustomization/)
and [StatefulSets](https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/).
