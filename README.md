# Concurrent Balance Service

An account balance service supporting credit, debit, and transfer, with PostgreSQL
persistence, concurrent updates, idempotent transaction IDs, and reliable Kafka balance events.

## Running

Requires Java 21 and Docker with Linux containers. Run the default suite:

~~~bash
./mvnw clean verify
~~~

On Windows, use mvnw.cmd. Every database integration test runs against a disposable
PostgreSQL 17 Testcontainers database with the production Flyway migrations.
Spring manages each container with its cached test context, and connection details
use dynamic ports and test-only credentials. Docker must be available; database
checks fail if it is unavailable. The Kafka integration tests also start an embedded
broker. Plain service unit tests use in-memory Java fixtures and need no database.

## Running with Docker

Requires Docker with Linux containers and the Docker Compose plugin. Copy
.env.example to .env and replace POSTGRES_PASSWORD with a long random value before
starting. .env is ignored by Git. Java and Maven are included in the build image.
Run ./mvnw clean verify before building; the image build packages the application
with tests skipped, while the
integration suite runs on the Docker-capable host or CI runner.

~~~bash
cp .env.example .env
# Edit .env and set POSTGRES_PASSWORD.
docker compose up --build -d --wait
~~~

On PowerShell, use Copy-Item .env.example .env. The API is available at
http://localhost:8080 and Swagger UI at http://localhost:8080/swagger-ui/index.html.
The application waits for PostgreSQL, Redis, and Kafka healthchecks before starting.
Its healthcheck calls /actuator/health/readiness, which includes database connectivity.
Redis is optional for readiness because durable JDBC receipts protect balance
operations when the cache is unavailable.

~~~bash
docker compose ps
docker compose logs -f app
docker compose down
~~~

Recreating containers preserves named volumes: postgres-data contains balances,
idempotency receipts, Flyway history, and pending Kafka events; redis-data contains
Redis AOF data; kafka-data contains the broker log. Do not use docker compose
down -v unless you intend to delete the database. PostgreSQL major upgrades require a supported
upgrade or dump/restore procedure; do not simply change its image major version.

The image uses a Java 21 JRE, a non-root user, a read-only root filesystem, and
writable /tmp. It needs no local database files. Only health is exposed through
Actuator. Application shutdown has a 30-second grace period.

Compose publishes the API, PostgreSQL, Redis, and Kafka on localhost. APP_PORT,
POSTGRES_PORT, REDIS_PORT, and KAFKA_PORT change host ports; container-to-container
connections use service names and fixed internal ports. The local PostgreSQL
container uses SCRAM password authentication. This single-node Compose setup is
for development; production needs managed secrets, restricted networking, TLS,
backups, and appropriate database, Redis, and Kafka permissions.

To run Java locally, start PostgreSQL and Redis, then pass the same database
password explicitly (Compose reads .env, but Maven does not):

~~~bash
docker compose up -d --wait postgres redis
export SPRING_DATASOURCE_PASSWORD='your-password-from-.env'
./mvnw spring-boot:run
~~~

PowerShell equivalent:

~~~powershell
$env:SPRING_DATASOURCE_PASSWORD = 'your-password-from-.env'
.\mvnw.cmd spring-boot:run
~~~

Stop the containerized app first if port 8080 is occupied. Kafka remains optional
for local Java execution. To build the image separately:

~~~bash
docker build -t concurrent-balance-service:local .
~~~

## Database

PostgreSQL is the runtime database. Spring JDBC uses Boot's managed PostgreSQL
driver and HikariCP connection pool. PostgreSQL is used for both runtime persistence
and database integration tests.

| Configuration | Default |
| --- | --- |
| SPRING_DATASOURCE_URL | jdbc:postgresql://localhost:5432/balance |
| SPRING_DATASOURCE_USERNAME | balance |
| SPRING_DATASOURCE_PASSWORD | Required; no default |
| spring.datasource.hikari.maximum-pool-size | 10 per application instance |
| spring.datasource.hikari.minimum-idle | 2 |
| spring.datasource.hikari.connection-timeout | 5000 ms |
| spring.datasource.hikari.validation-timeout | 2000 ms |
| spring.datasource.hikari.transaction-isolation | TRANSACTION_READ_COMMITTED |
| PostgreSQL lock_timeout / statement_timeout | 5s / 30s per application connection |

