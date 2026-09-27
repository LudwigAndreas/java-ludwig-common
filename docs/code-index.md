# Navigating this repository: the `code-index` MCP server

29 modules, 2153 indexed files, 10,233 symbols. Reading files to find things does not scale here,
and neither does `rg` across the reactor. Navigation goes through the **`code-index` MCP server**
(`mcp__code-index__*`), plus one generated manifest for the Maven questions the index cannot answer.

There is deliberately **one** index. A previous iteration of this repository carried a parallel
Universal Ctags + ast-grep toolchain under a `scripts/index.sh` that no longer exists; it was retired, because a second
index is a second thing to keep fresh and a second answer to the same question. Do not reintroduce
`universal-ctags`, `ast-grep`, `scip-java` or `semgrep`.

## Bootstrap — unconditional, every session

```
mcp__code-index__set_project_path   path = <repo root>
mcp__code-index__build_deep_index
```

Both, in that order, before the first query. `set_project_path` alone gives you a shallow index:
`find_files` and `search_code_advanced` work, but `get_file_summary` and `get_symbol_body` do not.

Measured on this repository (2026-09-27): `set_project_path` indexes 2153 files (`target/` is
excluded automatically); `build_deep_index` takes **~11-15s** and yields **10,233 symbols** — 1612
classes, 8425 methods, 173 file symbols, 23 functions, across java, xml, yaml, sql, markdown,
python, shell and json. The file watcher is **active** with a **6s debounce**, so edits you make
during a session are re-indexed without asking.

Fifteen seconds is cheap enough that the bootstrap is never conditional. Do not try to detect
whether the index is already warm — just run both.

## Where the index state lives — the verdict

**It stays in `$TMPDIR`. Do not relocate it into the repository.**

| | |
|---|---|
| location | `$TMPDIR/code_indexer/<project-hash>/` — here, `/var/folders/…/T/code_indexer/44cb144e3eac/` |
| contents | `index.db` (SQLite, ~6.5 MB) + `index.shallow.json` (~210 KB) |
| fallback location | `.code_indexer/` in the repo root — used only when the temp root is unwritable |
| survives a reboot? | **Yes, if it is less than 3 days old.** Reboot itself does not clear `/var/folders`. |
| what does clear it | `com.apple.bsd.dirhelper`, `RunAtLoad=true` and daily at 03:35, with `CLEAN_FILES_OLDER_THAN_DAYS=3`. So a repository untouched for three days loses its index at the next boot or the next 03:35. |

Measured, not assumed: deleting the state directory and re-running the two bootstrap calls restored
`index.db` with all 10,233 symbols in ~15s wall clock.

The reasoning for leaving it there: a wipe costs 15 seconds and the bootstrap is unconditional, so
the prune is invisible. Relocating into `.code_indexer/` would put a 6.5 MB binary in the working
tree that goes stale silently and has to be git-ignored — a permanent cost to avoid an occasional
15-second one. `.code_indexer/` is git-ignored anyway, so an accidental fallback cannot be committed.

## The query ladder

**Stop at the first rung that answers your question.**

| # | Question | Tool |
|---|---|---|
| 1 | Which module owns this path? What depends on it? Which POM tier? What is the gate? | `PROJECT_INDEX.md` / `scripts/manifest.sh module <path>` |
| 2 | *Why* is this module shaped this way? | that module's `README.md` — **the index cannot answer this at all**, see limit 4 |
| 3 | Which files exist under this path shape? | `find_files` (glob, e.g. `**/lock/*.java`) |
| 4 | What is in this file? | `get_file_summary` — classes, methods with line ranges, imports |
| 5 | What does this symbol do? | `get_symbol_body` — needs **both** `file_path` and a **qualified** `symbol_name` |
| 6 | Who calls this? Which code contains this text? | `search_code_advanced` — **not** `called_by`, see limit 3 |
| 7 | Anything the above could not answer | `rg` — last resort, and you must say why |

