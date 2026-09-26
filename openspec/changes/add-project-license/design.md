# Design: add-project-license

## Context

See proposal.md, "Why". The facts the decisions rest on, all verified on 2026-09-26:

- `LICENSE` (Apache License 2.0, 201 lines) is committed at the repository root since
  2026-09-26. Nothing else of the licensing posture exists yet.
- Two artifacts are distributed: the `:bootstrap` boot jar and the `:gnomish-plugin-api` jar.
  Spring Boot nests dependency jars unmodified under `BOOT-INF/lib/`, so each library's own
  `META-INF/LICENSE` and `NOTICE` travel inside the boot jar without any action of ours.
- The `:bootstrap` runtime classpath resolves 27 third-party artifacts: Spring 7.0.8 / Boot
  4.1.0, Jackson 2.22.1, SnakeYAML 2.6, Resilience4j 2.4.0, Micrometer 1.17.0, log4j-api and
  log4j-to-slf4j 2.25.5, commons-logging 1.3.6 and jspecify (Apache-2.0); slf4j-api and
  jul-to-slf4j 2.0.18 (MIT); logback 1.6.1 (`EPL-2.0` or `LGPL-2.1-only`);
  jakarta.annotation-api 3.0.0 (`EPL-2.0` or `GPL-2.0 with Classpath Exception`).
  `:gnomish-plugin-api` adds only project modules, slf4j-api and the
  `spring-boot-dependencies` platform (a BOM with an Apache-2.0 POM, which the report will
  list as a module). No copyleft-only module exists.
- Maven POM `<licenses>` is a flat list. Logback's own license page says the correct SPDX form
  is `EPL OR LGPL-2.1` and that its POM cannot express the `OR`. Any tool reading Maven
  metadata therefore sees two licenses and must decide what a list means.
- `gradle.properties` enables `org.gradle.configuration-cache` and `org.gradle.parallel`.
- The build already runs three CI-only scanners (CodeQL, OSV-Scanner, Gitleaks) in their own
  workflows; the OSV allowlist `osv-scanner.toml` is the precedent for a single repository-root
  policy file.
- `ModuleBuildFileSpec` in `:bootstrap` fails the build when any Gradle script — module build
  file or convention plugin — exceeds 200 lines. The root `build.gradle` has 195, and
  `bootstrap/build.gradle` has 189.

## Goals / Non-Goals

Design-level boundaries beyond the proposal: the gate must not change how `./gradlew check`
runs (NG6); no new module edge and no production code; the allowlist must express the
"at least one accepted" rule without per-module entries for the current inventory (M1); no
build file may cross the 200-line cap.

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
permission, concurrency and `timeout-minutes: 30` shape of `gitleaks.yml`, and uploads the
report directory as an artifact (NFR-O2). The same job holds the FR1 presence step
(`test -f LICENSE -a -f NOTICE`) and the FR3 identity step — for each of the two jars and each
of the two files, `unzip -p <jar> META-INF/<file> | cmp - <file>` — because they are
distribution checks, not code checks, and belong with the distribution gate; a listing
(`unzip -l`) would prove a name, not the bytes FR3 promises. *Rationale:* CLAUDE.md already
keeps security scanning CI-only; a `check`-wired task that had to be run with two global
flags would make every local `check` slower or fail. *Alternative rejected:* a `check`-wired
task with the flags forced from the convention plugin — Gradle offers no per-task way to
disable the configuration cache or parallel execution.

**D3 — One convention plugin, `license-gate-conventions`, applied at the root project.** It
applies the jk1 plugin to the root with `projects = [project(':bootstrap'),
project(':gnomish-plugin-api')]`, `configurations = ['runtimeClasspath']`, the normalizer, an
inventory renderer, and `allowedLicensesFile = config/allowed-licenses.json`. The root
`build.gradle` gains exactly two lines: the `id` and one comment line pointing at D2.
*Rationale:* the root build file is five lines under the 200-line cap and FR6 of
`split-into-modules` keeps build logic in `build-logic`; a convention plugin is also what the
TestKit suite there can exercise (FR8), on the model of `api-compatibility-gate-conventions`.
The plugin version enters `gradle/libs.versions.toml` and the marker `build-logic/build.gradle`,
so `buildscript` locking, verification metadata, Dependabot and OSV cover it (NFR-S2).
*Alternative rejected:* configuring the plugin inline in the root `build.gradle` —
untestable by TestKit, over the line cap, and against the build-logic placement rule.

