# Payment ledger

A deliberately small modular monolith demonstrating a payment → ledger flow with a transactional outbox. Java 25, Spring Boot, explicit SQL and PostgreSQL keep the transaction boundaries visible; a deterministic simulated processor stands in for an external payment provider.

## What it demonstrates

- Request idempotency, including concurrent requests with the same key.
- Atomic payment status changes and outbox event writes.
- At-least-once delivery with an idempotent consumer, giving at most one ledger entry per payment.
- Retry, failure isolation and parking for inspection.
- No database transaction held during the processor call.
- Tests against real PostgreSQL, including HTTP handling, rollback and concurrency.

## Run it

Install **JDK 25** and **Docker with Compose**, and start Docker. From the repository root, with ports **8080** and **5432** available:

```bash
./gradlew bootRun
```

Boot starts PostgreSQL through `compose.yaml`, applies Flyway migrations V1–V5, and serves the API at `http://localhost:8080`. The first run downloads Gradle/dependencies and pulls `postgres:18-alpine`; allow extra time. Ctrl-C stops the app.

```bash
./gradlew test
```

Tests use isolated Testcontainers databases, not the Compose database. Full-context tests disable scheduling and invoke the worker explicitly, without sleeps.

## Try it

In another terminal, define this helper (requires `curl`; `-i` shows HTTP status):

```bash
post_payment() {
  curl -sS -i http://localhost:8080/payments \
    -H 'Content-Type: application/json' -H "Idempotency-Key: $1" \
    -d "{\"userId\":\"demo-user\",\"amount\":$2}"
  echo
}
post_payment demo-success 1050  # 201, SUCCEEDED, sim_ reference
```

Copy the response's `id` below. The first ledger read may already contain the entry; processing runs in the background.

```bash
payment_id='PASTE_ID_HERE'
curl -sS "http://localhost:8080/payments/$payment_id/ledger-entries" # [] or [entry]
sleep 2
curl -sS "http://localhost:8080/payments/$payment_id/ledger-entries" # [entry]
curl -sS "http://localhost:8080/payments/$payment_id"                # SUCCEEDED
post_payment demo-success 1050  # 201, same ID; no second charge
post_payment demo-success 2050  # 409, key reused with a different amount
post_payment demo-decline 1001  # 201, FAILED, insufficient_funds
post_payment demo-unavailable 1002 # 201, PENDING; outcome unknown
```

The decline and unavailable IDs always return `[]` from the ledger endpoint in this demo. Replays return `201` and the payment's **current** state. Use new keys for a fresh run.

| Positive amount in cents | Simulated outcome |
| --- | --- |
| Ends in `01`, e.g. `1001` | Declined: `insufficient_funds` |
| Ends in `02`, e.g. `1002` | Processor unavailable; payment remains `PENDING` |
| Anything else, e.g. `1050` | Approved: reference `sim_<paymentId>` |

## Design choices

- **Spring JDBC over JPA:** explicit SQL keeps conflict handling, row locks and transaction boundaries visible.
- **PostgreSQL outbox over Kafka or a broker:** the status and event become durable in the same transaction, with no extra infrastructure for one local consumer.
- **Polling over `LISTEN/NOTIFY`:** the worker discovers persisted work after restarts without managing a listener connection; the small polling delay is acceptable here.
- **Modular monolith over separate services:** package boundaries separate responsibilities while one deployment avoids network and operational complexity this demo does not need.
- **Testcontainers over H2:** tests exercise PostgreSQL's actual constraints, JSONB, conflict handling and locking behavior.

## How it works

```text
POST /payments -> commit PENDING -> processor (no DB transaction)
                                     |
                       transaction: final status + outbox event
                                     |
                       worker: claim -> ledger -> mark processed
                               [one transaction per event]
GET /payments/{id}/ledger-entries --------> ledger_entries
```

The unique idempotency key and `INSERT ... ON CONFLICT` resolve competing requests. Replays compare user and amount and never call the processor. Only a newly inserted payment is charged; a guarded update allows a final transition only from `PENDING`. That update and its event commit together, independently of the earlier insert and external call.

The worker claims unprocessed events with `FOR UPDATE SKIP LOCKED`, then applies and marks each event in one transaction. Failures roll back; a separate statement increments `attempts` and records `last_error`.

Each poll handles up to 100 events, stopping at the first failure. Later polls prioritize fewer attempts, then oldest creation time. Exhausted events remain unprocessed, are never claimed again, and produce an ERROR log.

| Worker property | Default |
| --- | --- |
| `outbox.worker.enabled` | `true` |
| `outbox.worker.poll-interval` | `1s` (fixed delay) |
| `outbox.worker.max-attempts` | `5` |

