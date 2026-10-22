-- Transactional outbox: every posted journal leaves one row per affected customer account here,
-- written in the posting transaction. A relay publishes the rows to Kafka afterwards and stamps
-- published_at; a row whose publish failed keeps a count and the last reason, and is retried.
-- seq_no breaks ties between rows created in the same instant so the relay order is total.
CREATE TABLE outbox_event (
    id             UUID PRIMARY KEY,
    seq_no         BIGINT GENERATED ALWAYS AS IDENTITY,
    aggregate_type VARCHAR(50)  NOT NULL,
    aggregate_id   VARCHAR(100) NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    event_version  INTEGER      NOT NULL,
    partition_key  VARCHAR(100) NOT NULL,
    payload        JSONB        NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at   TIMESTAMP WITH TIME ZONE,
    attempts       INTEGER      NOT NULL DEFAULT 0,
    last_error     TEXT,
    CONSTRAINT ck_outbox_event_version CHECK (event_version >= 1),
    CONSTRAINT ck_outbox_event_attempts CHECK (attempts >= 0)
);

-- The relay only ever asks for unpublished rows in creation order; published rows drop out of
-- the index, so it stays small however long the history grows.
CREATE INDEX ix_outbox_event_unpublished ON outbox_event (created_at, seq_no) WHERE published_at IS NULL;
