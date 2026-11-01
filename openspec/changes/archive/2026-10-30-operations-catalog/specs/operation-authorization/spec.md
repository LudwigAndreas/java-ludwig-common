## Purpose

Defines how every platform service authorizes a call: code names the operation it performs and the rows it touches,
configuration alone defines which roles hold which operations with which row scope, and one catalog serves
enforcement, row scope, discovery and audit so that none of them can disagree.

## ADDED Requirements

### Requirement: Code declares operations, never roles
A module SHALL declare every operation it authorizes in the operation catalog with:
- an id `<area>.<object>.<verb>`: three lower-case segments of letters and digits, each starting with a letter;
- a sensitivity: `READ` (changes nothing), `CHANGE` (changes state), or `PRIVILEGED` (can lose data, changes who may
  do what, or decides whether a dependency is called at all);
- a surface: `BUSINESS` or `ADMIN`;
- optionally, the resource and data action it touches;
- a description key.

An operation of sensitivity `READ` SHALL NOT declare a data action other than read, and an operation of sensitivity
`CHANGE` or `PRIVILEGED` SHALL NOT declare the read action. Code SHALL authorize by operation id only, and SHALL NOT
name a role in any authorization it declares.

#### Scenario: The catalog is the union of every module's operations
- **WHEN** a service runs two modules that each declare operations
- **THEN** the catalog contains each declared operation exactly once, with its sensitivity, surface and resource

#### Scenario: An id outside the grammar
- **WHEN** a module declares `outbox.discard`, or `Outbox.Message.Discard`
- **THEN** the application fails to start, naming the id

#### Scenario: Two declarations share an id
- **WHEN** two modules declare `outbox.message.retry`
- **THEN** the application fails to start, naming the id and both declaring modules

#### Scenario: A description key resolves nowhere
- **WHEN** an operation's description key is defined in no message bundle
- **THEN** the application fails to start, naming the operation and the key

#### Scenario: A read operation declares a write
- **WHEN** an operation of sensitivity `READ` declares the write data action
- **THEN** the application fails to start, naming the operation

### Requirement: Roles are defined only in configuration
A service SHALL define its roles under `ludwig.security.roles.<ROLE>`. Each role has:
- `operations`, a map from operation id to scope;
- optionally `includes`, a list of other roles whose operations it also holds.

A scope SHALL be `ALL`, or one or more dimensions joined by `+` (for example `OWN+TENANT`). A role name SHALL be
accepted with or without the `ROLE_` prefix. `NONE`, wildcards and any other inheritance SHALL be refused.

A role's effective scope for an operation SHALL be the union of its own entry and those of every role it includes,
transitively. `ludwig.admin.grants` and `ludwig.security.data.policies` SHALL NOT exist.

#### Scenario: A role is defined in one block
- **WHEN** `CATALOG_EDITOR` lists `catalog.product.update: OWN+TENANT` and includes `CATALOG_READER`, which lists
  `catalog.product.read: TENANT`
- **THEN** a caller holding `ROLE_CATALOG_EDITOR` holds both operations, with those scopes

#### Scenario: An included role is widened
- **WHEN** `CATALOG_READER` gains `catalog.category.read: ALL`
- **THEN** every role that includes it holds `catalog.category.read` with no other change

#### Scenario: A cycle in includes
- **WHEN** `A` includes `B` and `B` includes `A`
- **THEN** the application fails to start, naming the cycle

#### Scenario: An include of an undefined role
- **WHEN** a role includes a role that is not defined under `ludwig.security.roles`
- **THEN** the application fails to start, naming both roles

#### Scenario: A wildcard is refused
- **WHEN** a role lists `catalog.*: ALL`
- **THEN** the application fails to start, naming the entry

#### Scenario: The removed keys are gone
- **WHEN** a service still configures `ludwig.admin.grants` or `ludwig.security.data.policies`
- **THEN** the application fails to start, naming the key and `ludwig.security.roles`

### Requirement: Holding an operation is holding a scope, and every gap fails closed
A caller SHALL hold an operation if and only if its effective scope for that operation, over all its resolved roles
and every contributed per-subject grant, is not none. An operation no role holds SHALL be refused for every caller.

