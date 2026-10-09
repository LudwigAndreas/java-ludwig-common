Every task is in `notification-service` (service tier, no in-repo dependents). Shorthands:

```
UNIT  = mvn -pl :notification-service test -Dtest='<Class>'
IT    = mvn -pl :notification-service verify -Dit.test='<Class>'
STYLE = mvn -q validate
```

**Prerequisite:** `add-in-app-notifications` must be applied and archived first. This change edits
`PreferenceEvaluator` and the category vocabulary, which that change also touches — do not run the
two concurrently.

Phases 1–5 are the announcement itself and are independently shippable (visible announcements, no
email). Phase 6 is the email broadcast. Phase 7 is retention, 8 documentation, 9 the gate.

## 1. The category class split

- [ ] 1.1 Give `CategoryClass` (and its persistence twin `CategoryKind` and wire twin
      `CategoryClassDto`) a third constant `PLATFORM`, with `bypassesOptOut` and
      `bypassesQuietHours` as **mandatory constructor arguments** so a fourth class cannot compile
      without both answers. Javadoc the three-way table and state that no build can check whether a
      category's class is the right product answer. Proved by
      `mvn -pl :notification-service compile` failing before the arguments are supplied.
- [ ] 1.2 Split `PreferenceEvaluator`'s single bypass into the two questions, consulting the new
      flags and never a named constant. Keep the decision exhaustive over the class — no `default`
      arm, for the reason recorded in `specs/enforcement-triad`. Proved by `UNIT` on
      `PreferenceEvaluatorTest`.
- [ ] 1.3 Extend `PreferenceEvaluatorTest` with the full three-by-two matrix, including the two
      control rows asserting `TRANSACTIONAL` and `MARKETING` behave exactly as before. Proved by
      `UNIT` on `PreferenceEvaluatorTest`.
- [ ] 1.4 Confirm the MapStruct triad fails the build when only one of the three category
      vocabularies has `PLATFORM` — add it to `CategoryKind` alone first and record the compile
      error in `state.json` `decisions` as evidence. Proved by
      `mvn -pl :notification-service compile`.

## 2. Configuration: the catalogue and the allowlist

- [ ] 2.1 Add an `Announcements` block to `NotificationProperties`: `enabled`,
      `allowed-audiences`, `targetable-roles`, `max-visibility-window`, `fan-out.batch-size`,
      `fan-out.run-interval`, and `categories` mapping a name to `{category-class, channels}`. All
      **string-keyed and string-valued**, because the `settings` package is deliberately free of
      service-layer types. Proved by `UNIT` on `NotificationPropertiesTest`.
- [ ] 2.2 Validate the catalogue in `NotificationConfigurationValidator`: an unknown channel, an
      unknown category class, a window exceeding the maximum, and `enabled` with an empty catalogue
      all refuse startup, naming the category and the value. Proved by `UNIT` on
      `NotificationConfigurationValidatorTest`.
- [ ] 2.3 Add the `announcements` block to `application.yml`, **`enabled: false`** with an empty
      catalogue, commented with why each policy setting is policy rather than data. Proved by `IT`
      on any existing test (the context must still start).

## 3. The announcement schema

- [ ] 3.1 `0020-announcement.sql`: id, category, template key, `audience_kind`, `audience_value`,
      `visible_from`, `visible_until`, `published_by`, `published_at`, `updated_at`; index on
      `(visible_until, visible_from)` for the read path. Real `--rollback DROP TABLE`. Comment why
      the audience is two columns and not a join table. Proved by `scripts/check_migrations.sh`.
- [ ] 3.2 `0021-announcement-content.sql`: primary key **`(announcement_id, locale)`**, FK
      `ON DELETE CASCADE`. Comment that the row count follows the number of supported locales and
      never the audience size. Proved by `scripts/check_migrations.sh`.
- [ ] 3.3 `0022-announcement-marker.sql`: primary key `(announcement_id, owner_user_id)`,
      `dismissed_at`, FK `ON DELETE CASCADE`. Comment that `CASCADE` here is the opposite answer to
      the inbox item's `SET NULL` and why. Proved by `scripts/check_migrations.sh`.
- [ ] 3.4 Append all three as `<include>` elements in order; comments only. Proved by
      `scripts/check_migrations.sh`.
- [ ] 3.5 Add `AnnouncementEntity`, `AnnouncementContentEntity` (composite key) and
      `AnnouncementMarkerEntity`, with **no `@Filterable` on either audience column** and javadoc
      saying a filterable audience lets a caller enumerate which roles have been addressed. Proved
      by `mvn -pl :notification-service compile` generating the Q-types.
- [ ] 3.6 Liquibase integration test: the three tables, the read index, both cascade actions and the
      composite content key are created from an empty database. Proved by `IT` on
      `AnnouncementSchemaIT`.