**D4 — The allowlist names the canonical license names the default normalizer emits, not SPDX
identifiers.** The default bundle emits names such as `Apache License, Version 2.0`,
`MIT License`, `Eclipse Public License - v 2.0`; the allowlist's `moduleLicense` rules match
those outputs, one rule per license FR5 names. The file lists only license rules — no
`moduleName` rule for the current inventory (M1) — and carries a header comment explaining the
"at least one" semantics and why LGPL is absent. *Rationale:* matching the normalizer's output
is what makes a spelling variant a non-event (FR6), and the default bundle is the one the
plugin's `checkLicense` documentation is written against. *Alternative rejected:* the plugin's
own `SpdxLicenseBundleNormalizer`, which would let the allowlist spell FR5's identifiers
literally. It is a second, separately maintained bundle in the plugin, and its coverage of the
exact strings logback and Jakarta Annotations declare is not documented; task 2.3 verifies the
chosen normalizer maps both dual-licensed modules' strings, and the ADR notes that switching
bundles is a one-line change plus a rewrite of the allowlist, should SPDX spelling become
preferable.

**D5 — The full inventory is a CI artifact, never packaged into the jar.** `NOTICE` stays
minimal (ASF practice, FR2); the generated report is uploaded per run (NFR-O2). *Rationale:*
packaging the report into the boot jar would make `bootJar` depend on a task that cannot
run under the configuration cache, dragging D2's constraint into every local build.
*Alternative rejected:* `META-INF/THIRD-PARTY.txt` generated at jar time.

**D6 — Jar contents come from one owner: the root files, wired by one convention plugin.**
`distribution-terms-conventions` in `build-logic` configures every `Jar` task of the module
that applies it with `metaInf { from rootProject.layout.projectDirectory.file('LICENSE'),
rootProject.layout.projectDirectory.file('NOTICE') }`. `bootstrap/build.gradle` and
`published-api-conventions.gradle` each apply it with one `id` line, so the source files are
named in exactly one script and neither module build file grows past its cap. The CI identity
step (D2) proves both jars carry the root bytes. *Rationale:* two hand-written `metaInf`
blocks would be a second implementation of one rule with nothing but review to keep them
equal (`manual-sync-pairs.md`), and the second block would push `bootstrap/build.gradle`
toward the 200-line cap. *Alternatives rejected:* per-module copies of the files; the same
`metaInf` block written twice.

**D7 — The CLA check is the `contributor-assistant/github-action`, signatures in-repo, in the
shape the maintainer's `clear-progress` repository already runs.** It runs in
`.github/workflows/cla.yml`, pinned to `v2.6.1`, on `pull_request_target` (`opened`,
`synchronize`) and `issue_comment` (`created`), with a job `if:` that lets a comment through
only when its body is `recheck` or the exact signing sentence — every other comment on every
PR is skipped without a run. Signatures go to `signatures/cla.json` on a `cla-signatures`
branch — an unprotected branch the human creates before the workflow is merged, because the
action writes through the contents API and its documentation requires the branch to be
unprotected. The workflow-level permissions are `contents: write`, `pull-requests: write` and
`statuses: write`, and no others (NFR-S1): the first records the signature file, the second
posts the one signing comment, the third sets the check's commit status. Workflow level, not
job level, is what `gitleaks.yml` and `osv-scan.yml` do; with one job the scope is the same.
The action's README also lists `actions: write`; `clear-progress` runs without it, so it is
omitted. The job carries `timeout-minutes: 30` and the concurrency group the other workflows
use, never checks out PR code, and allowlists the repository owner (a maintainer PR must not
ask its author to sign), `dependabot[bot]` and `github-actions[bot]`. The two comment texts
are set explicitly: `custom-notsigned-prcomment` links `CLA.md` on `main` and quotes the one
sentence to reply with; `custom-pr-sign-comment` is that sentence, `I have read the CLA
Document and I hereby sign the CLA.` — so the contributor sees one comment with one action
(UX2) and `CONTRIBUTING.md` can quote the same sentence. The agreement text is `CLA.md`,
drafted from the Apache Individual CLA with the project name substituted and laid out in the
seven-section shape of `clear-progress`'s agreement (definitions, copyright grant, patent
grant, retention of rights, representations, no obligation, governing law, then the signing
sentence); the licensing terms are not copied, since that agreement serves a noncommercial
license and this one serves Apache 2.0. The human approves the wording before the check is
enabled (Q1). *Rationale:* an in-repo action is auditable in the diff and needs no
third-party OAuth grant over the repository, and reusing a configuration the maintainer has
already exercised removes the permission and trigger guesswork. *Alternatives rejected:* the
hosted cla-assistant.io service (an external service with repository-wide OAuth scope); DCO
(does not grant the relicensing right that motivated the choice, proposal G4); copying
`clear-progress`'s `CLA.md` verbatim (wrong license model, wrong copyright holder).