Size pools across all replicas within the database's connection budget. Configure
production JDBC TLS with sslmode=verify-full and a trusted root certificate.
Lock/statement timeouts bound database contention; Kafka acknowledgement waits
are separately bounded. Existing ordered account row locks and unique transaction
IDs protect updates across application instances sharing the same PostgreSQL database.

Flyway owns schema creation through src/main/resources/db/migration. V1 creates
accounts, durable receipts, the transactional outbox (with PostgreSQL-compatible
TEXT payloads), and the singleton relay lock. Migration history and checksums are
validated on startup; SQL auto-initialization is disabled, automatic baselining is
not enabled, and Flyway clean is disabled. Add new versioned migrations for future
changes instead of editing an applied migration. All database integration tests
execute these same migrations on PostgreSQL.

For production, provision the database and roles through infrastructure. Use a
restricted runtime role with table DML/sequence privileges and a separate migration
role via SPRING_FLYWAY_URL, SPRING_FLYWAY_USER, and SPRING_FLYWAY_PASSWORD when
schema privileges should be separated. The Compose role is a local development
bootstrap role. Back up PostgreSQL regularly and verify restores.

## Architecture

~~~text
api/            BalanceService interface
domain/        Account, OperationType, TransactionRecord
concurrency/    AccountLockManager: per-account JVM locks, sorted transfer lock order
idempotency/    RedisIdempotencyStore: Redis cache backed by durable JDBC receipts
repository/     JdbcAccountRepository: account reads, row locks, and writes
service/        BalanceServiceImpl: validation and transactional balance operations
events/         Versioned Kafka events, JDBC transactional outbox, and scheduled relay
web/            BalanceController and GlobalExceptionHandler
~~~

The Spring application selects the JDBC account repository and Redis idempotency
store, with JDBC as its durable backing store. InMemoryAccountRepository and
InMemoryIdempotencyStore remain available for
plain unit tests that construct BalanceServiceImpl directly with its three-argument
constructor. The Spring constructor supplies a transaction manager for JDBC work
and the optional Kafka outbox recorder.

## Concurrency and atomicity

Each operation holds the relevant JVM account locks, starts a database transaction,
and reads the account rows with SELECT FOR UPDATE. Transfer locks both rows in
account ID order, preventing opposite transfers from forming a lock cycle even
when callers use separate service instances with independent JVM lock managers.
Unrelated accounts can be updated concurrently.

Balances are loaded after obtaining the database locks so a waiting operation
uses the latest committed values. Sufficiency checks, all account updates, and
the successful transaction record are committed together. Any database write
failure rolls back the whole operation, including an already-written source
balance. The transaction completes before the service releases its JVM locks.
The database also enforces nonnegative balances.

## Idempotency

After acquiring account row locks, the service checks the persisted transaction ID.
An identical completed operation returns without changing balances. Reusing the
same ID with a different operation, account, or amount raises
IdempotencyConflictException. The database primary key enforces transaction ID
uniqueness globally, including concurrent calls involving different accounts.
If two such calls race, the losing database transaction rolls back before the
service checks the winning record and reports the conflict.

Only successful operations are recorded. A failed withdrawal or transfer can be
retried with the same transaction ID after sufficient funds become available.
Both balances and duplicate-request protection survive application restarts.

## Redis idempotency cache

Start the local Redis container before running the application:

~~~bash
docker compose up -d redis
./mvnw spring-boot:run
~~~

Redis is enabled by default. The development container binds only to localhost,
uses AOF persistence, and limits its cache to 128 MB with LRU eviction. The database
retains successful transaction IDs permanently; Redis's default 24-hour TTL limits
cache memory, not the deduplication guarantee. Expiration, eviction, and Redis
restarts do not make a completed operation eligible to run again.

Redis caches completed TransactionRecord receipts as JSON, including operation,
accounts, amount, and completion time. Keys use a versioned namespace plus a
SHA-256 digest of the transaction ID. A cache hit still checks all request
parameters. Entries are written only after the database commits, including when
backfilling a receipt read within a transaction. Failed or rolled-back operations
are never published, and absent receipts are never cached. Redis does not hold
balance-operation locks: database row locks and the unique transaction ID remain
the concurrency safeguards.

