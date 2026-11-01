## Purpose

Lets an operator see a service's reloadable configuration and secret sources and force a reload of one on every
replica, without restarting pods and without ever exposing a configured value.

## ADDED Requirements

### Requirement: Reloadable sources are listable without their values
Where the replica-control channel and the administrative surface are present, `hot-reload-spring-boot-starter`
SHALL declare `hotreload.read`, `hotreload.operate` and `hotreload.privileged`, and `GET
/admin/v1/hotreload/sources` (`hotreload.read`) SHALL list every reloadable source with its id, kind and last
successful reload time on the answering replica. No configured value, secret, or file content SHALL appear.

#### Scenario: Listing sources
- **WHEN** an operator lists the sources of a service with one properties file and one Vault KV path
- **THEN** both appear with their kind, and the response contains none of their values

### Requirement: A source can be reloaded on every replica
The replica control `hotreloadreload` with `{source}` (`hotreload.operate`) SHALL reload the named source on every
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