## 4. Publishing

- [ ] 4.1 Add `AudienceKind` (`EVERYONE`, `ROLE`) and an `Audience` value type in `service.model`.
      Proved by `STYLE`.
- [ ] 4.2 Implement audience resolution in **one** method — the only place `targetable-roles` and
      `allowed-audiences` are read — refusing a role that is not on the allowlist **without
      revealing whether it exists**. Proved by `UNIT` on `AudienceResolverTest`.
- [ ] 4.3 Implement publish: render the template once per `supported-locales` **before inserting
      anything**, so a locale that fails rejects the whole publish; then insert the announcement and
      one content row per locale, in one transaction. Proved by `IT` on `AnnouncementPublishIT`.
- [ ] 4.4 Audit publish and content-correction through `audit-core`'s single `AuditSink` — typed
      record with `toAuditEvent()`, no `try`/`catch` around `record`. Proved by `IT` on
      `AnnouncementPublishIT` asserting the sink received the event.
- [ ] 4.5 Implement content correction: re-renders every supported locale together, leaves markers
      untouched, and **refuses** any attempt to change the audience. Proved by `IT` on
      `AnnouncementPublishIT`.
- [ ] 4.6 Add the publish/correct endpoints under a dedicated publishing role, distinct from
      `NOTIFICATION_ADMIN`, with the window and category validated as in 2.2. Proved by `IT` on
      `AnnouncementAdminIT`.
- [ ] 4.7 Add the ArchUnit rules: no collection-valued audience field or per-subject audience
      association on the announcement entity; no second role-audience construction path outside the
      resolver package; no `@Filterable` on the audience columns; and nothing in the inbox read path
      depending on the announcement entity or repository. Proved by `UNIT` on
      `InAppCarveOutRulesTest` (extended), **and** by temporarily introducing each violation and
      recording the failure in `state.json` `decisions`.

## 5. The recipient feed

- [ ] 5.1 Add the QueryDSL visibility predicate: window open, audience `EVERYONE` or
      `ROLE IN :myRoles` from the principal, and `NOT EXISTS` a marker for this caller. No join to
      `security_user_role`, no native SQL. Proved by `UNIT` on `AnnouncementPredicateTest`.
- [ ] 5.2 Implement `AnnouncementService`: list, unread count, get by id (including dismissed and
      expired-but-unpurged), and dismiss — resolving the caller from `SecurityPrincipals.require()`
      and taking no owner parameter. Content is read in the caller's locale with a default fallback.
      Proved by `UNIT` on `AnnouncementServiceTest`.
- [ ] 5.3 Add `AnnouncementController` — list, unread count, get, dismiss — on
      `isAuthenticated()` with no role, using the platform query options, page envelope and
      published filterable surface (`metadataName`). Proved by `IT` on `AnnouncementApiIT`.
- [ ] 5.4 Visibility integration test, which is the security-critical one: a caller holding the role
      sees it, one not holding it does not, revoking the role removes it on the next read without any
      job running, granting it reveals the backlog, and a user created after publication sees an
      `EVERYONE` announcement. Proved by `IT` on `AnnouncementVisibilityIT`.
- [ ] 5.5 Assert the inbox is untouched: the inbox list, count and item endpoints return exactly what
      they did before, with announcements present in the database. Proved by `IT` on
      `AnnouncementVisibilityIT`.
- [ ] 5.6 Add the i18n keys for the new failures (unknown category, role not targetable, window too
      long, audience not permitted) to both bundles with matching key sets and placeholders. Proved
      by `STYLE` and `UNIT` on `MessageBundleParityTest`.

## 6. The email broadcast

- [ ] 6.1 `0023-announcement-email-run.sql`: announcement id, status (platform vocabulary, stored as
      a string), cursor, created and total counts, timestamps; FK `ON DELETE CASCADE`. Comment that
      this is **this service's own run table** because there is no shared operation table. Proved by
      `scripts/check_migrations.sh`.
- [ ] 6.2 Add `AnnouncementEmailRunEntity` and its repository. Declare **no** status enum — reuse
      `OperationStatus`; `RuleGroup.OPERATIONS` already fails the build on a restatement, and this
      change relies on that rule rather than adding a copy. Proved by
      `mvn -pl :notification-service test -Dtest='ArchitectureTest'`.
- [ ] 6.3 Create a run at publish when, and only when, the category's channels include `EMAIL`; the
      announcement is visible before the run starts. Proved by `IT` on `AnnouncementEmailFanOutIT`.
- [ ] 6.4 Implement the batched fan-out: keyset page over `security_user` (joined to
      `security_user_role` for a `ROLE` audience) ordered by id after the cursor, create deliveries
      through the **existing** fan-out path, commit per batch, advance the cursor, renew the lease.
      Under `job-core`'s `RunLock`, like the digest and retention schedulers. Proved by `IT` on
      `AnnouncementEmailFanOutIT`.
