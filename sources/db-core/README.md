# db-core

***English** · [Русский](README.ru.md)*

Base entity classes, row-level auditing (`created_by` / `last_modified_by`), soft delete, immutable
snapshots, a base repository, QueryDSL helpers and mapped persistence exceptions for Spring Boot services
on PostgreSQL. Extend a base entity and get id, version and audit columns without boilerplate.

| | |
|---|---|
| Role | library (with auto-configuration) |
| Depends on (in-repo) | optional: [`web-core-spring-boot-starter`](../web-core-spring-boot-starter) |
| Used by | `audit`, `export`, `file-action`, `file-ingest`, `idempotency`, `identity-projection`, `outbox`, `pat`, `reconciliation`, `user-settings` starters; both services |
| Surfaces | none (contributes problem mappings to web-core) |

## Usage

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>db-core</artifactId>
</dependency>
```

A typical application-owned entity extends `AuditedEntity` - generated UUID id, optimistic locking and
created/updated by/at, all wired automatically:

```java
@Entity
@Table(name = "orders")
public class Order extends AuditedEntity<UUID> {
    private String description;
}
```

### Entity hierarchy

```
AbstractEntity<ID>                       id contract + equals/hashCode/toString
│
├── JpaBaseEntity<ID>                    application- or externally-assigned id
│     ├── VersionedEntity<ID>            + @Version
│     ├── DateAuditableEntity<ID>        + createdAt/updatedAt
│     │     └── UserAuditableEntity<ID>  + createdBy/updatedBy
│     ├── SoftDeleteEntity<ID>           + deleted/deletedAt
│     └── ExternalEntity<ID>             data owned by another system; id type is the source's
│           └── SnapshotEntity<ID>       immutable point-in-time copy
│
└── GeneratedEntity<ID>                  generated UUID id (GenerationType.UUID)
      └── AuditedEntity<ID>              + full audit + @Version - what most entities extend
```

- **Inner** entities (created by this application) extend `GeneratedEntity` / `AuditedEntity` /
  `VersionedEntity` / ...
- **Outer** entities (mirroring a Kafka event or another service's REST payload) extend `ExternalEntity`
  or `SnapshotEntity`; their id type is whatever the source uses (`Long`, `String`, `UUID`).

`GeneratedEntity` declares its own `id` instead of extending `JpaBaseEntity`, because JPA has no portable
way to add `@GeneratedValue` to an `@Id` already declared higher in a `@MappedSuperclass` chain.

### `ExternalEntity`

Carries `importedAt` (set on first persist, never changed), `sourceSystem` (never changed),
`sourceVersion` and `sourceTimestamp` (mutable, so the row can be upserted on each fetch):

```java
@Entity
@Table(name = "customers")
public class Customer extends ExternalEntity<String> {  // id = the source system's id
    private String name;
}

Customer customer = customerRepository.findById(externalId).orElseGet(Customer::new);
customer.setSourceVersion(newData.version());
customer.setName(newData.name());
customerRepository.save(customer);
```

### `SnapshotEntity`

An immutable copy: `SnapshotImmutabilityListener` (attached automatically) throws
`IntegrityViolationException` on any `@PreUpdate`. With the source id as primary key this means one row
per external entity, written once. For full version history, give the snapshot a generated surrogate id
and keep the source id as a non-unique column.

### Soft delete

`SoftDeleteEntity` declares `deleted` / `deletedAt`. Hibernate's `@SQLDelete` / `@SQLRestriction` need the
literal table name, so each entity adds them:

```java
@Entity
@Table(name = "widgets")
@SQLDelete(sql = "UPDATE widgets SET deleted = true, deleted_at = now() WHERE id = ?")
@SQLRestriction("deleted = false")
public class Widget extends SoftDeleteEntity<UUID> { ... }
```

`repository.delete(entity)` becomes an `UPDATE`, and deleted rows are excluded from derived and JPQL
queries. **Native queries bypass the restriction.**

### Repositories

```java
public interface OrderRepository extends BaseRepository<Order, UUID> {
}
```

`BaseRepository` = `JpaRepository` + `QuerydslPredicateExecutor` + `getByIdOrThrow(id)` (throws
`EntityNotFoundException`). Enable the implementation:

```java
@EnableJpaRepositories(repositoryBaseClass = BaseRepositoryImpl.class)
```

### Row auditing

`created_by` / `last_modified_by` are stamped automatically. The auditor comes from:

1. a service-defined `AuditorProvider` bean, if any;
2. with [`audit-spring-boot-starter`](../audit-spring-boot-starter): `ActorResolverAuditorProvider`, backed
   by the platform's single `ActorResolver`, so the row and its audit event name the same person;
3. otherwise the Spring Security principal's name, or `"system"` without Spring Security.

This is "who last touched this row". "What happened" is the audit trail in `audit_event`
([`audit-core`](../audit-core)); the two answer different questions and stay separate.

### Query helpers

```java
BooleanExpression predicate = Predicates.allOf(
        Predicates.whenNotNull(filter.status(), QOrder.order.status::eq),
        Predicates.whenNotNull(filter.customerId(), QOrder.order.customerId::eq));

