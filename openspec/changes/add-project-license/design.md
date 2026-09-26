# Design: add-project-license

## Context

See proposal.md, "Why". The facts the decisions rest on, all verified on 2026-09-26:

- Two artifacts are distributed: the `:bootstrap` boot jar and the `:gnomish-plugin-api` jar.
  Spring Boot nests dependency jars unmodified under `BOOT-INF/lib/`, so each library's own
  `META-INF/LICENSE` and `NOTICE` travel inside the boot jar without any action of ours.
- The `:bootstrap` runtime classpath resolves to Spring 7.0.8 / Boot 4.1.0, Jackson 2.22.1,
  SnakeYAML 2.6, Resilience4j 2.4.0, Micrometer 1.17.0, log4j-api and log4j-to-slf4j 2.25.5,
  commons-logging 1.3.6 and jspecify (Apache-2.0); slf4j-api and jul-to-slf4j 2.0.18 (MIT);
  logback 1.6.1 (`EPL-2.0` or `LGPL-2.1-only`); jakarta.annotation-api 3.0.0 (`EPL-2.0` or
  `GPL-2.0 with Classpath Exception`). `:gnomish-plugin-api` adds only project modules and
  slf4j-api. No copyleft-only module exists.
- Maven POM `<licenses>` is a flat list. Logback's own license page says the correct SPDX form
  is `EPL OR LGPL-2.1` and that its POM cannot express the `OR`. Any tool reading Maven
  metadata therefore sees two licenses and must decide what a list means.
- `gradle.properties` enables `org.gradle.configuration-cache` and `org.gradle.parallel`.
- The build already runs three CI-only scanners (CodeQL, OSV-Scanner, Gitleaks) in their own
  workflows; the OSV allowlist `osv-scanner.toml` is the precedent for a single repository-root
  policy file.

## Goals / Non-Goals

Design-level boundaries beyond the proposal: the gate must not change how `./gradlew check`
runs (NG6); no new module edge and no production code; the allowlist must express the
"at least one accepted" rule without per-module entries for the current inventory (M1).

## Decisions

**D1 — The gate is the `com.github.jk1.dependency-license-report` plugin, 3.1.4.** Its
`checkLicense` task passes a module when at least one declared license matches an allowed
rule, which is the SPDX meaning of a choice and the rule FR5 requires; `LicenseBundleNormalizer`
with its default transformation rules folds the spelling variants FR6 names; `configurations`
scopes the report to `runtimeClasspath`. *Rationale:* the semantics match the problem without
a per-module exception list. *Alternatives rejected:* (a) **OSV-Scanner `--licenses`** on the
existing lockfiles — run on 2026-09-26 with osv-scanner 2.6.0 it flagged logback for
`LGPL-2.1-only` although `EPL-2.0` was allowed, because
`pkg/osvscanner/vulnerability_result.go` calls `spdx.Satisfies` on each entry of the deps.dev
license list separately and records a violation for any entry that fails; it also reported
~35 test- and build-scope packages as `non-standard` and cannot scope a Gradle lockfile to
one configuration, so it would need `PackageOverrides` per dual-licensed module and per
unmapped test dependency, a hand-maintained registry that drifts with every bump.
OSV-Scanner stays for CVEs. (b) **CycloneDX SBOM plus an external policy tool** — two tools
and a second data format for one question. (c) **A hand-written allowlist of coordinates** —
NG4.

**D2 — The gate runs in its own CI workflow, not in `check`.** The plugin documents that the
configuration cache is unsupported and that Gradle 9 multi-project builds need `--no-parallel`;
both are on in `gradle.properties`. The workflow invokes
`./gradlew checkLicense --no-configuration-cache --no-parallel`, mirrors the trigger, skip,
permission, concurrency and timeout shape of `gitleaks.yml`, and uploads the report directory
as an artifact (NFR-O2). The same job holds the FR1 and FR3 presence steps (`test -f LICENSE`,
`unzip -l` on both jars), because they are distribution checks, not code checks, and belong
with the distribution gate. *Rationale:* CLAUDE.md already keeps security scanning CI-only; a
`check`-wired task that had to be run with two global flags would make every local `check`
slower or fail. *Alternative rejected:* a `check`-wired task with the flags forced from the
convention plugin — Gradle offers no per-task way to disable the configuration cache or
parallel execution.

