CREATE TABLE ledger_entries (
    id uuid PRIMARY KEY,
    payment_id uuid NOT NULL,
    event_id uuid NOT NULL,
    user_id varchar(64) NOT NULL,
    amount bigint NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT ledger_entries_payment_id_unique UNIQUE (payment_id),
    CONSTRAINT ledger_entries_event_id_unique UNIQUE (event_id),
    CONSTRAINT ledger_entries_amount_positive CHECK (amount > 0)
);
