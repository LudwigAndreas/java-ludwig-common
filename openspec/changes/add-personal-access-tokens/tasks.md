## 1. Enforcement first, so the rules exist before the code they govern

Written first deliberately. A rule added after the implementation is a rule shaped to pass the code
that already exists, which is the opposite of what it is for. These tasks are expected to fail
against an empty repository and to start passing as section 3 lands.

- [x] 1.1 Add `CREDENTIALS("credentials", true)` to `RuleGroup` in `architecture-rules`, with javadoc
  stating what separates a legitimate second credential *format* from a second credential
  *mechanism* — the distinction is the whole rule, as `RuleGroup.OPERATIONS` and
  `RuleGroup.PRESENTATION` already do for theirs.
  Proves it: `mvn -pl :architecture-rules -am verify`
- [x] 1.2 Implement the four `CREDENTIALS` rules — no second PAT store/SPI/token format; reveal
  accessor fenced to `pat-core`'s token package and the issuance service; secret carrier never a
  logger or audit-attribute argument; exactly one constructor of a PAT-credentialed principal — each
  with a unit test asserting the rule fires on a deliberately violating fixture. A rule with no
  failing fixture has never been shown to fail.
  Proves it: `mvn -pl :architecture-rules -am verify`
- [x] 1.3 Add the id'd `RegexpSinglelineJava` rule for a weak digest algorithm name to
  `checkstyle-rules`, with a unit test in `ru.ludwigandreas.checkstyle.unit`.
  Proves it: `mvn -pl :checkstyle-rules -am verify`
- [x] 1.4 Record all **six** unmechanisable rules in `build/architecture-rules/README.md` and
  `README.ru.md` alongside the existing scope-boundary notes, so the group's own documentation says
  what it cannot see. The list is the backlog, not an apology — do not trim it to make the group look
  complete.
  Proves it: `openspec validate add-personal-access-tokens`
- [x] 1.5 Do **not** write an ArchUnit rule for the long-lived-connection revalidation requirement.
  Nothing in this repository holds a connection open, so the rule's matching set would be empty: it
  would pass vacuously, stay green after the rule had been broken elsewhere, and read as coverage.
  Instead record the enforcing test as an explicit obligation on the future MCP starter change — in
  `build/architecture-rules/README.md` and `README.ru.md` next to the deferral, and in this change's
  `state.json` `decisions` so a later session does not rediscover the reasoning.
  Proves it: `openspec validate add-personal-access-tokens`, and the deferral is present in both
  locale READMEs and in `decisions`

## 2. `pat-core` — the format, and nothing stateful

- [x] 2.1 Create `sources/pat-core/pom.xml`: library, parent `common` with
  `<relativePath>../../pom.xml</relativePath>`, imports `ludwig-bom`, **zero in-repo dependencies**,
  Lombok `provided` only. Add the `<module>` entry to the root `pom.xml`.
  Proves it: `scripts/manifest.sh layout && scripts/manifest.sh module sources/pat-core` reports
  `role library`, `parent POM common`, and `depends on (0)`
- [x] 2.2 `PatSecret` — masked `toString()`, a fenced reveal accessor, `equals`/`hashCode` that do
  not compare the secret, and a comment at the class stating that the masking is one of three partial
  mechanisms and why no complete one exists.
  Proves it: `mvn -pl :pat-core -am test -Dtest=PatSecretTest`
- [x] 2.3 The `lpat_<keyId>_<secret>_<crc32c>` mint and parse, using `SecureRandom` and
  `java.util.zip.CRC32C`. A comment at the checksum stating that it is not a security control.
  Proves it: `mvn -pl :pat-core -am test -Dtest=PatTokenFormatTest` — round-trip, rejected checksum,
  rejected prefix, rejected arity
- [x] 2.4 The digest and `MessageDigest.isEqual` comparison, with the comment required by the
  enforcement table: the choice of a plain digest over a password KDF holds **only** because the
  secret is full-entropy and machine-generated, and no check can assert that if the generator changes.
  Proves it: `mvn -pl :pat-core -am test -Dtest=PatDigestTest`
