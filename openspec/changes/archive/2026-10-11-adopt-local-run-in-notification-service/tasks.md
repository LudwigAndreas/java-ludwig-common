## 1. Durable state

- [ ] 1.1 Confirm `add-ludwig-test-fixtures` is archived, then write
  `openspec/changes/adopt-local-run-in-notification-service/state.json` with its `contract` block
  before the first edit, per `docs/agent-state.md`: goal, output, the constraints "nothing under
  `services/notification-service/src/main` changes" and "none of the three structural POMs
  changes", and `done_when` as the commands in group 12. Proves:
  `test -f openspec/specs/partner-stub/spec.md && python3 -c "import json;print(json.load(open('openspec/changes/adopt-local-run-in-notification-service/state.json'))['contract']['done_when'])"`

## 2. Stub addressing (`test-support-local`)

- [x] 2.1 Rename `PartnerStub.clientName()` to `name()`, add `StubAddress` (`restClient()`,
  `property(String)`, `none()`) and `PartnerStub.address()` defaulting to `restClient()`, and make
  `StubServers` publish by address. Update the sample and existing tests to the new names only.
  Proves: `mvn -pl :test-support-local -am verify`
- [x] 2.2 Generalise the dead-stub check to "the property a stub publishes is configured by another
  source", exempt `none()`, treat an empty value as not configured, and move it out of the
  rest-client-conditional configuration. Extend `StubCoverageIT`: a property-addressed stub is
  wired; one overriding an unset property is refused naming stub and property; a `none()` stub
  starts and overrides nothing; the check runs under `FilteredClassLoader` without the rest-client
  starter. Proves:
  `mvn -pl :test-support-local -am verify -Dit.test=StubCoverageIT -Dfailsafe.failIfNoSpecifiedTests=false`
- [x] 2.3 Extend `StubFaultIT` with one property-addressed stub made unavailable and reset after
  the method. Proves:
  `mvn -pl :test-support-local -am verify -Dit.test=StubFaultIT -Dfailsafe.failIfNoSpecifiedTests=false`
- [x] 2.4 Add `PartnerStub.summaryLines(RunningStub)` (default empty) and print them under the
  stub's entry. Extend `StartupSummaryIT`. Proves:
  `mvn -pl :test-support-local -am verify -Dit.test=StartupSummaryIT -Dfailsafe.failIfNoSpecifiedTests=false`

## 3. Local mail server (`test-support-local`)

- [x] 3.1 Add `greenmail-junit5` as an `optional` dependency in
  `sources/test-support-local/pom.xml`, then regenerate the manifest. Proves:
  `scripts/manifest.sh build && scripts/manifest.sh stale && mvn -q -pl :test-support-local -am validate`
- [x] 3.2 Add `LocalRun.mail()` and `LocalMailbox` (design D3): loopback SMTP and IMAP on allocated
  ports, `spring.mail.*` published first, the class-presence check, the summary lines. Add
  `LocalMailIT`: the sample sends a message that `LocalMailbox` returns with recipient and subject;
  an IMAP client reads it at the printed address; without `.mail()` no `spring.mail.host` is
  published; a filtered class loader fails before `main` is reached. Proves:
  `mvn -pl :test-support-local -am verify -Dit.test=LocalMailIT -Dfailsafe.failIfNoSpecifiedTests=false`
- [x] 3.3 Update `sources/test-support-local/README.md` and `README.ru.md`: stub addressing, mail,
  summary lines, and a pointer to `notification-service` as the reference example. Proves:
  `diff <(grep '^#' sources/test-support-local/README.md | sed 's/[^#].*//') <(grep '^#' sources/test-support-local/README.ru.md | sed 's/[^#].*//') && mvn -q validate`

## 4. Dependencies and local configuration (`notification-service`)

