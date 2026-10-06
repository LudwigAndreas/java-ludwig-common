# Configuring data scopes and role mappings

How to define data-level scopes in a service and map them onto roles in configuration, so that a
role's access can be changed by editing configuration rather than code.

Everything here is existing behaviour of
[`security-spring-boot-starter`](../sources/security-spring-boot-starter/README.md) (package
`ru.ludwigandreas.security.data`). The module README is the reference; this document is the worked
guide. The `order` service used in the examples is illustrative - it is not a module in this
repository. The real reference implementation is `product` in `services/crud-service-example`.

## The model in one page

| Concept | What it is | Where it is defined |
|---|---|---|
| Resource | The thing being protected (`order`) | Code: the name given to `DataScopeMapping.forResource(...)` |
| Dimension | An axis a row can be restricted along (`owner`, `tenant`, `partner`, or your own) | Code: `ScopeDimension`, bound to a column in the mapping |
| Action | A verb on the resource (`read`, `write`, `delete`, or your own) | Code: a plain string passed to the guard |
| Grant | What a role gets for one resource and action: `ALL`, `NONE`, or `+`-joined dimensions | Configuration |
| Policy | The tree `resource -> action -> role -> grant` | Configuration: `ludwig.security.data.policies` |

The split is deliberate. Which column carries which dimension is code, because it names real
columns and must break the build when one is renamed. Which role gets which grant is configuration,
because that is the part operations needs to read and change without a release.

Modules involved:

| Module | Role |
|---|---|
| `security-spring-boot-starter` | Owns the model: dimensions, mappings, the configured policy, `DataAccessGuard`. |
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

## Step 2: map roles to grants

```yaml
ludwig:
  security:
    data:
      default-access: NONE          # a resource/action with no policy is denied
      policies:
        order:                      # resource
          read:                     # action
            ROLE_ORDER_ADMIN: ALL
            ROLE_ORDER_AGENT: OWN           # rows they created
            ROLE_TENANT_VIEWER: TENANT      # rows of their tenant
            ROLE_PARTNER: PARTNER+TENANT    # their partner AND their tenant
          write:
            ROLE_ORDER_ADMIN: ALL
            ROLE_ORDER_AGENT: OWN
```

Changing a role's access is an edit to its line. Setting it to `NONE`, or deleting the line, revokes
it.

How grants compose:

- Dimensions inside one grant are AND-ed (`OWN+TENANT` = "my rows, in my tenant").
- A caller holding several matching roles gets the union of their grants.
- `ALL` beats everything; `NONE` contributes nothing.
- A caller with no value for a required dimension (for example no tenant) is denied, not widened.
- A caller entitled to nothing gets an empty page from a list endpoint, not a 403.

## Step 3: enforce in the service

```java
// Query time: the scope becomes part of the WHERE clause, so paging and totals stay correct.
queryFactory.selectFrom(order)
        .where(guard.predicate("order", DataAction.READ).and(criteria))
        .fetch();

// Load time: the safety net for loads by id and every path a scoped query never sees.
OrderEntity entity = repository.getByIdOrThrow(id);
guard.check("order", DataAction.READ, entity, OrderEntity::getId);
```

Use both. Application code injects `DataAccessGuard`, never an individual provider.

## Custom dimensions

A service adds its own axis with `ScopeDimension.of(...)`. Three things are needed.

1. Bind it:

   ```java
   static final ScopeDimension REGION = ScopeDimension.of("region");

   DataScopeMapping.forResource("order", OrderEntity.class)
           .bind(REGION, order.regionCode, OrderEntity::getRegionCode)
           .build();
   ```

2. Allow custom tokens and name the dimension in the policy:

   ```yaml
   ludwig:
     security:
       data:
         strict-policy-tokens: false     # otherwise only OWN/TENANT/PARTNER are accepted
         policies:
           order:
             read:
               ROLE_REGIONAL_MANAGER: REGION
               ROLE_REGIONAL_AGENT: OWN+REGION
   ```

   The token is the dimension name, case-insensitive. A token naming a dimension the mapping does
   not bind still fails startup, so a typo is caught with strict tokens off.

