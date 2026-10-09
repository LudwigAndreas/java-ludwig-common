## ADDED Requirements

### Requirement: A dynamic property path is built by the module that owns the vocabulary
QueryDSL's `PathBuilder` resolves a property path at runtime and is therefore the one construct in this
platform's data access that the compiler does not check. A repository SHALL NOT construct one to serve a
caller-supplied property path. Where a caller-supplied path must be resolved - the `$orderby` clause,
whose paths are strings by definition - the module that owns the path vocabulary SHALL perform the
resolution and SHALL expose the result, and the repository SHALL consume it.

#### Scenario: A repository needs to order by a path named in a request
- **WHEN** a repository must turn a caller's ordering clause into QueryDSL ordering expressions
- **THEN** it obtains them from the module that validated the paths, and contains no `PathBuilder`
  field, no segment-splitting loop and no raw-typed ordering construction. A repository that walks the
  path itself has re-implemented validated resolution without the validation, and has done it once per
  repository

#### Scenario: The same walk is written in two repositories
- **WHEN** two repositories each carry a private reflective path walk for the same purpose
- **THEN** that is a finding against this requirement regardless of whether either is wrong, because
  the walk's correctness condition - that it addresses the same query root and alias as the predicate -
  is invisible at each site and silently produces a cross join when broken

#### Scenario: A repository builds a path that no request named
- **WHEN** a repository needs a path that is fixed at compile time
- **THEN** it uses the generated Q-type, as the QueryDSL-only requirement already states. This
  requirement concerns only paths that arrive as strings from a caller

#### Scenario: How this is enforced
- **WHEN** this requirement is enforced
- **THEN** it is `architecture-rules`' `persistence.dynamic-paths-are-not-hand-built`, which forbids a
  dependency on QueryDSL's `PathBuilder` anywhere in the analysed packages - not only in the repository
  ones. There is no allowed package and no exemption list: the legitimate users are the library modules that
  own a caller-facing path vocabulary, and a service's analysis never imports their classes, so an exemption
  list could only ever be wrong in one direction

#### Scenario: The module that owns the vocabulary runs the rule against itself
- **WHEN** the module that legitimately builds the paths - `odata-filter-spring-boot-starter` - runs the
  shared rule library over its own packages
- **THEN** it disables this one rule, and that is not debt: the rule's own javadoc names that module as the
  one place the walk belongs. "Owns the vocabulary" is not a property bytecode carries, so it is recorded at
  the point of the disable rather than encoded in the rule

#### Scenario: The alias agreement cannot be checked statically
- **WHEN** the companion condition is considered - that the ordering expressions and the predicate address
  the same query alias
- **THEN** no static check can express it, because an alias is the *value* of a string a `Q`-type was
  constructed with. It is checked at runtime instead, by the executor, which refuses a mismatched root and
  names both aliases - and that assertion's javadoc says why it is where it is
