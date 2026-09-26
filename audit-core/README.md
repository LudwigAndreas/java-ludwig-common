# audit-core

[English] | [Русский](README.ru.md)

The platform's **one** audit envelope, its sink SPI, and its redaction package.

This module exists because there were nine of it. Every starter in this repository that needed to
record what it had done invented a mechanism for doing so, and every one of those was a reasonable
local decision - a module needs somewhere for its trail to go, an SPI with a shipped default is the
idiomatic answer, and nobody writing the sixth was in a position to see the other five. The result
was nine SPIs, seven SLF4J implementations, three persistent stores with three different schemas, and
three different redaction markers. An auditor asking "show me everything this person did last
Tuesday" had nine places to look, in three formats, and one of them only existed in a log file that
had rotated away.

| Was | Is now |
|---|---|
| `ExportAuditSink` + `Slf4jExportAuditSink` | `AuditSink`; `ExportAuditEvent.toAuditEvent()` |
| `IngestAuditLogger` + `Slf4jIngestAuditLogger` | `AuditSink`; `IngestAuditEvent.toAuditEvent()` |
| `HotReloadAuditLogger` + `Slf4jHotReloadAuditLogger` | `AuditSink`; `HotReloadAuditEntry.toAuditEvent()` |
| `OutboxAuditLogger` + `Slf4jOutboxAuditLogger` | SPI kept (see below); `OutboxTransitionAudit.toAuditEvent()` |
| `ReconciliationAuditLogger` + `Slf4jReconciliationAuditLogger` | `AuditSink`; `ReconciliationAuditEvent.toAuditEvent()` |
| `AuditEventEmitter` + `LoggingAuditEventEmitter` | `AuditSink`; `OutboundCallAudit.toAuditEvent()` |
| `AccessAuditLogger` + `Slf4jAccessAuditLogger` | `AuditSink`; `AccessDecision.toAuditEvent()` |
| `SettingsAuditRecorder` writing its own table | a caller of `AuditSink` |
| `SecretRedaction`, `HeaderRedactor`, `SettingsRedaction` | `SensitivityClassifier` x4 + one `Redactor` |
| `db-core`'s `AuditorProvider` | **unchanged**, now fed from the same `ActorResolver` |

## Two modules, and why

`audit-core` depends on **nothing in this repository**, and must not be given a dependency on
anything in it.

The modules that most need a trail are the ones lowest in the stack: `security-spring-boot-starter`
records authorization decisions, `rest-client-spring-boot-starter` records outbound calls,
`hot-reload-spring-boot-starter` records configuration reloads. All three sit below `db-core`. Maven's
reactor DAG ignores scope, so even an `optional` or `provided` edge from here to `db-core` would be a
cycle the moment anything upstream of `db-core` wanted to audit. Everything that needs a database, a
scheduler or an outbox therefore lives in
[`audit-spring-boot-starter`](../audit-spring-boot-starter) instead - the same core/starter shape this
repository already uses for `db-core` and `web-core-spring-boot-starter`.

Redaction lives here, in `ru.ludwigandreas.audit.redaction`, rather than in a third module, because
this module already guarantees zero in-repo dependencies - which is the only property a separate
`redaction-core` would have added. The consequence is worth stating because it looks odd until you
know why: a module importing an *audit* module purely for its redaction package is the intended
arrangement, not a mistake. `observability-spring-boot-starter` already does exactly that, for
`Redaction.MASK` alone, so that an operator does not have two markers to learn - and
`web-core-spring-boot-starter` may do the same to mask a problem document.

## The envelope

```java
public record AuditEvent(
        UUID id,                        // assigned at construction; the idempotency key for shipping
        Instant occurredAt,
        String category,                // "export", "ingest", "access", "settings", "outbound-call", ...
        String action,                  // "run.completed", "access.denied", "setting.changed"
        Actor actor,                    // subject, principalType, displayName, onBehalfOf
        Resource resource,              // type, id, name
        AuditOutcome outcome,           // SUCCESS | FAILURE | DENIED | PARTIAL, + reason
        String correlationId,
        String traceId,
        Map<String, Object> attributes) // everything module-specific, already redacted
```

