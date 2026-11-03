## ADDED Requirements

### Requirement: The security starter works without web-core
An application with `security-spring-boot-starter` and without `web-core-spring-boot-starter` on its classpath SHALL
start. In it, a call to a `PRIVILEGED` operation without a stated reason, or with a personal access token, SHALL be
refused with the security starter's own reason-required or credential-not-permitted refusal, never with a linkage
error. Where web-core is present, those refusals SHALL be rendered as before: `400` (`INVALID_ARGUMENT` on gRPC) with
the reason-required problem type, and `403` (`PERMISSION_DENIED`) with the credential-not-permitted problem type.

#### Scenario: A worker without web-core starts
- **WHEN** an application with the security starter and no web-core on its classpath starts with every security
  auto-configuration enabled
- **THEN** it starts

#### Scenario: A privileged call without a reason, without web-core
- **WHEN** a caller holding a `PRIVILEGED` business operation calls it without a reason in that application
- **THEN** the call is refused with the security starter's reason-required refusal, carrying the reason-required code

#### Scenario: The refusal over HTTP is unchanged
- **WHEN** the same call is made over HTTP in an application with web-core
- **THEN** the response is `400` with the reason-required problem type
