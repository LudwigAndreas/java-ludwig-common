Every task is in `notification-service` (service tier, no in-repo dependents). Shorthands used
below:

```
UNIT  = mvn -pl :notification-service test -Dtest='<Class>'
IT    = mvn -pl :notification-service verify -Dit.test='<Class>'
ARCH  = mvn -pl :notification-service test -Dtest='NotificationArchitectureRulesTest'
STYLE = mvn -q validate
```

Phase 1–4 are the write side and are independently shippable (`IN_APP` requestable, inert until
asked for). Phase 5–7 are the read side. Phase 8 is the fallback, which depends on both. Do not
start a phase before the previous one's tasks pass.

## 1. The channel vocabulary and its classification

- [x] 1.1 Add `ChannelClass` (`INTERRUPTING`, `PASSIVE`) to `service.model`, with javadoc stating
      the test to apply — *does delivery reach somebody who is not asking for it right now?* — and
      stating that no build can check the answer, which discharges the first unmechanisable rule.
      Proved by `STYLE`.
- [x] 1.2 Make `ChannelType` carry `ChannelClass` as a **mandatory constructor argument** and add
      `IN_APP(PASSIVE)`, with `EMAIL`/`CHAT`/`WEBHOOK` as `INTERRUPTING`. Proved by
      `mvn -pl :notification-service compile` failing before the argument is supplied and passing
      after.
- [x] 1.3 Add `IN_APP` to `repository.entity.ChannelKind` and `web.dto.ChannelTypeDto`, and confirm
      the existing MapStruct mapper fails the build when only one of the three has it — add the
      constant to `ChannelKind` alone first and record the compile error in the change's
      `state.json` `decisions` as evidence the check is real. Proved by
      `mvn -pl :notification-service compile`.
- [x] 1.4 Unit-test the classification: every `ChannelType` constant has a non-null class, exactly
      one is `PASSIVE`, and the three vocabularies round-trip for all four constants. Proved by
      `UNIT` on `ChannelClassificationTest`.

## 2. Passive-channel semantics in the preference path

- [x] 2.1 Change `PreferenceEvaluator.evaluate` to skip the quiet-hours branch for a `PASSIVE`
      channel, consulting `channel.channelClass()` and never the `IN_APP` constant. Javadoc the
      reason: a quiet window exists so nobody is woken, and an item that waits wakes nobody.
      Proved by `UNIT` on `PreferenceEvaluatorTest`.
- [x] 2.2 Keep per-category opt-out applying to `PASSIVE`, including blanket-plus-exception
      precedence and the undeclinable-category bypass. Proved by `UNIT` on
      `PreferenceEvaluatorTest` covering the three scenarios in
      `specs/in-app-notification/spec.md` — "Requirement: Per-category opt-out applies to a passive
      channel".
- [x] 2.3 Make the `SuppressionService` call site skip the address-suppression check for a
      `PASSIVE` channel, with javadoc saying a suppression entry is a fact about an address and a
      passive channel has none. Proved by `UNIT` on `SuppressionServiceTest`.
- [x] 2.4 Make digest eligibility exclude `PASSIVE` channels, so a passive delivery is never
      `BATCHED`. Proved by `UNIT` on the digest tests.
- [x] 2.5 Add the ArchUnit rule forbidding any class in `..service.preference..` from reading the
      `ChannelType.IN_APP` constant, to the service's own architecture test sources — not to
      `architecture-rules`. Proved by `ARCH`, and by temporarily reintroducing a constant read and
      recording the failure in `state.json` `decisions`.

## 3. The inbox schema

- [x] 3.1 Write `src/main/resources/db/changelog/changes/0018-inbox-item.sql`: formatted SQL, id
      `notification-0018-inbox-item`, author `ludwig-notification`, `dbms:postgresql`, a real
      `--rollback DROP TABLE`, the columns and the
      `(owner_user_id, dismissed_at, read_at, created_at DESC)` index from `design.md` D3, and
      `delivery_id` nullable with `ON DELETE SET NULL`. Comment why nullable — the delivery is
      purged first and that is the normal case. Proved by `scripts/check_migrations.sh`.
- [x] 3.2 Write `0019-inbox-item-content.sql` the same way, primary key = item id, with a comment
      stating it is a second table for the same two reasons the delivery content table is one, and
      that the difference is the retention window. Proved by `scripts/check_migrations.sh`.
- [x] 3.3 Append both as `<include>` elements to `db.changelog-master.xml`, in ascending order,
      with a comment only — no other element kind. Proved by `scripts/check_migrations.sh`.
- [x] 3.4 Add `InboxItemEntity` and `InboxItemContentEntity` under `repository.entity`, with no
      `@Filterable` on `owner_user_id` and javadoc stating that a filterable owner would be an
      existence oracle. Proved by `mvn -pl :notification-service compile` generating the Q-types.