**D3 — One convention plugin, `license-gate-conventions`, applied at the root project.** It
applies the jk1 plugin to the root with `projects = [project(':bootstrap'),
project(':gnomish-plugin-api')]`, `configurations = ['runtimeClasspath']`, the normalizer, an
inventory renderer, and `allowedLicensesFile = config/allowed-licenses.json`. *Rationale:*
the root build file is under the 200-line cap and FR6 of `split-into-modules` keeps build
logic in `build-logic`; a convention plugin is also what the TestKit suite there can exercise
(FR8), on the model of `api-compatibility-gate-conventions`. The plugin version enters
`gradle/libs.versions.toml` and the marker `build-logic/build.gradle`, so `buildscript`
locking, verification metadata, Dependabot and OSV cover it (NFR-S2). *Alternative
rejected:* configuring the plugin inline in the root `build.gradle` — untestable by TestKit
and against the build-logic placement rule.

**D4 — The allowlist names canonical license names, not SPDX identifiers.** The normalizer's
default bundle emits names such as `Apache License, Version 2.0`, `MIT License`,
`Eclipse Public License - v 2.0`; the allowlist's `moduleLicense` rules match those outputs.
The file lists only license rules — no `moduleName` rule for the current inventory (M1) — and
carries a header comment explaining the "at least one" semantics and why LGPL is absent.
*Rationale:* matching the normalizer's output is what makes a spelling variant a non-event
(FR6). *Alternative rejected:* SPDX identifiers with a custom normalizer bundle — a second
table to keep in step with the plugin's own.

**D5 — The full inventory is a CI artifact, never packaged into the jar.** `NOTICE` stays
minimal (ASF practice, FR2); the generated report is uploaded per run (NFR-O2). *Rationale:*
packaging the report into the boot jar would make `bootJar` depend on a task that cannot
run under the configuration cache, dragging D2's constraint into every local build.
*Alternative rejected:* `META-INF/THIRD-PARTY.txt` generated at jar time.

**D6 — Jar contents come from one owner: the root files.** `bootstrap/build.gradle` and
`published-api-conventions.gradle` each add `metaInf { from rootProject.layout.projectDirectory
.file('LICENSE'), ...file('NOTICE') }`, so there is no copy to drift. The CI presence step
asserts both jars list both entries. *Alternative rejected:* per-module copies of the files.

**D7 — The CLA check is the `contributor-assistant/github-action`, signatures in-repo.** It
runs in `.github/workflows/cla.yml` on `pull_request_target` and `issue_comment`, writes
signatures to a `cla-signatures` branch (so `contents: write` and `pull-requests: write` are
the only grants, and only that workflow has them, NFR-S1), never checks out PR code, and
allowlists the repository owner and `dependabot[bot]`. The agreement text is `CLA.md`,
drafted from the Apache Individual CLA with the project name substituted; the human approves
the wording before the check is enabled (Q1). *Rationale:* an in-repo action is auditable in
the diff and needs no third-party OAuth grant over the repository. *Alternatives rejected:*
the hosted cla-assistant.io service (an external service with repository-wide OAuth scope);
DCO (does not grant the relicensing right that motivated the choice, proposal G4).

