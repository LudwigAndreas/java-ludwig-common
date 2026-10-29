## MODIFIED Requirements

### Requirement: Another owner's PATs are managed through the pat administrative area

`pat-spring-boot-starter` SHALL declare `pat.read`, `pat.operate` and `pat.privileged`, and serve:

- `GET /admin/v1/pat/tokens?$filter=&cursor=` and `GET /admin/v1/pat/tokens/{id}` (`pat.read`) - metadata only, never
  a secret or its hash;
- `POST /admin/v1/pat/tokens` (`pat.privileged`) - issue for another owner;
- `POST /admin/v1/pat/tokens/{id}/revoke` (`pat.privileged`) - revoke any owner's PAT.

`PatAuthorities.MANAGE_ANY` (`pat:manage:any`) SHALL no longer exist, and the self-service API SHALL act only on the
caller's own PATs. These endpoints SHALL refuse a PAT-credentialed caller with the credential-not-permitted problem
type even when `ludwig.admin.allow-personal-access-tokens=true`, because a PAT may not mint, rotate or revoke a PAT.

#### Scenario: Revoking a leaked token of another user
- **WHEN** a caller holding `pat.privileged` revokes `bob`'s PAT with a reason
- **THEN** it is revoked with the same bounded revocation window as an owner's revocation, and the audit event
  records the actor and the reason

#### Scenario: The administrative surface is opened to PATs
- **WHEN** a deployment sets `ludwig.admin.allow-personal-access-tokens=true` and a PAT-credentialed caller holding
  `pat.privileged` calls `POST /admin/v1/pat/tokens`
- **THEN** the response is `403` with the credential-not-permitted problem type

## ADDED Requirements

### Requirement: The administrative token listing is filterable on owner and lifetime
`$filter` on `GET /admin/v1/pat/tokens` SHALL accept `ownerSubject`, `expiresAt`, `revokedAt`, `lastUsedAt` and
`useCount`. The secret digests, the key ids, the token name, the scopes, the last-used address and the revocation
reason SHALL NOT be filterable.

#### Scenario: Tokens unused for ninety days
- **WHEN** an administrator lists `$filter=revokedAt eq null and lastUsedAt lt 2026-07-31T00:00:00Z`
- **THEN** only unrevoked tokens last used before that date are returned, without any secret material
