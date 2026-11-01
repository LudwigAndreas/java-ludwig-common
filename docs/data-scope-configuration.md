# Configuring data scopes and role mappings

**English** · **[Русский](data-scope-configuration.ru.md)**

How to scope the rows a service's operations touch, and grant those operations to roles in configuration, so that
a role's access is changed by editing configuration rather than code.

Everything here is existing behaviour of
[`security-spring-boot-starter`](../sources/security-spring-boot-starter/README.md) (packages
`ru.ludwigandreas.security.operation` and `ru.ludwigandreas.security.data`). The module README is the reference;
this document is the worked guide. The `order` service used in the examples is illustrative - it is not a module in
this repository. The real reference implementation is `product` in `services/crud-service-example`.

## The model in one page

| Concept | What it is | Where it is defined |
|---|---|---|
| Operation | What a caller does: `<area>.<object>.<verb>` (`sales.order.update`), with a sensitivity and, when it touches rows, a resource and data action | Code: an `OperationContributor` bean |
| Resource | The rows being protected (`order`) | Code: the name given to `DataScopeMapping.forResource(...)` |
| Dimension | An axis a row can be restricted along (`owner`, `tenant`, `partner`, `object`, or your own) | Code: `ScopeDimension`, bound to a column in the mapping |
| Scope | What a role gets for one operation: `ALL`, or `+`-joined dimensions | Configuration |
| Role | A set of operations with their scopes, optionally including other roles | Configuration: `ludwig.security.roles` |

The split is deliberate. Which operations exist, and which column carries which dimension, is code, because it
names real handlers and columns and must break the build when one is renamed. Which role holds which operation with
which scope is configuration, because that is the part operations needs to read and change without a release. Code
never names a role.

Modules involved:

| Module | Role |
|---|---|
| `security-spring-boot-starter` | Owns the model: the operation catalog, the role table, dimensions, mappings, `DataAccessGuard`. |
| `identity-projection-spring-boot-starter` | Supplies the caller's roles and attributes (`AuthorityResolver`) and per-user grant rows (`DatabaseDataScopeProvider`). |
| `test-support-security` | `TestPrincipalBuilder`, for building principals with roles and attributes in tests. |

## Step 1: bind dimensions to columns

```java
@Bean
DataScopeMapping<OrderEntity> orderScopeMapping() {
    QOrderEntity order = QOrderEntity.orderEntity;
    return DataScopeMapping.forResource("order", OrderEntity.class)
            .owner(order.createdBy, OrderEntity::getCreatedBy)
            .tenant(order.tenantId, OrderEntity::getTenantId, UUID::fromString)
            .partner(order.partnerId, OrderEntity::getPartnerId)
            .build();
}
```

Each binding declares a query half (the QueryDSL path) and a load half (the getter) together, so the
two cannot drift apart.

| Shape of the rule | Binding |
|---|---|
| One column holds one value | `.owner(...)`, `.tenant(...)`, `.partner(...)`, `.bind(dimension, ...)` |
| The row relates to many values, any of which may match | `.bindCollection(dimension, path.any(), getter)` |
| Anything else | `.bindExpression(...)` - nothing verifies the two halves agree, so test them against each other |

## Step 2: declare the operations

```java
public final class OrderOperations implements OperationContributor {

    public static final String READ = "sales.order.read";
    public static final String UPDATE = "sales.order.update";

    @Override
    public Collection<Operation> operations() {
        return List.of(
                Operation.business(READ, Sensitivity.READ, "sales.operation." + READ).on("order", DataAction.READ),
                Operation.business(UPDATE, Sensitivity.CHANGE, "sales.operation." + UPDATE)
                        .on("order", DataAction.WRITE));
    }
}
```

Register it as a bean, and give each description key a value in the service's message bundle (EN and RU): it is
what `GET /me/operations` shows a person, and startup fails on a key that resolves nowhere. One read operation covers
listing and fetching one kind of object; each distinct change is its own operation. A `READ` operation may not
declare a write, and a `CHANGE` or `PRIVILEGED` one may not declare the read action.

## Step 3: grant operations to roles

