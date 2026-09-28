# jira-client

***English** · [Русский](README.ru.md)*

A type-safe Java client for the **Jira Server / Data Center REST API v2**, targeting
**Jira Server 9.12.27 (buildNumber 9120027)**, plus the **ALM Works Structure 2.0 REST API**.

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>jira-client</artifactId>
</dependency>
```

```java
try (JiraClient jira = JiraClient.builder()
        .baseUrl("https://jira.example.com")
        .personalAccessToken(System.getenv("JIRA_TOKEN"))
        .verifyOnBuild(true)
        .build()) {

    jira.search()
            .searchAll(JqlQuery.builder()
                    .where(Jql.project().in("OPS", "PLAT"))
                    .where(Jql.status().notIn("Done", "Closed"))
                    .where(Jql.created().after("-30d"))
                    .orderByStableWalk()
                    .build())
            .forEach(issue -> log.info("{} {}", issue.key(), issue.fieldsOrEmpty().summary()));
}
```

---

## What this module is, and what it deliberately is not

It is a **plain library**. No Spring dependency, no auto-configuration, no starter. The only two
runtime dependencies are Jackson and the SLF4J API; the transport is `java.net.http.HttpClient` from
the JDK. That is a decision, not an omission: a Jira client is used from Spring services, batch jobs,
CLI tools and tests, and dragging a second HTTP stack plus an auto-configuration into all of them buys
nothing. A service that wants it as a bean writes one `@Bean` method — see
[Using it from Spring](#using-it-from-spring).

It targets **Server 9.12**, not Cloud. The two APIs diverged and the differences are not cosmetic:

| | Jira Server 9.12 (this client) | Jira Cloud |
|---|---|---|
| Base path | `/rest/api/2` | `/rest/api/3` |
| User identity | `name` (username) and `key` | `accountId` |
| Rich text | wiki markup, a plain `String` | Atlassian Document Format |
| Search pagination | `startAt` / `maxResults` | `nextPageToken` cursor |
| Tokens | personal access tokens (8.14+) | API tokens, OAuth 2.0 |

Every one of those is modelled the Server way here. The client mostly works against Cloud and its
username handling does not; `verifyConnection()` warns when it finds a Cloud deployment behind the
base URL.

---

## Design

### The layers

```
JiraClient                    facade: issues(), search(), projects(), structures(), ...
  └── JiraRestClient          URI resolution, auth, interceptors, retries, error mapping, JSON
        └── JiraTransport     one method: send a request, return a response
              └── JdkJiraTransport   java.net.http.HttpClient
```

Each boundary exists because something has to be replaceable at it. `JiraTransport` so a team already
standardized on Apache HttpClient, or needing a mutual-TLS wrapper, or recording fixtures in a test,
can swap the bottom without touching anything above. `JiraRestClient` because **no client library
models every endpoint** — every marketplace app adds a `/rest` namespace of its own — and a library
with no way past its own model forces its users to stand up a second HTTP client beside it, with a
second copy of the authentication, the retries and the error handling:

```java
JsonNode boards = jira.rest()
        .get("/rest/agile/1.0/board")
        .query("projectKeyOrId", "OPS")
        .operation("agile.board.list")
        .asTree();
