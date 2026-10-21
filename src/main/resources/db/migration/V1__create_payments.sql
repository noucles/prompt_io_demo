CREATE TABLE payments (
    id uuid PRIMARY KEY,
    idempotency_key varchar(255) NOT NULL,
    user_id varchar(64) NOT NULL,
    amount bigint NOT NULL,
    status text NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT payments_idempotency_key_unique UNIQUE (idempotency_key),
    CONSTRAINT payments_amount_positive CHECK (amount > 0),
    CONSTRAINT payments_status_valid CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED'))
);