```yaml
ludwig:
  security:
    roles:
      ORDER_AGENT:
        operations:
          sales.order.read: OWN              # rows they created
          sales.order.update: OWN
      TENANT_VIEWER:
        operations:
          sales.order.read: TENANT           # rows of their tenant
      PARTNER:
        operations:
          sales.order.read: PARTNER+TENANT   # their partner AND their tenant
      ORDER_ADMIN:
        includes: [ORDER_AGENT]
        operations:
          sales.order.read: ALL
          sales.order.update: ALL
```

Changing a role's access is an edit to its line; deleting the line revokes it.

How scopes compose:

- Dimensions inside one scope are AND-ed (`OWN+TENANT` = "my rows, in my tenant").
- A caller holding several roles gets the union of their scopes, and `includes` unites a role with the roles it
  includes, transitively.
- `ALL` beats everything. Holding an operation means holding a scope that is not empty; an operation no role lists is
  refused to everyone.
- A caller with no value for a required dimension (for example no tenant) is denied, not widened.
- A caller entitled to nothing gets an empty page from a list endpoint, not a 403.

## Step 4: enforce in the service

```java
// Query time: the scope becomes part of the WHERE clause, so paging and totals stay correct.
queryFactory.selectFrom(order)
        .where(guard.predicate(OrderOperations.READ).and(criteria))
        .fetch();

// Load time: the safety net for loads by id and every path a scoped query never sees.
OrderEntity entity = repository.getByIdOrThrow(id);
guard.check(OrderOperations.UPDATE, entity, OrderEntity::getId);
```

Use both. Application code injects `DataAccessGuard`, never an individual provider. The guard takes the operation;
the operation supplies the resource, so code and configuration name the same thing.

## Custom dimensions

A service adds its own axis with `ScopeDimension.of(...)`. Three things are needed.

1. Bind it:

   ```java
   static final ScopeDimension REGION = ScopeDimension.of("region");

   DataScopeMapping.forResource("order", OrderEntity.class)
           .bind(REGION, order.regionCode, OrderEntity::getRegionCode)
           .build();
   ```

2. Name the dimension in a role's scope:

   ```yaml
   ludwig:
     security:
       roles:
         REGIONAL_MANAGER:
           operations:
             sales.order.read: REGION
         REGIONAL_AGENT:
           operations:
             sales.order.read: OWN+REGION
   ```

   The token is the dimension name, case-insensitive. A token naming a dimension the operation's resource does not
   bind fails startup, so a typo is caught.

3. Give the caller a value. For a custom dimension a role's scope compares the column against the **principal
   attribute of the same name** (`region`). The stock `DatabaseAuthorityResolver` sets only `tenant` and `partner`,
   so your `AuthorityResolver` must supply the rest:

   ```java
   return Authorities.builder()
           .role("REGIONAL_MANAGER")
           .attribute("region", user.getRegionCode())
           .build();
   ```