Connection and command timeouts are 250 ms in the application configuration. A
Redis failure falls back to JDBC and bypasses Redis for five seconds per instance,
with a warning when entering that backoff. Redis write failures after commit do
not turn a successful balance operation into an error. Malformed, incomplete, or
miskeyed cached receipts also fall back to JDBC and are replaced from durable data.

Configuration:

| Property | Default |
| --- | --- |
| idempotency.redis.enabled | true |
| idempotency.redis.ttl | 24h |
| idempotency.redis.key-prefix | balance-service:local:idempotency:v1: |
| idempotency.redis.failure-backoff | 5s |
| spring.data.redis.host | localhost |
| spring.data.redis.port | 6379 |
| spring.data.redis.connect-timeout | 250ms |
| spring.data.redis.timeout | 250ms |

Use IDEMPOTENCY_REDIS_KEY_PREFIX to give each environment and database its own
namespace. Rotate this namespace whenever restoring or resetting the database so
old cache entries cannot outlive the database history they represent. Do not delete
durable receipts while promising permanent transaction-ID uniqueness.

For a managed production Redis instance, configure SPRING_DATA_REDIS_HOST,
SPRING_DATA_REDIS_PORT, SPRING_DATA_REDIS_USERNAME, SPRING_DATA_REDIS_PASSWORD, and
SPRING_DATA_REDIS_SSL_ENABLED as appropriate. Keep credentials in environment
configuration or your secret manager. The unauthenticated compose setup is for
local development. Set idempotency.redis.enabled=false to use JDBC alone.

The API continues to return the current balance on retries; this cache prevents
repeated mutations and does not replay the original HTTP response. Failed
insufficient-funds operations remain retryable after funding.

Regular tests do not require Redis. RedisIdempotencyStoreTest and
RedisIdempotencyIntegrationTest cover cache hits/misses, invalid data, outages,
after-commit failures, rollbacks, parameter conflicts, and concurrent calls; the
integration tests use real JDBC transactions with a simulated Redis boundary.
RedisLiveIntegrationTest checks JSON storage and actual TTL expiry against Redis
with isolated keys and a disposable PostgreSQL test database. Run it explicitly:

~~~powershell
docker compose up -d redis
$env:RUN_REDIS_TESTS = "true"
.\mvnw.cmd -Dtest=RedisLiveIntegrationTest test
~~~

## Validation and REST API

- POST /accounts creates an account from accountId and initialBalance.
- GET /accounts/{accountId}/balance returns the current balance.
- POST /accounts/{accountId}/credit and /debit accept amount and transactionId.
- POST /accounts/{accountId}/transfer also accepts destinationAccountId.

All request bodies are validated before service calls. Account IDs, destination
IDs, and transaction IDs are required, must not be blank, and may contain at
most 255 characters. Account path parameters follow the same rules. Amounts
must be positive integers; initialBalance is required and must be a nonnegative
integer. Missing or null amounts, fractions, and values outside the signed
64-bit integer range are rejected. Transfers must use different accounts.

Errors use application/problem+json and Spring Problem Detail fields: type,
title, status, detail, and instance. The existing error and message fields are
retained. Validation failures also include fieldErrors, mapping each invalid
field to a list of messages. Other errors return an empty fieldErrors object.

~~~json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Request validation failed.",
  "instance": "/accounts/A/credit",
  "error": "VALIDATION_ERROR",
  "message": "Request validation failed.",
  "fieldErrors": {
    "amount": ["must be greater than 0"]
  }
}
~~~

| Condition | HTTP status | Error code |
| --- | --- | --- |
| Invalid or missing request fields or account path | 400 | VALIDATION_ERROR |
| Malformed JSON, missing body, invalid numeric type or range | 400 | MALFORMED_REQUEST |
| Same-account transfer | 400 | SAME_ACCOUNT_TRANSFER |
| Domain amount validation | 400 | INVALID_AMOUNT |
| Unknown account | 404 | ACCOUNT_NOT_FOUND |
| Duplicate account | 409 | ACCOUNT_ALREADY_EXISTS |
| Insufficient funds | 409 | INSUFFICIENT_FUNDS |
| Reused transaction ID with different parameters | 409 | IDEMPOTENCY_CONFLICT |
| Unexpected failure, including database write failures | 500 | INTERNAL_SERVER_ERROR |