- [x] 2.5 `PatAttenuation` — the scope set as opaque strings, with the intersection expressed as a
  pure set operation over strings so that `pat-core` needs nothing from the security starter. This is
  what keeps the module at zero in-repo dependencies.
  Proves it: `mvn -pl :pat-core -am test -Dtest=PatAttenuationTest` — intersection, empty result,
  scope naming an absent authority, and the explicit assertion that no union is reachable
- [x] 2.6 `PatClaims` — the single definition of the `ludwig_pat` claim name and structure, read by
  both sides. Javadoc stating that a literal on either side would diverge into a silent privilege
  escalation rather than an error.
  Proves it: `mvn -pl :pat-core -am test -Dtest=PatClaimsTest`
- [x] 2.7 Ship the secret-scanner detection pattern as a resource, with a test applying it to a
  minted token and to near-miss strings.
  Proves it: `mvn -pl :pat-core -am test -Dtest=PatDetectionPatternTest`
- [x] 2.8 `PatProblemTypes` — the credential-rejected problem `type` URI and the PAT management
  location, as published constants. These live here rather than in the starter because the **edge**
  renders that response on the issuer's behalf and must configure against a definition rather than
  invent one — the same reason `PatClaims` lives here. Javadoc stating that no build in this
  repository can check what the edge actually returns.
  Proves it: `mvn -pl :pat-core -am test -Dtest=PatProblemTypesTest`
- [x] 2.9 `README.md` + `README.ru.md` stating the module's design decisions and what it deliberately
  leaves to others, per the repository's per-module README convention.
  Proves it: `openspec validate add-personal-access-tokens`

## 3. `security-spring-boot-starter` — the one service-side change

- [x] 3.1 Cross-check the record-component addition against the in-flight
  `add-api-compatibility-and-release-governance` change before editing `LudwigPrincipal`, and record
  the agreed compatibility classification in `state.json` `decisions`. Two changes classifying the
  same kind of edit differently is a merge conflict in the governance rules, not in the code.
  Proves it: `openspec show add-api-compatibility-and-release-governance` read, decision appended
- [x] 3.2 Add `Credential` and `CredentialKind` to `ru.ludwigandreas.security.principal`, and an
  **overloaded** `LudwigAuthentication(principal, credential)` constructor leaving the existing
  one-arg form untouched. Javadoc stating why this is on the authentication and not on the principal
  (a credential is how *this request* authenticated, not who the caller is) and why a fourth
  `PrincipalType` would silently break every `isType(USER)` check.
  Proves it: `mvn -pl :security-spring-boot-starter -am test -Dtest=LudwigAuthenticationTest` — the
  pre-existing constructor reports no credential, authorities are unchanged, and `getCredentials()`
  still returns null
- [x] 3.3 Confirm the addition is binary- and source-compatible: no existing signature changed, and
  every module still compiles against it. Replaces the canonical-constructor sweep the earlier design
  needed; a survey found zero `new LudwigPrincipal(` call sites and 9 `new LudwigAuthentication(`
  call sites, all of which keep using the one-arg form.
  Proves it: `mvn clean install -DskipTests` compiles every module, and
  `git diff sources/security-spring-boot-starter/src/main/java/ru/ludwigandreas/security/principal/LudwigPrincipal.java`
  is empty
- [x] 3.4 Teach `JwtPrincipalConverter` the `ludwig_pat` claim: resolve the owner's authorities
  through the existing `AuthorityLookup` unchanged, then intersect. Make this the only construction
  site of a PAT-credentialed principal, which is what rule 1.2's fourth clause enforces.
  Proves it: `mvn -pl :security-spring-boot-starter -am test -Dtest=PatAttenuatingConverterTest` —
  demoted owner, scope naming an absent authority, owner gaining an authority after issuance, and no
  claim at all