Derived from `security-spring-boot-starter`'s `AccessDecision` rather than invented: that record was
already the closest thing here to a universal envelope - actor, resource type, resource id, action,
outcome, reason.

Three rules hold for every event in the platform.

**`attributes` is redacted at construction, never at the sink.** The rule started in the hot-reload
module, whose `AuditingSourceChangeListener` said why: redaction is "deliberately applied at the point
the audit entry is built, not left to each logger implementation, so a custom logger persisting to a
database or shipping to a SIEM can't forget to do it." A sink that *can* receive an unredacted event
is a sink that will eventually write one somewhere permanent.

**Module events are subtypes in spirit, not replacements.** `ExportAuditEvent`, `OutboundCallAudit`,
`AccessDecision`, `IngestAuditEvent` and `ReconciliationAuditEvent` all keep their typed shape at the
call site - thirteen components assembled positionally are readable and thirteen entries put into a
`Map<String, Object>` are not - and each gained a `toAuditEvent()`. The typed record is the authoring
surface; `AuditEvent` is the transport and storage surface.

**No payloads, ever.** The rule `AccessDecision` and `OutboundCallAudit` each stated for themselves,
generalised: an audit trail is retained for years and read by people who are not entitled to the data
it guards, so it records *which* object was touched and never what was in it.

## The sinks

```java
public interface AuditSink {
    void record(AuditEvent event);
}
```

One method, because "an audit sink that could be asked questions would be tempting to read from, and a
trail that the thing being audited also reads is a trail that will eventually be filtered by it"
(`ExportAuditSink`, when it was one of nine). Reading is the query API in the starter, which is a
different type with a different set of callers.

| Sink | Where | What it does |
|---|---|---|
| `Slf4jAuditSink` | here | One structured line per event on the `ru.ludwigandreas.audit` logger |
| `NoopAuditSink` | here | Discards, for a test or a deployment that routes elsewhere |
| `CompositeAuditSink` | here | Fans out; several at once is the normal configuration |
| `JpaAuditSink` | starter | The append-only `audit_event` table |
| `OutboxAuditSink` | starter | Exactly-once shipping to a SIEM through the transactional outbox |

Adding this module to a service is enough to get a working trail: `AuditCoreAutoConfiguration` wires
the SLF4J sink, the composite and the failure policy with no database and no scheduler. That is
deliberate - a module below `db-core` cannot take the starter, and if the wiring lived only there
those modules would each have had to wire a sink for themselves, which is how nine of them ended up
with nine mechanisms.

### An implementation may throw, and that is new

Every one of the seven SPIs this replaces said "must not throw", and each was right about the events
it carried. The old rule was still wrong as a platform rule, because it made an unrecorded state
change silently normal.

What decides whether a caller survives is `AuditFailurePolicy`, applied by `FailurePolicyAuditSink`,
which every wiring in this platform puts in front of the real sink:

| Category | Default policy | Why |
|---|---|---|
| `settings` | `FAIL_OPERATION` | A settings or consent change that committed without its audit row is precisely what the trail exists to make impossible - and its audit write is already inside the caller's transaction, so rethrowing rolls the change back rather than reporting a change that stands |
| everything else | `LOG_AND_CONTINUE` | Failing a request because the audit write failed turns an audit outage into a service outage, and the operational response to that is invariably to switch the trail off |

A module must not put its own `try`/`catch` around `record`. Doing so overrides a deployment's
configured policy with one hard-coded in a library. (`AuthorizationDeniedAuditListener` is the single
exception in the platform, and its javadoc says why: it is protecting the authorization decision from
*the listener*, not the caller from the sink.)

Every failure reaches an `AuditSinkFailureListener` whichever policy applies, because otherwise a
`LOG_AND_CONTINUE` sink that has been failing since a schema change is a warning line nobody reads and
the first person to notice is an auditor asking why a month is missing. The starter binds it to a
Micrometer counter.

## Redaction

Two concerns, kept apart, because the three implementations this replaces looked like three redactors
and were actually three *classifiers* feeding one masking action:

