## Why

Twenty-nine module directories sit flat at the repository root, interleaved with `pom.xml`,
`README.md`, `Jenkinsfile`, `docs/`, `scripts/` and `openspec/`. Finding a module means scanning a
listing in which a BOM, a Checkstyle config jar, a runnable service and a starter all look alike.
The three-POM split — the thing `CLAUDE.md` calls the thing to get right — is invisible in the tree.

## What Changes

Every module moves one level down, into one of three directories by role:

| Directory | Contents |
|---|---|
| `build/` | `ludwig-bom`, `ludwig-service-parent`, `checkstyle-rules`, `architecture-rules` — what every other module inherits, imports, or is checked by |
| `services/` | `crud-service-example`, `notification-service` — parented by `ludwig-service-parent`, shipped as images |
| `sources/` | the other 23 — libraries, starters and the two test-support modules, all parented by the reactor root |

**No artifact coordinates change.** Same `groupId`, same `artifactId`, same `${revision}`, same
published jars. This is a directory move plus the POM plumbing that a directory move breaks.

## Non-goals

- Renaming any module or artifact. A directory move is already wide enough to review; a rename in
  the same change would make every diff hunk ambiguous between the two.
- Splitting `sources/` further into `libs/` and `starters/`. Considered and declined: it makes
  "is this a library or a starter" a judgement call on every new module, for one more level of
  nesting.
- Changing what is in any module, or any behaviour. No `src/` file changes except path references
  in prose.
- Moving `docs/`, `scripts/` or `openspec/`. They are not modules and are already unambiguous.

## Shared contracts touched

A new `repository-layout` capability spec is added, because the placement rule is exactly the kind
of invariant that drifts back: the next module added under a flat root would look unremarkable. No
existing spec in `openspec/specs/` changes — `pom-topology` governs parentage, which is unchanged,
and `test-layout` governs paths inside a module, which are unchanged.

## Impact

Every module is affected, so the POM tier table is the three-way split above rather than a list.
The load-bearing detail is that **26 of 29 module POMs currently declare a parent with no
`<relativePath>`**, relying on Maven's `../pom.xml` default. After the move that default resolves to
`sources/pom.xml`, which does not exist — so Maven would silently fall back to the local repository
and build against whatever stale `common` happens to be installed there. Every one of those POMs
needs an explicit `<relativePath>../../pom.xml</relativePath>`.

Because the reactor root and both `build/` POMs change, the verification gate is the whole reactor:
`mvn clean install`. There is no narrower gate.
