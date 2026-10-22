-- One row per (requester, Idempotency-Key). The unique constraint is what makes two concurrent
-- requests with the same key collapse into one: only one INSERT can win. The winner stores the
-- response it produced so later replays can return it without doing the work again.
CREATE TABLE idempotency_key (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    requester       VARCHAR(100)             NOT NULL,
    key_value       VARCHAR(64)              NOT NULL,
    request_hash    CHAR(64)                 NOT NULL,
    status          VARCHAR(20)              NOT NULL,
    response_status INTEGER,
    response_body   JSONB,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at    TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_idempotency_key_requester_key UNIQUE (requester, key_value),
    CONSTRAINT ck_idempotency_key_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT ck_idempotency_key_completed_has_response CHECK (
        (status = 'COMPLETED') = (response_status IS NOT NULL AND response_body IS NOT NULL AND completed_at IS NOT NULL))
);