3. Give the caller a value. For a custom dimension the configured policy compares the column
   against the **principal attribute of the same name** (`region`). The stock
   `DatabaseAuthorityResolver` sets only `tenant` and `partner`, so your `AuthorityResolver` must
   supply the rest:

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

Transitions are **actions on `order`**, not a resource of their own: the policy answers "which role
may perform this verb on which orders". Each relationship to an order is a **dimension**.

### Vocabulary

Keep every name in one place per resource. String constants rather than an enum for the resource
and dimensions: the guard takes `String`, `ScopeDimension` is a value type by design, and
`@PreAuthorize("hasPermission(..., 'read')")` needs compile-time strings.

```java
public final class OrderAccess {

    public static final String RESOURCE = "order";

    public static final String CREATE = "create";
    public static final String READ = DataAction.READ;
    public static final String DELETE = DataAction.DELETE;

    public static final ScopeDimension CUSTOMER = ScopeDimension.of("customer");
    public static final ScopeDimension TEAM = ScopeDimension.of("team");

    private OrderAccess() {
    }
}
```

Transitions are the exception: the state machine needs the list anyway, so the domain enum carries
the action name.

```java
public enum OrderTransition {
    CANCEL("cancel"),
    APPROVE("approve");

    private final String action;

    OrderTransition(String action) {
        this.action = action;
    }

    public String action() {
        return action;
    }
}
```

Naming rules:

- Actions: lower-case, one word or kebab-case (`approve`, `send-back`). The lookup is an exact
  string match, so `Approve` in configuration does not match `approve` in code.
- Dimensions: lower-case nouns naming the relationship (`customer`, `team`), not the column.
- Roles: the full `ROLE_...` form everywhere. The prefix is normalised, but one spelling is easier
  to search for.
- Resource: singular, the same word the mapping bean uses.

### Mapping

```java
@Bean
DataScopeMapping<OrderEntity> orderScopeMapping() {
    QOrderEntity order = QOrderEntity.orderEntity;
    return DataScopeMapping.forResource(OrderAccess.RESOURCE, OrderEntity.class)
            .owner(order.createdBy, OrderEntity::getCreatedBy)
            .bind(OrderAccess.CUSTOMER, order.businessCustomerId, OrderEntity::getBusinessCustomerId)
            .bind(OrderAccess.TEAM, order.implementationTeamId, OrderEntity::getImplementationTeamId)
            .build();
}
```

### Policy

```yaml
ludwig:
  security:
    data:
      default-access: NONE
      strict-policy-tokens: false
      policies:
        order:
          create:
            ROLE_USER: ALL
          read:
            ROLE_MODERATOR: ALL
            ROLE_USER: OWN
            ROLE_BUSINESS_CUSTOMER: CUSTOMER
            ROLE_IMPLEMENTATION_TEAM: TEAM
          delete:
            ROLE_USER: OWN
          cancel:
            ROLE_USER: OWN
          approve:
            ROLE_MODERATOR: ALL
```

The future change is one line:

```yaml
            ROLE_IMPLEMENTATION_TEAM: ALL   # was TEAM
```

### Enforcement

```java
// A transition: load, check the verb against this row, then let the domain decide validity.
OrderEntity entity = repository.getByIdOrThrow(id);
guard.check(OrderAccess.RESOURCE, transition.action(), entity, OrderEntity::getId);
entity.apply(transition);

// Create: no row exists yet, so ask only whether the caller holds any grant.
if (guard.scope(OrderAccess.RESOURCE, OrderAccess.CREATE).denies()) {
    throw new AccessDeniedException("order.create");
}
```

"Approve is only valid from status X" stays in the domain. The policy decides who may act on which
rows and knows nothing about statuses. If a rule per status pair is ever required, name the action
per edge (`approve-from-review`).

### Where the values come from

