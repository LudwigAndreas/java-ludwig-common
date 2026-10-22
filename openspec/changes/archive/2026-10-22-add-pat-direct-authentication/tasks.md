## 0. The precondition that is not code

- [ ] 0.1 Confirm a route exists by which machine traffic reaches services **without** traversing the
  company session gateway - a separate hostname, a path prefix, or a dedicated ingress. A request with
  no session is redirected to OIDC, which is fatal for `curl`, for CI and for an MCP client, so nothing
  below functions without this. If it does not exist, stop and report: this is infrastructure work and
  is not in this repository.
  Proves it: a `curl` carrying `Authorization: Bearer <anything>` against a service through that route
  returns that service's own `401` rather than a `302` to the OIDC provider

## 1. Enforcement first, and narrowing rather than widening

Written before the code it governs, deliberately. A rule added afterwards is a rule shaped to pass what
already exists.

- [x] 1.1 Extract the attenuation and the principal construction out of `JwtPrincipalConverter` into one
  dedicated type - `ru.ludwigandreas.security.authn.attenuation.AttenuatedAuthentications` - taking a
  subject, the owner's resolved authorities, a scope set and a token id, and returning a
  `LudwigAuthentication`. The converter becomes a caller. **No behaviour change**: the existing
  converter tests are the proof, and they are not to be edited.
  Proves it: `mvn -pl :security-spring-boot-starter -am test -Dtest=PatAttenuatingConverterTest` passes
  **unmodified** - all 11 assertions, including the demoted owner, the absent authority, and the
  malformed claim failing closed
