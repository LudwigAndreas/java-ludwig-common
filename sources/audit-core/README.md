# audit-core

***English** · [Русский](README.ru.md)*

The platform's single audit envelope (`AuditEvent`), the sink SPI (`AuditSink`) with a configurable
failure policy, the redaction package used everywhere a value may be sensitive, and the one answer to
"who is acting" (`ActorResolver`). It depends on nothing in this repository, so every module - including
those below `db-core` - can record an audit trail through it.

| | |
|---|---|
| Role | library (with auto-configuration) |
| Depends on (in-repo) | nothing |
| Used by | `audit`, `export`, `file-action`, `file-ingest`, `grpc-client`, `hot-reload`, `idempotency`, `messaging`, `observability`, `odata-filter`, `outbox`, `pat`, `reconciliation`, `rest-client`, `security` starters |
| Surfaces | none (writes to logs; persistent sinks are in [`audit-spring-boot-starter`](../audit-spring-boot-starter)) |

**Split.** Modules that need a trail sit at every level of the stack, several of them below `db-core`
(security, rest-client, hot-reload). Anything needing a database, a scheduler or the outbox therefore
lives in [`audit-spring-boot-starter`](../audit-spring-boot-starter); this module keeps zero in-repo
dependencies. Redaction lives here for the same reason: a module importing `audit-core` only for
`Redaction.MASK` (as `observability-spring-boot-starter` does) is the intended arrangement.

