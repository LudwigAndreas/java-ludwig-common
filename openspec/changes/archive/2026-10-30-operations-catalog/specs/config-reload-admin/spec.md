## MODIFIED Requirements

### Requirement: Reloadable sources are listable without their values
Where the replica-control channel and the administrative surface are present, `hot-reload-spring-boot-starter`
SHALL declare `hotreload.source.read` (`READ`) and `hotreload.source.reload` (`CHANGE`) on the `ADMIN`
surface, and `GET /admin/v1/hotreload/sources` (`hotreload.source.read`) SHALL list every reloadable source with its id, kind and last
successful reload time on the answering replica. No configured value, secret, or file content SHALL appear.

#### Scenario: Listing sources
- **WHEN** an operator lists the sources of a service with one properties file and one Vault KV path
- **THEN** both appear with their kind, and the response contains none of their values

### Requirement: A source can be reloaded on every replica
The replica control `hotreloadreload` with `{source}` (`hotreload.source.reload`) SHALL reload the named source on every
targeted replica and report per replica `reloaded`, `unchanged` or `rejected`. A rejected reload SHALL keep the
last-known-good value. An unknown source SHALL be `400`. Each reload SHALL be audited as a watcher-triggered reload
is, with values redacted.

#### Scenario: Vault rotated a secret early
- **WHEN** an operator reloads the Vault KV source after a rotation, in a service with two replicas
- **THEN** the command succeeds with `reloaded` from both replicas, and both use the new secret on their next call

#### Scenario: The new file does not validate
- **WHEN** an operator reloads a properties source whose new content fails its typed configuration's validation
- **THEN** each replica reports `rejected`, the command is `SUCCEEDED`, and every replica keeps its last-known-good
  value

#### Scenario: Reloading a Vault lease source
- **WHEN** an operator reloads a `VAULT_LEASE` source of a rotating secret
- **THEN** each replica requests a new secret from Vault and reports the delivery's outcome; a renewable
  (non-rotating) lease reports `unchanged`

#### Scenario: Without the replica channel
- **WHEN** a service runs `hot-reload-spring-boot-starter` without `job-core`'s replica-control channel or without
  the administrative surface
- **THEN** no `hotreload` operation, endpoint or control exists, and the starter behaves as before
