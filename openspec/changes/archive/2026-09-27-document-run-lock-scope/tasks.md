## 1. The English README

- [x] 1.1 Add the pointer sentence at the end of the "What it is not for" subsection of
      `job-core/README.md`. Verified by `grep -n 'openspec/specs/run-lock' job-core/README.md`
      returning exactly one line inside that subsection.

## 2. The Russian README

- [x] 2.1 Add the same sentence, translated, at the same place in `job-core/README.ru.md`. Verified
      by `grep -c 'openspec/specs/run-lock' job-core/README.ru.md` returning 1. Both locales move
      together because the archive guidance requires it, and a README updated in one locale is an
      incomplete change.

## 3. The verification gate

- [x] 3.1 `scripts/manifest.sh stale` exits 0 — no POM changed, so the manifest must still be
      current and this proves nothing drifted.
- [x] 3.2 `scripts/gate.sh --change document-run-lock-scope job-core` passes: `mvn -q validate`,
      `mvn -pl job-core -am verify`, the seven in-repo dependents, and
      `openspec validate document-run-lock-scope`.
