# Payment ledger

The ledger depends only on the `PaymentEvent` contract and deliberately has no foreign keys to `payments` or `payment_events`, so it can become a separate service; the transactional outbox provides reliable event delivery, and unique payment and event IDs make ledger writes idempotent.

The outbox worker polls with a fixed delay (`outbox.worker.poll-interval`, default `1s`). Each failure rolls back processing, then increments `attempts` and stores up to 1,000 characters in `last_error`. A batch stops at its first failure; later polls prioritize events with fewer failures. Events reaching `outbox.worker.max-attempts` (default `5`) are parked: they remain unprocessed in `payment_events` for inspection and are no longer claimed.