| Classifier | Rule | Came from |
|---|---|---|
| `KeyNameSensitivityClassifier` | Key name matches a secret-naming pattern | hot-reload |
| `ProvenanceSensitivityClassifier` | Value came from Vault at all, whatever it is called | hot-reload |
| `ConfiguredNamesSensitivityClassifier` | A deployment named it, matched case-insensitively | rest-client |
| `DeclaredSensitivityClassifier` | The definition says it is personal data | user-settings |

They **compose as a union** - `SensitivityClassifier.anyOf` - never as a priority order. A value
sensitive by provenance but innocuous by name (a Vault key called `timeout`) is sensitive, and so is
one innocuous by provenance but named `client_secret`. Any rule that let one classifier clear what
another flagged is a rule that leaks; the cost of the union being wrong is a masked timeout in a log
line. All four are still needed: a heuristic finds the secret nobody configured, a configured list is
the only thing that catches a credential in a field called `x-partner-key`, and a declaration is the
only one that knows a field called `mobile` is personal data.

`Redactor` is the masking half, and covers every shape this platform writes down: scalars, attribute
maps walked recursively, header multimaps, **JSON bodies walked structurally**, and form-encoded
bodies replaced wholesale.

The structural JSON walk is `HeaderRedactor`'s and survived intact, along with its reasoning, because
a lowest-common-denominator merge that reduced everything to scalar masking would have been a
regression rather than a consolidation:

> Body redaction is structural, not textual. A regular expression over the raw JSON would also match
> the string `"password"` appearing as a *value*, and would miss a field nested three objects deep.
> Parsing and walking the tree is slower and correct, and it only ever runs when a caller has
> explicitly opted into body logging - which is the one place where being slow and correct is
> obviously the right trade.

### One mask

`Redaction.MASK` is `***REDACTED***`, and it is a `static final` with deliberately **no property that
moves it**. Both halves of that come from `SettingsRedaction`, whose argument was the best statement
of a security decision in this repository and none of which stopped being true:

> A fixed marker rather than a hash or a truncation. A hash is reversible for any value drawn from a
> small set - which most settings are, and a phone number certainly is - and a truncation leaks
> exactly the part of an identifier that identifies. Neither is worth the debuggability it buys,
> because the audit row already records which setting changed, when, and by whom, and that is what the
> trail is read for.
>
> The marker is deliberately not configurable. A deployment that could change it could set it to the
> empty string, at which point a redacted value and a value that was never set become the same row.

Picking one was a **data migration**, not a constant change: `[redacted]` was not only logged, it was
persisted into `user_setting_audit.old_value` and `new_value`. The `audit-004` changeset rewrites those
rows; without it the table would spell one concept two ways and no query could tell "redacted under the
old rule" from "a user whose setting value is literally the string `[redacted]`". `****` and
`***REDACTED***` were only ever logged and need nothing.

A second mask constant anywhere else in the repository fails the build - as a **Checkstyle** rule
(`SecondRedactionMask`), not an ArchUnit one, because a mask is the *value* of a string constant and
ArchUnit reads bytecode, where `JavaField` does not expose it. The companion rule about audit SPIs is
in ArchUnit, where a type's name and shape genuinely are structure. Same division of labour as
everywhere else here: `architecture-rules` owns structure, `checkstyle-rules` owns source text.

## One answer to "who is acting"

There were two. `db-core`'s `SpringSecurityAuditorProvider` stamped `created_by` / `last_modified_by`
onto entity rows; `user-settings`' audit recorder called `SecurityPrincipals.currentSubject()`. When
two paths to one answer disagree, a row's `last_modified_by` and the audit event describing that
modification name the same person differently, and the join an auditor needs does not exist.

`ActorResolver` is now the single resolution, and `db-core`'s field stamping is fed from it by
`ActorResolverAuditorProvider` in the starter. **The stamping itself is unchanged.** `AuditedEntity`
and `AuditorProvider` answer "who last touched this row", which is not an event trail, and folding
them in would have changed the mapping of every entity in the platform for no gain.

Three resolvers, most specific first:

