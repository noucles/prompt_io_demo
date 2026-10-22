# Payment ledger

Code is organized by feature under `com.nielsen`: `payment` owns the payment API, service, persistence, records and DTOs; `processor` provides the charging boundary; `outbox` defines the event contract and storage; `ledger` consumes events and serves ledger queries; `config` holds application configuration.

The ledger depends only on the `PaymentEvent` contract and deliberately has no foreign keys to `payments` or `payment_events`, so it can become a separate service; the transactional outbox provides reliable event delivery, and unique payment and event IDs make ledger writes idempotent.

The outbox worker polls with a fixed delay (`outbox.worker.poll-interval`, default `1s`). Each failure rolls back processing, then increments `attempts` and stores up to 1,000 characters in `last_error`. A batch stops at its first failure; later polls prioritize events with fewer failures. Events reaching `outbox.worker.max-attempts` (default `5`) are parked: they remain unprocessed in `payment_events` for inspection and are no longer claimed.

`GET /payments/{paymentId}/ledger-entries` returns a JSON array containing `paymentId`, `eventId`, `userId`, `amount` (in cents), and `createdAt` for each entry. It reads only the ledger table. A valid UUID with no entries returns `200 []`, whether the payment is awaiting event processing, failed, or does not exist. A successful payment may initially return `[]` and show its entry after a later poll; a malformed UUID returns `400`.

Not implemented: `GET /users/{userId}/ledger-entries`, which would need pagination and a `user_id` index.
