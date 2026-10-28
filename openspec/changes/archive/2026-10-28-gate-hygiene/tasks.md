## 1. Image-pin checker (repository tooling)

- [x] 1.1 Add `scripts/testdata/check_image_pins/{good,bad}` fixtures, including the prose line from the archived
      proposal (good), a fenced docker run of postgres by tag alone (bad), an inline code span with a tag-only `image:` (bad),
      a digest-only reference (good) and a YAML image key naming bare postgres (bad); add `--self-test` to the script; verify
      `scripts/check_image_pins.sh --self-test` exits 0
- [x] 1.2 Markdown-only fence and code-span tracking in `check_image_pins.py`; exclude `scripts/testdata/*` from the
      tracked listing; verify `scripts/check_image_pins.sh` exits 0 on the tree and `--self-test` still exits 0
- [x] 1.3 Add `scripts/check_image_pins.sh --self-test` to `gate.sh`'s `COMMANDS`; verify `scripts/gate.sh --list file-action-spring-boot-starter`
      prints it

## 2. PAT last-use write (`pat-spring-boot-starter`)

- [x] 2.1 Add the version-less, forward-only last-use update to `PatQueryRepository`/`PatQueryRepositoryImpl` and call it
      from `PatUsageTracker`; update `PatServiceTest`'s fake; verify `mvn -pl :pat-spring-boot-starter test`
- [x] 2.2 `PatUsageRaceIT`: latch-parked write, revoke, release; assert the revoke succeeded, `revoked_at` is still set,
      and an uncached exchange is `401`; plus the rotation scenario; verify `mvn -pl :pat-spring-boot-starter verify
      -Dit.test=PatUsageRaceIT`
- [x] 2.3 Run `PatDirectAuthenticationIT` 20 times in a loop, `for i in $(seq 20); do mvn -q -pl
      :pat-spring-boot-starter verify -Dit.test=PatDirectAuthenticationIT || break; done`, with no failure

## 3. `StubFaultIT.slow` (`test-support-local`)

- [x] 3.1 Reproduce: loop `mvn -q -pl :test-support-local verify -Dit.test=StubFaultIT` until a failure occurs or 30
      runs pass; record the cause in `state.json` `decisions`
- [x] 3.2 Fix the identified cause (not the timeout); verify 30 consecutive passing runs of the same loop

## 4. `JdbcRunLockIT` (`job-core`, test sources)

- [x] 4.1 Set `sslMode=disable` on the test's `PGSimpleDataSource` (or fix the cause the reproduction found);
      verify 30 consecutive passing runs of `mvn -q -pl :job-core verify -Dit.test=JdbcRunLockIT`

## 5. Follow-ups list

- [x] 5.1 Remove the image-pin, flaky-IT and StubFaultIT items from `openspec/intents/admin-surface-follow-ups.md`,
      keeping the API-baseline item as a release-checklist entry and adding "the migrations, protos and build-metadata self-tests are not run by the gate"; verify `openspec validate gate-hygiene --strict`

## 6. Verification gate

- [x] 6.1 `scripts/gate.sh --change gate-hygiene pat-spring-boot-starter test-support-local job-core`, which runs
      `mvn -q validate`, the three modules and every in-repo dependent named in the proposal; the script checks must
      now pass except `check_api_baseline` while the releases repository stays unreachable, recorded as such