`rg` stays available on purpose. An agent that cannot fall back will invent something worse. The
discipline is not that the tool is forbidden — it is that **reaching rung 7 requires stating, in
your response, which rung you tried and what it failed to return.**

## The six limits — each verified in this repository

Every one of these has bitten. The workaround is the point.

**1. `get_symbol_body` needs a qualified name *and* the file path.** Both arguments are required:
`file_path` = `job-core/src/main/java/…/JdbcRunLock.java` **and** `symbol_name` = `JdbcRunLock.tryAcquire`
— not `tryAcquire`. Omitting `file_path` fails validation outright; an unqualified `symbol_name`
finds nothing. *Workaround:* `get_file_summary` first — it gives you both the qualified names and
their line ranges.

**2. Two different "not indexed" errors, both misleading.**
  - *"File not found in index or deep index not built"* — the file almost certainly exists; you
    skipped `build_deep_index`.
  - *"Operation failed: unable to open database file"* — the on-disk state was pruned (see above).
  
  Neither means the file is absent. **Neither is a reason to reach for `grep`.** Re-run the
  bootstrap and retry the same query.

**3. `called_by` is intra-file only, and it produces false positives.** This is the most dangerous
limit, because the field is present and looks authoritative. Verified: `get_file_summary` on
`JdbcRunLock.java` reports `called_by: []` for `tryAcquire`, while `search_code_advanced` for
`tryAcquire` returns **67 matches**, including real callers in four other modules —
`audit-spring-boot-starter/…/AuditRetentionPurge.java:105`,
`export-spring-boot-starter/…/ExportRetentionPurge.java:79`,
`…/ExportSubscriptionScheduler.java:89`, `idempotency-spring-boot-starter/…/RetentionPurgeIT.java:99`.
In the same output, `Handle.renew` lists *itself* as a caller. *Workaround:* for cross-module
callers use `search_code_advanced`; **never conclude "nothing calls this" from `called_by`.** To
actually prove a symbol has no callers, use the compiler: remove it and run the gate for the module
plus every dependent named by `scripts/manifest.sh module <path>`.

**4. `docstring` is always null for Java, and `get_symbol_body` omits the leading javadoc.**
Verified on `JdbcRunLock.tryAcquire`: the body comes back from `@Override` onward, and every
`docstring` field in the file summary is `null`. In a repository whose *design arguments* live in
javadoc and in module READMEs, **the index cannot answer "why" at all.** *Workaround:* the module
`README.md` (rung 2), or read the line range immediately above the symbol's `line` from the file
summary.

**5. The index knows nothing about Maven.** Not module roles, not POM tier, not in-repo dependency
direction, not which module owns a path, not what the gate for a change is. *Workaround:* that is
exactly what `PROJECT_INDEX.md` / `project-index.json` / `scripts/manifest.sh module` exist for.
They are generated from the POMs and the filesystem — never hand-written — and `scripts/manifest.sh
stale` tells you if they have drifted (content-based, over a SHA of the POM set, not by mtime).

**6. `search_code_advanced` paginates at 10 results by default.** The `tryAcquire` search above
reported `"total_matches": 67, "returned": 10, "has_more": true`. Reading only the first page and
concluding you have seen every caller is the same error as trusting `called_by`. Raise
`max_results` or page through `start_index` before you draw a conclusion about coverage.

## Keeping it fresh

- **Source edits:** nothing to do. The file watcher re-indexes on a 6s debounce.
- **POM edits:** re-run `scripts/manifest.sh build`. The manifest is not watched, and a `PostToolUse`
  hook reminds you. A drifted manifest is worse than none, because an agent that trusts it stops
  checking.
- **A `refresh_index` call** forces a shallow re-scan if you suspect the watcher missed something;
  `build_deep_index` after it if you need symbols.

## Tools you may not call

`clear_settings` is denied in `.claude/settings.json`. It discards the index configuration for every
project the server knows about, not just this one, and nothing in normal work needs it.