## Usage

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>audit-core</artifactId>
</dependency>
```

Adding the dependency gives a working trail: `AuditCoreAutoConfiguration` wires the SLF4J sink, the
composite sink and the failure policy, with no database and no scheduler.

### Recording an event

Keep a typed record at the call site and convert it:

```java
public record ExportAuditEvent(UUID runId, OperationStatus status, ...) {
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

// Inject AuditSink. Do not declare your own SPI, do not catch, do not wrap.
audit.record(new ExportAuditEvent(...).toAuditEvent());
```

### The envelope

```java
public record AuditEvent(
        UUID id,                        // assigned at construction; the idempotency key for shipping
        Instant occurredAt,
        String category,                // see AuditCategories
        String action,                  // "run.completed", "access.denied", "setting.changed"
        Actor actor,                    // subject, principalType, displayName, onBehalfOf
        Resource resource,              // type, id, name
        AuditOutcome outcome,           // SUCCESS | FAILURE | DENIED | PARTIAL, + reason
        String correlationId,
        String traceId,
        Map<String, Object> attributes) // module-specific, already redacted
```

Rules for every event:

1. **`attributes` is redacted when the event is built, never in the sink.** A sink that can receive an
   unredacted event will eventually persist one.
2. **No payloads.** The trail is retained for years and read by people not entitled to the data it
   guards: record *which* object was touched, never its content.
3. **`DENIED` and `FAILURE` are different.** Denied = the platform refused; failure = it tried and could
   not. They lead to opposite investigations.

**Categories** (`AuditCategories`):

| Category | Recorded by |
|---|---|
| `access` | security - authorization decisions |
| `audit` | audit starter - retention purge of the trail itself |
| `config` | hot-reload - configuration and secret reloads; notification-service - announcements |
| `credential` | pat - personal access token lifecycle |
| `export` | export - report runs |
| `file-action` | file-action - file operations (string constant in `FileActionAuditEvent`, not in `AuditCategories`) |
| `idempotency` | idempotency - claims and replays |
| `ingest` | file-ingest - file runs |
| `messaging` | messaging - dead letters and drops |
| `outbound-call` | rest-client, grpc-client - outbound calls |
| `outbox` | outbox - message transitions |
| `query` | odata-filter - query execution |
| `reconciliation` | reconciliation - sync runs and records |
| `settings` | user-settings - settings and consent changes |

Each module README lists its own actions in its **Audit** section.

### Sinks

```java
public interface AuditSink {
    void record(AuditEvent event);
}
```

Write-only by design: a trail that the audited code can read is a trail it can eventually filter. Reading
is the query API in the starter, a different type with different callers.

| Sink | Module | What it does |
|---|---|---|
| `Slf4jAuditSink` | here | One structured line per event on logger `ru.ludwigandreas.audit` |
| `NoopAuditSink` | here | Discards (tests, or a deployment routing elsewhere) |
| `CompositeAuditSink` | here | Fans out to several sinks; the normal configuration |
| `JpaAuditSink` | starter | Append-only `audit_event` table |
| `OutboxAuditSink` | starter | Exactly-once shipping to a SIEM through the outbox |

### Failure policy

A sink may throw. Whether the caller survives is decided by `AuditFailurePolicy`, applied by
`FailurePolicyAuditSink` in front of every real sink:

| Category | Default | Why |
|---|---|---|
| `settings` | `FAIL_OPERATION` | A settings or consent change must not commit without its audit row. The write is in the caller's transaction, so failing rolls the change back |
| everything else | `LOG_AND_CONTINUE` | An audit outage must not become a service outage |

A module must not put its own `try`/`catch` around `record` - that overrides the deployment's policy.
(`AuthorizationDeniedAuditListener` is the single exception: it protects the authorization decision from
the listener itself.) Every failure, under either policy, reaches an `AuditSinkFailureListener`.

### Redaction

Four **classifiers** decide what is sensitive; they compose as a **union** (`SensitivityClassifier.anyOf`),
so no classifier can clear what another flagged:

| Classifier | Rule |
|---|---|
| `KeyNameSensitivityClassifier` | Key name matches a secret-naming pattern (`password`, `token`, `secret`, ...) |
| `ProvenanceSensitivityClassifier` | Value came from Vault, whatever its name |
| `ConfiguredNamesSensitivityClassifier` | The deployment listed the name (case-insensitive) |
| `DeclaredSensitivityClassifier` | The definition declares it personal data |

One **`Redactor`** masks scalars, attribute maps (recursively), header multimaps, JSON bodies (walked
structurally: a regex would match `"password"` as a value and miss nested fields) and form-encoded
bodies (replaced whole).

**One mask:** `Redaction.MASK` = `***REDACTED***`, a constant with no property to change it. A hash is
reversible for small value sets and a truncation leaks the identifying part; a configurable mask could be
set to the empty string, making "redacted" and "never set" the same value. Checkstyle rule
`SecondRedactionMask` fails the build on a second mask constant anywhere.

### Who is acting

`ActorResolver` is the single resolution of the current actor. `db-core`'s `created_by` /
`last_modified_by` stamping is fed from the same resolver (by `ActorResolverAuditorProvider` in the
starter), so a row and the audit event about it name the same person.

| Resolver | Module | Active when |
|---|---|---|
| `PrincipalActorResolver` | security starter | `LudwigPrincipal` is on the classpath |
| `SpringSecurityActorResolver` | here | Spring Security present, `LudwigPrincipal` absent |
| `ActorResolver.unattributed()` | here | Neither; events carry `Actor.system()` |

The three are mutually exclusive **by classpath** (`@ConditionalOnMissingClass` on nested configuration
classes), not by auto-configuration order: an ordering mistake would silently record the wrong actor for
years. A service-defined `ActorResolver` bean replaces all three.

### Correlation

`CorrelationProvider.none()` is the default (this module cannot depend on observability). A service with
observability or web-core publishes:

```java
@Bean
CorrelationProvider auditCorrelationProvider(CorrelationContext context) {
    return context::currentCorrelationId;
}
```

The correlation id is what joins an audit event to the logs, traces and downstream calls of the request.

## Configuration

All `ludwig.audit.*` properties are bound by `AuditProperties` here, including blocks implemented by the
starter (one class per prefix, so no property is bound in one place and ignored in another).

| Property | Default | Meaning |
|---|---|---|
| `ludwig.audit.enabled` | `true` | Master switch |
| `ludwig.audit.source-system` | `spring.application.name` | Writer recorded on each persisted row |
| `ludwig.audit.slf4j.enabled` | `true` | SLF4J sink |
| `ludwig.audit.failure.default-policy` | `LOG_AND_CONTINUE` | Policy for categories not listed below |
| `ludwig.audit.failure.by-category.<category>` | `settings: FAIL_OPERATION` | Per-category policy |
| `ludwig.audit.redaction.key-name-heuristic` | `true` | Enable the key-name classifier |
| `ludwig.audit.redaction.key-name-pattern` | shipped pattern | Replace the key-name regex |
| `ludwig.audit.redaction.sensitive-provenance-prefixes` | `vault:`, `vault-lease:` | Provenances treated as sensitive |
| `ludwig.audit.redaction.sensitive-names` | empty | Always-sensitive keys, fields and headers |
| `ludwig.audit.jpa.*`, `.outbox.*`, `.retention.*`, `.liquibase.*` | | See [`audit-spring-boot-starter`](../audit-spring-boot-starter#configuration) |

The redaction *pattern* is configurable, the *mask* is not: widening what counts as sensitive is always
safe.

## Deployment

Nothing to deploy. Route the `ru.ludwigandreas.audit` logger to wherever the deployment keeps audit logs
(it is a normal SLF4J logger). For a database trail or SIEM shipping, add
[`audit-spring-boot-starter`](../audit-spring-boot-starter).

## Observability

**Logs.**

| Logger / level | Content |
|---|---|
| `ru.ludwigandreas.audit` / `INFO` | One line per event: `audit id=… at=… category=… action=… outcome=… actor=… actorType=… onBehalfOf=… resourceType=… resourceId=… correlationId=… traceId=… reason=… attributes=…` |
| `WARN` | `Audit sink <sink> failed for <category>/<action> (event <id>); the operation was not affected` (under `LOG_AND_CONTINUE`) |

**Metrics.** None here. With the starter, sink failures are counted as `ludwig.audit.sink.failures`.

**Known gaps:** a service using only `audit-core` (no starter) has no metric for sink failures - only the
`WARN` line. Register an `AuditSinkFailureListener` bound to a counter, or add the starter.

## Audit

This module *is* the audit mechanism; it records nothing on its own behalf.

## Runbook

| Symptom | Likely cause | What to do |
|---|---|---|
| `Audit sink ... failed` warnings | Downstream sink (database, outbox) failing | Look at the exception in the same line; fix the sink. Events in that window are missing from the failed sink but present in other sinks of the composite (e.g. the log) |
| Settings changes fail with a 503 | `settings` category is `FAIL_OPERATION` and the sink fails | Intended. Restore the sink (usually the `audit_event` table) |
| Actor is `system` for user actions | No resolver could read the principal | Check that the security starter (or Spring Security) is on the classpath and the request is authenticated |
| A secret appears in an audit line | Name not caught by the classifiers | Add it to `ludwig.audit.redaction.sensitive-names` immediately; rotate the secret |

## Testing

Unit tests cover the envelope builder, failure policies, each classifier, the union, the redactor on every
shape, and the classpath-exclusive actor resolvers (with and without Spring Security / `LudwigPrincipal`).
A module that audits should assert in its own tests that the expected `AuditEvent` reaches an
`AuditSink` test double.

## Design decisions

- **One envelope, one SPI, one mask, one actor resolution** for the whole platform, enforced: ArchUnit
  checks that a module audits through `AuditSink`; Checkstyle forbids a second mask.
- **Typed records at call sites, `AuditEvent` for transport.** Thirteen named components read better than
  thirteen map entries.
- **No tamper-evidence (hash chains, signatures).** It requires key management and a verifier to mean
  anything; a half-built chain looks like a guarantee and is not. The starter enforces append-only instead.
- **No automated "every mutation is audited" check.** Which methods mutate state is a semantic judgement no
  structural rule can make.