- [x] 1.2 Re-point `credentials.one-attenuation-path` at that single type instead of at
  `ru.ludwigandreas.security.authn.jwt..`, and record in the rule's javadoc that this is a narrowing -
  one class rather than a package - and why the alternative (adding the filter's package) was refused.
  Proves it: `mvn -pl :architecture-rules -am verify`, and
  `mvn -pl :pat-spring-boot-starter -am test -Dtest=PatArchitectureRulesTest` still reports
  `56 rules | 56 passed`
- [x] 1.3 Add a violating fixture to `architecture-rules` for the new shape: a class outside the
  construction type that builds a credential-backed authentication. A rule with no failing fixture has
  never been shown to fail.
  Proves it: `mvn -pl :architecture-rules -am test -Dtest=CredentialRulesTest` - the rule fires on the
  fixture and stays silent on the compliant one
- [x] 1.4 Record the two new unmechanisable conventions in `build/architecture-rules/README.md` **and**
  `README.ru.md`, taking the credential area's list from six to eight. Do not trim the list to make the
  group look complete.
  Proves it: `openspec validate add-pat-direct-authentication`, and both locale READMEs name eight

## 2. `pat-core` - the introspection shape, defined once

- [x] 2.1 Add the introspection request and response records. RFC 7662 field names (`active`, `sub`,
  `scope`, `aud`, `exp`) plus `pat_id`. **No secret, no digest, no key id** - and a comment at the type
  saying the key id is excluded because it is secret-adjacent lookup material that changes on rotation.
  No new dependency: `pat-core` stays at zero in-repo dependencies.
  Proves it: `mvn -pl :pat-core -am test -Dtest=PatIntrospectionTest` - round-trip, the inactive form,
  and a reflective assertion over the response's components that none is credential material
- [x] 2.2 Update `pat-core`'s `README.md` and `README.ru.md` with the introspection shape and why it
  lives here rather than in either module that uses it - the same seam argument as `PatClaims`.
  Proves it: `openspec validate add-pat-direct-authentication`

## 3. `pat-spring-boot-starter` - the introspection endpoint

- [x] 3.1 Add the RFC 7662-shaped `/introspect` endpoint, reusing `PatVerifier` **unchanged** so the
  parse, checksum, point read, constant-time comparison, revocation, expiry, CIDR allowlist and
  `CachePurpose.SECURITY` cache all apply exactly as they do to the exchange. Path configurable under
  `ludwig.pat.introspection.path`.
  Proves it: `mvn -pl :pat-spring-boot-starter -am verify -Dtest=PatIntrospectionIT` - a live token
  reports active with its owner, scopes, audiences and expiry
- [x] 3.2 Every failure answers `{"active": false}` identically, with the reason going to
  `PatMetrics.recordExchangeFailure` and the audit sink only.
  Proves it: `mvn -pl :pat-spring-boot-starter -am verify -Dtest=UniformIntrospectionFailureIT` -
  asserts the six responses (unknown key, bad secret, malformed, revoked, expired, refused source) are
  **byte-for-byte identical** and that each is distinguishable in the metric tag. Model it on
  `UniformExchangeFailureIT`, including normalising the per-request timestamp
- [x] 3.3 Apply the existing credential guard to the endpoint, so a PAT-derived authentication cannot
  introspect. A credential that can operate on credentials makes revoking the original pointless.
  Proves it: `mvn -pl :pat-spring-boot-starter -am verify -Dtest=PatIntrospectionIT` - a token-backed
  caller is refused with `403` and the credential-not-permitted problem code, not merely a 403
- [x] 3.4 Add the endpoint to the module's `README.md` and `README.ru.md`, next to the exchange, with
  the sentence that it exists because this platform's edge cannot perform the exchange and that it is
  removable.
  Proves it: `openspec validate add-pat-direct-authentication`

## 4. `security-spring-boot-starter` - the filter

- [x] 4.1 Add the introspection client using Spring Framework's `RestClient`. **No new dependency** -
  it arrives through `spring-boot-starter-web`, which this module already has. Deliberately not
  `rest-client-spring-boot-starter`: it would put a sizeable module on the classpath of the one nearest
  the bottom of the reactor, for one POST, and every consumer of the platform pays that.
  Proves it: `git diff --exit-code sources/security-spring-boot-starter/pom.xml` is empty, and
  `mvn -pl :security-spring-boot-starter -am test -Dtest=PatIntrospectionClientTest`
- [x] 4.2 Declare the introspection cache as a `CacheDefinition` with `CachePurpose.SECURITY`, resolved
  from `LudwigCacheRegistry`. The TTL *is* the revocation window for this path, which is what that
  purpose means; `PERFORMANCE` would remove the startup ceiling and admit stale reads while reading as
  a throughput win.
  Proves it: `mvn -pl :security-spring-boot-starter -am test -Dtest=PatIntrospectionCacheTest` - the
  purpose is `SECURITY`, and a TTL above the security ceiling fails startup naming the cache
- [x] 4.3 Add `PatAuthenticationFilter`: recognise the `lpat_` prefix, introspect, and call
  `AttenuatedAuthentications` from 1.1. It computes no authority itself. Leave an existing
  authentication alone, and pass a non-`lpat_` credential through untouched.
  Proves it: `mvn -pl :security-spring-boot-starter -am test -Dtest=PatAuthenticationFilterTest` -
  a valid token authenticates with the intersected authority; an inactive one does not authenticate; an
  ordinary JWT passes through; an already-authenticated request is untouched
- [x] 4.4 Register it in `ResourceServerAutoConfiguration` after the stripping filter, **off** unless
  `ludwig.security.pat.filter.enabled`. Extend the chain-ordering javadoc from four filters to five.
  **Revised during implementation**: this task said "and before the bearer filter", making the mTLS
  filter's argument. That is not achievable and would not have helped - Spring Security orders
  `BearerTokenAuthenticationFilter` ahead of `BasicAuthenticationFilter` (so the position this module
  uses for every filter, stripping included, is *after* it), and that filter authenticates
  unconditionally without checking for an existing authentication, so it would have answered `401` on
  the `lpat_` credential regardless of what ran before it. The fix is
  `PatAwareBearerTokenResolver`, installed only when the filter is enabled: the resource server is told
  there is no bearer token here, which is true. Found by the chain assertion below, which is exactly
  why the task demanded the chain rather than the configuration.
  Proves it: `mvn -pl :security-spring-boot-starter -am test -Dtest=SecurityAutoConfigurationTest` -
  no filter and no default-resolver override by default; enabled without an issuer URL fails startup;
  and when enabled the filter sits after the stripping filter and before the authorization filter,
  asserted against `SecurityFilterChain.getFilters()`
- [x] 4.4a `PatAwareBearerTokenResolver`: delegates to `DefaultBearerTokenResolver` and reports no token
  for an `lpat_` credential. Delegation rather than reimplementation, so the form-parameter and
  two-places-at-once decisions are not re-made by accident.
  Proves it: `mvn -pl :security-spring-boot-starter -am test -Dtest=PatAwareBearerTokenResolverTest`
- [x] 4.5 Extend `RevocationWindowValidator` with the third composition (introspection cache TTL +
  authority cache TTL), computed and logged whether or not the filter is enabled, with the largest of
  the three checked against the ceiling.
  Proves it: `mvn -pl :security-spring-boot-starter -am test -Dtest=RevocationWindowValidationTest` -
  three totals logged; the new path alone over the ceiling fails startup naming it; and the existing
  six assertions still pass
- [x] 4.6 Add a comment at the property stating that this path is scaffolding for a platform whose edge
  cannot exchange a token, and that it is to be removed when the edge can - one of the two new
  unmechanisable conventions.
  Proves it: `openspec validate add-pat-direct-authentication`
- [x] 4.7 Update `sources/security-spring-boot-starter/README.md` and `README.ru.md`: the second
  authentication path, the three revocation-window compositions, the availability trade-off stated
  plainly (issuer down means PATs stop within the introspection cache TTL, against an assertion
  lifetime on the edge route), and the one-property removal.
  Proves it: `openspec validate add-pat-direct-authentication`

## 5. End to end

- [x] 5.1 An integration test with the filter enabled: a token issued through the management API
  authenticates a request to a protected endpoint, with the authority being the intersection; a revoked
  token stops working; and the denial record names the token.
  Proves it: `mvn -pl :pat-spring-boot-starter -am verify -Dtest=PatDirectAuthenticationIT`
- [x] 5.2 Assert the removal path: with the property off, an `lpat_` credential is unauthenticated and
  an exchanged assertion carrying `ludwig_pat` still authenticates with the same effective authority.
  This is the test that makes "scaffolding" true rather than claimed.
  Proves it: `mvn -pl :pat-spring-boot-starter -am verify -Dit.test=PatDirectAuthenticationIT` - both
  configurations in one suite, 6/6
- [x] 5.3 **Added during implementation.** The suite found a second defect neither half could show: the
  introspection endpoint sat behind `anyRequest().authenticated()` while the client that calls it carries
  no credential of its own, so every introspection got a `401` and failed closed. Extended
  `PatExchangeConfigurationValidator` to warn when the mounted introspection path is not in
  `ludwig.security.public-paths`, exactly as it already does for the exchange, and documented why public is
  correct here rather than a weakening - the exchange is already public and discloses strictly more.
  Proves it: `mvn -pl :pat-spring-boot-starter -am verify -Dit.test=PatDirectAuthenticationIT`

## 6. The verification gate, in full

No POM changes, so this is **not** a full-install gate. Confirm that first - if any POM changed, the
gate widens to `mvn clean install` and `scripts/manifest.sh build` is required before it.

- [x] 6.1 `git diff --exit-code -- '**/pom.xml'` is empty, and `scripts/manifest.sh stale` exits 0
- [x] 6.2 `mvn -q validate`
- [x] 6.3 `mvn -pl :pat-core -am verify`
- [x] 6.4 `mvn -pl :pat-spring-boot-starter -am verify`
- [x] 6.5 `mvn -pl :security-spring-boot-starter -am verify`
- [x] 6.6 `mvn -pl :architecture-rules -am verify`
- [x] 6.7 Every in-repo dependent, which is the direction that catches a breaking change - `-am` builds
  dependencies, not dependents: `mvn -pl :crud-service-example -am verify`,
  `mvn -pl :export-spring-boot-starter -am verify`,
  `mvn -pl :file-action-spring-boot-starter -am verify`,
  `mvn -pl :file-ingest-spring-boot-starter -am verify`,
  `mvn -pl :identity-projection-spring-boot-starter -am verify`,
  `mvn -pl :messaging-spring-boot-starter -am verify`,
  `mvn -pl :notification-service -am verify`,
  `mvn -pl :object-storage-spring-boot-starter -am verify`,
  `mvn -pl :test-support-security -am verify`,
  `mvn -pl :user-settings-spring-boot-starter -am verify`
- [x] 6.8 `scripts/check_image_pins.sh`
- [x] 6.9 `scripts/check_api_baseline.sh` — **FAILED, as expected in this environment**
  because the releases repository was unreachable. If it still cannot resolve a baseline, record that
  API compatibility is unverified rather than reporting the gate as passing; the script's own message is
  the reason. `AttenuatedAuthentications` is new rather than a changed signature, so nothing here is
  expected to be breaking - but "expected" is not "checked"
- [x] 6.10 `openspec validate add-pat-direct-authentication`
- [x] 6.11 `scripts/gate.sh --change add-pat-direct-authentication pat-core pat-spring-boot-starter security-spring-boot-starter architecture-rules`
  — run last as an independent check that the gate script agrees with the commands above, not instead of
  them. **Result: FAILED at `scripts/check_api_baseline.sh`, exit 1, and stopped there**, so it never
  reached validate or verify. That is the script working rather than a new problem - it refuses to report
  a pass on a baseline it could not resolve. Its `--list` output is a subset of the commands run by hand
  above, so the two agree on what the gate is. **The gate as a whole is not green**, and task 6.9 is why
- [x] 6.12 Write `openspec/changes/add-pat-direct-authentication/receipt.json` per `docs/agent-state.md`
  with the real gate output, test counts, retries and anything unresolved - filled in accurately even
  where unflattering, since those are the fields the receipt exists for. Note the JDK used: Maven
  defaults to 26 on this machine and JaCoCo 0.8.12 cannot instrument JDK-26 locale classes, which fails
  Spring context loading in integration tests
