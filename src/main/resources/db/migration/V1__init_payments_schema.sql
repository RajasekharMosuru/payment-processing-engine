-- V1__init_payments_schema.sql
-- Initial schema for the payment processing engine.
-- All DDL is managed by Flyway — never use spring.jpa.hibernate.ddl-auto=create.

CREATE TABLE IF NOT EXISTS payments (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key         VARCHAR(64)     NOT NULL UNIQUE,
    source_account_id       VARCHAR(64)     NOT NULL,
    destination_account_id  VARCHAR(64)     NOT NULL,
    amount                  NUMERIC(18, 2)  NOT NULL CHECK (amount > 0),
    currency                CHAR(3)         NOT NULL,
    status                  VARCHAR(20)     NOT NULL DEFAULT 'INITIATED',
    failure_reason          VARCHAR(512),
    retry_count             INTEGER         NOT NULL DEFAULT 0,
    last_attempt_at         TIMESTAMPTZ,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),
    version                 BIGINT          NOT NULL DEFAULT 0,

    CONSTRAINT chk_payment_status CHECK (
        status IN ('INITIATED', 'PROCESSING', 'COMPLETED', 'FAILED')
    )
);

-- Indexes for the most common query patterns
CREATE INDEX idx_payments_idempotency_key ON payments (idempotency_key);
CREATE INDEX idx_payments_status          ON payments (status);
CREATE INDEX idx_payments_source_account  ON payments (source_account_id);
CREATE INDEX idx_payments_created_at      ON payments (created_at DESC);

-- Composite index for retry scheduler query
CREATE INDEX idx_payments_retry
    ON payments (status, retry_count, last_attempt_at)
    WHERE status = 'FAILED';

-- Trigger: keep updated_at current automatically
CREATE OR REPLACE FUNCTION update_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_payments_updated_at
    BEFORE UPDATE ON payments
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();