- [x] 4.1 In `services/notification-service/pom.xml`, add `test-support-local`,
  `wiremock-standalone` and `archunit` at test scope with no version, then regenerate the manifest.
  If `dependencyConvergence` fails, stop: the fix belongs in `ludwig-bom` and changes the gate to
  the whole reactor - record it in `state.json` before continuing. Proves:
  `scripts/manifest.sh build && scripts/manifest.sh stale && mvn -q -pl :notification-service -am validate`
- [x] 4.2 Add `src/test/resources/application-localrun.yml` (design D5). Proves:
  `git diff --exit-code services/notification-service/src/main && mvn -q -pl :notification-service -am test-compile`
- [x] 4.3 Add `local/LocalNotificationApplication` with PostgreSQL, Kafka, mail and the `localrun`
  profile only - no stubs, no fixtures yet - and `LocalNotificationApplicationIT` asserting
  `/actuator/health` is 200 on a random port with every channel, both consumers and the poller
  enabled. **If the service cannot start this way without a `src/main` change, stop and record
  it.** Proves:
  `mvn -pl :notification-service -am verify -Dit.test=LocalNotificationApplicationIT -Dfailsafe.failIfNoSpecifiedTests=false`

## 5. Stand-ins (`notification-service`)

- [x] 5.1 Add `ChatApiStub` (address `ludwig.notification.channels.chat.base-url`) and
  `WebhookReceiverStub` (address `none()`), and `NotificationStubs`. Proves:
  `mvn -q -pl :notification-service -am test-compile`
- [x] 5.2 Add `IdentityProviderStub` (design D4): discovery document, JWK set, token minting for a
  named caller with audience and tenant claim. Proves:
  `mvn -q -pl :notification-service -am test-compile`
- [x] 5.3 Wire the three stand-ins into `LocalNotificationApplication` and extend
  `LocalNotificationApplicationIT`: a minted administrator token is accepted on a protected
  endpoint; no token is 401; a token for another audience is 401. Proves:
  `mvn -pl :notification-service -am verify -Dit.test=LocalNotificationApplicationIT -Dfailsafe.failIfNoSpecifiedTests=false`
- [x] 5.4 Add the stub's summary lines - one `curl` per seeded caller and the API documentation
  address - and assert in `LocalNotificationApplicationIT` that the printed administrator command's
  URL and token, replayed by the test, return 200. Proves: the command in 5.3

## 6. Seed fixtures (`notification-service`)

- [x] 6.1 Add `RecipientSeed` and `PreferenceSeed` over `RecipientFixtures`, and
  `NotificationFixtures` naming every fixture. Proves:
  `mvn -q -pl :notification-service -am test-compile`
- [x] 6.2 Add `NotificationSeed`, `AnnouncementSeed` and `IngressSeed` (design D6). Proves:
  `mvn -q -pl :notification-service -am test-compile`
- [x] 6.3 Register the fixtures on `LocalNotificationApplication` and extend
  `LocalNotificationApplicationIT`: the seeded user's inbox returns the seeded notification and the
  announcement; the ingress record produced a delivery; submitting for the email-and-chat recipient
  reaches `LocalMailbox` and the chat stand-in; submitting for the webhook recipient records one
  signed request; closing and relaunching leaves the same rows. Proves: the command in 5.3

## 7. Scenario tests (`notification-service`)

- [x] 7.1 Add `ChatOutageIT` (context test, design D7): unavailable -> retryable failure with a
  next attempt; cleared and retried -> delivered. Proves:
  `mvn -pl :notification-service -am verify -Dit.test=ChatOutageIT -Dfailsafe.failIfNoSpecifiedTests=false`
- [x] 7.2 Add `SlowWebhookIT`: slow beyond the read timeout -> retryable failure, and the call
  returns within the configured timeout plus a margin. Proves:
  `mvn -pl :notification-service -am verify -Dit.test=SlowWebhookIT -Dfailsafe.failIfNoSpecifiedTests=false`
