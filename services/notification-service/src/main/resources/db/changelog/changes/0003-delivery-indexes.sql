--liquibase formatted sql

-- Indexes that serve the claim query and the two SLO gauges. Almost every one of them is partial,
-- which is the whole point: the vast majority of rows in this table are settled and will never be
-- claimed again, so an index covering them would be mostly dead weight - larger to scan, slower to
-- maintain on every insert, and no use to the only query that is hot.
--
-- The claim query's index comes first, and its column order is the query's:
--
--     WHERE status IN ('PENDING','FAILED')   -> the partial predicate
--       AND channel = ?                      -> leading equality
--       AND priority >= ?                    -> range, and the ORDER BY's first key
--       AND next_attempt_at <= ?             -> range, and the ORDER BY's second key
--     ORDER BY priority DESC, next_attempt_at, id
--     LIMIT ? FOR UPDATE SKIP LOCKED
--
-- Channel leads because it is the only equality predicate, so it is what makes the scan a range rather
-- than a filter. priority DESC and next_attempt_at follow in ORDER BY order, which lets Postgres stop
-- after LIMIT rows instead of sorting the whole backlog - the difference between a claim that is
-- constant-time in the queue depth and one that is not. id is the tie-break, and including it makes
-- the ordering total: without it two rows with the same priority and timestamp could be returned in
-- different orders by different replicas, which is not wrong but makes a concurrency bug impossible to
-- reproduce.

--changeset ludwig-notification:notification-0003-delivery-indexes dbms:postgresql
--comment The claim index, the two SLO gauges, and the dedup guard - all but two of them partial.
CREATE INDEX ix_notification_delivery_claim
    ON notification_delivery (channel, priority DESC, next_attempt_at, id)
    WHERE status IN ('PENDING', 'FAILED');

-- Oldest-pending age, the SLO signal worth alerting on. A separate index rather than reusing the claim
-- one: min(created_at) over the claimable set cannot be answered from an index whose leading column is
-- the channel without scanning every channel's range.
CREATE INDEX ix_notification_delivery_pending_age
    ON notification_delivery (created_at)
    WHERE status IN ('PENDING', 'FAILED');

-- The stale sweeper runs every minute and must not scan the table to find nothing.
CREATE INDEX ix_notification_delivery_stale
    ON notification_delivery (claimed_at)
    WHERE status = 'CLAIMED';

-- The digest job scans only what is actually batched.
CREATE INDEX ix_notification_delivery_digest
    ON notification_delivery (digest_group, next_attempt_at)
    WHERE status = 'BATCHED' AND digest_group IS NOT NULL;

-- The last line of defence against a double send: even if two replicas both got past the
-- request-level idempotency claim, the second fan-out cannot create a second row for the same
-- recipient-channel pair.
CREATE UNIQUE INDEX ux_notification_delivery_dedup_key
    ON notification_delivery (dedup_key)
    WHERE dedup_key IS NOT NULL;

-- How an inbound provider receipt finds the delivery it is about.
CREATE INDEX ix_notification_delivery_provider_message
    ON notification_delivery (channel, provider_message_id)
    WHERE provider_message_id IS NOT NULL;

-- The retention purge and the scrub both select on settled_at.
CREATE INDEX ix_notification_delivery_settled
    ON notification_delivery (settled_at)
    WHERE settled_at IS NOT NULL;

CREATE INDEX ix_notification_delivery_request ON notification_delivery (request_id);

-- Every scoped admin search ANDs the caller's tenant into its WHERE clause.
CREATE INDEX ix_notification_delivery_tenant
    ON notification_delivery (tenant_id, created_at DESC);
--rollback DROP INDEX ix_notification_delivery_claim;
--rollback DROP INDEX ix_notification_delivery_pending_age;
--rollback DROP INDEX ix_notification_delivery_stale;
--rollback DROP INDEX ix_notification_delivery_digest;
--rollback DROP INDEX ux_notification_delivery_dedup_key;
--rollback DROP INDEX ix_notification_delivery_provider_message;
--rollback DROP INDEX ix_notification_delivery_settled;
--rollback DROP INDEX ix_notification_delivery_request;
--rollback DROP INDEX ix_notification_delivery_tenant;
