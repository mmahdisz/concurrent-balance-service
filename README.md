# Concurrent Balance Service

An in-memory account balance service supporting `credit`, `debit`, and
`transfer`, built to stay correct under concurrent access and duplicate
(retried) requests.
---
## Running

```bash
mvn test          # run the full test suite
mvn spring-boot:run   # optional: start the REST wrapper on :8080
```
---
## Architecture

```
api/            BalanceService — the service interface
domain/         Account (mutable balance holder), OperationType, TransactionRecord
concurrency/    AccountLockManager — one lock per account, deadlock-free multi-lock ordering
idempotency/    IdempotencyStore — records successfully-applied transactionIds
repository/     AccountRepository / InMemoryAccountRepository
service/        BalanceServiceImpl — wires the above together; this is where correctness lives
web/            Optional REST wrapper (BalanceController) + error mapping (GlobalExceptionHandler)
```
---
The core pieces (to keep simplicity over feature/architecture):

- **`Account`** just holds an id and an `AtomicLong` balance. It enforces
  none of the business invariants itself — it's a dumb, mutable holder.
- **`AccountLockManager`** hands out one `ReentrantLock` per account id and
  is the *only* thing responsible for making compound operations
  (check-then-mutate) safe.
- **`IdempotencyStore`** is a `ConcurrentHashMap` from `transactionId` to
  the `TransactionRecord` of the transaction that already applied it.
- **`BalanceServiceImpl`** is where all of this comes together: validate →
  acquire the relevant lock(s) → check idempotency → mutate → record
  idempotency, in that order, inside a single locked section.
---
## Concurrency

**Mechanism:** one `ReentrantLock` per account id, obtained from
`AccountLockManager`. Every operation resolves the account(s) it will
touch, then performs its entire check-then-mutate sequence while holding
the lock(s) for exactly those accounts, nothing more.

**Why a lock instead of lock-free CAS:** `credit` alone could be done
lock-free with `AtomicLong.addAndGet`. `debit`, though, is a genuine
check-then-act ("is balance ≥ amount, and if so subtract") — a CAS retry
loop is possible but reads awkwardly next to `transfer`, which has to hold
an invariant across *two* accounts at once, which CAS can't do atomically
without extra machinery (e.g. a version-stamped multi-word CAS, or
STM). A per-account mutex makes all three operations read as straight-line
code and makes the reasoning about atomicity trivial: "this block runs with
the account(s) exclusively owned by this thread."

**Why per-account, not one global lock:** a single global lock would make
every operation trivially correct but would serialize the *entire*
service — an operation on account A would block an unrelated operation on
account B for no reason. Per-account locks mean accounts A and B never
contend unless an operation explicitly touches both (a transfer between
them). `MultiAccountConcurrencyTest` demonstrates this: 20 accounts, each
hammered by its own thread concurrently, every one lands on the exact
expected balance.

**What happens to concurrent operations on the same account:** they
serialize on that account's lock. Whichever thread acquires the lock first
runs its entire operation (check + mutate) to completion before the next
one can even read the balance, so there is no window for a lost update, a
stale read, or a negative balance. `SingleAccountConcurrencyTest` exercises
exactly the scenario from the spec — balance 1000, two threads each try to
debit 700 — and asserts exactly one succeeds.