- [x] 3.5 `SecurityProperties` additions — including `revalidation-interval` — and the fail-fast
  startup validation computing **both** revocation-window compositions (request/response: assertion
  lifetime + edge cache + authority cache; long-lived: revalidation interval + authority cache),
  logging both and refusing to start when either exceeds the ceiling. In the style and with the same
  justification as the existing `require-audience` check. Computing only the first would log a
  correct-looking number while being false for the callers whose window is largest.
  Proves it: `mvn -pl :security-spring-boot-starter -am test -Dtest=RevocationWindowValidationTest` —
  each path over ceiling fails naming which path and every contributing value, both under ceiling
  logs both totals
- [x] 3.6 Confirm no change is needed to `AudienceValidator` or
  `AudienceValidatingJwtDecoderPostProcessor`: an audience-scoped PAT assertion is an ordinary `aud`
  claim and the existing validator already refuses one that does not name the service. If a change
  *is* needed, that is a finding to record in `state.json` `decisions` rather than a quiet edit, as
  the design claims this enforcement is free.
  Proves it: `mvn -pl :security-spring-boot-starter -am verify -Dtest=PatAudienceScopingIT` — an
  assertion minted for another audience is rejected with no new code in the validator path
- [x] 3.7 Extend `SecurityMetrics` and the denial audit record so a denial names both the PAT and the
  absent authority. This is what stops the first investigation at the owner's demotion rather than at
  the token.
  Proves it: `mvn -pl :security-spring-boot-starter -am test -Dtest=PatDenialAuditTest`
- [x] 3.8 Update `sources/security-spring-boot-starter/README.md` and `README.ru.md` — the credential
  dimension, the attenuation invariant, the precondition that PAT support requires an edge performing
  the exchange, and the long-lived-connection revalidation obligation a future streaming transport
  inherits.
  Proves it: `openspec validate add-personal-access-tokens`
- [x] 3.9 Run the full dependent set, because this is the module with seven in-repo dependents and
  they are the direction that catches a breaking change — `-am` builds dependencies, not dependents.
  Proves it: `mvn -pl :crud-service-example -am verify`,
  `mvn -pl :export-spring-boot-starter -am verify`,
  `mvn -pl :file-action-spring-boot-starter -am verify`,
  `mvn -pl :identity-projection-spring-boot-starter -am verify`,
  `mvn -pl :notification-service -am verify`,
  `mvn -pl :test-support-security -am verify`,
  `mvn -pl :user-settings-spring-boot-starter -am verify`

## 4. `pat-spring-boot-starter` — the issuer, out of the box

- [x] 4.1 Create `sources/pat-spring-boot-starter/pom.xml`: starter, parent `common` with
  `<relativePath>../../pom.xml</relativePath>`, imports `ludwig-bom`; depends on `pat-core`,
  `security-spring-boot-starter`, `db-core`, `web-core-spring-boot-starter`, `audit-core`,
  `cache-spring-boot-starter`, JPA, QueryDSL, `liquibase-core` and `postgresql`. Add the root
  `<module>` entry. **No `ludwig-bom` edit** — confirm every version is already managed.
  Proves it: `scripts/manifest.sh build && scripts/manifest.sh module sources/pat-spring-boot-starter`
  reports `role starter` and the expected dependency set; `git diff --exit-code build/ludwig-bom/pom.xml`
- [x] 4.2 The Liquibase changelog under `src/main/resources/db/changelog`: the `ludwig_pat` table with
  a unique index on each key id, the second key id/digest pair for rotation, scopes, **the audience
  set**, owner, expiry, revocation reason, CIDR allowlist, retention and debounced last-use columns.
  Proves it: `mvn -pl :pat-spring-boot-starter -am verify -Dtest=PatSchemaIT` — Testcontainers
  Postgres, changelog applies and is idempotent on re-run
- [x] 4.3 The entity and the QueryDSL repository. No SQL, no derived query methods. Add the inverse
  `SqlConfinementTest` asserting the module contains no SQL or JDBC **anywhere**.
  Proves it: `mvn -pl :pat-spring-boot-starter -am test -Dtest=SqlConfinementTest`
