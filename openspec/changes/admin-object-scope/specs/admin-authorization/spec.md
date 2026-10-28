## ADDED Requirements

### Requirement: An administrative operation can be scoped to named objects
A handler MAY declare the parameter that names the object it acts on. When the deployment's data-scope policy has an
entry for resource `admin.<area>` and the operation's tier, a call to such a handler SHALL be allowed only if the
caller's data scope for that resource and tier, along dimension `object`, admits the named object. Role entries in
that policy SHALL be `ALL` or `NONE`. Per-subject grants with dimension `object` SHALL add objects. A refusal SHALL
be `403` on an operate or privileged endpoint and `404` on a read endpoint, and SHALL be audited. When no such policy
entry exists, holding the operation SHALL admit every object.

#### Scenario: A team operates only its own task
- **WHEN** the policy maps `ROLE_SUPPLY_OPS` to `NONE` for `admin.reconciliation` `operate`, bob holds that role and
  `reconciliation.operate`, and bob has a grant of `object` = `supplier-sync`
- **THEN** bob's run of `supplier-sync` is accepted and his run of `customer-sync` is `403`

#### Scenario: An object outside scope is not disclosed
- **WHEN** the same deployment scopes `read` the same way and bob reads `customer-sync`
- **THEN** the response is `404`, identical to the response for a task that does not exist

#### Scenario: No policy entry keeps today's behaviour
- **WHEN** the deployment has no data-scope policy for `admin.reconciliation`
- **THEN** a holder of `reconciliation.operate` may run every task

#### Scenario: A role-level object list is refused
- **WHEN** a policy entry for `admin.reconciliation` maps a role to `OBJECT`
- **THEN** the application fails to start, naming the entry and stating that objects are granted per subject
