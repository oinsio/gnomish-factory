## Why

The OSV-Scanner gate now fails with 18 advisories across 14 packages. Two
distinct defects hide behind that number:

1. **The reviewed-risk allowlist stopped being applied.** `osv-scanner.toml`
   sits at the repo root, where it was written when the build had exactly one
   `gradle.lockfile` there. After `split-into-modules`, the lock state moved to
   `adapters/github/gradle.lockfile`, `bootstrap/gradle.lockfile` and
   `bootstrap/buildscript-gradle.lockfile`; OSV-Scanner resolves its config
   relative to each scanned lockfile, so the root allowlist is silently ignored
   and the 6 already-reviewed WireMock-transitive advisories (5 Jetty 11 +
   Handlebars) are re-reported — 12 of the 18 rows.
2. **Six genuinely new advisories appeared**, one of them on the *production*
   runtime classpath (`log4j-api` 2.25.4, GHSA-qv9r-c865-cp47), the rest on test
   and buildscript classpaths (`httpclient5` 5.6.1, `httpcore5(-h2)` 5.4.2 in a
   module the existing `force` never reached).

A permanently red security gate is worse than no gate: it trains everyone to
ignore it, so the next real production CVE lands unnoticed. Both defects are
fixed at once, and the allowlist's reach is made structural so a future module
split cannot silently disable it again.

## What Changes

**MODIFIED**

- The OSV-Scanner CI job passes the repo-root `osv-scanner.toml` explicitly, so
  the reviewed-risk allowlist governs every lockfile in the module tree, not
  only files sitting next to it.
- `httpclient5` is pinned to the fixed line (5.6.3+) wherever it resolves: the
  test classpaths of `:adapters:github` and `:bootstrap`, and the `:bootstrap`
  buildscript classpath.
- The existing `httpcore5` / `httpcore5-h2` 5.4.3 override is extended to
  `:adapters:github`'s test classpaths, which the current per-module `force`
  does not cover.
- `log4j-api` / `log4j-to-slf4j` are pinned to the fixed version on
  `:bootstrap`'s production runtime classpath, overriding the Spring Boot BOM.
- The Gradle lock state is regenerated and committed for every affected module.
- `osv-scanner.toml` entries are re-reviewed: rationale and re-review dates
  restated against the current WireMock 4.x / Jetty 11 situation.

**ADDED**

- A documented, reproducible local command for running the same scan a
  developer sees in CI, so the gate can be checked before pushing.

**REMOVED**

- Nothing.

## Goals

- **G1** — `./gradlew check --write-locks` followed by the documented OSV scan
  reports zero unignored vulnerabilities, both locally and in CI.
- **G2** — Every advisory that remains suppressed is suppressed deliberately:
  test- or buildscript-scope only, with a written rationale and a re-review
  date.
- **G3** — The allowlist's reach survives future module additions without
  per-module edits.

## Non-Goals

- **NG1** — Upgrading WireMock to the 4.x beta line to escape Jetty 11; the
  project pins stable dependencies only (ADR 0001).
- **NG2** — Upgrading Spring Boot to a line that manages the fixed transitive
  versions; that is a separate change with its own compatibility surface.
- **NG3** — Introducing an automated dependency-update bot (Dependabot/Renovate)
  configuration for Gradle dependencies.
- **NG4** — Changing which scanner is used, or how its SARIF results reach the
  code-scanning dashboard.
- **NG5** — Re-litigating the accepted WireMock-transitive risks; they are
  re-reviewed, not reopened.

## Users & Scenarios

- **U1 — Developer before pushing.** Runs the documented scan command locally,
  sees the same verdict CI will produce, and fixes pins before the push instead
  of after a red build.
- **U2 — Developer adding a dependency.** Adds a library, regenerates locks, and
  gets a scanner failure naming the artifact and advisory if the new transitive
  graph carries a known CVE.
- **U3 — Reviewer of a suppression.** Opens `osv-scanner.toml` and can tell, per
  entry, which classpath scope it affects, why no fix is adoptable, and when the
  decision expires.
- **U4 — Maintainer adding a module.** Adds a new Gradle module with its own
  lockfile and the existing allowlist applies to it with no extra wiring.

## Requirements

### Functional

- **FR1** — The CI OSV-Scanner job SHALL evaluate every committed Gradle
  lockfile in the repository against the single repo-root `osv-scanner.toml`
  allowlist, regardless of which directory the lockfile lives in.
- **FR2** — A new Gradle module contributing a lockfile SHALL be covered by that
  same allowlist without adding a per-module configuration file.
- **FR3** — `org.apache.httpcomponents.client5:httpclient5` SHALL resolve to a
  version not affected by GHSA-hjcp-jmpx-g3qm on every configuration where it
  appears, including buildscript classpaths.
- **FR4** — `org.apache.httpcomponents.core5:httpcore5` and `httpcore5-h2` SHALL
  resolve to a version not affected by GHSA-hf6x-8p5f-cgmf /
  GHSA-v3jc-474w-2wm6 on every configuration where they appear, including
  `:adapters:github`'s test classpaths.