- [ ] 6.5 Prove resumability and exactly-once: interrupt a run part way, resume it, and assert the
      delivery count equals the audience size and no recipient has two deliveries — relying on the
      existing unique `dedupKey` rather than a second mechanism. Proved by `IT` on
      `AnnouncementEmailFanOutIT`.
- [ ] 6.6 Prove the per-recipient rules still apply: a suppressed address is suppressed, a recipient
      with no verified address is one terminal delivery and does not stop the run, and a `PLATFORM`
      announcement to somebody inside quiet hours is deferred rather than sent. Proved by `IT` on
      `AnnouncementEmailFanOutIT`.
- [ ] 6.7 Expose the run through `OperationResponse`, built with `OperationResponses`: 202 with a
      status-resource header at publish, a non-terminal poll carrying `Retry-After` and progress, a
      terminal poll linking the announcement. Proved by `IT` on `AnnouncementOperationIT`.
- [ ] 6.8 Implement cancellation: the endpoint answers **202**, the loop stops between batches,
      already-created deliveries are **not** recalled, the envelope reports how many were created,
      and cancelling an already-terminal run returns the envelope rather than a 409. Proved by `IT`
      on `AnnouncementOperationIT`.
- [ ] 6.9 Add the fan-out metrics — runs, deliveries created, duration — tagged by category and
      outcome only, with **no** tag valued by announcement id, recipient or content. Proved by
      `UNIT` on `AnnouncementMetricTest`.

## 7. Retention

- [ ] 7.1 Add `retention.announcement-ttl` (90d), measured from `visible_until` rather than from
      creation, with javadoc on the anchor. Proved by `UNIT` on `NotificationPropertiesTest`.
- [ ] 7.2 Add the purge step to `RetentionService` and `RetentionScheduler`, under the existing
      `RunLock` and batched shape: content, markers and the run go with the announcement in one
      cascade. Proved by `IT` on `AnnouncementRetentionIT`.
- [ ] 7.3 Assert retention cost does not scale with audience: purging an `EVERYONE` announcement
      nobody dismissed is a constant number of rows, and a still-visible announcement is never
      purged whatever its age. Proved by `IT` on `AnnouncementRetentionIT`.
- [ ] 7.4 Extend `NotificationConfigurationValidator` so `announcement-ttl` shorter than
      `max-visibility-window` refuses startup — an announcement purged while still visible would
      vanish mid-window. Proved by `UNIT` on `NotificationConfigurationValidatorTest`.

## 8. Documentation

- [ ] 8.1 Add an announcements section to `README.md` beside the inbox one: the one-row aggregate,
      the audience predicate, live-vs-snapshot with the table, the two feeds and why they are two,
      the configuration block, the three-row category-class table, the email operation, and the
      stated divergence risk. Update the retention table and the known-gaps list (org units,
      notification-local groups and acknowledgement as explicit non-goals). Proved by review against
      `design.md` D1–D8 plus `openspec validate add-platform-announcements`.
- [ ] 8.2 Mirror every change into `README.ru.md`, section for section. Proved by a section-heading
      diff between the two files.
- [ ] 8.3 Document the endpoints in their OpenAPI descriptions, including that the audience is not
      filterable, that cancel answers 202, and that already-created deliveries are not recalled.
      Proved by `IT` on `InboxOpenApiIT` (extended) or a sibling `AnnouncementOpenApiIT`.
- [ ] 8.4 Record in `state.json` `decisions` the evidence gathered in 1.4 and 4.7 that each new check
      fails when violated. Proved by `openspec validate add-platform-announcements`.

## 9. The verification gate, in full

- [ ] 9.1 `scripts/manifest.sh stale` is clean — no POM should have changed; if one did, run
      `scripts/manifest.sh build` and re-check.
- [ ] 9.2 `scripts/check_image_pins.sh`
- [ ] 9.3 `scripts/check_migrations.sh`
- [ ] 9.4 `mvn -q validate`
- [ ] 9.5 `mvn -pl :notification-service -am verify` — unit, integration, the JaCoCo gate and the
      architecture rules.
- [ ] 9.6 No in-repo dependents: confirm `scripts/manifest.sh module services/notification-service`
      still reports none rather than assuming it.
- [ ] 9.7 `openspec validate add-platform-announcements --strict`
- [ ] 9.8 Or 9.2–9.5 in one, which is the authoritative list:
      `scripts/gate.sh --change add-platform-announcements notification-service` (bare artifactId —
      `gate.sh` rejects the `:` form it prints for `mvn`). Note that
      `scripts/check_api_baseline.sh` is part of that list and cannot pass without access to the
      releases repository; it is unrelated to this change.
