# The idempotency claim

## Purpose
`idempotency-spring-boot-starter` claims a key so that a retried request does the work once. The
claim has two modes, and choosing between them is a required decision at every call site because
picking the wrong one is a correctness bug that only appears under concurrency.

## Requirements

### Requirement: The claim mode is chosen explicitly at every call site
Every claim SHALL name its mode, `TRANSACTIONAL` or `STANDALONE`. There SHALL be no default.

#### Scenario: A consumer's whole unit of work is one transaction
- **WHEN** a Kafka consumer claims a key inside the transaction that does its work
- **THEN** `TRANSACTIONAL` is correct: the claim commits with the work, and a rollback frees the
  key. A duplicate is told "done, and here is the owning request's id"

#### Scenario: Work spans transactions or calls out
- **WHEN** the HTTP filter claims a key, or work spans transactions or makes outbound calls
- **THEN** `STANDALONE` is correct: the claim commits independently as
  `IN_PROGRESS → COMPLETED | FAILED` and is leased

### Requirement: TRANSACTIONAL refuses rather than degrades when no transaction is open
A `TRANSACTIONAL` claim SHALL fail when there is no active transaction.

#### Scenario: A TRANSACTIONAL claim is made outside a transaction
- **WHEN** no transaction is open
- **THEN** the claim throws rather than committing independently. Degrading is the failure the
  mode exists to prevent, and it would surface only as a key that cannot be retried after some
  unrelated rollback, days later

### Requirement: A STANDALONE claim is leased, and a failed claim is immediately reclaimable
A `STANDALONE` claim SHALL carry a lease that expires. A claim in `FAILED` SHALL be immediately
claimable again.

#### Scenario: A process dies mid-request holding a STANDALONE claim
- **WHEN** the holder dies before completing
- **THEN** the lease expires and the key becomes claimable. A key stuck `IN_PROGRESS` forever is
  a caller who can never retry, which is worse than the double execution it prevented because it
  is permanent

#### Scenario: A request fails and the caller retries
- **WHEN** an attempt is marked `FAILED` and the same key arrives again
- **THEN** it is claimed fresh. A failed attempt must not block the retry it exists to enable

### Requirement: The claim is a single conditional upsert
The claim SHALL be one `INSERT … ON CONFLICT (scope, idempotency_key) DO UPDATE … RETURNING`
statement.

#### Scenario: Two replicas claim the same key simultaneously
- **WHEN** both attempt the claim at once
- **THEN** exactly one wins, in one round trip, and the loser is told so by the returned row.
  Read-then-insert would let both insert and abort a transaction that has already written real
  work; `DO NOTHING` returns no row on conflict, so the loser must re-select — which under `READ
  COMMITTED` can still miss a row whose inserting transaction has not committed

### Requirement: The mode is a transaction boundary declared where the decision is made
The two modes SHALL be expressed with a `TransactionTemplate`, not with `@Transactional`.

#### Scenario: A claim method dispatches to an annotated method on itself
- **WHEN** a `claim` method calls an annotated `claimTransactionally` on `this`
- **THEN** the proxy is bypassed silently and only under concurrency, because `@Transactional` is
  applied by a proxy around calls arriving from outside the bean. The mode is a runtime value, so
  the boundary is declared where the decision is made

### Requirement: The HTTP surface is off by default and opted into per endpoint
The HTTP filter SHALL be disabled unless `ludwig.idempotency.http.enabled` is set, and an
endpoint SHALL opt in with `@Idempotent` or a configured path-and-method matcher.

#### Scenario: The module appears on a classpath
- **WHEN** a service adds the dependency without enabling the filter
- **THEN** no endpoint's behaviour changes. A filter that started deduplicating every matching
  endpoint on a dependency bump would change live APIs as a side effect

### Requirement: The HTTP filter has exactly four outcomes
For an opted-in endpoint the filter SHALL answer according to the key's state: a **fresh** key
runs the handler and stores its response; a **completed** key replays the stored response; an
**in-flight** key gets `409` with `Retry-After`; a **fingerprint mismatch** gets `422` naming the
key and never the first request's response.

#### Scenario: A caller retries a timed-out POST
- **WHEN** a duplicate arrives for a completed key
- **THEN** the original response is replayed in full — the `201`, its body, what was created and
  where — not a boolean saying "you already did this". Replay is what makes the feature real for
  an API

#### Scenario: A duplicate arrives while the first request is still running
- **WHEN** the key is `IN_PROGRESS`
- **THEN** `409` with `Retry-After`. Not a response that does not exist yet, and not a second
  execution

#### Scenario: The same key arrives with a different request body
- **WHEN** the fingerprint does not match the claim's
- **THEN** `422` naming the key, and the first request's response is never disclosed

### Requirement: Some responses are deliberately not stored
The filter SHALL NOT store a body truncated past `max-stored-response`, and SHALL treat a 4xx or
5xx as *the work did not happen* — marking the claim `FAILED` and freeing the key.

#### Scenario: The handler returns a 5xx
- **WHEN** an opted-in handler fails
- **THEN** the key is freed. A stored 5xx would make a transient failure permanent for that key

#### Scenario: The handler returns a 4xx and the client corrects the request
- **WHEN** a rejected request is corrected and retried under the same key
- **THEN** it succeeds, rather than being refused as a fingerprint mismatch against a stored
  rejection

#### Scenario: A 2xx response is too large to store
- **WHEN** a successful response exceeds `max-stored-response`
- **THEN** the claim **completes** — the work happened and must not repeat — and a later
  duplicate gets `409 ludwig.idempotency.error.not-replayable` with **no** `Retry-After`. That is
  a different code from the in-flight 409 on purpose: the two need opposite things from the
  caller, and a `Retry-After` here would produce a polite infinite loop

### Requirement: The filter renders problems through web-core, shipping no advice of its own
The `409` and `422` SHALL be RFC 9457 documents produced by `web-core`'s `ProblemDetailFactory`
and message bundles.

#### Scenario: The filter rejects a request
- **WHEN** the filter produces an error response
- **THEN** it has the same document shape as the shared advice produces, in the caller's
  language, as UTF-8 — reached by invoking the same beans directly, because a filter is outside
  Spring's per-handler exception handling