The ledger records succeeded events only. Unique `payment_id` and `event_id` constraints plus `ON CONFLICT DO NOTHING` make redelivery harmless. There are deliberately no foreign keys from the ledger to payment/outbox tables: its data contract is `PaymentEvent`.

## Guarantees and their tests

These guarantees describe the application paths, not arbitrary manual database writes. Delivery is **at least once**, not exactly once; parking means eventual ledger delivery is not unconditional.

| Guarantee | Evidence in `src/test/java/com/nielsen` |
| --- | --- |
| Same key/body replays; different body conflicts; concurrent commit/rollback resolves safely | `payment/PaymentServiceTest` |
| Initial insert precedes charging; no transaction spans the processor call | `payment/PaymentServiceTest` |
| Final status and event commit or roll back together | `payment/PaymentServiceTest` |
| Final payments cannot transition again; status/outcome fields stay consistent | `payment/PaymentRepositoryTest` |
| At most one entry per payment/event; declines create none | `ledger/LedgerServiceTest` |
| Ledger write and processed mark are atomic; retry, parking, malformed payloads and locked rows are handled | `ledger/LedgerEventWorkerTest` |
| HTTP flow exposes the ledger before and after delivery | `PaymentFlowIntegrationTest` |

## Tradeoffs and limitations

- **Stuck `PENDING`:** no recovery sweeper or provider reconciliation. A crash, unavailable processor, or approved charge followed by a failed completion/outbox transaction can leave a pending payment. Replaying does not retry the charge. A real adapter must use the payment ID as its provider idempotency key.
- **Conflicting duplicates:** ledger uniqueness suppresses a second event even if its user or amount differs; inconsistency is not flagged.
- **Private outbox and trust:** the ledger trusts event contents because the payment service is the only application path that inserts events, inside the status transaction. This is an application convention, not separate database permissions. A shared broker would require an explicit trust policy, such as restricted publishing, verified signatures or checking authoritative payment state.
- **Failure bookkeeping:** a crash after rollback but before recording the failure can miss an attempt; concurrent workers can also race that bookkeeping. Parked events require manual inspection and recovery.
- **Reconciliation:** the query below finds succeeded payments without entries, including normal delivery lag. It is a manual diagnostic across modules, not a repair job; it cannot discover an external charge left `PENDING` locally.
- **Packaged deployment:** the jar does not start Compose. Provision PostgreSQL and supply `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD` (example below).
- **Money model:** one implicit currency, positive integer cents; no refunds, balances or double-entry accounting.
- **API scope:** no authentication. Ledger reads return `200 []` for pending, failed or nonexistent payments; they do not consult payment tables. `GET /payments/{id}` returns `404` for an unknown ID. Errors use problem JSON, but body validation currently reports the generic `"Validation failure"` detail.
- **No user history endpoint:** `GET /users/{id}/ledger-entries` is omitted; it would need pagination and a `user_id` index.

Against the running Compose database:

```bash
docker compose exec postgres psql -U myuser -d mydatabase -c \
  "SELECT p.id, p.updated_at FROM payments p
   LEFT JOIN ledger_entries l ON l.payment_id = p.id
   WHERE p.status = 'SUCCEEDED' AND l.id IS NULL;"
```

For a packaged run against the demo Compose database (stop `bootRun` first):

```bash
docker compose up -d
./gradlew bootJar
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/mydatabase \
SPRING_DATASOURCE_USERNAME=myuser SPRING_DATASOURCE_PASSWORD=secret \
java -jar build/libs/payment-ledger-0.0.1-SNAPSHOT.jar
```

## What would change in production

- Add a broker when other services need the events, retaining the outbox for atomic publication intent.
- Run multiple workers when throughput requires it; `SKIP LOCKED` already supports concurrent claims.
- Add a reconciliation job and provider status queries to resolve stuck `PENDING` payments safely.
- Alert on parked events so an ERROR log leads to operator action.
- Define a retention policy for processed events to bound table growth.
- Add authentication and authorization for payment creation and reads.

## Project layout

Under `src/main/java/com/nielsen`:

```text
payment/    Payment API, service, repository, records, DTOs and conflict exception
processor/  Charging interface, outcomes and deterministic simulator
outbox/     PaymentEvent message contract and outbox persistence
ledger/     Consumer, worker, repository and ledger API
config/     Conditional scheduling configuration
```

The ledger imports no payment classes. Its business service uses only the `PaymentEvent` contract from outbox; its worker also uses `OutboxRepository` to claim and acknowledge delivery. SQL migrations live in `src/main/resources/db/migration`; tests mirror the feature packages.
