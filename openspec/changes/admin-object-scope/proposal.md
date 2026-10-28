## Why

Today a grant of `reconciliation.operate` lets its holder run, backfill and requeue every reconciliation task in
the service. Large deployments have teams that own one partner integration each: the supply team should operate
`supplier-sync`, and only that. `openspec/intents/admin-surface-follow-ups.md` §2 deferred this, noting it should
be "a data-scope binding, not a new grant syntax". The platform already has a data-scope model (policies per
resource, action and role; per-subject grant rows managed through `identity-admin`), and this change reuses it for
administrative objects.

## What Changes

- **The administrative resource.** An operation on a named object can be narrowed by data scope. The resource type
  is `admin.<area>` (for example `admin.reconciliation`), the action is the tier (`read`, `operate`,
  `privileged`), and the dimension is a new built-in `object`, whose values are object names.
- **Opt-in per area and tier.** Scoping is turned on by a data-scope policy entry under
  `ludwig.security.data.policies."admin.<area>".<tier>`:
  - a role mapped to `ALL` keeps every object;
  - a role mapped to `NONE` gets only the objects that per-subject grants name.

  Grants are created with the existing `POST /admin/v1/identity/grants`, using resource `admin.reconciliation`,
  action `operate`, dimension `object` and value `supplier-sync`. With no policy entry for `admin.<area>`, nothing
  changes: holding the operation means every object, as today.
- **Declaring the object.** A handler declares which path variable names the object with `@AdminObject` on that
  parameter. The surface checks the object after the operation check and before the handler runs:
  - a denial is `403`, audited like every administrative denial;
  - an object outside the caller's scope on a read endpoint is `404`, so its existence is not disclosed.
- **Lists.** Listings filter to the caller's objects through `AdminObjectScope`, the one programmatic entry point.
- **First adopter: `reconciliation`.** Its `{task}` endpoints and `GET /admin/v1/reconciliation/tasks` honour the
  `object` scope. Other areas with named objects (`jobs`, `fileingest`) are listed as candidates.
- **BREAKING**: `ScopeDimension.BUILT_IN` gains `OBJECT`. A policy token `OBJECT` is now accepted under strict
  tokens, where before it was refused at startup.

## Capabilities

### New Capabilities
None.

### Modified Capabilities
- `admin-authorization`: adds per-object scoping of administrative operations.
- `reconciliation-admin`: tasks are scoped objects.

## Non-goals

- Per-object scoping in areas other than `reconciliation`. Each is a small follow-up once this has been applied.
- Object patterns or wildcards (`supplier-*`). Values are exact names, as for every other dimension.
- Role-level object lists in configuration (`ROLE_SUPPLY_OPS: [supplier-sync]`). That would be the new grant
  syntax the follow-up warned against. Role entries stay `ALL` or `NONE`, and objects are granted per subject.
- Scoping row-level resources inside an area (outbox messages, audit events). Those already have their own
  confinement rules.

## Impact

- `security-spring-boot-starter` (library; parent `common`), which has 16 in-repo dependents, all run by the gate.
- `reconciliation-spring-boot-starter` (library; parent `common`), which has no in-repo dependents.
- `architecture-rules` (library; parent `common`) gains the rule `admin-object-is-a-path-variable` in
  `RuleGroup.ADMIN`. Its in-repo dependents are every module that runs the shared rules, which the gate covers.
- `identity-projection-spring-boot-starter` is unchanged. Its grants already carry any dimension the deployment
  accepts, and `object` becomes acceptable through `BUILT_IN`.
- Shared contracts: `admin-authorization` and `reconciliation-admin` are modified. `data-access` is untouched,
  since this introduces no queries.
- No POM changes.
- READMEs (EN and RU) of both modules, `docs/data-scope-configuration.md`, the admin guide, and `CHANGELOG`
  (EN and RU).