Startup SHALL fail on:
- a configuration entry naming an operation that is not catalogued;
- a dimension that the operation's resource does not bind;
- a dimension other than `ALL` or `OBJECT` on an operation that declares no resource (`OBJECT` is bound by the
  object the call names, not by rows);
- an authorization annotation naming an uncatalogued operation.

Startup SHALL log, once, every catalogued operation that no role holds. An unknown role name SHALL NOT fail
startup, because roles come from the identity provider.

#### Scenario: A misspelled operation in a role
- **WHEN** a role lists `outbox.message.dicsard`
- **THEN** the application fails to start, naming the entry and listing the catalogued operations of area `outbox`

#### Scenario: A dimension the resource cannot apply
- **WHEN** a role grants `catalog.product.read: TENANT` and the `product` resource binds no tenant
- **THEN** the application fails to start, naming the role, the operation and the dimension

#### Scenario: A row dimension on an operation without rows
- **WHEN** a role grants `cache.performance.evict: OWN`
- **THEN** the application fails to start, stating that the operation touches no rows and admits only `ALL` or
  `OBJECT`

#### Scenario: An operation nobody holds
- **WHEN** `outbox.message.discard` is catalogued and no role lists it
- **THEN** every call requiring it is refused, and the startup log names it among the operations no role holds

### Requirement: Roles come from the authority resolver, and the credential's attenuation still applies
The roles evaluated SHALL be those the platform's authority resolution answers: the identity projection for users,
and `ludwig.identity.service-roles` for service principals. They are taken after the personal-access-token
intersection. A role carried as a claim in the token SHALL NOT be considered.

#### Scenario: A token asserts a role the projection does not hold
- **WHEN** a user's token carries a `roles` claim with `NOTIFICATION_ADMIN` and the projection holds no such role
- **THEN** every operation that only `NOTIFICATION_ADMIN` holds is refused

#### Scenario: A revoked role stops working within the authority cache window
- **WHEN** a role is removed from a user in the directory and the projection commits the change
- **THEN** the user's calls needing it are refused no later than the authority cache TTL after the commit

### Requirement: A call is authorized by the operation it declares
A handler, an application-service method or a gRPC method SHALL declare the operation it performs, and SHALL be
invoked only when the caller holds that operation. A refusal SHALL be `403` (`PERMISSION_DENIED` on gRPC) before
any of its code runs, and SHALL be recorded as an authorization denial through the platform's audit path, naming
the operation.

A method that admits any authenticated caller SHALL declare that instead. An `ADMIN` operation declared on an
endpoint outside `/admin/v1/`, or a `BUSINESS` operation declared on an endpoint under it, SHALL fail startup.

#### Scenario: A service method guarded by its operation
- **WHEN** a caller who does not hold `catalog.product.update` calls the product update
- **THEN** the response is `403`, the product is unchanged, and the denial is audited naming
  `catalog.product.update`

#### Scenario: The same operation over two transports
- **WHEN** one operation guards both an HTTP endpoint and a gRPC method
- **THEN** a holder succeeds on both and a non-holder is refused on both, with one denial event each

#### Scenario: An administrative operation on a business path
- **WHEN** a handler outside `/admin/v1/` declares `outbox.message.retry`
- **THEN** the application fails to start, naming the handler and the operation

### Requirement: A privileged operation needs a stated reason and a direct credential
A call to an operation of sensitivity `PRIVILEGED` SHALL carry `Operation-Reason`: on gRPC, `operation-reason`
metadata. Its value is 1 to 500 characters after trimming. Without one, the call SHALL be refused with `400`
(`INVALID_ARGUMENT`) and the reason-required problem type, after the operation is found held and before anything
runs. On other operations the reason SHALL be optional and recorded when present.

A call to a `PRIVILEGED` operation made with a personal access token SHALL be refused with `403` and the
credential-not-permitted problem type, unless `ludwig.security.operations.allow-personal-access-tokens=true`.

#### Scenario: A privileged call without a reason
- **WHEN** a holder of `outbox.message.discard` discards without `Operation-Reason`
- **THEN** the response is `400` with the reason-required problem type and the message is unchanged

