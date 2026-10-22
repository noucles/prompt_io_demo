CREATE TABLE payment_events (
    id uuid PRIMARY KEY,
    payment_id uuid NOT NULL REFERENCES payments(id),
    event_type text NOT NULL,
    payload jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    processed_at timestamptz,
    attempts int NOT NULL DEFAULT 0,
    last_error text,
    CONSTRAINT payment_events_payment_id_unique UNIQUE (payment_id),
    CONSTRAINT payment_events_type_valid
        CHECK (event_type IN ('PAYMENT_SUCCEEDED', 'PAYMENT_FAILED'))
);

CREATE INDEX payment_events_unprocessed_created_at_idx
    ON payment_events (created_at) WHERE processed_at IS NULL;