| Resolver | Where | When it wins |
|---|---|---|
| `PrincipalActorResolver` | `security-spring-boot-starter` | `LudwigPrincipal` is on the classpath - it is the only resolver that can read a subject out of one |
| `SpringSecurityActorResolver` | here | Spring Security is present and `LudwigPrincipal` is not |
| `ActorResolver.unattributed()` | here | Neither is - a batch worker, a test; `Actor.system()` is then what events carry |

The three are made mutually exclusive **by classpath**, with `@ConditionalOnMissingClass` naming the
classes as strings, rather than ordered by autoconfiguration precedence. That is the outcome of two failed
attempts, and both failures are worth knowing about because neither announced itself:

- `@ConditionalOnClass` on a `@Bean` **method** was evaluated as met on a classpath with no Spring
  Security, and the context then failed to start with a `NoClassDefFoundError`. The condition has to be at
  *class* level, on a nested `@Configuration`, so the class that mentions the resolver is never loaded.
- `@AutoConfiguration(afterName = ...)` naming the security starter did **not** make its resolver win over
  a bean declared by that nested class. The plainer resolver won, `Authentication.getName()` on a
  `LudwigPrincipal` token answered with the object's `toString()`, and an administrator's own id silently
  became `system` in every audit event - a wrong value in a trail retained for years rather than an error.

Mutual exclusion on the classpath cannot be got wrong by an ordering change, which is why it is what ships.
A deployment that wants something else publishes its own `ActorResolver`; all three yield to it.

## Configuration

Every property is under `ludwig.audit` and bound by one `AuditProperties` class, which lives here even
though its `jpa`, `outbox`, `retention` and `liquibase` blocks describe features only the starter
implements. Two `@ConfigurationProperties` classes on one prefix is how a property ends up bound in
one of them and silently ignored in the other; describing a feature costs this module no dependency.

```yaml
ludwig:
  audit:
    enabled: true
    source-system: ${spring.application.name}      # recorded as the writer of each row
    slf4j:
      enabled: true
    failure:
      default-policy: LOG_AND_CONTINUE
      by-category:
        settings: FAIL_OPERATION
    redaction:
      key-name-heuristic: true
      key-name-pattern:                            # replaces the shipped one
      sensitive-provenance-prefixes: [ "vault:", "vault-lease:" ]
      sensitive-names: [ ]                         # always-sensitive keys, fields, headers
```

The redaction *pattern* is configurable where the *mask* is not. The asymmetry is deliberate: widening
what counts as sensitive is always safe.

## What a module does

```java
// 1. Keep your typed record. Add toAuditEvent().
public record ExportAuditEvent(UUID runId, RunStatus status, ...) {
    public AuditEvent toAuditEvent() {
        return AuditEvent.builder()
                .category(AuditCategories.EXPORT)
                .action("run." + status.name().toLowerCase(Locale.ROOT))
                .actor(principalId == null ? Actor.system() : Actor.of(principalId))
                .resource(new Resource(RESOURCE_TYPE, runId.toString(), definitionKey))
                .outcome(degradedStages.isEmpty() ? AuditOutcome.success()
                        : AuditOutcome.partial("degraded stages: " + ...))
                .correlationId(correlationId)
                .attributes(attributes)
                .build();
    }
}

// 2. Inject AuditSink. Do not declare an SPI, do not catch, do not wrap.
audit.record(new ExportAuditEvent(...).toAuditEvent());
```

## Not in scope, as a decision

**Hash-chaining or signing for tamper evidence.** It is a real feature and it is deliberately not
half-built here. It needs key management and a verification tool to mean anything, and half of it - a
chain nobody verifies - is worse than none, because it looks like a guarantee. What the starter does
claim is enforced append-only: no code path updates a row and none deletes one outside the retention
purge, with `db-core`'s `SnapshotImmutabilityListener` rejecting the attempt rather than a convention
asking nicely.

**Asserting that a module audits what it should.** "Every state mutation emits an event" needs to know
which methods mutate state, which is a semantic judgment no structural rule can make. The ArchUnit
rule checks the thing that *is* structural: that when a module audits, it audits through this type.