```

That call gets the same credentials, the same retry policy, the same metrics and the same exception
mapping as a modelled one.

### Errors

Every failure is an unchecked `JiraException`. The subclasses are the ones a caller has a different
reaction to:

| Exception | When | What to do |
|---|---|---|
| `JiraAuthenticationException` | 401 | Never retried — repeating a 401 is how a service account gets CAPTCHA-locked |
| `JiraAuthorizationException` | 403 | Check `mypermissions`; may also be a CAPTCHA challenge |
| `JiraNotFoundException` | 404 | Means "not visible to this account" as often as "does not exist" — Jira hides existence from users who may not browse |
| `JiraConflictException` | 409, stale Structure forest version | Re-read and reapply; do not re-send |
| `JiraRateLimitException` | 429 | Carries `Retry-After`; reduce concurrency |
| `JiraApiException` | any other non-2xx | Carries Jira's `errorMessages` and per-field `errors` |
| `JiraTransportException` | no response at all | Safe to retry a read; a write may already have been applied |
| `JiraSerializationException` | body did not match the model | Usually a proxy's HTML page, or a Jira version change |

`JiraApiException` parses Jira's error collection, so `getFieldErrors().get("summary")` answers
"Summary is required." without re-parsing the body.

### Retries

Three attempts, 500 ms base, exponential, **full jitter**, capped at 20 s, and only for:

- **idempotent methods.** A `POST` that timed out may already have created an issue, and Jira offers
  no idempotency key that would make re-sending it safe. `/search` and Structure's `/value` opt back
  in individually, because they are reads that happen to be `POST`s.
- **429, 502, 503, 504** and transport failures. **500 is excluded on purpose** — Jira returns it for a
  server-side bug on a request that will fail identically every time, and retrying triples the load on
  an instance that is already struggling.

Jitter is not decoration. When a Jira node restarts, every client mid-request retries at once; an
unjittered backoff reconverges them into a second thundering herd at exactly the same instant.

A server-supplied `Retry-After` always wins, capped so a proxy asking for an hour cannot park a
request thread for an hour.

---

## JQL

A builder whose **types decide which operators exist**. A text field gets `~`, a date field gets `>=`,
and neither gets the other's — because Jira rejects `summary >= "x"` at query time and this library
would rather that be a compile error.

```java
String jql = JqlQuery.builder()
        .where(Jql.project().is("OPS"))
        .where(Jql.assignee().inGroup("platform-team"))
        .where(Jql.status().changedTo("Done").after("-7d"))
        .where(Jql.summary().contains(userSuppliedText))
        .whereIf(onlyUnresolved, Jql.resolution().isEmpty())
        .where(Jql.numberCustomFieldById(10004).atLeast(3))
        .orderByStableWalk()
        .render();
```

**Every value is escaped.** `userSuppliedText` above cannot break out of its literal, whatever quotes
and backslashes it carries — which is JQL injection, and it is reachable from any search box wired
straight through to a query. Field names are quoted when JQL requires it, including the ones that
collide with a reserved word.

`orderByStableWalk()` appends `ORDER BY created ASC, issuekey ASC`. Worth understanding why it exists:
Jira pages by offset, so a walk ordered by `updated` reads a result set that reorders underneath it,
and rows get returned twice or missed entirely. Ordering by keys that do not change is what makes a
paged export complete.

The escape hatches are `JqlValue.raw(...)` for a construct the builder does not model and
`JqlFunction.of(name, args...)` for a marketplace app's function — the latter still escapes its
arguments, so prefer it.

---

## Custom fields

Custom field ids are **per-instance**. `Story Points` is `customfield_10004` on one Jira and
`customfield_11702` on another, and the failure mode of hard-coding one is silent: reading a field
that does not exist returns no value rather than an error.

Resolve names once, at startup:

```java
CustomFieldRegistry fields = jira.customFields();
CustomField<Double> storyPoints = fields.field("Story Points", Double.class);

Optional<Double> points = jira.fieldAccess().read(issue, storyPoints);
String jqlName = fields.jqlClauseName("Story Points");   // cf[10004] - survives a rename
```

The registry **refuses to guess** when two custom fields share a display name — Jira permits that, and
they are different fields — and names the candidate ids instead, so the problem surfaces at startup
rather than as an integration that read the wrong field for six months.

Reading and writing are not symmetric, and this is where hand-rolled clients go wrong. A select field
*reads back* as `{"self":..., "value":"Blue", "id":"10100"}` and is *written* as `{"id":"10100"}`.
`FieldAccess` decodes the read shapes; `FieldValues` builds the write shapes:

```java
jira.issues().update("OPS-1", IssueInput.builder()
        .field(storyPoints.id(), 5)
        .field(team.id(), FieldValues.option("10500"))
        .field(reviewers.id(), FieldValues.users("jsmith", "adoe"))
        .build());
```

### Binding issues onto your own types

```java
record Ticket(
        @JiraFieldBinding(id = "key")            String key,
        @JiraFieldBinding(id = "summary")        String summary,
        @JiraFieldBinding(name = "Story Points") Double storyPoints,
        @JiraFieldBinding(name = "Team")         CustomFieldOption team) { }

List<Ticket> tickets = jira.search().searchAll(query)
        .map(issue -> jira.binder().bind(issue, Ticket.class))
        .toList();