#### Scenario: Authorization is decided before the reason
- **WHEN** a caller who does not hold the operation makes the same call without a reason
- **THEN** the response is `403`, so the reason requirement reveals nothing

#### Scenario: A personal access token on a privileged operation
- **WHEN** a PAT-credentialed caller whose owner holds `pat.token.revoke` calls it
- **THEN** the response is `403` with the credential-not-permitted problem type

### Requirement: Rows are scoped by the operation, not by a role
An operation that declares a resource SHALL scope the rows it touches by the caller's effective scope for that
operation:
- `ALL` admits every row;
- a dimension admits the rows that dimension binds to the caller (`OWN` the caller's own, `TENANT` the caller's
  tenant, `PARTNER` the caller's partner, `OBJECT` the objects granted to the caller, or a dimension the module
  contributes);
- several dimensions joined by `+` must all admit a row;
- scopes from several roles are united.

Listings SHALL omit rows outside scope. A single row outside scope SHALL be answered as not found on a read, and
refused on a change.

#### Scenario: An editor updates only their own products
- **WHEN** `CATALOG_EDITOR` holds `catalog.product.update: OWN` and its holder updates a product another user
  created
- **THEN** the update is refused and the product is unchanged

#### Scenario: Two roles widen the scope
- **WHEN** a caller holds one role with `notification.delivery.read: TENANT` and another with
  `notification.delivery.read: ALL`
- **THEN** deliveries of every tenant are listed to that caller

#### Scenario: A row of another tenant is not disclosed
- **WHEN** a caller with `TENANT` scope reads a delivery of another tenant
- **THEN** the response is the same not-found the caller gets for a delivery that does not exist

### Requirement: An administrative operation can be confined to named objects
An `ADMIN` handler MAY declare the parameter that names the object it acts on. When the caller's effective scope
for the operation is restricted, the call SHALL be allowed only if that scope admits the named object along
dimension `OBJECT`. Objects SHALL be granted per subject through the identity projection. A role SHALL be able to
grant `OBJECT` (to be filled per subject) or `ALL`. A refusal SHALL be `404` for a `READ` operation and `403`
otherwise, and SHALL be audited.

#### Scenario: A team runs only its own reconciliation task
- **WHEN** bob's role grants `reconciliation.run.start: OBJECT` and bob has a per-subject grant of object
  `supplier-sync`
- **THEN** his run of `supplier-sync` is accepted and his run of `customer-sync` is `403`

#### Scenario: An object outside scope is not disclosed
- **WHEN** the same deployment grants `reconciliation.task.read: OBJECT` and bob reads `customer-sync`
- **THEN** the response is `404`, identical to a task that does not exist

### Requirement: A caller can discover the operations it holds
`GET /me/operations` SHALL answer any authenticated caller, as `application/json`, with the operations the caller
holds. Each is listed with its id, sensitivity, surface, scope (`ALL` or its dimensions) and description in the
caller's locale. It SHALL NOT list an operation the caller does not hold. `/admin/v1/me/operations` SHALL NOT exist.

#### Scenario: A UI decides which controls to show
- **WHEN** a caller holding only `ROLE_NOTIFICATION_SUPPORT` calls it in notification-service
- **THEN** the response lists `outbox.message.read` with scope `ALL` and `notification.delivery.read` with scope
  `TENANT`, and does not list `outbox.message.discard`

#### Scenario: A caller holding nothing
- **WHEN** an authenticated caller holds no operation
- **THEN** the response is `200` with an empty list

#### Scenario: The former path
- **WHEN** a request reaches `/admin/v1/me/operations`
- **THEN** it is answered as an unmapped path

### Requirement: An administrator can see who holds an operation
`GET /admin/v1/security/operations/{id}/holders` (`security.operation.read`) SHALL list every configured role
that holds the operation, with its scope, and, when the role holds it through `includes`, the role it was included
from. An uncatalogued id SHALL be `404`.

#### Scenario: Who may discard outbox messages
- **WHEN** an administrator asks for the holders of `outbox.message.discard`
- **THEN** the response lists `NOTIFICATION_ADMIN` with scope `ALL`, and every role that includes it with `via`
  naming `NOTIFICATION_ADMIN`