Attributes are single-valued. A caller who needs several values for one dimension is covered under
[Custom providers](#custom-providers).

## Worked example: orders with transitions

Requirements:

- A user creates and deletes orders and sees only their own.
- A moderator sees every order.
- A business customer sees the orders they were chosen as business customer for.
- An implementation team member sees the orders assigned to their team.
- Transitions between statuses are not stored. A user may cancel; a moderator may approve.
- Later, the implementation team may need full read access, ideally as one configuration change.

### Model

Each verb is an **operation on `order`** - creating, reading, deleting and each transition - so a role is granted
exactly the verbs it performs, on exactly the rows its scope admits. Each relationship to an order is a
**dimension**.

### Vocabulary

Keep every name in one place per resource: the operation ids, and the dimensions the mapping binds.

```java
public final class OrderOperations implements OperationContributor {

    public static final String RESOURCE = "order";

    public static final String CREATE = "sales.order.create";
    public static final String READ = "sales.order.read";
    public static final String DELETE = "sales.order.delete";
    public static final String CANCEL = "sales.order.cancel";
    public static final String APPROVE = "sales.order.approve";

    public static final ScopeDimension CUSTOMER = ScopeDimension.of("customer");
    public static final ScopeDimension TEAM = ScopeDimension.of("team");

    private static final String KEY = "sales.operation.";

    @Override
    public Collection<Operation> operations() {
        return List.of(
                Operation.business(CREATE, Sensitivity.CHANGE, KEY + CREATE).on(RESOURCE, DataAction.WRITE),
                Operation.business(READ, Sensitivity.READ, KEY + READ).on(RESOURCE, DataAction.READ),
                Operation.business(DELETE, Sensitivity.CHANGE, KEY + DELETE).on(RESOURCE, DataAction.DELETE),
                Operation.business(CANCEL, Sensitivity.CHANGE, KEY + CANCEL).on(RESOURCE, "cancel"),
                Operation.business(APPROVE, Sensitivity.CHANGE, KEY + APPROVE).on(RESOURCE, "approve"));
    }
}
```

The state machine maps each transition to its operation:

```java
public enum OrderTransition {
    CANCEL(OrderOperations.CANCEL),
    APPROVE(OrderOperations.APPROVE);

    private final String operation;

    OrderTransition(String operation) {
        this.operation = operation;
    }

    public String operation() {
        return operation;
    }
}
```

Naming rules:

- Operations: `<area>.<object>.<verb>`, three lower-case segments of letters and digits. Startup refuses anything
  else, and a role entry naming an uncatalogued operation.
- Dimensions: lower-case nouns naming the relationship (`customer`, `team`), not the column.
- Roles: written without the `ROLE_` prefix in `ludwig.security.roles`; the prefix is accepted and normalised.
- Resource: singular, the same word the mapping bean uses.

### Mapping

```java
@Bean
DataScopeMapping<OrderEntity> orderScopeMapping() {
    QOrderEntity order = QOrderEntity.orderEntity;
    return DataScopeMapping.forResource(OrderOperations.RESOURCE, OrderEntity.class)
            .owner(order.createdBy, OrderEntity::getCreatedBy)
            .bind(OrderOperations.CUSTOMER, order.businessCustomerId, OrderEntity::getBusinessCustomerId)
            .bind(OrderOperations.TEAM, order.implementationTeamId, OrderEntity::getImplementationTeamId)
            .build();
}
```

### Roles

```yaml
ludwig:
  security:
    roles:
      USER:
        operations:
          sales.order.create: ALL
          sales.order.read: OWN
          sales.order.delete: OWN
          sales.order.cancel: OWN
      MODERATOR:
        operations:
          sales.order.read: ALL
          sales.order.approve: ALL
      BUSINESS_CUSTOMER:
        operations:
          sales.order.read: CUSTOMER
      IMPLEMENTATION_TEAM:
        operations:
          sales.order.read: TEAM
```

The future change is one line:

```yaml
          sales.order.read: ALL   # was TEAM
```

### Enforcement

```java
// A transition: load, check the operation against this row, then let the domain decide validity.
OrderEntity entity = repository.getByIdOrThrow(id);
guard.check(transition.operation(), entity, OrderEntity::getId);
entity.apply(transition);

// Create: no row exists yet, so ask only whether the caller holds the operation.
if (guard.scope(OrderOperations.CREATE).denies()) {
    throw new AccessDeniedException(OrderOperations.CREATE);
}
```

"Approve is only valid from status X" stays in the domain. A role's scope decides who may act on which rows and
knows nothing about statuses. If a rule per status pair is ever required, declare an operation per edge
(`sales.order.approvefromreview`).

### Where the values come from

| Dimension | Principal attribute | Value |
|---|---|---|
| `team` | `team` | the user's team id |
| `customer` | `customer` | the user's own subject |

The `customer` row is a workaround. A configured scope cannot say "compare this custom column to the caller's
subject", so the resolver copies the subject into an attribute. The alternative is a `DataScopeProvider` bean
(below), which moves that one rule out of configuration.

### Team cardinality

| Situation | What is needed |
|---|---|
| A team has many orders; an order has one team; a user is in one team | The team id column on the order plus the `team` attribute. No extra table. |
| One order is handled by several teams | An order-to-team collection on the entity, bound with `.bindCollection(TEAM, ...)`. Domain data, not a security table. |
| One user is in several teams | A `DataScopeProvider` that returns the set of team ids. |

## Administrative objects

An administrative operation on a named object - a reconciliation task, say - can be narrowed to the objects a caller
is granted. The dimension is the built-in `object`, whose values are object names; the handler declares which
parameter names the object. A role grants `OBJECT` (the objects are then granted per subject) or `ALL` (every
object); any other dimension on an operation that touches no rows fails startup.

Worked example - the supply team operates `supplier-sync` and nothing else, the platform team everything:

```yaml
ludwig:
  security:
    roles:
      SUPPLY_OPS:
        operations:
          reconciliation.task.read: OBJECT
          reconciliation.run.start: OBJECT
      PLATFORM_ADMIN:
        operations:
          reconciliation.task.read: ALL
          reconciliation.run.start: ALL
```

Then each member of the team is granted the task per operation, with `POST /admin/v1/identity/grants` and the body
`{"subject": "bob", "principalType": "USER", "resourceType": "reconciliation.run.start", "action": "invoke",
"dimension": "object", "dimensionValue": "supplier-sync"}` (and again with `"resourceType":
"reconciliation.task.read"`; an `expiresAt` suits on-call access). An operation that touches no rows is asked for
with its own id and the action `invoke`. Bob's run of `supplier-sync` is accepted, his run of `customer-sync` is
`403`, and his read of `customer-sync` is `404` - the same answer as for a task that does not exist. The task listing
shows him only `supplier-sync`. A grant reaches other replicas within the data-scope cache TTL, as every grant does.

Only `reconciliation` scopes its objects so far. `jobs` (job names) and `fileingest` (sources) are the candidates.

## Custom providers

Any number of `DataScopeProvider` beans may be declared. They are collected, together with the identity-projection
grant table, and their answers are united with the roles' scopes. A provider is asked with the operation's resource
and data action (an operation that touches no rows: its id and `invoke`).

```java
@Bean
DataScopeProvider teamScopeProvider(TeamMembershipRepository memberships) {
    return (principal, resourceType, action) -> {
        boolean applies = OrderOperations.RESOURCE.equals(resourceType) && DataAction.READ.equals(action);
        if (!applies) {
            return DataScope.none();
        }
        Set<String> teamIds = memberships.teamIdsOf(principal.subject());
        return teamIds.isEmpty()
                ? DataScope.none()
                : DataScope.restrictedTo(Map.of(OrderOperations.TEAM, teamIds));
    };
}
```

- A provider can only widen access. `DataScope.none()` means "I contribute nothing", not "deny".
- `ALL` from any provider short-circuits the rest.
- Order does not change the result; `@Order` only decides which runs first.
- Every provider runs on every scope check, so exit on resource and action before any lookup.
- A provider answers for a subject from data. It must not compare roles: who holds what is the role table's job, and
  a role compared in code cannot be changed from configuration. Widening still works: `sales.order.read: ALL` on a
  role is united with the provider's answer and wins.

## Keeping code and configuration in step

Startup fails the deploy for:

- an operation id outside the grammar, a duplicate id, or a description key that resolves nowhere;
- a role entry naming an operation the service does not catalogue, with the area's catalogued operations listed;
- a malformed scope (`OWNN`, `ALL+TENANT`), `NONE`, or a wildcard;
- a dimension the operation's resource does not bind, an operation whose resource has no `DataScopeMapping`, or a
  row dimension on an operation that touches no rows;
- an `includes` cycle, or an include of an undefined role;
- an authorization annotation naming an uncatalogued operation;
- `ludwig.security.data.policies`, `default-access` or `strict-policy-tokens` still being set.

It logs, once, every catalogued operation no role holds. An undefined role name in a caller's authorities is not an
error, because roles come from the identity provider.

Keep the full role table in the service's `application.yml` as the reviewed baseline and override single lines per
environment, so an access change is a one-line diff. `GET /admin/v1/security/operations/{id}/holders` shows who
holds an operation in the running deployment.

## Known limits

| Limit | Consequence |
|---|---|
| The role table is compiled once at startup | A configuration change needs a restart; it is not a live toggle. |
| Scopes are written inline per role | A scope expression cannot be named once and reused; `includes` shares whole roles. |
| A custom dimension reads a principal attribute | Comparing a custom column to the caller's subject needs the attribute workaround or a provider bean. |
| Principal attributes are single-valued | Several values for one dimension need a provider bean or grant-table rows. |
| `guard.scope(...)` does not audit | A refused `create` checked that way is not recorded as a denial the way `guard.check(...)` is. |

Changing any of these is a platform change to `security-spring-boot-starter` and goes through
OpenSpec against the `operation-authorization` capability.