- [x] 3.5 Liquibase integration test: both tables and the index are created from an empty database
      and the rollback drops them cleanly. Proved by `IT` on `InboxSchemaIT`.

## 4. In-app settlement in the fan-out transaction

- [x] 4.1 Add `IN_APP` as a render target in the template path (`RenderRequest` /
      `RenderedNotification`), reusing the existing FreeMarker resolution and the recipient's
      locale. No new engine, no per-channel renderer. Proved by `UNIT` on the renderer tests.
- [x] 4.2 Settle `IN_APP` in `NotificationServiceImpl`'s fan-out transaction: `ACCEPTED →
      DELIVERED`, writing the item and its content, next to the existing `SUPPRESSED`/`BATCHED`/
      `DEAD` settlements. Never write `PENDING`. Javadoc the carve-out at the settlement site:
      why the queue exists, why this destination is not what it protects against, and that the
      boundary is the "no implementation supports `IN_APP`" test. Proved by `IT` on
      `InAppSettlementIT`.
- [x] 4.3 Make an `IN_APP` delivery for an `ADDRESS` recipient terminal at fan-out with a reason
      naming the unresolvable recipient, leaving the request `202` and the other recipients
      unaffected. Proved by `IT` on `InAppSettlementIT`.
- [x] 4.4 Store no `recipient_address` on an `IN_APP` delivery, and confirm no metric tag and no
      lifecycle event carries a recipient, body or item id. Proved by `UNIT` on
      `InAppPiiTest` plus `IT` on the existing event-topic test.
- [x] 4.5 Add the context test asserting `ChannelRegistry.find(IN_APP)` is empty, with javadoc
      saying why neither ArchUnit nor Checkstyle can own it — support is a predicate's return
      value, not a type's shape or a source-text fact. Proved by `IT` on
      `NoInAppChannelBeanIT`.
- [x] 4.6 Add the integration test asserting a claim run with in-app deliveries present returns
      exactly the rows it would have returned without them. Name it `…IT` so failsafe runs it.
      Proved by `IT` on `ClaimIgnoresInAppIT`.
- [x] 4.7 Add the two ArchUnit rules — no read-state field on a class assignable to the delivery
      entity, and nothing on the in-app settlement path accessing the delivery content entity or
      its repository — to the service's own architecture test sources. Proved by `ARCH`.

## 5. The inbox aggregate and its service

- [x] 5.1 Add `service.model` views for an inbox item and the unread count, and the
      `repository.query` QueryDSL predicates for owner, read state, category and the default
      newest-first order. No JPQL, no derived query methods, no native SQL. Proved by `UNIT` on
      `InboxPredicateTest`.
- [x] 5.2 Implement the three monotonic transitions — seen, read (which also sets seen), dismissed
      — each idempotent and each refusing a caller who is not the owner. Transactions in the
      service layer. Proved by `UNIT` on `InboxServiceTest`.
- [x] 5.3 Implement mark-all-read as a **QueryDSL update clause** returning the moved count, and
      javadoc that this is why the change claims no native-SQL carve-out. Proved by `IT` on
      `MarkAllReadIT` asserting twelve-then-zero and that no other owner's item moves.
- [x] 5.4 Add the ArchUnit rule asserting `owner_user_id` carries no `@Filterable`. Proved by
      `ARCH`.

## 6. The recipient-facing read API

- [x] 6.1 Add `InboxController` with the seven endpoints in `design.md` D6, resolving the owner
      from the authenticated principal — no owner path variable, no owner query parameter. Proved
      by `IT` on `InboxControllerIT`.
- [x] 6.2 Wire the list endpoint to `odata-filter-spring-boot-starter`'s options object, page
      envelope and published filterable surface, defaulting to newest first and excluding
      dismissed items. Proved by `IT` on `InboxQueryIT` covering paging position, the metadata
      document omitting the owner field, and a combined read-state-plus-category filter.
- [x] 6.3 Make another subject's item and a nonexistent item return the **same** refusal, through
      `web-core`'s single ProblemDetail pipeline — no `@RestControllerAdvice` in this service.
      Proved by `IT` on `InboxScopingIT` asserting the two responses are byte-identical.
- [x] 6.4 Confirm no inbox endpoint returns an `OperationResponse`, a status-resource header or a
      `Retry-After`; an inbox write completes in the request. Proved by `IT` on
      `InboxControllerIT`.
- [x] 6.5 Add the i18n keys for every piece of user-facing inbox text to
      `i18n/ludwig-notification-messages.properties` and `…_ru.properties`, with matching key
      sets and no hard-coded text. Proved by `STYLE` (`NonAsciiSourceText`) and `UNIT` on the
      existing bundle key-set parity test.

## 7. Retention

- [x] 7.1 Add `retention.inbox-ttl` (90d), `retention.inbox-content-ttl` (90d) and
      `retention.inbox-unread-max-age` (**unset**) to `NotificationProperties`, with javadoc on
      the last one stating that purging an unread notification discards something the recipient
      was meant to receive, so it must be a decision somebody made — which discharges the third
      unmechanisable rule. Proved by `UNIT` on `NotificationPropertiesTest`.
- [x] 7.2 Add the two sweeps to `RetentionService`, anchored on `coalesce(read_at, dismissed_at)`,
      under the existing `RunLock` and in the existing batched shape. An unread item with no
      ceiling configured is never purged. Proved by `IT` on `InboxRetentionIT`.
- [x] 7.3 Add the unread-ceiling sweep, counted and logged **distinctly** from the read-anchored
      sweep. Proved by `IT` on `InboxRetentionIT` asserting the two counters move independently.
- [x] 7.4 Extend `NotificationConfigurationValidator` so the pod refuses to start when
      `inbox-content-ttl` exceeds `inbox-ttl`, in the same shape as the four existing window
      relations. Proved by `UNIT` on `NotificationConfigurationValidatorTest`.

## 8. The suppression fallback

- [x] 8.1 Add `preferences.fallback.<category-class>` to `NotificationProperties`, unset by
      default, with javadoc stating it is configuration keyed by category class rather than a
      request field for the same reason the transactional bypass is, and that no build can check
      whether the configured answer is right — the second unmechanisable rule. Proved by `UNIT`
      on `NotificationPropertiesTest`.
- [x] 8.2 Evaluate the fallback once per recipient after that recipient's interrupting deliveries
      have settled and before commit, applying only when **all** are `SUPPRESSED`, the class
      declares a fallback, the caller did not already request that channel, and the recipient has
      not opted out of it. Proved by `IT` on `SuppressionFallbackIT` covering all five scenarios
      in `specs/in-app-notification/spec.md` — "Requirement: A configured fallback …".
- [x] 8.3 Count fallback deliveries as their own metric, tagged by channel, category and outcome
      only. Proved by `UNIT` on the metrics test asserting no tag is valued by recipient or item.

## 9. Documentation

- [x] 9.1 Update `services/notification-service/README.md`: the state machine diagram (the in-app
      settlement), the channel table, the **add-a-channel section** — which currently says adding
      a transport is adding a bean, now true for every interrupting transport and explicitly not
      the whole story, with the carve-out's boundary and its check named — the preferences
      section, the retention table and the known-gaps list. Proved by review against
      `design.md` D1/D8 plus `openspec validate add-in-app-notifications`.
- [x] 9.2 Mirror every change into `README.ru.md`, section for section. Proved by a section-heading
      diff between the two files.
- [x] 9.3 Document the new API in the controllers' OpenAPI descriptions, including that the owner
      is never a parameter and that a foreign item and a missing item are indistinguishable.
      Proved by `IT` on the existing OpenAPI snapshot test.
- [x] 9.4 Record in `state.json` `decisions` the evidence gathered in 1.3, 2.5 and 4.5 that each
      new check actually fails when violated, which is what "encode every rule twice" asks for.
      Proved by `openspec validate add-in-app-notifications`.

## 10. The verification gate, in full

- [x] 10.1 `scripts/manifest.sh stale` is clean — no POM changed, so `scripts/manifest.sh build`
      should not be needed; if a POM did change, run it and re-check.
- [x] 10.2 `scripts/check_image_pins.sh` — the README edits in phase 9 must not introduce a bare
      image tag.
- [x] 10.3 `scripts/check_api_baseline.sh` — the new endpoints are additive, so this must pass
      without a baseline waiver; if it reports a break, the API shape is wrong, not the baseline.
- [x] 10.4 `scripts/check_migrations.sh`
- [x] 10.5 `mvn -q validate`
- [x] 10.6 `mvn -pl :notification-service -am verify` — unit, integration, the JaCoCo gate
      (70% instruction / 60% branch) and the architecture rules.
- [x] 10.7 No in-repo dependents to build: `scripts/manifest.sh module services/notification-service`
      reports none, so there is no dependent step. Confirm that is still true rather than
      assuming it.
- [x] 10.8 `openspec validate add-in-app-notifications --strict`
- [x] 10.9 Or 10.2–10.6 in one, which is the authoritative list and the form to prefer:
      `scripts/gate.sh --change add-in-app-notifications notification-service`
      (bare artifactId — `gate.sh` rejects the `:` form and prints it for `mvn`).
