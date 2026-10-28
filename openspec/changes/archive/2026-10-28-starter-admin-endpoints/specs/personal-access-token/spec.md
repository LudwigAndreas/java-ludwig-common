## MODIFIED Requirements

### Requirement: Only an authorized caller may issue a PAT, and only for a permitted subject

Issuance SHALL be authorized through `security-spring-boot-starter`. A caller SHALL be able to issue a PAT for
themselves through the self-service management API, which SHALL accept only the caller's own subject as owner.
Issuing on behalf of another subject SHALL be possible only through the `pat` area of the administrative surface and
SHALL require the `pat.privileged` operation.

#### Scenario: An unauthenticated request reaches the management API
- **WHEN** no credential is presented
- **THEN** the response is the existing localized `401` problem document from the security starter's
  entry point, not a bespoke one

#### Scenario: A caller issues a PAT for themselves
- **WHEN** an authenticated caller requests a PAT whose owner is their own subject
- **THEN** it is issued, subject to the scope, expiry and lifetime requirements above

#### Scenario: A caller issues a PAT for someone else without the administrative authority
- **WHEN** an authenticated caller who does not hold `pat.privileged` requests a PAT whose owner is a different
  subject, on either API
- **THEN** the request is refused with `403`, and the attempt is recorded as an audit event, because a request to
  mint a credential for another identity is a security event whether it succeeds or not

#### Scenario: A caller names another owner on the self-service API
- **WHEN** an authenticated caller requests, on the self-service API, a PAT whose owner is a different subject,
  whatever operations they hold
- **THEN** the request is refused with `403`, and the attempt is recorded as an audit event, because a request to
  mint a credential for another identity is a security event whether it succeeds or not

#### Scenario: An administrator issues a PAT for someone else
- **WHEN** a caller holding `pat.privileged` posts to `/admin/v1/pat/tokens` with owner `bob` and an `Admin-Reason`
- **THEN** the PAT is issued to `bob` under the same scope, expiry and lifetime requirements, the secret is returned
  once to the caller, and the audit event records the actor, the owner and the reason

## ADDED Requirements

### Requirement: Another owner's PATs are managed through the pat administrative area

`pat-spring-boot-starter` SHALL declare `pat.read`, `pat.operate` and `pat.privileged`, and serve:

- `GET /admin/v1/pat/tokens?owner=&status=` and `GET /admin/v1/pat/tokens/{id}` (`pat.read`) - metadata only, never
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
