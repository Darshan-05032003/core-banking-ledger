-- Logins for staff and customers. A user may hold several roles (a branch manager who is both
-- TELLER and CHECKER), so roles live in their own table. A CUSTOMER login is tied to exactly one
-- customer record and a customer has at most one login.
CREATE TABLE app_user (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username      VARCHAR(50)              NOT NULL,
    password_hash VARCHAR(100)             NOT NULL,
    customer_id   BIGINT,
    enabled       BOOLEAN                  NOT NULL,
    created_by    VARCHAR(100)             NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_app_user_username UNIQUE (username),
    CONSTRAINT uk_app_user_customer UNIQUE (customer_id),
    CONSTRAINT fk_app_user_customer FOREIGN KEY (customer_id) REFERENCES customer (id)
);

CREATE TABLE app_user_role (
    user_id BIGINT      NOT NULL,
    role    VARCHAR(20) NOT NULL,
    CONSTRAINT pk_app_user_role PRIMARY KEY (user_id, role),
    CONSTRAINT fk_app_user_role_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT ck_app_user_role CHECK (role IN ('CUSTOMER', 'TELLER', 'CHECKER', 'ADMIN'))
);

-- Who did what, to which record, with which outcome. Failed attempts are rows too, which is why
-- the writer uses its own transaction. Like the ledger, the trail can only grow.
CREATE TABLE audit_log (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    actor          VARCHAR(100)             NOT NULL,
    roles          VARCHAR(100)             NOT NULL,
    action         VARCHAR(50)              NOT NULL,
    target_type    VARCHAR(30)              NOT NULL,
    target_id      VARCHAR(64),
    outcome        VARCHAR(10)              NOT NULL,
    error_code     VARCHAR(50),
    client_ip      VARCHAR(45),
    correlation_id VARCHAR(64),
    details        JSONB,
    CONSTRAINT ck_audit_log_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE')),
    CONSTRAINT ck_audit_log_failure_has_code CHECK ((outcome = 'FAILURE') = (error_code IS NOT NULL))
);

CREATE INDEX ix_audit_log_target ON audit_log (target_type, target_id);
CREATE INDEX ix_audit_log_actor_occurred_at ON audit_log (actor, occurred_at);

CREATE TRIGGER trg_audit_log_append_only
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

CREATE TRIGGER trg_audit_log_no_truncate
    BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_mutation();
