# identity-source-directory-spring-boot-starter

***English** · [Русский](README.ru.md)*

Feeds [`identity-projection-spring-boot-starter`](../identity-projection-spring-boot-starter) from providers that
expose their users over an API. A service implements **one interface**, `IdentityDirectory`, against its provider;
this starter syncs changes incrementally from a persisted watermark, sweeps each source completely on a schedule,
runs both as [`job-core`](../job-core) jobs under run locks, and isolates failures per source.

| | |
|---|---|
| Role | starter |
| Depends on (in-repo) | [`identity-projection-spring-boot-starter`](../identity-projection-spring-boot-starter), [`job-core`](../job-core) |
| Surfaces | outbound calls to the provider (the service's own client) |

## Usage

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>identity-source-directory-spring-boot-starter</artifactId>
</dependency>
```

```yaml
ludwig:
  identity:
    sources:
      staff:
        role-mapping:
          catalog-admins: [CATALOG_ADMIN]
        unmapped-roles: DROP
```

### Implementing a directory

```java
@Component
class StaffDirectory implements IdentityDirectory {

    private final ProviderUsersClient users;   // the service's own @LudwigRestClient

    StaffDirectory(ProviderUsersClient users) {
        this.users = users;
    }

    @Override
    public String source() {
        return "staff";
    }

    @Override
    public DirectoryPage fetch(DirectoryQuery query) {
        ProviderPage page = users.list(query.cursor(), query.changedSince(), query.pageSize());
        List<IdentityChange> changes = page.users().stream().map(user -> user.active()
                ? (IdentityChange) new IdentityChange.Upsert("staff", user.id(), user.updatedAt(), user.etag(),
                        new UserProfile(user.name(), user.orgId(), user.groups(), null))
                : new IdentityChange.Disable("staff", user.id(), user.updatedAt(), user.etag())).toList();
        Instant watermark = page.users().isEmpty() ? null : page.users().get(page.users().size() - 1).updatedAt();
        return new DirectoryPage(changes, page.nextCursor(), watermark);
    }
}
```

**The contract.** With `changedSince` set, return only users changed at or after it, **in ascending change order**,
and set the page's `watermark` to the last change time on it. With `changedSince` null, list every user of the
source. A null `nextCursor` ends the listing. A provider that cannot filter by change overrides
`filtersByChange()` to return `false`; every sync is then a sweep. Timeouts, retries and circuit breaking are the
client's ([`rest-client`](../rest-client-spring-boot-starter)).

### Sync and sweep

| | Incremental sync | Sweep |
|---|---|---|
| Job (and run lock) | `ludwig-identity-<source>-sync` | `ludwig-identity-<source>-sweep` |
| Asks for | changes since the stored watermark | every user |
| Commits | each page's changes **and** its watermark together, so a stopped sync resumes after the last committed page | each page's changes; the listed subjects are staged |
| At the end | records the outcome | disables the source's active users it did not list - unless the projection's `max-disable-ratio` aborts it - and advances the watermark |
| On failure | keeps the watermark and the users; records the error type; the next run retries | is abandoned and disables nobody |

A source with no watermark is swept by its first sync. Both jobs are listed by `GET /admin/v1/jobs` and can be run
now with `job.definition.run`; at most one replica runs each. A failure of one source never delays another.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `ludwig.identity.directory.sources.<source>.initial-delay` | `PT15S` | Before the first sync after start |
| `ludwig.identity.directory.sources.<source>.sync-interval` | `PT5M` | Between incremental syncs |
| `ludwig.identity.directory.sources.<source>.sweep-interval` | `P1D` | Between sweeps (also before the first) |
| `ludwig.identity.directory.sources.<source>.page-size` | `500` | Page size asked of the directory (1-10000) |
| `ludwig.identity.directory.sources.<source>.lease` | `PT1H` | Run lease; longer than the longest run |
| `ludwig.identity.directory.liquibase.enabled` | `true` | `false` when including the changelog from your own master |

The source itself, and its role mapping, are declared under `ludwig.identity.sources` (see the projection). Two
directories for one source, or a directory for an undeclared source, fail startup.

## Deployment

- **Schema**: `db/changelog/identity-directory/identity-directory-changelog.xml`, standalone or included:
  `identity_directory_state` - one row per source with its watermark and last sync and sweep (time, outcome, error
  type, pages, counts). Never user data.
- **job-core**: the jobs need its run-lock table.

## Observability

| Metric | Tags | Meaning |
|---|---|---|
| `ludwig_identity_directory_run_seconds` | `source`, `kind` (`sync`, `sweep`), `outcome` (`succeeded`, `failed`, `aborted`) | Each run's duration |

The changes themselves are counted by the projection (`ludwig_identity_changes_total`, `ludwig_identity_sweeps_total`).
`GET /admin/v1/identity/sources` shows each directory's watermark and last sync and sweep.

**Logs.** `WARN ludwig-identity-<source>-sync failed: <exception>`; `INFO Swept identity source ...`. The provider's
error message is not stored (it can carry the user data it failed on); its type is.

**Recommended alerts:** `outcome="failed"` for a source over several intervals; any `outcome="aborted"` sweep; a
watermark that stops moving while the provider changes.

## Runbook

| Symptom | Likely cause | What to do |
|---|---|---|
| A source's syncs fail | Provider down, credentials expired | `GET /admin/v1/identity/sources` for the error type; fix the client; the next run retries |
| Sweep aborted | Empty or truncated listing, or a real mass offboarding | Check the provider; for a real offboarding raise the projection's `max-disable-ratio` for one run |
| A deleted user still active | Provider's change filter does not report deletions | The next sweep disables them; run `ludwig-identity-<source>-sweep` now via the jobs area |
| Changes missed | Directory returned changes out of order | Fix the directory's ordering; a sweep repairs |

## Testing

`DirectorySyncIntegrationTest` against PostgreSQL with an in-memory provider (first sync sweeps, resume after an
interrupted page, failure isolation, sweep disabling, a failed sweep changing nothing, jobs under their run lock,
progress and metrics), `DirectoryValidationTest`, `DirectoryArchitectureTest`.

## Design decisions

- **One interface for the service, everything else here**: a provider is a page reader, not a copy of this module.
- **Watermark and page in one transaction**: resumable without re-reading or skipping.
- **Sweeps on a schedule**: a change filter rarely reports deletions; a complete listing does, guarded by the
  projection's threshold.
- **job-core jobs under run locks**: one replica per source, visible and runnable from the jobs area, no second
  scheduler.