**D8 — Global policy lives in ADR 0009; this design records only the local mechanics.** The
dual-license rule, the LGPL exclusion, the subprocess position, the test/build exemption and
the CLA reversibility outlive this change and are cited by capability
(`project-licensing`), not by change name. *Alternative rejected:* leaving them here, where
they archive with the change.

**D9 — Plugin announcements are issues, the registry lives in `docs/`.** Pull requests are
closed for now (FR13), so the one contribution flow that must stay open — telling operators a
plugin exists — cannot be a PR. An issue form (`.github/ISSUE_TEMPLATE/plugin-announcement.yml`)
with exactly the registry's five columns gives the maintainer a row to paste; the form applies
the `plugin-announcement` label, which the human creates in the repository before the form is
merged (an issue form cannot create a label). The registry is `docs/community-plugins.md`, not
a README section, so README stays short (UX3) and the list can grow. Every entry carries the
plugin's own license and the page carries the as-is disclaimer, because a registry row is the
one place an operator could mistake a third-party plugin for part of the Apache-licensed
factory. Both terms — *community plugin registry* and *plugin announcement* — are defined in
`docs/glossary.md` in this change, as `process-invariants.md` requires of a new domain term.
*Alternatives rejected:* a README table (README length); PR-based additions (closed by FR13;
the format admits them later without change); an external "awesome list" repository (splits
the record from the contract it lists).

**Sync surfaces: none** — this change adds no parallel implementation and touches no
declared pair. The two places that could have become a pair are dissolved by construction:
the `metaInf` wiring is one convention plugin applied twice (D6), not two blocks; and the
allowlist's names must equal the normalizer's output names (D4), which is a dependency on the
plugin's bundle, not a hand-synced pair in this codebase — the FR8 functional test is what
fails if the bundle renames a canonical name.

**Single-owner mechanisms.**

| Owner                                              | Value (type)                                   | Consumers                                                                                                     | Old way removed                                                                                    | Enforced by                                                                                                                                                                                 |
|----------------------------------------------------|------------------------------------------------|---------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `config/allowed-licenses.json`                     | the accepted-license set (jk1 allowlist rules) | `checkLicense` via `license-gate-conventions` (root)                                                          | none pre-existed; no other allowlist may be added — the convention plugin hard-codes this one path | the convention plugin sets `allowedLicensesFile` once; the FR8 functional test runs the gate through the plugin, not a hand-built configuration                                             |
| `distribution-terms-conventions` over root `LICENSE` and `NOTICE` | the distribution terms (files) | `bootstrap/build.gradle` (applies the plugin); `published-api-conventions.gradle` (applies the plugin) | no per-module copies and no hand-written `metaInf` block exist or are introduced                   | the CI identity step (D2) compares bytes in both jars; `grep -rn "metaInf" --include=*.gradle` outside `distribution-terms-conventions.gradle` returns nothing (task 5.1 records the sweep) |

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
- [The action needs `actions: write` after all] → `clear-progress` runs the same action and
  version without it; task 3.3's verification on a test pull request confirms it here before
  the check is made required, and if it fails the grant is added with its reason in D7,
  never silently.
- [Someone deletes or edits `LICENSE`] → the license job's presence step fails on the next
  push; the identity step fails if the jars and the root ever disagree.
- [A `moduleName`-scoped rule is added quietly later] → the header comment demands a reason
  beside it, and `/audit-codebase` reviews the allowlist as a policy file like
  `osv-scanner.toml`.
- [A build file crosses the 200-line cap] → `ModuleBuildFileSpec` fails `:bootstrap:test`;
  D3 and D6 keep each module build file's growth to one `id` line and at most one comment.

## Migration Plan

1. The human creates the empty, unprotected `cla-signatures` branch and the
   `plugin-announcement` label (tasks 6.1, 6.2).
2. Land the build, workflow and documentation changes; the license job is green on the first
   run because `LICENSE` already exists.
3. The human reviews `CLA.md`, enables the CLA workflow ("enabling" is merging it) and makes
   the `CLA` and `License gate` checks required in branch protection, together with the
   existing checks (task 6.3).
4. Rollback: delete the two workflows and the two convention applications; `NOTICE`, the ADR
   and the jar `META-INF` entries are inert and can stay.

## Open Questions

- Q1–Q3 of the proposal. None changes the specs or the task breakdown: the CLA wording is a
  text the human edits in place; a corporate CLA is an additional file; moving the gate into
  `check` is a later change triggered by the plugin's changelog.