Framework errors such as an unsupported method or content type keep their
standard HTTP status and headers and use the same Problem Detail format, with
codes such as HTTP_405 and HTTP_415. Server errors return a generic message;
exception details are logged on the server.

## Testing

- CreditOperationTest, DebitOperationTest, and TransferOperationTest cover operation
  correctness and validation with in-memory fixtures.
- IdempotencyTest covers retries, parameter conflicts, and concurrent duplicates.
- SingleAccountConcurrencyTest covers double spending, concurrent credits, and
  mixed withdrawals.
- MultiAccountConcurrencyTest covers independent accounts, total balance
  conservation, and opposite-direction transfers.
- ApiValidationIntegrationTest verifies request and path constraints, field-level
  validation responses, malformed JSON, valid boundaries, HTTP error statuses and
  headers, and safe server error responses with database rollback.
- BalanceServiceApplicationTests verifies Spring wiring with the PostgreSQL datasource.
- BalanceServiceIntegrationTest exercises the real REST controller, service,
  JDBC repositories, and PostgreSQL database. It checks persisted balances and transaction
  records, HTTP errors, retrying failed transfers, rollback after a database
  constraint failure, concurrent withdrawals and duplicates across separate
  service instances, globally conflicting IDs, and transfer lock ordering.

PostgresPersistenceIntegrationTest covers migration validation/restart safety,
PostgreSQL constraints, JSON outbox writes, and atomic rollback of balances,
receipts, and events. These checks run in the default suite.

Integration tests clear their database tables before each case and use independent
transactions rather than wrapping the test in a rollback transaction. Concurrent
worker failures propagate through futures so unexpected exceptions fail the test.

## Apache Kafka balance events

Kafka publishes completed credit, debit, and transfer changes to
`balance.account-changed.v1`. The REST API remains synchronous: database commit
completes the operation and Kafka delivery happens in the background. Account creation
is outside this event contract. Existing historical receipts are not backfilled.

Docker Compose enables Kafka automatically, with a pinned Apache Kafka 4.0.2 KRaft
broker, persistent storage, separate container/host listeners, and a healthcheck.
For a local Java process:

~~~powershell
docker compose up -d --wait postgres redis kafka
$env:SPRING_DATASOURCE_PASSWORD = 'your-password-from-.env'
$env:BALANCE_KAFKA_ENABLED = "true"
$env:BALANCE_KAFKA_CREATE_TOPIC = "true"
$env:BALANCE_KAFKA_REPLICAS = "1"
$env:BALANCE_KAFKA_MIN_IN_SYNC_REPLICAS = "1"
.\mvnw.cmd spring-boot:run
~~~

`KAFKA_BOOTSTRAP_SERVERS` defaults to `localhost:9092`; the app container uses
`kafka:19092`. When changing `KAFKA_PORT`, also set the bootstrap address for
host clients. Inspect events with:

~~~bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:19092 --topic balance.account-changed.v1 --from-beginning --property print.key=true
~~~

Each record has the account ID as its Kafka key and a plain JSON value:

~~~json
{
  "schemaVersion": 1,
  "eventId": "c87d8833-8059-4ea7-a2d3-eaa69f69c6b8",
  "transactionId": "transfer-123",
  "operation": "TRANSFER",
  "accountId": "A",
  "counterpartyAccountId": "B",
  "delta": -20,
  "balance": 980,
  "occurredAt": "2026-11-14T10:00:00Z"
}
~~~

Amounts use the same integer units as the API. Credit/debit emit one event;
transfer emits two events with distinct event IDs, one per affected account.
`balance` is the balance immediately after that mutation. Transfer events share
the transaction ID and do not become visible atomically across Kafka partitions.
Consumers requiring an atomic transfer view must correlate them by transaction ID.

### Delivery and ordering