**Deadlock:** `transfer` is the only place two locks are held at once. If
locks were acquired in the order the caller passed the arguments
(`sourceAccountId` then `destinationAccountId`), a concurrent `transfer(A,
B, ...)` and `transfer(B, A, ...)` could deadlock: thread 1 holds A and
waits for B, thread 2 holds B and waits for A. `AccountLockManager.withLocks`
avoids this by always acquiring locks in a fixed **global** order — sorted
by account id — regardless of which account the caller called "source" and
which "destination". Two opposite-direction transfers between the same pair
always contend for the same lock first, so circular wait — the necessary
condition for deadlock — can't occur. `MultiAccountConcurrencyTest.
bidirectionalConcurrentTransfersCompleteWithoutDeadlock` fires 3,000
transfers in each direction between the same two accounts concurrently and
asserts it completes inside a timeout rather than hanging.

**Locks are never released and re-acquired mid-operation** — no lock is
dropped and retaken partway through a credit/debit/transfer, so there's no
window where another thread could observe or act on a partially-applied
operation.

---
## Idempotency

**Mechanism:** a transaction is recorded in `IdempotencyStore` *only after*
it has been successfully applied, keyed by `transactionId`. Every operation
does, inside the account lock(s) it's already holding:

1. Look up `transactionId` in the store.
2. If present and the recorded operation matches this call's parameters
   (same type, same account(s), same amount) → return immediately, no-op.
   The effect was already applied by whichever call recorded it.
3. If present but the parameters *don't* match → throw
   `IdempotencyConflictException`. This is deliberately stricter than the
   spec requires — it defends against a caller bug where the same
   transactionId gets reused for a genuinely different operation, which
   would otherwise either silently corrupt state or silently swallow a
   legitimate request.
4. If absent → run the operation. Only if it succeeds, record it.

**Why only successful applications are recorded:** a failed attempt
(invalid amount, unknown account, insufficient funds) never mutates any
balance, so it's not something that needs to be "not applied twice" — there
was nothing applied. Not recording failures means a retry of a
transactionId that failed purely because of *insufficient funds at that
moment* is free to succeed later once the balance allows it, without that
being a double-application of anything. `IdempotencyTest.
retryAfterAGenuinelyFailedAttemptIsFreeToSucceedLater` covers this.

**Why this is safe under concurrent duplicates, not just sequential
retries:** the check ("has this transactionId already applied?"), the
mutation, and the record-write all happen while holding the same account
lock(s) the mutation itself needs. So if two threads call
`credit("A", 500, "TX-1")` at the exact same instant, they still serialize
on account A's lock: whichever gets the lock first sees no record, applies
the credit, and writes the record before releasing the lock; the second
thread then acquires the lock, sees the record, and returns without
touching the balance. The idempotency map's own `ConcurrentHashMap`
thread-safety is *not* what makes this correct by itself — it's a
defense-in-depth property. The account lock is the actual correctness
argument. `IdempotencyTest` has three tests firing 50 threads at the exact
same `transactionId` concurrently (credit, debit, and transfer) and
asserting the effect applied exactly once.

**Assumption:** a given `transactionId` is always retried with identical
parameters, matching how idempotency keys are used in practice (e.g.
Stripe-style idempotency keys). The mismatch check above is a safety net on
top of that assumption, not a replacement for it.

---

## Transfer

**Atomicity:** `transfer` acquires both accounts' locks (in the canonical
order described above) before doing anything, and holds both for the
entire operation. Inside that section: check the source has sufficient
funds → debit source → credit destination → record the transactionId. There
is no I/O and no yield point between the debit and the credit, and no other
thread can observe either account mid-operation (both are locked), so it's
impossible for an external observer to see "debited from source, not yet
credited to destination" — the state transition is all-or-nothing from
every other thread's point of view.

**Failure semantics:** if the source has insufficient funds, an exception
is thrown *before* either balance is touched — neither account is mutated,
and nothing is recorded in the idempotency store, so a retry can
legitimately re-attempt later. There is no scenario in this design where a
transfer partially applies (source debited, destination not credited, or
vice versa): the two mutations are separated only by a plain in-memory
field write with no possibility of failure or interruption between them
(no checked exceptions, no I/O) once the sufficiency check has passed.

**Deadlock:** see the Concurrency section above — ruled out by the fixed
global lock-acquisition order, not by timeouts or retries.

**Same-account transfer (`transfer(A, A, amount, txId)`):** rejected with
`SameAccountTransferException` rather than silently treated as a no-op.
Reasoning: a caller passing the same account as source and destination is
far more likely to be a bug (e.g. a copy-paste error, or a UI defaulting
`destination` to `source`) than an intentional request, and a silent no-op
would hide that bug completely. Rejecting it loudly surfaces the mistake at
the call site.

---

## Validation

- `amount <= 0` → `InvalidAmountException`, checked before any lock is
  acquired (no reason to pay locking cost for a request that's invalid on
  its face).
- Unknown account → `AccountNotFoundException` for `credit`/`debit`, and for
  `transfer` both source and destination are checked before any lock is
  taken.
- Same-account transfer → `SameAccountTransferException` (see above).
- Reused `transactionId` with different parameters →
  `IdempotencyConflictException` (see Idempotency above).

All of these are `RuntimeException`s under a common `BalanceServiceException`
base, and the REST layer's `GlobalExceptionHandler` maps them to HTTP status
codes (400 for validation, 404 for unknown account, 409 for insufficient
funds / idempotency conflict).

---

## Testing

- `CreditOperationTest`, `DebitOperationTest`, `TransferOperationTest` —
  correctness and validation for each operation in isolation.
- `IdempotencyTest` — sequential repeats of the same `transactionId` for all
  three operations, a reused-`transactionId`-with-different-params rejection
  case, a "failed attempt can be retried later" case, and three tests
  firing concurrent duplicate requests (50 threads, one shared
  `transactionId`) at credit/debit/transfer respectively.
- `SingleAccountConcurrencyTest` — the exact double-spend scenario from the
  spec (two 700-debits against a 1000 balance, distinct transactionIds,
  exactly one must win), repeated 20 times via `@RepeatedTest` to raise the
  odds of catching a race; 1,000 concurrent credits on one account with a
  fully deterministic expected final balance; 2,000 high-contention mixed
  debits asserting the balance never goes negative.
- `MultiAccountConcurrencyTest` — 20 independent accounts each driven by
  their own thread to prove unrelated accounts don't interfere; a
  multi-account random-transfer stress test asserting the total balance
  across all accounts is exactly preserved and no account goes negative;
  and the bidirectional-transfer deadlock test.
- `BalanceServiceApplicationTests` — Spring context sanity check.

Most tests construct `BalanceServiceImpl` directly with an
`InMemoryAccountRepository`, rather than going through the Spring context —
these are plain unit/concurrency tests and starting a full application
context for each one would only slow the suite down for no added
confidence. `BalanceServiceApplicationTests` is the one test that verifies
the wiring itself.

---
