DROP INDEX payment_events_unprocessed_created_at_idx;

CREATE INDEX payment_events_unprocessed_attempts_created_at_idx
    ON payment_events (attempts, created_at) WHERE processed_at IS NULL;
