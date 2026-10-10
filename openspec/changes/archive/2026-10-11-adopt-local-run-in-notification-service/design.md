## Context

See `proposal.md` for motivation. What was read in the repository and shapes the approach:

- `notification-service` does not depend on `rest-client-spring-boot-starter` and has no
  `@LudwigRestClient`. Its outbound HTTP is two plain `RestClient`s built in
  `NotificationChannelConfig`: the chat channel, addressed by
  `ludwig.notification.channels.chat.base-url`, and the webhook channel, whose target is a URL in
  each recipient's profile. Mail goes through `JavaMailSender` (`spring.mail.*`).
- Chat, webhook, the Kafka ingress and the identity projection are all **off** by default
  (`NOTIFICATION_CHAT_ENABLED`, `NOTIFICATION_WEBHOOK_ENABLED`, `NOTIFICATION_KAFKA_ENABLED`,
  `IDENTITY_PROJECTION_ENABLED`). `NotificationTestBase` additionally stops the queue poller.
- Tokens are validated from `spring.security.oauth2.resourceserver.jwt.issuer-uri`, with
  `ludwig.security.jwt.audiences=notification-service` and a `tenant_id` claim. Integration tests
  replace the decoder with `RejectingJwtDecoderConfiguration` and inject principals.
- The service's tests already have `RecipientFixtures` (recipients, opt-outs, quiet hours, locale)
  and `TestPrincipals`.
- `test-support-local` today: `PartnerStub.clientName()` is both the stub's name and the
  rest-client name; the address property is derived from it; `StubCoverageCheck` exists only when
  the rest-client starter is on the classpath; the summary's lines are fixed.
- `test-support`'s `GreenMailFixture` is a JUnit extension on fixed port 3025.
- `test-support-local` has no in-repo dependents; `notification-service` has none either.

## Goals / Non-Goals

**Goals:**

- A developer clones, runs one command, pastes one printed `curl`, and is at a breakpoint in any
  delivery path.
- The example is complete enough to copy: stubs, fixtures, local configuration, the rules test, a
  test that launches it.
- The module changes are the minimum the adoption forces, each justified by something
  `notification-service` actually needs.

**Non-Goals:**

- Anything under Non-goals in `proposal.md`.
- A generic "local identity provider" in a shared module.
- Making the existing `NotificationTestBase` tests use the new stubs.

## Decisions

### D1. Stub addressing becomes explicit (`test-support-local`)

`PartnerStub.clientName()` is renamed `name()` - the key under `ludwig.local.stubs.<name>.*` and in
`StubServers.stub(name)`. A new `default StubAddress address()` says where the stub's URL goes:

| `StubAddress` | Publishes | Default |
|---|---|---|
| `restClient()` | `ludwig.rest-client.clients.<name>.base-url` | yes |
| `property("...")` | the named property | |
| `none()` | nothing | |

*Why a type and not a nullable string:* the three cases are checked differently (D2), and "no
address on purpose" has to be distinguishable from "forgot".
*Alternative considered:* move the chat channel to `@LudwigRestClient`. Rejected for this change:
it adds the starter's retry and circuit breaker in front of the service's own
`DeliveryBackoffCalculator` and `HttpFailureClassifier`, which changes delivery semantics.

### D2. The coverage check generalises instead of forking

The "dead stub" direction already compares against configuration from every property source
except the stubs' own. It becomes: *the property a stub publishes must be configured by some other
source*. That covers `restClient()` and `property(...)` with one rule; `none()` is exempt. The
"client without a stub" direction stays tied to `RestClientRegistry` and to stubs with a
`restClient()` address. The dead-stub half moves out of the rest-client-conditional configuration,
so it also runs in a service without the starter.

Consequence for `notification-service`: `chat.base-url` defaults to empty in `application.yml`
(`${CHAT_BASE_URL:}`), and an empty value counts as "not configured". The local configuration
(D5) therefore sets it to a placeholder remote host, which the stub then overrides - the same
shape the sample application uses.

### D3. Mail is in-process and is not a `BackingService`

`LocalRun.mail()` starts GreenMail (SMTP and IMAP) on loopback with OS-allocated ports, publishes
`spring.mail.host`, `spring.mail.port` and the two SMTP auth properties off, and registers a
`LocalMailbox` bean exposing received messages. The summary prints the SMTP address and the IMAP
address with the mailbox credentials.

*Why not `GreenMailFixture`:* it is a JUnit extension on a fixed port, with no lifecycle outside a
test. *Why not the `BackingService` enum:* that enum means "a digest-pinned container from
`test-support`"; GreenMail is a library with no image. `greenmail-junit5` becomes an `optional`
dependency of `test-support-local`, and the class-presence check that guards containers guards it.

