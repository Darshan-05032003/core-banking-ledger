CREATE TABLE customer (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    full_name      VARCHAR(100)             NOT NULL,
    email          VARCHAR(254)             NOT NULL,
    phone          VARCHAR(16)              NOT NULL,
    date_of_birth  DATE                     NOT NULL,
    kyc_status     VARCHAR(20)              NOT NULL,
    kyc_reason     VARCHAR(255),
    kyc_decided_at TIMESTAMP WITH TIME ZONE,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_customer_email UNIQUE (email),
    CONSTRAINT ck_customer_kyc_status CHECK (kyc_status IN ('PENDING', 'VERIFIED', 'REJECTED')),
    CONSTRAINT ck_customer_kyc_reason CHECK (kyc_status <> 'REJECTED' OR kyc_reason IS NOT NULL)
);