Pageable pageable = PageableUtils.of(page, size, properties.getMaxPageSize(), sort, defaultSort);
```

`Predicates.allOf` drops `null` expressions and always returns a non-null predicate; `PageableUtils.of`
clamps the page size and applies a default sort.

### Error responses

With [`web-core-spring-boot-starter`](../web-core-spring-boot-starter) on the classpath, `DbCoreProblemMapper`
and the `i18n/ludwig-db-messages` bundle turn persistence exceptions into localized RFC 9457 problems:

| Exception | Status | Code |
|---|---|---|
| `EntityNotFoundException` | 404 | `ludwig.db.error.entity-not-found` |
| `DuplicateEntityException` | 409 | `ludwig.db.error.duplicate-entity` |
| `IntegrityViolationException` | 409 | `ludwig.db.error.integrity-violation` |
| `UnsupportedIdTypeException` | 500 | `ludwig.db.error.unsupported-id-type` |

So a missing row is a 404 even on code paths where the service did not convert it. The text is generic
(this module knows the entity class, not its API name); a service throwing its own `LocalizedException`
takes precedence.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `ludwig.db.auditing-enabled` | `true` | Enable JPA auditing wiring |
| `ludwig.db.default-page-size` | `20` | Default page size for `PageableUtils` callers |
| `ludwig.db.max-page-size` | `200` | Upper bound for `PageableUtils` callers |
| `ludwig.db.metrics.enabled` | `true` | Emit Micrometer metrics (when Micrometer is present) |

## Deployment

Nothing to deploy: the module ships no schema. Each entity's table is created by the owning service's
Liquibase changelog, including the base-class columns (`id`, `version`, `created_at`, `created_by`,
`updated_at`, `updated_by`, `deleted`, `deleted_at`, `imported_at`, `source_system`, `source_version`,
`source_timestamp` as applicable). Services run with `spring.jpa.hibernate.ddl-auto=validate`.

## Observability

**Metrics** (when Micrometer is present):

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `ludwig.db.auditor.resolved` | counter | `present` | Auditor lookups. Sustained `present=false` for user requests means the principal is not reaching the auditor (security wiring) |
| `ludwig.db.snapshot.mutation.blocked` | counter | `entityType` | Rejected updates to a `SnapshotEntity`: something treats externally-owned data as its own. **Alert on any** |

**Logs.** None of its own. Hibernate SQL logging is controlled by the service.

**Known gaps:** none at module level; connection pool and query metrics come from Spring Boot (Hikari,
`spring.data.repository` observations) and are the service's responsibility.

## Audit

Not applicable: the module stamps `created_by` / `last_modified_by` on rows but records no audit events.

## Runbook

| Symptom | Likely cause | What to do |
|---|---|---|
| `created_by` is `system` for user requests; `ludwig_db_auditor_resolved_total{present="false"}` grows | No principal at stamp time (async thread, missing security context) | Check the code path runs in the request thread or propagates the security context |
| `IntegrityViolationException` on update of a snapshot; `ludwig_db_snapshot_mutation_blocked_total` grows | Code modifies an immutable `SnapshotEntity` | Fix the code: insert a new snapshot or use `ExternalEntity` for upserts |
| Soft-deleted rows appear in results | A native query | Add `deleted = false` to the native query |
| `OptimisticLockException` / 409 under concurrent edits | Two writers updated the same version | Expected; the client retries with the fresh version |
| `getByIdOrThrow` not implemented at runtime | `repositoryBaseClass` not set | Add `@EnableJpaRepositories(repositoryBaseClass = BaseRepositoryImpl.class)` |

## Testing

- `mvn test`: entity equality, predicate and pageable helpers.
- `mvn verify` (Docker, PostgreSQL): id generation, auditing, optimistic locking, soft delete and snapshot
  immutability end to end.

## Design decisions

- **Two entity families.** The id strategy and mutability rules differ between data this application owns
  and data it mirrors; mixing them is how an external id gets overwritten by a generated one.
- **Snapshot immutability is enforced by a listener**, and reused by the audit trail, so the platform has
  one append-only mechanism.
- **Generic error texts.** Domain wording belongs to the service.