| Dimension | Principal attribute | Value |
|---|---|---|
| `team` | `team` | the user's team id |
| `customer` | `customer` | the user's own subject |

The `customer` row is a workaround. The configured policy cannot say "compare this custom column to
the caller's subject", so the resolver copies the subject into an attribute. The alternative is a
`DataScopeProvider` bean (below), which moves that one rule out of configuration.

### Team cardinality

| Situation | What is needed |
|---|---|
| A team has many orders; an order has one team; a user is in one team | The team id column on the order plus the `team` attribute. No extra table. |
| One order is handled by several teams | An order-to-team collection on the entity, bound with `.bindCollection(TEAM, ...)`. Domain data, not a security table. |
| One user is in several teams | A `DataScopeProvider` that returns the set of team ids. |

## Custom providers

Any number of `DataScopeProvider` beans may be declared. `CompositeDataScopeProvider` collects all of
them, together with the configured policy and the identity-projection grant table, and unions the
results.

```java
@Bean
DataScopeProvider teamScopeProvider(TeamMembershipRepository memberships) {
    return (principal, resourceType, action) -> {
        boolean applies = OrderAccess.RESOURCE.equals(resourceType)
                && DataAction.READ.equals(action)
                && principal.hasRole("ROLE_IMPLEMENTATION_TEAM");
        if (!applies) {
            return DataScope.none();
        }
        Set<String> teamIds = memberships.teamIdsOf(principal.subject());
        return teamIds.isEmpty()
                ? DataScope.none()
                : DataScope.restrictedTo(Map.of(OrderAccess.TEAM, teamIds));
    };
}
```

- A provider can only widen access. `DataScope.none()` means "I contribute nothing", not "deny".
- `ALL` from any provider short-circuits the rest.
- Order does not change the result; `@Order` only decides which runs first.
- Every provider runs on every scope check, so exit on resource, action and role before any lookup.
- The role and action in a provider are Java, so that rule cannot be narrowed or reassigned from
  configuration. Widening still works: `ROLE_IMPLEMENTATION_TEAM: ALL` in the policy is unioned with
  the provider's answer and wins.

## Keeping code and configuration in step

Startup validation (`DataScopePolicyValidator`) fails the deploy for:

- a misspelled grant (`OWNN`, `ALL+TENANT`),
- a policy for a resource with no `DataScopeMapping`,
- a policy using a dimension the mapping does not bind,
- a resource listed as both unscoped and policed.

It does **not** validate action names. `aprove:` in configuration is accepted, and the real
`approve` falls back to `default-access: NONE` - closed, but silent. A test in the service covers
both directions:

```java
@Test
void everyActionHasAPolicyAndEveryPolicyIsAKnownAction() {
    Set<String> known = new HashSet<>(Set.of(OrderAccess.CREATE, OrderAccess.READ, OrderAccess.DELETE));
    Arrays.stream(OrderTransition.values()).map(OrderTransition::action).forEach(known::add);

    Set<String> configured = properties.getData().getPolicies().get(OrderAccess.RESOURCE).keySet();

    assertThat(configured).isEqualTo(known);
}
```

Keep the full policy tree in the service's `application.yml` as the reviewed baseline and override
single lines per environment, so an access change is a one-line diff.

## Known limits

| Limit | Consequence |
|---|---|
| Policies are compiled once at startup (`ScopePolicies`) | A configuration change needs a restart; it is not a live toggle. |
| Grants are written inline per role | A grant expression cannot be named once and reused across roles. |
| A custom dimension reads a principal attribute | Comparing a custom column to the caller's subject needs the attribute workaround or a provider bean. |
| Principal attributes are single-valued | Several values for one dimension need a provider bean or grant-table rows. |
| `guard.scope(...)` does not audit | A refused `create` checked that way is not recorded as a denial the way `guard.check(...)` is. |
| Action names are not validated at startup | Covered by the service test above. |

Changing any of these is a platform change to `security-spring-boot-starter` and goes through
OpenSpec against the `data-access` capability.
