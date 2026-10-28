## Purpose

Moves the user-settings starter's administrative endpoints onto the shared administrative surface, so that reading
another subject's settings and writing scope-level settings is authorized by the consuming service's roles rather
than one configurable authority.

## ADDED Requirements

### Requirement: Administrative settings access is on the shared surface

`user-settings-spring-boot-starter` SHALL declare `settings.read`, `settings.operate` and `settings.privileged`, and
serve:

| endpoint | operation |
|---|---|
| `GET /admin/v1/settings/subjects/{subject}` | `settings.read` |
| `GET /admin/v1/settings/subjects/{subject}/consents` | `settings.read` |
| `GET /admin/v1/settings/subjects/{subject}/consents/asof?at=` (was `.../consents/as-of`) | `settings.read` |
| `PUT /admin/v1/settings/scopes/{scopeType}/{scopeId}` | `settings.operate` |
| `POST /admin/v1/settings/backfill` | `settings.operate` |

The paths under `/admin/settings/`, `ludwig.user-settings.access.admin-authority` and
`ludwig.user-settings.web.admin-base-path` SHALL no longer exist.

#### Scenario: Support reads a user's settings
- **WHEN** a caller holding `settings.read` reads subject `alice`
- **THEN** the resolved settings are returned as the former administrative endpoint returned them

#### Scenario: The former path
- **WHEN** a request reaches `/admin/settings/subjects/alice`
- **THEN** it is answered as an unmapped path

### Requirement: The tenant rule is kept, and its answer aligned with the shared surface

A `USER` caller SHALL reach only subjects and scopes of their own tenant, as the settings access policy already
requires; a `SERVICE` caller SHALL be confined or not exactly as `ludwig.user-settings.access.allow-service-principals`
already decides. A refusal on these endpoints SHALL be `404`, as `admin-authorization` requires for a resource of
another tenant, replacing the `403` the former endpoints answered; the self-service endpoints keep their `403`.

A subject's tenant is whatever the deployment's `SettingsTenantResolver` answers. The default resolver answers with
the caller's tenant for every subject, so a name from another tenant resolves to an empty subject of the caller's
own tenant and no other tenant's row is read; a resolver that places subjects in their own tenant makes the refusal
reachable.

#### Scenario: A tenant administrator reads another tenant's user
- **WHEN** a user of tenant `acme` holding `settings.read` reads a subject the tenant resolver places in tenant
  `globex`
- **THEN** the response is `404`, where the former endpoint answered `403`

#### Scenario: Another area's operation does not reach settings
- **WHEN** a handler of another area's administrative operation reads another subject's settings in-process
- **THEN** the settings access policy refuses it as it refuses any non-administrative caller
