-- The notification consumer's side of at-least-once delivery. processed_event remembers which
-- event ids a consumer group has handled; the primary key is what turns a redelivered event
-- into a no-op. notification is the simulated SMS or email sent to the customer, written in the
-- same transaction as the processed_event row so the two can never disagree.
CREATE TABLE processed_event (
    event_id       UUID         NOT NULL,
    consumer_group VARCHAR(100) NOT NULL,
    processed_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_processed_event PRIMARY KEY (event_id, consumer_group)
);

CREATE TABLE notification (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id   UUID         NOT NULL,
    account_id BIGINT       NOT NULL,
    channel    VARCHAR(10)  NOT NULL,
    recipient  VARCHAR(100) NOT NULL,
    message    TEXT         NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_notification_account FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT uk_notification_event UNIQUE (event_id),
    CONSTRAINT ck_notification_channel CHECK (channel IN ('SMS', 'EMAIL'))
);

CREATE INDEX ix_notification_account_id ON notification (account_id, id);
