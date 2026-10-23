-- The purge job deletes published rows older than the retention period. Without this index it
-- would scan the whole table every night; the unpublished index cannot help because those rows
-- are exactly the ones it excludes.
CREATE INDEX ix_outbox_event_published_at ON outbox_event (published_at) WHERE published_at IS NOT NULL;
