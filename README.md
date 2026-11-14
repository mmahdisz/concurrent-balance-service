# Concurrent Balance Service

An account balance service supporting credit, debit, and transfer, with H2
persistence, concurrent updates, and idempotent transaction IDs.

## Running

Requires Java 21.

~~~bash
./mvnw test
./mvnw spring-boot:run
~~~

On Windows, use mvnw.cmd. A locally installed Maven also works with mvn test
and mvn spring-boot:run. The REST API starts on port 8080.

## Running with Docker

Requires Docker with Linux containers and the Docker Compose plugin. Java and Maven
are included in the build image; no local Java installation is needed.

~~~bash
docker compose up --build -d --wait
~~~

The API is available at http://localhost:8080 and Swagger UI at
http://localhost:8080/swagger-ui/index.html. The application waits for Redis's
healthcheck before starting. Its own healthcheck calls
/actuator/health/readiness, which includes application readiness and database
connectivity. Redis is optional for readiness because JDBC protects balance
operations when the cache is unavailable.

~~~bash
docker compose ps
docker compose logs -f app
docker compose down
~~~

Stopping or recreating containers preserves both named volumes: balance-data holds
H2 balances and durable idempotency receipts, and redis-data holds Redis AOF data.
The container database is separate from the local ./data directory. Keep this
file-backed H2 deployment to one application container; scaling the application
requires a shared database service and its driver/configuration.

The image is built in two stages: Maven runs the regular test suite and packages
the service, then only the executable JAR is copied into a Java 21 JRE image. Maven
dependencies use a BuildKit cache. The runtime uses a non-root user, a read-only
root filesystem, writable /tmp, and a writable database volume. Only health is
exposed through Actuator. Application shutdown has a 30-second grace period.

Compose publishes the API and Redis only on localhost. Use APP_PORT and REDIS_PORT
to change their host ports (defaults: 8080 and 6379). Containers always communicate
with Redis at redis:6379. IDEMPOTENCY_REDIS_KEY_PREFIX defaults to a separate Docker
namespace; use a distinct namespace per database/environment and rotate it after
restoring a database snapshot. The compose configuration is intended for local
use; configure network access, Redis authentication/TLS, and secrets for your
deployment environment.

To run Java locally with only Redis in Docker, use docker compose up -d redis and
the local running instructions above. Stop the containerized app first if port
8080 is already occupied. To build the app image separately:

~~~bash
docker build -t concurrent-balance-service:local .
~~~

## Database

The application uses Spring JDBC and file-backed H2 at jdbc:h2:file:./data/balance
with username sa and an empty password. Accounts and successful transaction
records survive application restarts. Generated database files in data/ are
ignored by Git. schema.sql creates missing tables on startup without dropping data.

Override spring.datasource.url, spring.datasource.username, and
spring.datasource.password using standard Spring Boot configuration or the
SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME, and SPRING_DATASOURCE_PASSWORD
environment variables.

Tests override the datasource URL with jdbc:h2:mem:balance-tests;DB_CLOSE_DELAY=-1.
They run against an isolated in-memory H2 database and do not touch the application
database files.

## Architecture

~~~text
api/            BalanceService interface
domain/        Account, OperationType, TransactionRecord
concurrency/    AccountLockManager: per-account JVM locks, sorted transfer lock order
idempotency/    RedisIdempotencyStore: Redis cache backed by durable JDBC receipts
repository/     JdbcAccountRepository: account reads, row locks, and writes
service/        BalanceServiceImpl: validation and transactional balance operations
web/            BalanceController and GlobalExceptionHandler
~~~

The Spring application selects the JDBC account repository and Redis idempotency
store, with JDBC as its durable backing store. InMemoryAccountRepository and
InMemoryIdempotencyStore remain available for
plain unit tests that construct BalanceServiceImpl directly with its three-argument
constructor. The Spring constructor supplies a transaction manager for JDBC work.

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
with isolated keys and an isolated in-memory H2 database. Run it explicitly:

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
- BalanceServiceApplicationTests verifies Spring wiring with the H2 datasource.
- BalanceServiceIntegrationTest exercises the real REST controller, service,
  JDBC repositories, and H2 database. It checks persisted balances and transaction
  records, HTTP errors, retrying failed transfers, rollback after a database
  constraint failure, concurrent withdrawals and duplicates across separate
  service instances, globally conflicting IDs, and transfer lock ordering.

Integration tests clear their database tables before each case and use independent
transactions rather than wrapping the test in a rollback transaction. Concurrent
worker failures propagate through futures so unexpected exceptions fail the test.