### D4. Authentication: a stand-in issuer, in the service's test sources

`IdentityProviderStub implements PartnerStub`, address
`property("spring.security.oauth2.resourceserver.jwt.issuer-uri")`. It generates an RSA key at
start, serves `/.well-known/openid-configuration` and the JWK set, and mints signed tokens for
named callers with the audience and tenant claim the service requires. Its summary lines are one
`curl` per seeded caller.

*Why not `RejectingJwtDecoderConfiguration` or a header backdoor:* a local run is called from
outside the JVM, so a principal cannot be injected, and a backdoor is code that must then be kept
out of an image. With a stand-in issuer the service runs its production decoder, audience check
and authority mapping, and "wrong audience is 401" is debuggable locally.
*Why in `notification-service` and not `test-support-security`:* one user. Putting it there would
also make every consumer of `test-support-security` acquire `test-support-local`.

### D5. Local configuration is a test resource

`src/test/resources/application-localrun.yml`, activated by `.profiles("localrun")`. It turns the
channels, the Kafka ingress, the identity projection and the poller on, sets the receipt and
webhook signing secrets to obvious local values, and gives `chat.base-url` its placeholder. The
profile is not named `local`, so it cannot be mistaken for, or collide with, a main-resources
profile. `src/main` is not touched.

### D6. Fixtures reuse what the tests already have

| Fixture | Runs after | Writes |
|---|---|---|
| `RecipientSeed` | - | the six recipient shapes, through `RecipientFixtures` |
| `PreferenceSeed` | `RecipientSeed` | the opt-out and the quiet hours |
| `NotificationSeed` | `PreferenceSeed` | one delivered and one queued notification, through the application service |
| `AnnouncementSeed` | `RecipientSeed` | one published announcement, through `AnnouncementService` |
| `IngressSeed` | `RecipientSeed` | one request on the ingress topic (`needsKafka`) |

Fixtures that call a secured service build their caller with `TestPrincipals`. The webhook
recipient's URL is read from `StubServers.stub("webhooks").baseUrl()`, which is what `none()`
addressing is for. Fixtures carry no stereotype and are named once in `NotificationFixtures`.

### D7. Two kinds of test, both new

- **Context tests** (`ChatOutageIT`, `SlowWebhookIT`): `@SpringBootTest`, the `localrun` profile,
  `@WithPartnerStubs(NotificationStubs.class)`, fixtures imported. They show a stand-in and a fault
  inside an ordinary integration test.
- **Launch tests** (`LocalNotificationApplicationIT`, `DuplicateIngressIT`): start the application
  through `LocalRun` on a random port and call it over HTTP with a minted token. They are what
  keeps the example itself alive.

`NotificationTestBase` is not changed and its tests keep running beside these in one JVM. That is
safe because the launcher does not register the shared PostgreSQL container as a bean.

### D8. The rule for a service with no rest clients

`LocalRunArchitectureTest extends LocalRunRulesTest` gives the package rule and, with no
`@LudwigRestClient` present, a vacuous client rule. The service adds one rule of its own beside
its existing in-app and announcement rules: only `NotificationChannelConfig` may call
`RestClient.Builder.build()`. A new outbound partner then has to be added in the class whose
partners `NotificationStubs` covers, where a reviewer sees both.

### D9. Dependency direction and POMs

**Does `test-support-local` already depend on `notification-service`, directly or transitively?**
`scripts/manifest.sh module sources/test-support-local`: it depends on `test-support` and
`rest-client-spring-boot-starter` only, and `notification-service` has no in-repo dependents. No
cycle.

Of the three structural POMs, **none changes**: no version is added to `ludwig-bom`, and neither
the root POM nor `ludwig-service-parent` is edited. `sources/test-support-local/pom.xml` gains
`greenmail-junit5` as `optional`; `services/notification-service/pom.xml` gains
`test-support-local`, `wiremock-standalone` and `archunit` at test scope, all without versions.

### D10. Conventions introduced, and the check for each

| Convention | Check | Owner |
|---|---|---|
| A stub's published property is configured by another source | launcher start-up check | behaviour test in `test-support-local` |
| `notification-service` builds outbound HTTP clients in one class | ArchUnit rule local to the service | ArchUnit |
| The local application lives in a `local` package | `LocalRunRulesTest` | ArchUnit |
| Local configuration lives under `src/test` | the profile file's location; `git diff --exit-code services/notification-service/src/main` in the gate tasks | filesystem; no build check is possible for "a file was not added to main", so the README states it |
| The reference example starts | `LocalNotificationApplicationIT` | behaviour test |

### D11. No second mechanism

