## Context

The change is confined to `messaging-db-spring-boot-starter`, which has no in-repo dependents. It is written up
because it changes the shared `messaging-db-admin` contract and the claim query every log consumer runs.

## Decisions

1. **Pause as a table, read by the claim.** `consumer_pause(group_id, topic, paused_by, paused_at, reason,
   PK(group_id, topic))`. `LogStore.claim` adds `AND NOT EXISTS (SELECT 1 FROM consumer_pause p WHERE p.group_id =
   o.group_id AND p.topic = o.topic)`.
   - A per-replica flag was rejected: it would need the replica broadcast and would be lost on restart.
   - A column on `consumer_offset` was rejected: a pause is per topic, not per partition, and the offset rows for a
     partition may not exist yet when a group first subscribes.
2. **Move under the row lock, and only while paused.** The move runs in one transaction:
   - it verifies that the pause row exists;
   - it takes `SELECT ... FOR UPDATE` (not `SKIP LOCKED`) on the targeted `consumer_offset` rows, which waits out
     any record still in flight from before the pause;
   - it resolves the target positions (`earliest` = minimum retained offset − 1, `latest` = head − 1, `timestamp` =
     first `log_offset` with `produced_at >= t`, minus 1, and an explicit offset clamped to the retained range);
   - it updates `committed_offset`, `attempts = 0` and `next_attempt_at = now()`;
   - it computes skipped and replayed counts from the old and new positions.

   The pause is required although the row lock alone would make the move safe. An operator moving a running group
   cannot know which record was in flight, and Kafka's own reset tool makes the same demand.
3. **Positions use the existing semantics.** `committed_offset` is "last handled". Every target is expressed as
   "next to deliver − 1", so the delivery code is unchanged.
4. **Admin layer.** `messaging/admin/service` gains `pause`, `resume` and `moveOffsets`. These are audited through
   `AdminAudit`, with resource `consumer-group` and id `<group>/<topic>`. The controller follows the module's
   existing `admin/web` controller.
5. **Migration discipline.** `0005-create-consumer-pause.sql` begins with the same
   `pg_advisory_xact_lock(hashtext('ludwig-messaging-db'))` and uses `IF NOT EXISTS`, satisfying
   `db-log-transport`'s "installs safely from every service that shares the database". `scripts/check_migrations.sh`
   covers its form.

**Dependency-direction check.** Does `messaging-db-spring-boot-starter` gain an in-repo dependency? No. It already
depends on `security-spring-boot-starter`, `web-core-spring-boot-starter` and `job-core`.

**POMs.** No POM changes: not the root `pom.xml`, `ludwig-bom` or `ludwig-service-parent`.

**Mechanical checks.** No new convention.

## Risks / Trade-offs

- [A pause forgotten in production.] → The listing shows it, with who paused it and since when. `LogLagMonitor`'s
  lag metric grows, and the existing lag alert fires as it would for a stuck consumer.
- [A shared database serves several services, and one service pauses another service's group.] → Groups are
  addressed by name, and a service's admin surface can address only groups whose listeners it runs. Any other
  group is `404`, which is decided from the local listener registry rather than the table.
