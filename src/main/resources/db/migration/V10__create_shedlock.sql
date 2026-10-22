-- ShedLock's lock table: one row per scheduled job name. An instance that wants to run a job
-- first tries to take the row until lock_until; when several instances share the database only
-- one of them succeeds, so a job runs once per schedule however many copies of the app are up.
CREATE TABLE shedlock (
    name       VARCHAR(64)              NOT NULL,
    lock_until TIMESTAMP WITH TIME ZONE NOT NULL,
    locked_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    locked_by  VARCHAR(255)             NOT NULL,
    CONSTRAINT pk_shedlock PRIMARY KEY (name)
);