```

`name = ...` is resolved through the registry at bind time, so the same source file is correct in
every environment. Records, immutable classes and Jackson's own annotations all work, because the
binder rewrites the issue into a JSON object keyed by your property names and hands it to Jackson
rather than assigning fields reflectively.

---

## Writing issues: `fields` versus `update`

Jira has two sections in a write payload and the difference matters more than create-versus-update:

```java
IssueInput.builder()
        .labels("a", "b")      // fields: REPLACES the label set
        .addLabel("c")         // update: adds one, leaves the rest alone
        .removeLabel("d")
        .clear("customfield_10001")   // explicit JSON null - "clear this field"
        .addComment("done by automation")   // atomic with the edit
        .build();
```

`.labels(...)` discards whatever was there, including a label another process added between your read
and your write. `.addLabel(...)` does not. Every multi-valued field here has an add/remove pair for
that reason.

A field you do not mention is left alone; `clear(...)` sets it to null. Those are different on the
wire, which is why an unset optional is never serialized as `null` — the client's mapper omits nulls,
and `clear()` writes a `NullNode` so the distinction survives.

### Transitions

```java
jira.issues().transitionByName("OPS-1", "Resolve Issue",
        IssueInput.builder().resolution("Fixed").addComment("fixed in 1.4.2").build());
```

Never hard-code a transition id: ids belong to a workflow, so an id that works in one project is a
different transition — or nothing — in another. `transitionByName` costs one extra request and fails
loudly, listing what *was* available.

Setting fields through the transition is also not the same as editing then transitioning. Only the
transition form can satisfy a transition screen's required fields, and only it is atomic.

---

## Pagination

```java
// Lazy: findFirst over a query matching 200 000 issues makes one request.
Optional<Issue> first = jira.search().searchAll(query).findFirst();

// A page at a time, for batch processing.
jira.search().searchAllPages(request).forEach(page -> repository.saveAll(page.values()));
```

Jira is not consistent about how it signals the end of a walk — some endpoints send `isLast`, some
send `total`, some send neither, and search sends `total: -1` when it was told not to count.
`Page.hasMore()` folds all three into one answer. The walk also advances by the rows **actually
returned** rather than by the page size requested, because Jira silently caps `maxResults` and
advancing by the requested size would skip the rows it withheld.

---

## Structure (ALM Works)

Present only where the app is installed; without it every call in this namespace returns 404. Probe
once at startup rather than per request.

```java
Forest forest = jira.forests().readStructure(113);
List<ForestNode> tree = forest.tree();

// Rolled-up columns - values that exist nowhere in Jira's own API, because Jira has no hierarchy
// to roll anything up over.
ValueResponse values = jira.structureValues().compute(
        ForestSpec.structure(113),
        forest.rows().stream().map(ForestRow::rowId).toList(),
        List.of(AttributeSpec.field("customfield_10004")));

jira.forests().move(ForestSpec.structure(113), rowId, underRowId, afterRowId);
```

Three things about Structure that this client handles and that are easy to get wrong by hand:

- **A row is not an issue.** It is a *position*, and the same issue can occupy several rows in one
  structure. Every write addresses a `rowId`.
- **The forest arrives as one `formula` string**, not as nested JSON — a fifty-thousand-row structure
  as objects would be tens of megabytes. `ForestFormula` decodes
  `10394:0:4/356,10332:0:14707,...` into rows and rebuilds the hierarchy.
- **Every change quotes the version it was computed against.** Structure rejects an update made
  against a stale version rather than merging it, which is the only thing standing between two
  concurrent reorderings and silent data loss. That rejection arrives as `JiraConflictException`;
  the fix is to re-read and recompute, not to re-send.

The API coverage here is Structure's Structure, Forest, Value and Item resources, verified against the
published Structure 2.0 REST documentation. Anything beyond them is reachable through
`jira.rest()`.

---

## Observability

```java
JiraClient.builder()
        .metrics(new MicrometerJiraClientMetrics(meterRegistry))
        .addInterceptor((request, chain) -> chain.proceed(
                request.toBuilder().header("X-Correlation-Id", MDC.get("correlationId")).build()))
        .build();
```

| Meter | Type | Tags |
|---|---|---|
| `jira.client.requests` | timer | `operation`, `method`, `status` (bucketed `2xx`/`4xx`/`5xx`/`none`), `outcome` |
| `jira.client.retries` | counter | `operation`, `reason` |
| `jira.client.rate.limited` | counter | `operation` |

Tags carry the **operation** (`issue.get`, `search`), never the URI. One time series per issue key is
a metrics outage, and the status is bucketed for the same reason.

Micrometer is an `optional` dependency and `MicrometerJiraClientMetrics` is the only class that
references it, so a consumer that never calls it never needs it on the classpath.

---

## Using it from Spring

```java
@Configuration
class JiraConfiguration {

