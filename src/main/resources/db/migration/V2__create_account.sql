-- Feeds the running-sequence part of the account number; the check digit is computed in code.
CREATE SEQUENCE account_number_seq START WITH 1 INCREMENT BY 1 MAXVALUE 9999999 NO CYCLE;

CREATE TABLE account (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_number VARCHAR(12)              NOT NULL,
    customer_id    BIGINT                   NOT NULL,
    type           VARCHAR(20)              NOT NULL,
    currency       CHAR(3)                  NOT NULL,
    status         VARCHAR(20)              NOT NULL,
    opened_on      DATE                     NOT NULL,
    closed_on      DATE,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_account_number UNIQUE (account_number),
    CONSTRAINT fk_account_customer FOREIGN KEY (customer_id) REFERENCES customer (id),
    CONSTRAINT ck_account_number_format CHECK (account_number ~ '^[0-9]{12}$'),
    CONSTRAINT ck_account_type CHECK (type IN ('SAVINGS', 'CURRENT')),
    CONSTRAINT ck_account_status CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    CONSTRAINT ck_account_closed_on CHECK ((status = 'CLOSED') = (closed_on IS NOT NULL))
);

CREATE INDEX ix_account_customer_id ON account (customer_id);
