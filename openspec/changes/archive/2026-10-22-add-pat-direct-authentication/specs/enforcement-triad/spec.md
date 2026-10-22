## MODIFIED Requirements

### Requirement: `RuleGroup.CREDENTIALS` owns the credential-handling structural rules

`architecture-rules`' `CREDENTIALS` group SHALL carry the structural rules that make the PAT attenuation
invariant mechanical. The rule fencing construction of a credential-backed authentication SHALL name a
**single type**, not a package.

**Narrowed by this change rather than widened**, and the direction is the point. Adding a second
authentication path created pressure to add a second package to the rule. Extracting the construction
into one type instead meant the rule could name that type - which is stricter than what it replaced.

A rule that grows a package each time a caller is added is a rule that expires quietly. One that names
a single type cannot be satisfied by adding a caller; it can only be satisfied by routing through it.

#### Scenario: A second authentication path is added
- **WHEN** a change introduces another way to authenticate a credential
- **THEN** it calls the single construction type, and the rule is unchanged. A change that instead adds
  a package to the rule is a finding, not a fix

#### Scenario: A module declares a second PAT store, SPI or token format
- **THEN** the `CREDENTIALS` group fails the build naming the type, unchanged by this change

#### Scenario: Raw secret material is read from an unpermitted package
- **THEN** the build fails naming the class, unchanged by this change

#### Scenario: The secret carrier is passed to a logger or an audit attribute
- **WHEN** the secret carrier type is passed as an argument to an SLF4J logging method or into an
  audit event attribute
- **THEN** the build fails. The rule matches on the **typed** parameter, which is what makes it
  checkable at all; a raw `String` that has already escaped the carrier defeats it, which is why the
  carrier's own `toString()` masks and why the reveal accessor is fenced as well - three partial
  mechanisms rather than one complete one, because no complete one exists

#### Scenario: A second code path constructs a PAT-credentialed principal
- **WHEN** any class other than the designated converter constructs a principal carrying the
  personal-access-token credential kind
- **THEN** the build fails. This is the rule that holds the attenuation invariant: the intersection
  cannot be bypassed if there is exactly one constructor of such a principal and it performs the
  intersection

## ADDED Requirements

### Requirement: Two further conventions in this area cannot be checked, and are recorded

The conventions this change introduces that no build can hold SHALL be recorded with their reasons,
bringing the credential area's unmechanisable list from six to eight.

#### Scenario: The two new unmechanisable rules are enumerated
- **THEN** they are:
  1. **Machine traffic must bypass the company session gateway.** Not in this repository and not
     expressible in it. A service cannot tell how it was reached, so not even a startup warning is
     available. The mitigation is the module README and the fact that the failure is unmistakable - an
     OIDC redirect in answer to a `curl`.
  2. **This capability is meant to be deleted.** No check can assert that a deployment removed the
     filter once its edge gained PAT support. A deprecation note on the property is the whole
     mitigation, and it will be read by whoever is already looking at the property rather than by
     whoever should be.

#### Scenario: A reader looks for the reasons at the code rather than in the spec
- **WHEN** either site is read in source
- **THEN** a comment at that site states why no check is possible, rather than the reason living only
  here where the next person to change the code will not look
