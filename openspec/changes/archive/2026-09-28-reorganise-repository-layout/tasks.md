## 1. Move the directories

- [x] 1.1 Create `build/`, `services/`, `sources/` and move all 29 module directories into them by
      role, with plain `mv` so that tracked, modified and untracked files travel together. Verified
      by `ls build services sources` showing 4, 2 and 23 entries, and by
      `find . -maxdepth 2 -name pom.xml -not -path './target/*'` listing 30 POMs (29 + root).

## 2. Repair the build

- [x] 2.1 Root `pom.xml`: prefix every `<module>` entry with its directory, and repoint the
      Checkstyle `configLocation` and `suppressionsLocation` at `build/checkstyle-rules/`. Verified
      by `grep -c '<module>' pom.xml` = 29 and no `<module>` entry lacking a `/`.
- [x] 2.2 Add `<relativePath>../../pom.xml</relativePath>` to every module POM parented by `common`,
      and fix the three existing `<relativePath>` declarations (`ludwig-bom`, and the two services
      pointing at `ludwig-service-parent`). Verified by `mvn -q validate` succeeding, which cannot
      happen if a parent fails to resolve in-reactor.
- [x] 2.3 Jenkinsfile: switch the image-build `-pl` from module paths to `:artifactId`. Verified by
      `grep -n 'pl ' Jenkinsfile` showing the `:` form and no bare directory name.

## 3. Repair the tooling

- [x] 3.1 `scripts/project_index.py`: discover modules at depth 2, record the directory, and emit
      gate commands using `-pl :<artifactId>`. Verified by `scripts/manifest.sh build` reporting 29
      modules and `scripts/manifest.sh module sources/job-core/README.md` resolving correctly.
- [x] 3.2 Add `scripts/manifest.sh --check-layout`, failing on any module whose directory does not
      match its role. Verified by running it clean, and by temporarily asserting it fails for a
      deliberately misplaced module.
- [x] 3.3 `scripts/gate.sh`: emit `-pl :<artifactId>`. Verified by
      `scripts/gate.sh --list job-core` printing the `:` form.
- [x] 3.4 `.claude/hooks/stop-gate.sh`: map a changed path to its module using two path segments
      rather than one. Verified by piping a synthetic payload and seeing real module names.

## 4. Repair the prose

- [x] 4.1 Update every path reference in `CLAUDE.md`, `README.md`, `docs/*.md`, `openspec/specs/**`
      and the module READMEs (both locales). Verified by a repo-wide grep for a module name
      followed by `/` that is not preceded by `build/`, `services/` or `sources/` returning only
      package paths and intentional historical mentions.
- [x] 4.2 Regenerate `PROJECT_INDEX.md` and `project-index.json`, and confirm the dependency graph
      is unchanged. Verified by diffing the `inRepoDependencies`/`inRepoDependents` of the old and
      new manifests and finding no difference.

## 5. The verification gate

- [x] 5.1 `scripts/manifest.sh stale` exits 0 and `scripts/manifest.sh --check-layout` exits 0.
- [x] 5.2 `rm -rf ~/.m2/repository/ru/ludwigandreas && mvn clean install` succeeds — the proof that
      every parent resolved in-reactor and not from a stale installed artifact.
- [x] 5.3 `openspec validate reorganise-repository-layout` passes.
