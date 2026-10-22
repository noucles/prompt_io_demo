ALTER TABLE payments
    ADD COLUMN processor_reference text,
    ADD COLUMN failure_reason text,
    ADD CONSTRAINT payments_processor_reference_consistent
        CHECK ((status = 'SUCCEEDED') = (processor_reference IS NOT NULL)),
    ADD CONSTRAINT payments_failure_reason_consistent
        CHECK ((status = 'FAILED') = (failure_reason IS NOT NULL));
