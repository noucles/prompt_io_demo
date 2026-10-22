# Payment ledger

The ledger depends only on the `PaymentEvent` contract and deliberately has no foreign keys to `payments` or `payment_events`, so it can become a separate service; the transactional outbox provides reliable event delivery, and unique payment and event IDs make ledger writes idempotent.