The service inserts JSON into `kafka_outbox` in the same JDBC transaction as the
balance changes and durable idempotency receipt. A serialization or outbox write
failure rolls back all changes. Rejected operations and successful request retries
create no extra events. Redis is never used as the event source.

A scheduled relay reads bounded batches, waits for each Kafka acknowledgement,
and removes each acknowledged row in its own database transaction. A database
lock serializes relays across instances, and a failed record stops that batch.
It holds only the relay lock while sending, so Kafka outages do not hold account
locks. Account row locks establish mutation order; sequence IDs and account keys
preserve that order during publishing. Keep the topic partition count fixed while
ordered delivery is required; increasing it can remap account keys.

Delivery is **at least once**: a crash after broker acknowledgement and before
the database deletion commits can publish the same event again. Consumers must
persist `eventId` deduplication with their own state changes and commit Kafka
offsets after those changes commit. Producer idempotence protects Kafka client
retries; it does not remove this database/Kafka commit gap. Consumers should
process each partition sequentially, apply bounded retries, and route permanently
invalid messages to a monitored dead-letter topic before advancing offsets.

Kafka uses `acks=all`, producer idempotence, and one in-flight request per connection,
following [Apache Kafka's producer guidance](https://kafka.apache.org/40/configuration/producer-configs/).
Acknowledgement waits are bounded, as recommended by
[Spring Kafka](https://docs.spring.io/spring-kafka/reference/kafka/sending-messages.html).
The relay retries on the next poll without discarding failed events. A repeatedly
failing event blocks later delivery and requires operator investigation. Monitor
queue growth and disk capacity during outages; pending events have no automatic
expiry. Database availability still governs the REST API readiness check.

### Configuration and operations

| Property | Default |
| --- | --- |
| balance.kafka.enabled | false; true in Compose |
| balance.kafka.publisher.enabled | true |
| balance.kafka.create-topic | false; true in Compose |
| balance.kafka.topic | balance.account-changed.v1 |
| balance.kafka.partitions | 6 |
| balance.kafka.replicas | 3; 1 in Compose |
| balance.kafka.min-in-sync-replicas | 2; 1 in Compose |
| balance.kafka.batch-size | 100 |
| balance.kafka.poll-interval | 1000 milliseconds |
| balance.kafka.send-timeout | 15s |
| spring.kafka.producer.properties.delivery.timeout.ms | 10000 |
| spring.kafka.producer.properties.request.timeout.ms | 5000 |
| spring.kafka.producer.properties.max.block.ms | 2000 |

Keep the send timeout above the producer delivery timeout plus its maximum block
time. Disabling only `publisher.enabled` pauses delivery while continuing to queue
events; disabling `balance.kafka.enabled` also stops recording new events. Pending
rows survive both changes and will be delivered when re-enabled. Startup does not
require a broker when topic creation is disabled.

Micrometer meters `balance.kafka.outbox.pending`, `balance.kafka.outbox.published`,
and `balance.kafka.outbox.failures` expose queue depth, acknowledged/deleted events,
and failed relay attempts through the configured metrics backend. Only the health
Actuator endpoint is exposed by default. Alert on backlog growth and delivery failures.

The single relay deliberately favors ordering and correctness over throughput.
For higher throughput, use a CDC outbox connector or partitioned relay ownership.
Application replicas can share PostgreSQL; the database relay lock serializes
publishing across replicas. Production topics should be provisioned
through infrastructure, with replication factor 3, minimum ISR 2, retention sized
for the consumer recovery window, and broker auto-creation disabled. Configure
`spring.kafka.properties.security.protocol`, SASL/TLS properties, credentials, and
least-privilege topic ACLs through your deployment secrets/configuration. The local
single-broker plaintext Compose setup is for development.

The regular `mvnw.cmd verify` suite uses Docker for disposable PostgreSQL databases
and starts an embedded KRaft broker without an external Kafka prerequisite. Kafka tests cover actual JSON delivery, account
keys and ordering, transfer events, duplicate/conflicting requests, concurrent
retries, transaction rollback, broker errors, acknowledgement timeout, concurrent
relays, and redelivery after a Kafka acknowledgement/database cleanup failure.
