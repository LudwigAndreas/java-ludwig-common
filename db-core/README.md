# db-core

Enterprise-ready base entity classes, auditing, soft delete, exceptions and QueryDSL/Spring Data JPA
utilities for Java 17 + PostgreSQL + Spring Boot services. Add the dependency, extend a base entity,
done - no boilerplate id/version/audit columns, no manual `AuditorAware` wiring.

## Quick start

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>db-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

Extend `AuditedEntity` for a typical application-owned entity - generated UUID id, optimistic
locking, and full created/updated-by-and-at auditing, all wired automatically:

```java
@Entity
@Table(name = "orders")
public class Order extends AuditedEntity<UUID> {

    private String description;
    // ...
}
```

## Entity hierarchy

Two families, both rooted at `AbstractEntity<ID>` (id contract + `equals`/`hashCode`/`toString`):

```
AbstractEntity<ID>
│
├── JpaBaseEntity<ID>            plain, app/externally-assigned id ("inner", manual-id branch)
│     ├── VersionedEntity<ID>          + @Version
│     ├── DateAuditableEntity<ID>      + createdAt/updatedAt
│     │     └── UserAuditableEntity<ID>    + createdBy/updatedBy
│     ├── SoftDeleteEntity<ID>         + deleted/deletedAt
│     └── ExternalEntity<ID>           "outer" family root: id/lifecycle owned by an external
│           └── SnapshotEntity<ID>         system (Kafka/REST), ID never defaulted to UUID
│
└── GeneratedEntity<ID>          generated UUID id (JPA 3.1 GenerationType.UUID)
      └── AuditedEntity<ID>           + full audit + @Version - the class most entities extend
```

`GeneratedEntity`/`AuditedEntity` declare their own `id` field rather than extending
`JpaBaseEntity`: JPA has no portable way to add `@GeneratedValue` on top of an `@Id` field already
declared (without generation) further up a `@MappedSuperclass` chain, so the generated-id branch
duplicates that handful of lines instead of risking undefined same-attribute-name behavior.

**Inner vs outer:** entities created by *this* application (orders, users, ...) extend
`GeneratedEntity`/`AuditedEntity`/`VersionedEntity`/etc. Entities that mirror data owned by an
*external* system (a Kafka event, a REST payload from another service) extend `ExternalEntity` or
`SnapshotEntity` - their id type is whatever the source system uses (`Long`, `String`, `UUID`, ...),
never forced to UUID.

### `ExternalEntity` provenance fields

`ExternalEntity` carries `importedAt`/`sourceSystem`/`sourceVersion`/`sourceTimestamp`, shared by every
outer entity - `importedAt` is filled in automatically on first persist and (like `sourceSystem`) never
changes afterward; `sourceVersion`/`sourceTimestamp` stay mutable so a plain `ExternalEntity` subclass
can be upserted in place each time a new version is fetched, without needing `SnapshotEntity`'s
immutability:

```java
@Entity
@Table(name = "customers")
public class Customer extends ExternalEntity<String> {  // id = the source system's id
    private String name;
}

// on each fetch: load-or-create, update fields, save - an ordinary UPDATE
Customer customer = customerRepository.findById(externalId).orElseGet(Customer::new);
customer.setSourceVersion(newData.version());
customer.setName(newData.name());
customerRepository.save(customer);
```

### `SnapshotEntity`

An immutable point-in-time copy of externally-sourced data - adds nothing over `ExternalEntity` but the
immutability guarantee:

```java
@Entity
@Table(name = "customer_snapshots")
public class CustomerSnapshot extends SnapshotEntity<String> {  // id = the source system's id
    private String name;
}
```

`SnapshotImmutabilityListener` (attached automatically) throws `IntegrityViolationException` on any
`@PreUpdate` - snapshot rows are append-only.

Note that since `ID` here is still the source system's id (per `ExternalEntity`), this shape supports
exactly one row per external entity, immutable once written - suitable for "import once, never touched
again" data. If you need to keep every fetched *version* of the same external entity as its own row
(true version history), give the snapshot a generated surrogate id instead and keep the source id as a
plain, non-unique column, so multiple rows can share it.

### Soft delete

`SoftDeleteEntity` only declares the `deleted`/`deletedAt` columns; because Hibernate's
`@SQLDelete`/`@SQLRestriction` need the literal table name, each concrete entity adds them itself:

```java
@Entity
@Table(name = "widgets")
@SQLDelete(sql = "UPDATE widgets SET deleted = true, deleted_at = now() WHERE id = ?")
@SQLRestriction("deleted = false")
public class Widget extends SoftDeleteEntity<UUID> { ... }
```

With those in place, `repository.delete(entity)` becomes an `UPDATE`, and deleted rows are
transparently excluded from normal finder/query methods (native queries bypass the restriction).

## Repositories

```java
public interface OrderRepository extends BaseRepository<Order, UUID> {
}
```

`BaseRepository` = `JpaRepository` + `QuerydslPredicateExecutor` + `getByIdOrThrow(id)` (throws
`EntityNotFoundException`). To get a real implementation of `getByIdOrThrow`, point Spring Data at
`BaseRepositoryImpl` as the repository base class:

```java
@EnableJpaRepositories(repositoryBaseClass = BaseRepositoryImpl.class)
```

## Auditing

Auto-configured out of the box: if Spring Security is on the classpath, the current auditor is the
authenticated principal's name; otherwise it falls back to `"system"`. Override by providing your
own `AuditorProvider<String>` bean, or implement `AuditorProvider<T>` directly for a non-`String`
auditor type.

## Query helpers

```java
BooleanExpression predicate = Predicates.allOf(
        Predicates.whenNotNull(filter.status(), QOrder.order.status::eq),
        Predicates.whenNotNull(filter.customerId(), QOrder.order.customerId::eq));

Pageable pageable = PageableUtils.of(page, size, properties.getMaxPageSize(), sort, defaultSort);
```

`Predicates.allOf` filters out `null` expressions and always returns a safe, non-null predicate;
`PageableUtils.of` clamps the page size and applies a default sort when none was requested.

## Metrics

Optional (only if Micrometer is on the classpath): `ludwig.db.auditor.resolved` counts every
`AuditorAware` lookup, tagged `present=true/false` - a sustained run of `false` usually means Spring
Security isn't wired the way this module expects. `ludwig.db.snapshot.mutation.blocked` counts rejected
writes to an immutable `SnapshotEntity`, tagged by entity type - worth alerting on, since it means
something in your system thinks it owns data that's actually owned by an external source.

## Configuration (`ludwig.db.*`)

| Property | Default | Meaning |
|---|---|---|
| `ludwig.db.auditing-enabled` | `true` | Enables `@EnableJpaAuditing` wiring |
| `ludwig.db.default-page-size` | `20` | Convention default for `PageableUtils` callers |
| `ludwig.db.max-page-size` | `200` | Convention upper bound for `PageableUtils` callers |
| `ludwig.db.metrics.enabled` | `true` | Emit Micrometer metrics (only if Micrometer is on the classpath) |

## Testing

`mvn test` runs the unit test suite (entity equality, predicate/pageable helpers) with no external
dependencies. The integration tests additionally spin up a real PostgreSQL container via
Testcontainers to exercise id generation, auditing, optimistic locking, soft delete and snapshot
immutability end to end; they need a working Docker daemon.
