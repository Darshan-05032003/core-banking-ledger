-- A transfer that needs a second pair of eyes ends in one of two more states besides COMPLETED:
-- REJECTED by a checker or EXPIRED by the clock. Who decided, and why, is kept on the row.
ALTER TABLE transfer
    ADD COLUMN decided_by      VARCHAR(100),
    ADD COLUMN decision_reason VARCHAR(255);

ALTER TABLE transfer DROP CONSTRAINT ck_transfer_status;
ALTER TABLE transfer
    ADD CONSTRAINT ck_transfer_status
        CHECK (status IN ('COMPLETED', 'FAILED', 'PENDING_APPROVAL', 'REJECTED', 'EXPIRED')),
    ADD CONSTRAINT ck_transfer_decided_has_actor
        CHECK (status NOT IN ('REJECTED', 'EXPIRED') OR decided_by IS NOT NULL),
    ADD CONSTRAINT ck_transfer_rejected_has_reason
        CHECK (status <> 'REJECTED' OR decision_reason IS NOT NULL);

-- Money reserved on an account for a transfer that is waiting for approval. While a hold is
-- ACTIVE the amount is subtracted from the available balance but still counted in the ledger
-- balance, because nothing has been posted yet. A hold ends either CAPTURED (the transfer was
-- approved and posted) or RELEASED (rejected or expired); released_at records that moment.
CREATE TABLE hold (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id  BIGINT                   NOT NULL,
    transfer_id BIGINT                   NOT NULL,
    amount      NUMERIC(19, 2)           NOT NULL,
    currency    CHAR(3)                  NOT NULL,
    status      VARCHAR(20)              NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    released_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_hold_account FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT fk_hold_transfer FOREIGN KEY (transfer_id) REFERENCES transfer (id),
    CONSTRAINT ck_hold_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_hold_status CHECK (status IN ('ACTIVE', 'RELEASED', 'CAPTURED')),
    CONSTRAINT ck_hold_active_not_released CHECK ((status = 'ACTIVE') = (released_at IS NULL))
);

-- Partial indexes: only ACTIVE holds are ever looked up, and a transfer may have at most one.
CREATE UNIQUE INDEX uk_hold_active_transfer ON hold (transfer_id) WHERE status = 'ACTIVE';
CREATE INDEX ix_hold_active_account ON hold (account_id) WHERE status = 'ACTIVE';
CREATE INDEX ix_hold_active_expires_at ON hold (expires_at) WHERE status = 'ACTIVE';
