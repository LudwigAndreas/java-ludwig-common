## MODIFIED Requirements

### Requirement: There is exactly one code path that builds a PAT-credentialed authentication

The intersection SHALL be performed in exactly one place, and no other code SHALL be able to construct
a `LudwigAuthentication` carrying a personal-access-token credential. That place SHALL be a single
dedicated type, and every authentication path SHALL call it rather than reimplementing it.

**The requirement is unchanged; what changed is how it is enforced, and the change makes the rule
stricter.** With two authentication paths - the `ludwig_pat` claim reader and the direct filter - the
obvious response is to add the filter's package to `credentials.one-attenuation-path`. That **widens**
the rule: two packages today, three next year, and the invariant is gone by increments.

Instead the attenuation and principal construction move into one type that both callers use, and the
rule is re-pointed at that type alone - one class rather than a package. Neither caller computes
authority; each supplies a subject and a scope set and receives an authentication.

The reasoning the rule's own javadoc gives still applies and is why this matters: *the intersection is
one line, the union is also one line, and the difference between them is invisible in review.* That is
as true of a filter as it was of a converter.

#### Scenario: A second converter or filter tries to build a PAT-credentialed authentication
- **WHEN** a class other than the single dedicated construction type builds a `LudwigAuthentication`
  whose credential kind is the personal-access-token kind
- **THEN** the `architecture-rules` build fails, naming the class and the rule

#### Scenario: Both authentication paths are exercised
- **WHEN** a token-backed request arrives as an exchanged assertion, and another arrives as an `lpat_`
  credential through the filter
- **THEN** both produce the same effective authority for the same token and owner, because both went
  through the same construction

#### Scenario: The rule's scope is inspected after this change
- **THEN** it names one type rather than a package, which is narrower than before - a second
  authentication path must not mean a second place where authority is computed

### Requirement: Revocation is immediate at the issuer and bounded everywhere else

Revocation SHALL take effect at the issuer on the next exchange or introspection. The total time until a
revoked PAT can no longer be used anywhere SHALL be computed and logged at startup, and startup SHALL
fail when it exceeds `ludwig.security.pat.max-revocation-window`.

**Three** paths now compose differently and all three SHALL be computed, with the largest checked
against the ceiling:

- *Request/response callers via the edge* - assertion lifetime + edge exchange cache lifetime + service
  authority cache TTL.
- *Long-lived connections* - revalidation interval + service authority cache TTL.
- *Callers authenticated by the direct filter* - introspection cache TTL + service authority cache TTL.

Independently configured TTLs compose into the one number that matters, and nobody multiplies them out
in production. Computing only the first two would log a correct-looking number while being false for
exactly the callers the direct path creates.

#### Scenario: A revoked PAT is presented through either path
- **WHEN** a revoked PAT is presented to the exchange, or introspected by the filter
- **THEN** no authentication results, the failure is the uniform response for that endpoint, and a
  counter tagged as a revoked-token attempt is incremented

#### Scenario: Any composition exceeds the ceiling
- **THEN** the application fails to start, naming which path exceeded it, every contributing value and
  the total

#### Scenario: A deployment starts within the ceiling
- **THEN** all three computed totals are logged, so the number an operator needs during an incident
  exists in the record of every deployment rather than only in whoever last reasoned about it

#### Scenario: The direct filter is disabled
- **THEN** its composition is still computed and logged, because a deployment that enables the filter
  later should not discover its window for the first time at that point
