-- Transfers join deposits, withdrawals and reversals as a journal type.
ALTER TABLE journal_entry DROP CONSTRAINT ck_journal_entry_type;
ALTER TABLE journal_entry
    ADD CONSTRAINT ck_journal_entry_type CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'REVERSAL'));

CREATE SEQUENCE transfer_reference_seq START WITH 1 INCREMENT BY 1 NO CYCLE;

-- One row per transfer attempt, including the ones the rules rejected: the customer and the
-- support desk can quote the reference of a failed transfer just like a completed one. Only a
-- COMPLETED transfer has moved money, so only it points at a journal.
CREATE TABLE transfer (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reference        VARCHAR(20)              NOT NULL,
    from_account_id  BIGINT                   NOT NULL,
    to_account_id    BIGINT                   NOT NULL,
    amount           NUMERIC(19, 2)           NOT NULL,
    currency         CHAR(3)                  NOT NULL,
    remarks          VARCHAR(200),
    status           VARCHAR(20)              NOT NULL,
    journal_entry_id BIGINT,
    failure_code     VARCHAR(50),
    failure_reason   VARCHAR(255),
    created_by       VARCHAR(100)             NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at     TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_transfer_reference UNIQUE (reference),
    CONSTRAINT uk_transfer_journal_entry UNIQUE (journal_entry_id),
    CONSTRAINT fk_transfer_from_account FOREIGN KEY (from_account_id) REFERENCES account (id),
    CONSTRAINT fk_transfer_to_account FOREIGN KEY (to_account_id) REFERENCES account (id),
    CONSTRAINT fk_transfer_journal_entry FOREIGN KEY (journal_entry_id) REFERENCES journal_entry (id),
    CONSTRAINT ck_transfer_distinct_accounts CHECK (from_account_id <> to_account_id),
    CONSTRAINT ck_transfer_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_transfer_status CHECK (status IN ('COMPLETED', 'FAILED', 'PENDING_APPROVAL')),
    CONSTRAINT ck_transfer_completed_has_journal CHECK ((status = 'COMPLETED') = (journal_entry_id IS NOT NULL)),
    CONSTRAINT ck_transfer_failed_has_code CHECK ((status = 'FAILED') = (failure_code IS NOT NULL))
);

-- The daily limit sums a sender's transfers for the current day.
CREATE INDEX ix_transfer_from_account_created_at ON transfer (from_account_id, created_at);
CREATE INDEX ix_transfer_to_account_id ON transfer (to_account_id);
