## 1. Restore the allowlist's reach (FR1, FR2, D1)

- [x] 1.1 Add `--config=osv-scanner.toml` to the `scan-args` input of the
      `osv-scan` job in `.github/workflows/osv-scan.yml`, keeping the existing
      `-r` / `./` arguments.
- [x] 1.2 Extend the workflow's header comment: the lock state now lives in
      per-module lockfiles, the root config is the ONLY one consulted (an
      explicit `--config` shadows per-directory files), and a new module needs
      no scan wiring (M5, UX2).

## 2. Re-review the accepted-risk allowlist (FR8, NFR-S2, D6)

- [x] 2.1 Verify the Jetty 11 situation is unchanged: max Maven Central release
      of the 11.x line, and the newest published WireMock version; record both
      in the file's rationale block.
- [x] 2.2 Set `ignoreUntil = 2026-11-01` on all six entries (5 Jetty +
      Handlebars) and confirm each entry states its classpath scope, why no
      adoptable fix exists, and the condition that retires it.
- [x] 2.3 Confirm the file contains **no** entry for an artifact on a production
      runtime classpath (NFR-S1, M2).

## 3. Pin the fixable versions (FR3, FR4, FR5, FR6, D2, D4, D5)

- [x] 3.1 Add `httpclient5` to `gradle/libs.versions.toml` under the security
      overrides block, at the fixed version, with a comment naming
      GHSA-hjcp-jmpx-g3qm, the scope it applies to, and the condition for
      dropping the override; add the matching `[libraries]` entry.
- [x] 3.2 Add `log4j` to the same block at 2.25.5 with a comment naming
      GHSA-qv9r-c865-cp47 and flagging it as the one **production-scope**
      override in this change; add `[libraries]` entries for `log4j-api` and
      `log4j-to-slf4j` (they must move together).
- [x] 3.3 Add `buildscriptHttpclient5Version` to `gradle.properties` next to the
      existing buildscript override properties, with the same comment shape.
- [x] 3.4 Force `httpclient5` from the `buildscript {}` blocks of the root
      `build.gradle` and `bootstrap/build.gradle`, alongside the existing
      `httpcore5` force.

## 4. Make the test-classpath overrides structural (FR4, NFR-R2, M3, M5, D3)

- [x] 4.1 Move the `httpcore5` / `httpcore5-h2` test-classpath
      `resolutionStrategy.force` block out of `bootstrap/build.gradle` into the
      shared test-stack convention plugin
      (`build-logic/src/main/groovy/test-conventions.gradle`), and add
      `httpclient5` to it.
- [x] 4.2 Carry the rationale comment across intact — why `force` and not
      `constraints` (Boot BOM `strictly`, D2) — and add why the block is
      inert on modules that never resolve these artifacts.
- [x] 4.3 Delete the now-redundant block from `bootstrap/build.gradle`, leaving
      a one-line pointer to the convention plugin.

## 5. Apply the production log4j pin (FR5, D4)

- [x] 5.1 Add `log4j-api` / `log4j-to-slf4j` to `bootstrap/build.gradle`'s
      `constraints { }` block next to the existing logback override, with the
      advisory named.
- [x] 5.2 Resolve `:bootstrap`'s runtime classpath; if the Boot BOM pins log4j
      `strictly` and the constraint fails or is ignored, switch to
      `resolutionStrategy.force` per D2 and record the reason in the comment.

## 6. Regenerate and verify the lock state (FR7, NFR-R1, NFR-R2, M4)

- [x] 6.1 Run `./gradlew check --write-locks` and commit every changed lockfile.
- [x] 6.2 Grep the affected lockfiles and assert the pinned versions are present and
      the vulnerable ones absent: `httpclient5` ≥ 5.6.3 everywhere it appears,
      `httpcore5`/`httpcore5-h2` 5.4.3 in `adapters/github/gradle.lockfile`,
      `log4j-api`/`log4j-to-slf4j` 2.25.5 in `bootstrap/gradle.lockfile`.
- [x] 6.3 Confirm `./gradlew check` is green — in particular the WireMock-backed
      `:adapters:github` and `:bootstrap` suites and the Logback log-capture
      specs (M4).

## 7. Close the loop (FR9, UX4, M1, M2, D7)

- [x] 7.1 Run the scan locally with the documented command and confirm zero
      unsuppressed vulnerabilities across every lockfile the scan walks (M1), with at most
      six suppressed, none production-scope (M2).
- [x] 7.2 Document that command in README's "Building" section, next to the
      existing dependency-locking note.
- [x] 7.3 Verify M3: no security-override version literal outside
      `gradle/libs.versions.toml` and `gradle.properties`.
- [x] 7.4 Recommend a commit message covering the workflow, build, lock-state and
      documentation changes, referencing this change and its FR IDs; the human
      commits (`.claude/rules/process-invariants.md`).
