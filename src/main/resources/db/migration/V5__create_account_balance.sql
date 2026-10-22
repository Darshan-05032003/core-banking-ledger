-- Materialised balance per customer account. The ledger entries are the source of truth; this
-- row exists so a balance check and a funds reservation can be done with one locked read instead
-- of summing the account's history on every transfer. Available never exceeds ledger: the gap
-- is money reserved by pending holds.
CREATE TABLE account_balance (
    account_id        BIGINT PRIMARY KEY,
    ledger_balance    NUMERIC(19, 2) NOT NULL DEFAULT 0,
    available_balance NUMERIC(19, 2) NOT NULL DEFAULT 0,
    currency          CHAR(3)        NOT NULL,
    version           BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT fk_account_balance_account FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT ck_account_balance_ledger_not_negative CHECK (ledger_balance >= 0),
    CONSTRAINT ck_account_balance_available_not_negative CHECK (available_balance >= 0),
    CONSTRAINT ck_account_balance_available_within_ledger CHECK (available_balance <= ledger_balance)
);

-- Accounts opened before this table existed could not have received any money.
INSERT INTO account_balance (account_id, ledger_balance, available_balance, currency, version)
SELECT id, 0, 0, currency, 0
FROM account;
