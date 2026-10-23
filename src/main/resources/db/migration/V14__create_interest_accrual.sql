-- One row per savings account per day: the balance the account held at the end of that day, the
-- annual rate in force, and the interest earned for the day kept at four decimals so a month of
-- fractions adds up before anything is rounded to paise. The unique key is what makes the daily
-- job safe to run twice. posted_journal_id is set when the month's accruals are paid out as one
-- INTEREST journal; rows that still carry NULL are the interest the bank owes but has not yet
-- credited.
CREATE TABLE interest_accrual (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id        BIGINT                   NOT NULL,
    accrual_date      DATE                     NOT NULL,
    balance           NUMERIC(19, 2)           NOT NULL,
    annual_rate       NUMERIC(9, 6)            NOT NULL,
    amount            NUMERIC(19, 4)           NOT NULL,
    currency          CHAR(3)                  NOT NULL,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    posted_journal_id BIGINT,
    CONSTRAINT fk_interest_accrual_account FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT fk_interest_accrual_journal FOREIGN KEY (posted_journal_id) REFERENCES journal_entry (id),
    CONSTRAINT uk_interest_accrual_account_date UNIQUE (account_id, accrual_date),
    CONSTRAINT ck_interest_accrual_balance_not_negative CHECK (balance >= 0),
    CONSTRAINT ck_interest_accrual_rate_not_negative CHECK (annual_rate >= 0),
    CONSTRAINT ck_interest_accrual_amount_not_negative CHECK (amount >= 0)
);

-- Month-end posting only ever asks for the rows not yet paid out; posted rows leave the index.
CREATE INDEX ix_interest_accrual_unposted ON interest_accrual (account_id, accrual_date)
    WHERE posted_journal_id IS NULL;

ALTER TABLE journal_entry DROP CONSTRAINT ck_journal_entry_type;
ALTER TABLE journal_entry
    ADD CONSTRAINT ck_journal_entry_type
        CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'INTEREST', 'REVERSAL'));
