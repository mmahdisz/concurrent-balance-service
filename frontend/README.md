# Balance Lab

React + TypeScript + Vite console for the existing Spring Boot balance service.
TanStack Query refreshes consistent PostgreSQL snapshots every two seconds.
No mock balances or fabricated infrastructure-health indicators are used.

Technical monitoring reads `/api/monitoring` every 15 seconds for Prometheus
app metrics and Docker container or Kubernetes pod/workload observations.
Missing/stale evidence is explicit; the page links to a provisioned Grafana
dashboard. See [monitoring setup and scope](../monitoring/README.md).

## Develop

Requires Node 22 (Node 20.19+ also works), Java 21, and the existing PostgreSQL setup.
Start the backend using the repository README. Enable experiments for local Java with:

~~~powershell
$env:BALANCE_DEMO_ENABLED = "true"
.\mvnw.cmd spring-boot:run
~~~

In a second terminal, from frontend:

~~~powershell
npm ci
npm run dev
~~~

Open http://localhost:5173. Vite proxies /accounts, /api, /actuator, and API
documentation to http://localhost:8080. Set API_TARGET before starting Vite to
use a different backend address. Credentials stay on the backend.

## Build and serve together

~~~powershell
npm ci
npm test
npm run build
cd ..
.\mvnw.cmd package
~~~

Maven copies frontend/dist into the JAR's static resources. Open the backend's
root URL to use the UI. A backend-only build remains supported without Node;
run the frontend build first when the JAR should include the UI.

The root Dockerfile builds and tests the frontend automatically, then packages
both applications. The existing docker compose up --build -d --wait command
serves the UI at http://localhost:8080 and enables the demonstration endpoints.
Hash navigation makes all UI views work without interfering with API routes.

## What is persisted

- Accounts and successful receipts come from PostgreSQL.
- Monetary values in snapshot responses are decimal strings. Inputs are encoded
  as exact decimal JSON literals; numeric monetary business responses are parsed
  losslessly before ordinary JSON parsing can round them. No fixed currency,
  scale, or BIGINT money limit is assumed.
- Credit, debit, and transfer use the original /accounts API and transactionId
  field. Keep the ID when retrying an uncertain response; generate a new ID for a
  new operation. Editing a submitted payload generates a fresh ID unless explicit
  conflict mode is enabled. Retry original payload resends the unchanged submission.
  Mutations are never retried automatically.
- The history includes successful receipts only. Rejected API requests appear in
  this tab's inspector. Idempotent acknowledgements do not create new receipts.
- The inspector retains the last 100 explicit actions in memory, including exact
  raw request/response bodies and browser round-trip duration. Polling is omitted.
- The activity chart measures new committed receipts observed since opening this
  tab; it is not a historical server throughput series.
- The outbox backlog counts pending database rows. Publishing enabled is a
  configuration flag, not a claim that Kafka is reachable.
- Persian/RTL is the default, with an English option and persisted light/dark
  themes and density. Times use UTC. Snapshots older than 10 seconds or with a
  failed refresh are marked stale; pausing polling is labeled separately.
- The request path and selectable structure map are source-based explanations.
  Monitoring distinguishes database observations from unknown cache/delivery
  health. Prometheus metrics are available through the monitoring stack;
  trace/log evidence and a durable Kafka observer are not connected.

## Concurrency experiments

BALANCE_DEMO_ENABLED defaults to false outside Compose. Each run creates two
unique lab-UUID accounts and executes bounded work in server threads. Existing
balance, locking, receipt, Redis, and Kafka behavior is exercised unchanged.
These are shared-stack demonstrations, not isolated infrastructure fault tests.
The current API does not support cancellation or durable reproducible run reports.

POST /api/demo/runs accepts scenario (COMPETING, DUPLICATE, OPPOSING, BURST),
requests (2..100), and concurrency (2..16), returning 202 with a run ID.
GET /api/demo/runs/{id} reports the result. One active run is allowed per service
instance; concurrent starts return 429. The last 20 reports remain in memory
until restart/eviction. This tab remembers the last run ID across reloads.
Account IDs and transaction IDs in a report can be inspected through history.

COMPETING always sends two transfers of 80 against 100 units. DUPLICATE uses
one ID for repeated transfers of 10. OPPOSING and BURST alternate direction
between two accounts with 1,000 units each. Configure a larger burst and worker
count to observe contention. Reported p95 is service-call duration, including
lock waits; total elapsed includes setup and verification.

Run verification waits for every task, then checks actual balances and unique
receipts. Reports distinguish accepted replays, insufficient-funds rejections,
and technical errors. FAILED means the final result could not be verified;
partial commits may exist. Lab balances and receipts persist, and no reset or
deletion action is provided.

GET /api/console/snapshot returns totals, 20 accounts (accountOffset),
and 20 receipts (receiptOffset), with an optional exact account filter matching
either side. All reads use one REPEATABLE_READ snapshot. Totals cover the full
database regardless of pagination or history filtering.

## Verify

~~~powershell
npm test
npm run build
~~~

For deterministic frontend tests against a Vite server, without a backend:

~~~powershell
# Start npm run dev in another terminal first.
$env:UI_BASE_URL = "http://127.0.0.1:5173"
npx playwright test e2e/frontend.spec.ts
~~~

These tests use HTTP fixtures only in the test process to cover decimal requests,
retry/conflict semantics, field errors, unavailable evidence, themes, RTL, keyboard
focus and all sections at 320px and desktop widths.

Separate integration browser tests exercise real accounts, transfers, replay, insufficient funds,
inspection, experiments, mobile layout, and connection recovery. Start a
disposable backend with demo enabled, then:

~~~powershell
$env:UI_BASE_URL = "http://localhost:18082"
npx playwright test e2e/console.spec.ts
~~~

The browser suite uses locally installed Chrome. It creates durable test
accounts, so use a disposable database. Screenshots are saved under test-results.
Backend integration tests use PostgreSQL Testcontainers and run in the root
Maven suite, including all four scenarios and exact-value snapshot checks.
