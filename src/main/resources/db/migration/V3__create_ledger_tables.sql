-- Internal accounts of the bank itself. Customer money is a liability (the bank owes it), the
-- cash that backs it is an asset, and interest paid out is an expense.
CREATE TABLE gl_account (
    id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code     VARCHAR(40)  NOT NULL,
    name     VARCHAR(100) NOT NULL,
    type     VARCHAR(20)  NOT NULL,
    CONSTRAINT uk_gl_account_code UNIQUE (code),
    CONSTRAINT ck_gl_account_type CHECK (type IN ('ASSET', 'LIABILITY', 'INCOME', 'EXPENSE'))
);

INSERT INTO gl_account (code, name, type) VALUES
    ('CASH_VAULT', 'Cash in vault', 'ASSET'),
    ('INTEREST_EXPENSE', 'Interest paid on deposits', 'EXPENSE'),
    ('SUSPENSE', 'Suspense', 'LIABILITY');

CREATE SEQUENCE journal_reference_seq START WITH 1 INCREMENT BY 1 NO CYCLE;

CREATE TABLE journal_entry (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reference   VARCHAR(20)              NOT NULL,
    type        VARCHAR(20)              NOT NULL,
    description VARCHAR(255)             NOT NULL,
    posted_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by  VARCHAR(100)             NOT NULL,
    reversal_of BIGINT,
    CONSTRAINT uk_journal_entry_reference UNIQUE (reference),
    CONSTRAINT fk_journal_entry_reversal_of FOREIGN KEY (reversal_of) REFERENCES journal_entry (id),
    -- A journal can be reversed at most once; the second reversal fails here even under a race.
    CONSTRAINT uk_journal_entry_reversal_of UNIQUE (reversal_of),
    CONSTRAINT ck_journal_entry_type CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'REVERSAL')),
    CONSTRAINT ck_journal_entry_reversal CHECK ((type = 'REVERSAL') = (reversal_of IS NOT NULL))
);

CREATE TABLE ledger_entry (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    journal_entry_id BIGINT         NOT NULL,
    account_id       BIGINT,
    gl_account_id    BIGINT,
    direction        VARCHAR(6)     NOT NULL,
    amount           NUMERIC(19, 2) NOT NULL,
    currency         CHAR(3)        NOT NULL,
    CONSTRAINT fk_ledger_entry_journal FOREIGN KEY (journal_entry_id) REFERENCES journal_entry (id),
    CONSTRAINT fk_ledger_entry_account FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT fk_ledger_entry_gl_account FOREIGN KEY (gl_account_id) REFERENCES gl_account (id),
    CONSTRAINT ck_ledger_entry_one_account CHECK ((account_id IS NULL) <> (gl_account_id IS NULL)),
    CONSTRAINT ck_ledger_entry_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_ledger_entry_amount_positive CHECK (amount > 0)
);

CREATE INDEX ix_ledger_entry_journal_entry_id ON ledger_entry (journal_entry_id);
CREATE INDEX ix_ledger_entry_account_id ON ledger_entry (account_id) WHERE account_id IS NOT NULL;
CREATE INDEX ix_ledger_entry_gl_account_id ON ledger_entry (gl_account_id) WHERE gl_account_id IS NOT NULL;