- [x] 4.4 `PatService` — issue, list, revoke, rotate-with-overlap, expire, purge. Mandatory expiry,
  maximum-lifetime ceiling, non-empty scope set, **non-empty audience set with no default**, and the
  fail-fast check that each scope is one the owner currently holds.
  Proves it: `mvn -pl :pat-spring-boot-starter -am test -Dtest=PatServiceTest` — one test per refusal
  named in the `personal-access-token` spec, including the refusal of an omitted audience set
- [x] 4.5 The `CacheDefinition` bean declaring `CachePurpose.SECURITY`, resolved from
  `LudwigCacheRegistry`. No Caffeine builder and no cache SPI of its own — `RuleGroup.CACHING` fails
  the build on either.
  Proves it: `mvn -pl :pat-spring-boot-starter -am test -Dtest=PatCacheDefinitionTest` — purpose is
  `SECURITY`, and a TTL above the security ceiling fails startup naming the cache
- [x] 4.6 The typed audit event records with `toAuditEvent()` for issuance, rotation, revocation,
  expiry, refused issuance, first use, dormant wake and unseen source. No audit SPI, no `*.audit`
  logger, no redaction mask, no `try`/`catch` around `record`.
  Proves it: `mvn -pl :pat-spring-boot-starter -am test -Dtest=PatAuditEventTest`, and
  `mvn -pl :pat-spring-boot-starter -am test -Dtest=ArchitectureRulesTest` with `RuleGroup.AUDIT`
  enabled
- [x] 4.7 Debounced, asynchronous, best-effort last-use tracking. A failure is logged and metered and
  never fails the exchange.
  Proves it: `mvn -pl :pat-spring-boot-starter -am test -Dtest=LastUsedTrackingTest` — debounce
  honoured, and a simulated write failure leaves the exchange successful
- [x] 4.8 The management REST API: three model layers wired by MapStruct, transactions in the service
  layer, `@PreAuthorize` for self-issuance versus issuance for another subject, and the credential
  guard refusing any PAT-credentialed caller with `403` before authorization is evaluated.
  Proves it: `mvn -pl :pat-spring-boot-starter -am verify -Dtest=PatManagementIT` — the `401`, the
  self-issue, the `403` for another subject, and the `403` for a PAT-backed caller on every endpoint
- [x] 4.9 The RFC 8693 exchange endpoint and the `PatAssertionMinter` SPI with its default JOSE
  implementation. The endpoint accepts the `resource`/`audience` parameter, refuses an audience the
  PAT does not permit, and mints an assertion whose `aud` names exactly that one audience — never
  none, never the PAT's whole set. The response declares its own cacheable lifetime. Startup fails
  when the exchange is enabled with no configured issuer and audience.
  Proves it: `mvn -pl :pat-spring-boot-starter -am verify -Dtest=TokenExchangeIT` — a valid exchange
  produces an assertion carrying `sub` and `ludwig_pat` **and no role or permission claim**; `aud` is
  the single requested audience; an unpermitted audience and an omitted audience both fail; a supplied
  minter bean replaces the default; a missing configured audience fails startup
- [x] 4.10 The uniform failure response. One status, one problem type, one body for unknown key id,
  bad secret, failed checksum, revoked, expired, refused source and unpermitted audience; the reason
  tagged in metrics and audit only.
  Proves it: `mvn -pl :pat-spring-boot-starter -am verify -Dtest=UniformExchangeFailureIT` — asserts
  the seven responses are **byte-for-byte identical** and that each is distinguishable in the metric
  tag
- [x] 4.11 The client-facing failure contract, which is a different response from 4.10 because the
  client called a service and the edge called the exchange. Document the required edge response
  verbatim in the README — `401`, `WWW-Authenticate: Bearer error="invalid_token"`, and the problem
  document built from `pat-core`'s published type and management location — and ship the
  `ProblemDetail` mapper that renders it so the edge can proxy the issuer's own rendering rather than
  construct one. State at the mapper that no build here can verify what the edge returns.
  Proves it: `mvn -pl :pat-spring-boot-starter -am verify -Dtest=CredentialRejectedProblemIT` — the
  rendered document carries the credential-rejected type and the management location, discloses no
  cause, and is distinct from the authorization-denied type