- [x] 7.3 Add `DuplicateIngressIT` (launch test): one request delivered twice with
  `KafkaDeliveries.duplicate` yields one notification and one message in `LocalMailbox`. Proves:
  `mvn -pl :notification-service -am verify -Dit.test=DuplicateIngressIT -Dfailsafe.failIfNoSpecifiedTests=false`

## 8. Architecture rules (`notification-service`)

- [x] 8.1 Add `architecture/LocalRunArchitectureTest extends LocalRunRulesTest` over
  `ru.ludwigandreas.notification` and `NotificationStubs`. Proves:
  `mvn -pl :notification-service -am test -Dtest=LocalRunArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false`
- [x] 8.2 Add the service-local rule "only `NotificationChannelConfig` calls
  `RestClient.Builder.build()`" beside the existing in-app and announcement rules, with a self-test
  that evaluates it against a violating fixture class. First confirm with the code index that no
  other main class does so today; if one does, record it as a finding and name it in the rule's
  allowed set with the reason. Proves:
  `mvn -pl :notification-service -am test -Dtest='*OutboundHttp*' -Dsurefire.failIfNoSpecifiedTests=false`

## 9. Documentation (`notification-service`)

- [x] 9.1 Add "Running locally" to `services/notification-service/README.md` and `README.ru.md`
  with the same headings: the command, a sample of the summary, calling it, the seeded callers and
  recipients, making the chat API fail at a breakpoint, reading sent mail, the fixture modes, and
  that local configuration lives under `src/test`. Proves:
  `diff <(grep '^#' services/notification-service/README.md | sed 's/[^#].*//') <(grep '^#' services/notification-service/README.ru.md | sed 's/[^#].*//') && scripts/check_image_pins.sh && mvn -q validate`

## 10. Repository documentation

- [x] 10.1 Add entries under `## [Unreleased]` in `CHANGELOG.md` and `CHANGELOG.ru.md`: the local
  application of `notification-service`, and the `test-support-local` API change. Proves:
  `diff <(grep '^## ' CHANGELOG.md) <(grep '^## ' CHANGELOG.ru.md) && grep -c "LocalNotificationApplication" CHANGELOG.md CHANGELOG.ru.md`
- [x] 10.2 Add the outbound-HTTP rule and the generalised dead-stub check to the "Enforced" table in
  `docs/harness-enforcement.md`, and a "local development" pointer to the service README in
  `docs/runbook.md`. Proves:
  `grep -c "NotificationChannelConfig" docs/harness-enforcement.md && grep -c "spring-boot:test-run" docs/runbook.md`

## 11. Run it once by hand

- [x] 11.1 Start `mvn -pl :notification-service spring-boot:test-run`, paste the printed
  administrator command, set the chat fault with the printed command, submit a chat notification,
  clear the fault, and record the observed outputs in `state.json` `decisions`. This is the one
  check no test replaces: that the printed text is usable by a person. Proves:
  `python3 -c "import json;d=json.load(open('openspec/changes/adopt-local-run-in-notification-service/state.json'))['decisions'];assert any('11.1' in x for x in d)"`

## 12. Verification gate

- [x] 12.1 Nothing under the service's main sources changed. Proves:
  `git diff --exit-code a1bc188 -- services/notification-service/src/main`
- [x] 12.2 Manifest. Proves:
  `scripts/manifest.sh build && scripts/manifest.sh stale && scripts/manifest.sh layout`
- [x] 12.3 Checkstyle on every module. Proves: `mvn -q validate`
- [ ] 12.4 The gate for both touched modules and their dependents. Proves:
  `scripts/gate.sh --change adopt-local-run-in-notification-service test-support-local notification-service`
- [x] 12.5 The change validates. Proves:
  `openspec validate adopt-local-run-in-notification-service --strict`
- [x] 12.6 Write `openspec/changes/adopt-local-run-in-notification-service/receipt.json` per
  `docs/agent-state.md`. Proves:
  `python3 -c "import json;json.load(open('openspec/changes/adopt-local-run-in-notification-service/receipt.json'))"`