    @Bean(destroyMethod = "close")
    JiraClient jiraClient(JiraProperties properties, MeterRegistry meterRegistry) {
        return JiraClient.builder()
                .baseUrl(properties.baseUrl())
                .personalAccessToken(properties.token())
                .metrics(new MicrometerJiraClientMetrics(meterRegistry))
                .verifyOnBuild(true)
                .loadCustomFields(true)
                .build();
    }
}
```

One instance per Jira instance per application, held for the process's lifetime. It is thread-safe;
building one per request throws away connection pooling and, with `loadCustomFields`, re-reads the
field catalogue every time.

`verifyOnBuild(true)` and `loadCustomFields(true)` are both worth turning on in a long-lived service.
They move "the token expired", "the base URL has a typo" and "that custom field name is misspelled"
from the first request at 3am to the moment the context starts.

---

## Failing early

What the builder checks before it returns, without a network call:

- the base URL parses, is absolute, and is `http`/`https`;
- the base URL ends in a slash — without one, `URI.resolve` discards the last path segment, so an
  instance served from a context path (`https://example.com/jira`) silently sends every request to
  `https://example.com/rest/...` and every one of them 404s;
- credentials were chosen. There is no implicit anonymous default, because an anonymous client against
  a permissioned Jira gets 404s rather than 401s — which reads as "wrong issue keys", not as "not
  authenticated". `anonymous()` says so explicitly when that is what is wanted.

`verifyOnBuild(true)` adds what only the server can answer: `/serverInfo` (which most instances answer
anonymously, so it separates "wrong URL" from "wrong credentials") and `/myself`.

---

## Authentication

`personalAccessToken(...)` is the right choice on 9.12. A PAT can be scoped and expired without
touching the account's password, it is not accepted by Jira's non-REST endpoints so a leaked token
cannot be used to log into the web UI, and it does not trip the CAPTCHA-after-failed-logins protection
that, with Basic auth, locks an integration account out of *every* subsequent call.

`basicAuth(...)` is supported for older instances. Both have rotating variants
(`PersonalAccessTokenCredentials.rotating(supplier)`) consulted on **every attempt**, so a secret that
rotates mid-retry is picked up rather than producing three 401s. `JiraCredentials` is an open SPI for
anything else.

No `trustAllCertificates` switch exists, and will not: pass an `SSLContext` built over a truststore
holding your CA. A client that can be told to skip verification eventually is, in production.

---

## API coverage

**Jira Platform v2** — issues (CRUD, bulk create, assign, transitions, edit/create metadata, notify,
archive), JQL search, comments, worklogs with estimate adjustment, attachments, issue links and link
types, remote links, watchers, votes, entity properties, projects, versions, components, project
roles, users, groups, field definitions and custom field options, issue types, statuses, status
categories, priorities, resolutions, filters and share permissions, permissions, server info and
configuration, application roles.

**Structure 2.0** — structures, permission rules, forests (read, update, move, remove, add issues),
computed attribute values, items.

**Anything else** — `jira.rest()`, including the Jira Software Agile and Service Desk namespaces,
which are out of this module's declared scope but one call away.

---

## Testing

113 unit tests, no Jira required. `RecordingTransport` answers from a queue of canned responses and
records what was asked, which is the only way the interesting paths get covered at all — a 429 with a
`Retry-After`, a connection reset mid-write and a reverse proxy's HTML error page are not reproducible
against a real server.

The suite asserts on **what goes on the wire**: URLs, query strings, headers and payload shapes. That
is the part of a REST client that no amount of testing the models covers, and that a real Jira would
only ever report as a 400.

---

## Conventions this module follows, and one it does not

It follows the reactor's build configuration, the shared Checkstyle rules, and the platform's habit of
explaining *why* in the code rather than only in the README.

It does not follow the platform's "three model layers with generated mappers" rule, and the reason is
that the rule does not apply. That rule is about a service not leaking its persistence model into its
API; this module has no persistence and no API of its own — its models *are* the boundary, and mapping
Jira's JSON onto an intermediate model and then onto a second one would add a layer that exists only
to be traversed. `IssueBinder` is the seam for a consumer that wants its own domain type, and it is
the consumer's type, not this module's.