**D9 — Plugin announcements are issues, the registry lives in `docs/`.** Pull requests are
closed for now (FR13), so the one contribution flow that must stay open — telling operators a
plugin exists — cannot be a PR. An issue form (`.github/ISSUE_TEMPLATE/plugin-announcement.yml`)
with exactly the registry's five columns gives the maintainer a row to paste; the registry is
`docs/community-plugins.md`, not a README section, so README stays short (UX3) and the list
can grow. Every entry carries the plugin's own license and the page carries the as-is
disclaimer, because a registry row is the one place an operator could mistake a third-party
plugin for part of the Apache-licensed factory. *Alternatives rejected:* a README table
(README length); PR-based additions (closed by FR13; the format admits them later without
change); an external "awesome list" repository (splits the record from the contract it
lists).

**D8 — Global policy lives in ADR 0009; this design records only the local mechanics.** The
dual-license rule, the LGPL exclusion, the subprocess position, the test/build exemption and
the CLA reversibility outlive this change and are cited by capability
(`project-licensing`), not by change name. *Alternative rejected:* leaving them here, where
they archive with the change.

**Sync surfaces: none** — this change adds no parallel implementation and touches no
declared pair. The one near-miss is D4: the allowlist's names must equal the normalizer's
output names. That is a dependency on the plugin's bundle, not a hand-synced pair in this
codebase; the FR8 functional test is what fails if the bundle renames a canonical name.

**Single-owner mechanisms.**

| Owner                          | Value (type)                                   | Consumers                                                                                | Old way removed                                                                                    | Enforced by                                                                                                                                     |
|--------------------------------|------------------------------------------------|------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------|
| `config/allowed-licenses.json` | the accepted-license set (jk1 allowlist rules) | `checkLicense` via `license-gate-conventions` (root)                                     | none pre-existed; no other allowlist may be added — the convention plugin hard-codes this one path | the convention plugin sets `allowedLicensesFile` once; the FR8 functional test runs the gate through the plugin, not a hand-built configuration |
| root `LICENSE` and `NOTICE`    | the distribution terms (files)                 | `bootstrap/build.gradle` jar `metaInf`; `published-api-conventions.gradle` jar `metaInf` | no per-module copies exist or are introduced                                                       | the CI presence step (D2) lists both entries in both jars                                                                                       |

## Risks / Trade-offs

- [The plugin's normalizer bundle renames a canonical license name in a later release] →
  the FR8 functional test's spelling-variant scenario goes red on the Dependabot PR that
  bumps the plugin, before the real gate misreports anything.
- [`--no-parallel` makes the license job slower than a parallel resolve] → the job resolves
  two configurations of two modules against the warm Gradle cache; bounded by the
  `timeout-minutes` budget, and it runs beside `check`, not inside it.
- [A future dependency is dual-licensed with two non-accepted options, or single-licensed
  under a permissive license the list lacks (ISC, 0BSD, Unicode)] → the gate goes red and the
  fix is one line in the allowlist with its reason; the ADR says a permissive addition needs
  no ADR amendment, a copyleft addition does.
- [`pull_request_target` runs with a write token on the base branch] → the CLA workflow
  never checks out or executes PR code; the action only reads PR metadata and writes the
  signature file. This is the action's documented, intended trigger.
- [The human forgets to add `LICENSE`] → the license job fails on the first push after this
  change lands (FR1 presence step), which is the intended reminder.
- [A `moduleName`-scoped rule is added quietly later] → the header comment demands a reason
  beside it, and `/audit-codebase` reviews the allowlist as a policy file like
  `osv-scanner.toml`.

## Migration Plan

1. Land the build and workflow changes; the license job is red until `LICENSE` exists.
2. The human adds `LICENSE` through GitHub and reviews `CLA.md`.
3. Enable the CLA workflow (it is a file in the diff; "enabling" is merging it) and make the
   `CLA` check required in branch protection, together with the existing checks.
4. Rollback: delete the two workflows and the convention application; `NOTICE`, the ADR and
   the jar `META-INF` entries are inert and can stay.

## Open Questions

- Q1–Q3 of the proposal. None changes the specs or the task breakdown: the CLA wording is a
  text the human edits in place; a corporate CLA is an additional file; moving the gate into
  `check` is a later change triggered by the plugin's changelog.