- **FR5** — `org.apache.logging.log4j:log4j-api` and `log4j-to-slf4j` SHALL
  resolve to a version not affected by GHSA-qv9r-c865-cp47 on `:bootstrap`'s
  production runtime classpath.
- **FR6** — Every version pin introduced SHALL be declared in
  `gradle/libs.versions.toml` as the single source of versions, with a comment
  naming the advisory it clears and the condition for dropping the override.
- **FR7** — The committed lock state SHALL be regenerated so it reflects the new
  pins; the scan reads lockfiles, not the version catalog.
- **FR8** — Each entry retained in `osv-scanner.toml` SHALL carry the affected
  classpath scope, the reason no adoptable fix exists, and a future re-review
  date.
- **FR9** — The repository SHALL document a single command a developer can run
  locally to reproduce the CI scan verdict.

### Non-Functional — Reliability

- **NFR-R1** — The gate SHALL stay reproducible: the same lock state scanned
  with the same configuration yields the same verdict on any machine, with no
  dependency on the working directory the scanner is invoked from.
- **NFR-R2** — Overriding a BOM-managed or `strictly`-constrained transitive
  SHALL fail the build loudly if the override stops applying, rather than
  silently falling back to the vulnerable version — a stale lockfile entry is
  the detection point.

### Non-Functional — Security

- **NFR-S1** — No advisory affecting a production (`runtimeClasspath` /
  `productionRuntimeClasspath`) artifact SHALL be suppressed; suppression is
  permitted only for test- and buildscript-scope artifacts.
- **NFR-S2** — Suppressions SHALL expire: every entry carries an `ignoreUntil`
  date, after which the gate fails again and forces a re-decision.
- **NFR-S3** — The CI job SHALL keep failing the run on any vulnerability that
  is not explicitly suppressed (`fail-on-vuln` stays on).

### Non-Functional — Observability

- **NFR-O1** — A failing scan SHALL name the package, version, advisory ID, and
  the source lockfile, so the responsible module is identifiable without
  re-running the scan.
- **NFR-O2** — It SHALL be evident from the CI job definition which
  configuration file governs the suppressions.

### Non-Functional — Cost

- **NFR-C1** — The change SHALL not increase the number of scanner runs per
  push; the existing single-job, concurrency-cancelled trigger pattern stays.

## Operator Experience Criteria

- **UX1** — A developer reading the CI failure can tell, from the row alone,
  which module's lockfile carries the vulnerable artifact.
- **UX2** — A developer reading `osv-scanner.toml` can tell for each entry why
  the risk is accepted without consulting git history or an external document.
- **UX3** — Bumping a security override is a single edit in
  `gradle/libs.versions.toml` plus a lock regeneration — no hunting through
  module build scripts for scattered version literals.
- **UX4** — The local scan command is discoverable from the project
  documentation, not only from the workflow file.

## Success Metrics

- **M1** — OSV-Scanner reports **0** unsuppressed vulnerabilities across every
  lockfile in the tree (16 today; four of them carry the current findings).
- **M2** — At most **6** advisories remain suppressed, **0** of them affecting a
  production runtime classpath.
- **M3** — **0** version literals for security overrides outside
  `gradle/libs.versions.toml` (buildscript-classpath forces excepted, which read
  their versions from `gradle.properties`).
- **M4** — `./gradlew check` stays green after the pins — no test-startup
  regression from the bumped HTTP and logging stacks.
- **M5** — Adding a hypothetical new module lockfile requires **0** edits to
  security-scan configuration for the allowlist to apply.

## Open Questions

- **Q1** — Explicit `--config` on the scan job versus per-directory
  `osv-scanner.toml` files: the former is one place and satisfies FR2, the
  latter is what the scanner resolves by default. Resolved in `design.md`.
- **Q2** — For `log4j`, pin the minimal patch on the BOM-managed line, or move
  to the newest stable line? Minimal-patch keeps the delta from the Boot BOM
  small; resolved in `design.md`.
- **Q3** — Should the Jetty/Handlebars re-review date be extended as-is, or
  shortened to force a re-check once WireMock 4.x ships stable?

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `quality-gates`: the **Security scanning** requirement gains explicit scope —
  the scan covers every module's lockfile under one repo-root suppression
  allowlist, suppressions are limited to non-production classpaths and must
  expire, and a documented local command reproduces the CI verdict.

## Impact

- **CI**: `.github/workflows/osv-scan.yml` (scan arguments).
- **Build**: `gradle/libs.versions.toml` (new/updated security-override
  versions), `gradle.properties` (buildscript-classpath override versions),
  `adapters/github/build.gradle`, `bootstrap/build.gradle`, and the root
  `build.gradle` buildscript block.
- **Lock state**: `adapters/github/gradle.lockfile`, `bootstrap/gradle.lockfile`,
  `bootstrap/buildscript-gradle.lockfile`, plus any other module lockfile whose
  graph shifts.
- **Security config**: `osv-scanner.toml`.
- **Docs**: developer-facing instructions for the local scan command.
- **No production source changes**; no API, port, or behavioural surface is
  touched.