- [x] 4.12 Rate limiting per key id and per source, with the checksum rejected before any database
  access.
  Proves it: `mvn -pl :pat-spring-boot-starter -am verify -Dtest=ExchangeRateLimitIT` — per-key limit,
  per-source limit across many key ids, and a malformed token causing zero queries
- [x] 4.13 The `ProblemDetail` mappers contributed into `web-core`'s pipeline, with no
  `@RestControllerAdvice`, plus the i18n bundles
  `i18n/ludwig-pat-messages.properties` and `..._ru.properties`.
  Proves it: `mvn -pl :pat-spring-boot-starter -am verify -Dtest=PatProblemDetailIT`, and the bundle
  key-set parity check
- [x] 4.14 Listen for the identity projection's owner-disabled signal and mark that owner's PATs
  revoked with a distinguishing reason. Optional dependency on `messaging-spring-boot-starter`, as
  `identity-projection-spring-boot-starter` does for Kafka.
  Proves it: `mvn -pl :pat-spring-boot-starter -am test -Dtest=OwnerDisabledRevocationTest`
- [x] 4.15 Retention and purge: digests cleared at expiry or revocation, record retained, purged past
  retention, purge audited.
  Proves it: `mvn -pl :pat-spring-boot-starter -am verify -Dtest=PatRetentionIT`
- [x] 4.16 The autoconfiguration registration in
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, and
  `architecture-rules` enabled with `RuleGroup.CREDENTIALS`, `AUDIT`, `CACHING`, `PERSISTENCE` and
  `WEB` on, via a subclass of `ArchitectureRulesTest` plus
  `src/test/resources/architecture-rules.properties`.
  Proves it: `mvn -pl :pat-spring-boot-starter -am test -Dtest=PatArchitectureRulesTest`, with
  `target/architecture-report.json` written
- [x] 4.17 `README.md` + `README.ru.md`: the design decisions, the edge precondition, **both**
  revocation-window compositions and how to compute each for a deployment, the three edge
  obligations written out verbatim because no build can check them (exchange per destination
  audience; cache per `(secret digest, audience)` for the declared lifetime; render the
  credential-rejected `401`), a worked `curl` example showing the single static header, and the
  deployment checklist (signing key, changelog include, scanner registration of the `lpat_` format
  **before** the first token is issued).
  Proves it: `openspec validate add-personal-access-tokens`

## 5. Coverage and the aggregate report

- [x] 5.1 Confirm both new modules clear the enforced JaCoCo gate and appear in the aggregate report —
  a new jar module missing from it is a silent coverage hole.
  Proves it: `mvn clean install && python3 scripts/check_aggregate_report.py`

## 6. The verification gate, in full

The root `pom.xml` gains two `<module>` entries and is the parent of every library module, so there
is no narrower honest gate than a full install. The per-module commands above are the development
loop; this is the gate.

- [x] 6.1 `scripts/manifest.sh build` — POMs changed, so the manifest is stale until this runs
- [x] 6.2 `scripts/manifest.sh layout` — both new modules are in `sources/` as their role dictates
- [x] 6.3 `scripts/manifest.sh stale` exits 0
- [x] 6.4 `mvn -q validate` — Checkstyle on every module, including the new rule from 1.3
- [x] 6.5 `mvn clean install` — the full reactor, which subsumes every `-pl` build and every
  dependent named in section 3.8
- [x] 6.6 `openspec validate add-personal-access-tokens`
- [ ] 6.7 `scripts/gate.sh --change add-personal-access-tokens pat-core pat-spring-boot-starter security-spring-boot-starter architecture-rules checkstyle-rules`
  — run last as the independent check that the gate script agrees with the commands above, rather
  than instead of them
- [x] 6.8 Write `openspec/changes/add-personal-access-tokens/receipt.json` per `docs/agent-state.md`
  with the real gate output, test counts, retries and anything unresolved — filled in accurately
  even where unflattering, since those are the fields the receipt exists for