No audit sink, operation envelope, cache, `ProblemDetail` handling, redaction mask or endpoint is
added. Seed data goes through the service's existing services, so the in-app and announcement
rules already enforced there apply to it.

## Risks / Trade-offs

- **The service may not start with everything on.** Nobody has run it with chat, webhook, both
  Kafka consumers and the poller enabled at once outside a deployment. → Task 6.3 is exactly that,
  before any fixture or stub is written; if it needs a `src/main` change, the task stops and the
  finding is recorded.
- **`dependencyConvergence` in `ludwig-service-parent`.** Three new test dependencies may split a
  version. → Resolved in `ludwig-bom` if it happens, which widens the gate to the whole reactor;
  the task says so.
- **Issuer discovery timing and claim mapping.** The decoder may fetch discovery at start-up, and
  the security starter maps authorities from claims this design has not read in detail. → Task 7.3
  proves a minted token is accepted and a wrong-audience one refused before the summary lines are
  written.
- **The hot-reload starter is enabled in the service** and may expect files or Vault. → Covered by
  6.3.
- **Renaming `clientName()` breaks the sample and its tests.** → Same change, same module; the API
  is marked provisional and has no other consumer.
- **Two changes open on one module.** `add-ludwig-test-fixtures` is unarchived. → Archive it first;
  this change's spec deltas are additions only so the order is the only constraint.
- **Start-up time.** PostgreSQL, Kafka and several Liquibase changelogs. → Accepted; the launch
  tests share the per-JVM containers with the rest of the suite.

## Open Questions

- Whether the stand-in issuer should move to a shared module when `crud-service-example` adopts
  the launcher. It changes nothing here.

## Implementation notes

Where the implementation departs from the decisions above, and what it found. Each is also in
`state.json`.

**Requirements this change does NOT meet, each blocked by a defect outside it**

- *"The identity projection SHALL be enabled."* It is off in `application-localrun.yml`. With
  `ludwig.identity.kafka.enabled=true` the service does not start:
  `IdentityKafkaAutoConfiguration` (`identity-projection-spring-boot-starter`) guards its listener
  container factory with `@ConditionalOnBean(ListenerContainerFactoryBuilder)` but is ordered only
  after `IdentityProjectionAutoConfiguration`, so it is evaluated before `MessagingAutoConfiguration`
  registers that builder. A deployment with `IDENTITY_PROJECTION_ENABLED=true` hits the same.
- *"A token with the wrong audience is 401."* It is 200. `AudienceValidatingJwtDecoderPostProcessor`
  (`security-spring-boot-starter`) only wraps a `NimbusJwtDecoder`; with an `issuer-uri` Boot's
  decoder bean is a `SupplierJwtDecoder`, so the audience validator is never installed.
  `LocalNotificationApplicationIT.wrongAudienceIsNotRejectedYet` pins the current behaviour. The
  local profile deliberately does not set Boot's own `jwt.audiences`, which would make the local
  run stricter than a deployment.

**Spec corrected to what the service actually does** (the delta spec has been edited)

- There is no webhook recipient: `DefaultRecipientResolver` has no per-user webhook address, and a
  request names the URL as an address recipient. No shipped template has a webhook variant either,
  so the local run adds `local-order-shipped` as a test resource.
- Announcements are served by `/api/v1/announcements`, not by the inbox.
- No opt-out or quiet-hours recipient is seeded: `user-settings-spring-boot-starter` is `provided`
  and excluded by the Spring Boot plugin, so the deployed service has no preference store.

**Decisions changed**

- **D6.** Recipients are written through `SecurityUserRepository`, not `RecipientFixtures`, which
  needs the excluded user-settings starter. Notifications and the announcement are submitted over
  the service's own REST API with a minted administrator token (`LocalApi`), not through
  application services; `PreferenceSeed` does not exist.
- **D7.** The launch tests call `LocalNotificationApplication.start(...)` and never touch `LocalRun`,
  so the package rule needs no exemption. The context tests extend `NotificationTestBase` unchanged.
- **D8.** The outbound-HTTP rule also covers `RestClient.create`, `RestClient.builder` and the JDK
  `HttpClient` factories.
- **D2.** The start-up check is two classes: `StubAddressCheck` (dead stub, always) and
  `StubCoverageCheck` (rest client without a stub, only with the rest-client starter).
- **D3.** A `SummaryContributor` interface was added so the mail section, and the service's
  "call the service" section, can be printed without the summary naming their types.
- **`spring-boot:test-run` needs `-Dspring-boot.run.main-class`**, and runs on the plugin's
  classpath, not failsafe's. Task 11.1 (run it by hand) is what found the user-settings problem;
  every integration test had passed.
