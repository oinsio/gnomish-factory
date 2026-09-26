# Proposal: add-project-license

## Why

Until 2026-09-26 the repository had no license. Under copyright law that means "all rights
reserved": nobody may legally run, copy, or build on the factory, and an adapter author who
compiles against `gnomish-plugin-api` has no terms to rely on, even though that module's POM
already promises Apache 2.0. The `LICENSE` file landed that day (commit `Add Apache License
2.0`); everything that makes the license real — the notice, the terms inside the shipped jars,
the contributor agreement, the recorded posture — is still missing, and the gap grows with
every release and with the first external contributor, whose code would arrive under no
agreement at all.

Adopting a license also creates an obligation the build does not yet enforce: the fat jar
bundles roughly thirty third-party jars (27 artifacts on the runtime classpath today), and
nothing fails the build if a future dependency drags a copyleft license into that jar. The
runtime inventory was audited on 2026-09-26 and is clean (every shipped library is Apache-2.0
or MIT, plus two dual-licensed EPL-2.0 libraries), so this is the moment to lock the posture
in rather than to remediate it later.

## What Changes

- **ADDED**: the project is licensed under the Apache License 2.0. The `LICENSE` file is
  already in the repository root; this change guards its presence in CI and never generates
  or overwrites it.
- **ADDED**: a `NOTICE` file at the repository root, minimal in the Apache Software
  Foundation sense — product name, the copyright line `Copyright 2026 The Gnomish Factory
  Authors`, the license statement, and the record of which option the project takes for its
  two dual-licensed dependencies.
- **ADDED**: both distributed artifacts (the `:bootstrap` boot jar and the
  `:gnomish-plugin-api` jar) carry `LICENSE` and `NOTICE` under `META-INF/`, wired by one
  build convention so there is one place that names the source files.
- **ADDED**: a dependency-license gate in CI over the runtime classpath of the two
  distributed modules, driven by one allowlist file; a dependency with no allowed license
  fails the job.
- **ADDED**: an ADR recording the license choice, the dual-license rule, the position on
  out-of-process tools and on test/build-only dependencies, and the contributor agreement.
- **ADDED**: a Contributor License Agreement (CLA) required from external contributors,
  checked on pull requests, with a `CONTRIBUTING.md` describing it — and stating that pull
  requests are not accepted for now (single-maintainer phase); the CLA is in place for the
  day they open.
- **ADDED**: a community plugin registry, `docs/community-plugins.md`, listing third-party
  plugins built against `gnomish-plugin-api` as-is, without vetting or endorsement. Authors
  announce a plugin through a GitHub issue form; the maintainer adds the entry, so no pull
  request is needed from the author. The terms *community plugin registry* and *plugin
  announcement* enter the glossary.
- **MODIFIED**: README gains a License section; the developer guide gains the local
  reproduction of the license gate beside the existing OSV section.
- **MODIFIED**: `quality-gates` gains the license gate as a CI-enforced requirement.

## Capabilities

### New Capabilities

- `project-licensing`: the terms under which the factory and the plugin contract are
  distributed and contributed to — the license and notice files, their presence in every
  shipped artifact, the recorded option for dual-licensed dependencies, and the contributor
  agreement.

### Modified Capabilities

- `quality-gates`: CI gains a dependency-license gate over the runtime classpath of the
  distributed modules (an ADDED requirement beside the existing security-scanning one; no
  existing requirement changes).

## Goals

- **G1** — Every use of the factory and of the plugin contract is covered by one explicit
  license, stated identically in the repository root, in both shipped jars, and in the
  published POM.
- **G2** — A redistributor of the boot jar can meet Apache 2.0 §4(d) and EPL-2.0 §3.2 by
  copying one file: the `NOTICE` names the bundled dual-licensed components, the option
  taken, and where their source lives.
- **G3** — A dependency whose only licenses are copyleft cannot reach a distributed jar
  without failing CI; a dual-licensed dependency with one permissive option passes without
  a per-package exception.
- **G4** — The project keeps the ability to relicense after external contributions arrive,
  and the price of that ability (a signature) is paid once per contributor.

## Non-Goals

- **NG1** — Per-file license headers (full Apache header or one-line SPDX). Deferred; the
  root `LICENSE` governs the whole repository, and a 13-line header competes with the
  100–120-line file budget.
- **NG2** — Licensing of test-only and build-only dependencies (Spock, WireMock, Jetty, JUnit,
  PIT, Error Prone, Spotless, japicmp). They are never shipped and stay outside the gate.
- **NG3** — Replacing OSV-Scanner or changing the CVE gate. OSV-Scanner's own license mode
  was evaluated for this purpose and does not fit (the evidence is recorded in the design);
  the CVE gate is untouched.
- **NG4** — A hand-maintained list of every third-party dependency with versions. Such a list
  drifts with every Dependabot bump; a generated report is the only complete inventory.
- **NG5** — Publishing `gnomish-plugin-api` to Maven Central. Out of scope; the change only
  makes the jar it would publish carry its terms.
- **NG6** — Wiring the license gate into `./gradlew check`. The gate runs in its own CI job;
  the build-shape constraint that forces this is recorded in the design (D2).

## Users & Scenarios

- **U1** — An operator downloads the boot jar and asks their compliance tooling what they
  may do with it: the jar answers with `META-INF/LICENSE` and `META-INF/NOTICE`, and the
  NOTICE explains why an LGPL string appears in logback's metadata.
- **U2** — An adapter author compiles a closed-source tracker plugin against
  `gnomish-plugin-api`: Apache 2.0 permits it, the patent grant protects them, and the POM,
  the jar, and the repository agree on the terms.
- **U3** — A maintainer merges a Dependabot bump that swaps a transitive library for an
  LGPL-only fork: the license job fails on that PR, naming the module and its licenses,
  before anything ships.
- **U4** — An external contributor opens a first pull request: the CLA check asks for one
  signature, records it, and stays green on every later PR from the same account.
- **U5** — A maintainer reviewing a suspicious red license job reproduces the verdict locally
  with one documented command and reads the same report CI produced.
- **U6** — A plugin author has built a Jira tracker adapter against `gnomish-plugin-api` and
  wants operators to find it: they fill in the plugin-announcement issue form, the maintainer
  adds one row to the registry, and the entry carries the plugin's own license so an
  operator knows the factory's terms do not extend to it.

## Requirements

### Functional

- **FR1** — The repository root SHALL contain `LICENSE` with the Apache License 2.0 text. The
  file is present since 2026-09-26; the license CI job SHALL verify its presence on every
  run, and no build task SHALL generate or overwrite it.
- **FR2** — The repository root SHALL contain `NOTICE` holding exactly: the product name, the
  line `Copyright 2026 The Gnomish Factory Authors`, the Apache 2.0 statement, a statement
  that the boot jar bundles third-party jars unmodified with their own `META-INF` notices,
  and, for each dual-licensed bundled dependency (Logback, Jakarta Annotations), the option
  taken (EPL-2.0) and the URL of its source repository.
- **FR3** — The `:bootstrap` boot jar and the `:gnomish-plugin-api` jar SHALL each contain
  `META-INF/LICENSE` and `META-INF/NOTICE`, byte-identical to the repository-root files, and
  the license CI job SHALL verify that identity by comparing contents, not by listing names.
- **FR4** — The build SHALL provide a license gate that resolves the `runtimeClasspath` of
  `:bootstrap` and `:gnomish-plugin-api`, reads every declared license of every resolved
  module, and fails when a module declares no license that the allowlist accepts.
- **FR5** — One allowlist file SHALL be the only source of accepted licenses. It SHALL accept
  the licenses known under the SPDX identifiers Apache-2.0, MIT, BSD-2-Clause, BSD-3-Clause,
  EPL-1.0, EPL-2.0, MPL-2.0, CDDL-1.0, CDDL-1.1 and GPL-2.0 with Classpath Exception, and
  SHALL NOT accept LGPL, GPL or AGPL in any version. The identifiers name licenses; the
  spelling the file uses is the design's choice (D4). A module with several declared licenses
  SHALL pass when at least one is accepted.
- **FR6** — The gate SHALL normalize license-name variants (`Apache 2`, `The Apache Software
  License, Version 2.0`, `Apache-2.0`) to one canonical name before matching, so a spelling
  difference is never a violation and never an exception entry.
- **FR7** — A dedicated CI workflow SHALL run the gate on every push and on fork pull
  requests, with the invocation flags the design fixes for this build shape (D2), and SHALL
  run the FR1 and FR3 checks in the same job. It SHALL NOT be part of `./gradlew check`.
- **FR8** — The gate SHALL be covered by a functional build test that runs it against a
  miniature project with an offline Maven repository: a module declaring only LGPL fails,
  a module declaring `EPL-2.0` and `LGPL-2.1` passes, and a module declaring an Apache
  spelling variant passes.
- **FR9** — `docs/adr/0009-project-license.md` SHALL record: the Apache 2.0 choice; the
  dual-license rule (SPDX `OR` is the licensee's choice; Maven POM `<licenses>` is a flat
  list that loses the operator, so tooling treats several declared licenses as
  "at least one accepted"; the project takes the EPL option and records it); the exclusion
  of LGPL from the allowlist despite its linkability, because a fat jar blurs the
  separate-library boundary; the position that git, docker and agent CLIs are subprocesses
  the project never distributes; that test- and build-only dependencies are out of scope;
  the CLA decision and its reversibility.
- **FR10** — External contributions SHALL require a signed CLA, checked automatically on
  pull requests and recorded durably; `CONTRIBUTING.md` SHALL state the requirement, link the
  CLA text, and explain the one-time signature flow.
- **FR11** — README SHALL gain a License section stating Apache 2.0, pointing at `NOTICE` and
  `CONTRIBUTING.md`, and stating that the agent CLI a sandbox image installs is brought by
  the operator under its vendor's terms.
- **FR12** — The developer guide SHALL document how to reproduce the license gate locally with
  the same verdict CI produces, beside the OSV section.
- **FR13** — `CONTRIBUTING.md` SHALL state that pull requests are not accepted for now,
  that the CLA check nevertheless exists and will apply when they open, and that the two
  ways to contribute today are issues (bug reports, proposals) and plugin announcements
  (FR14).
- **FR14** — The repository SHALL hold a community plugin registry at
  `docs/community-plugins.md`: one row per third-party plugin with name, link, what tracker
  or check it adapts, maintainer, and the plugin's declared license; a disclaimer that the
  project lists plugins as-is and neither vets nor endorses them; and the instruction that a
  plugin is announced by opening an issue from the plugin-announcement form, whose fields
  match the row's columns. The maintainer adds the row; no pull request is needed from the
  author. README and the adapter-author guide SHALL link the registry. The terms *community
  plugin registry* and *plugin announcement* SHALL be defined in `docs/glossary.md`.

### Non-Functional Reliability

- **NFR-R1** — The gate SHALL be deterministic over the committed lock state: the same
  lockfiles produce the same verdict locally and in CI, with no network-dependent license
  lookup beyond the POMs Gradle already resolves.
- **NFR-R2** — The gate SHALL be idempotent with respect to Dependabot: a version bump that
  keeps a module's licenses SHALL need no allowlist edit.

### Non-Functional Observability

- **NFR-O1** — A failing gate SHALL name each violating module with its coordinates and
  every license it declares, in the job log and in a report attached to the run.
- **NFR-O2** — Every run SHALL attach the full generated license inventory of the two
  distributed modules as a workflow artifact, so the complete list exists without being
  hand-maintained (NG4).

### Non-Functional Security

- **NFR-S1** — The license workflow SHALL run with a read-only token. The CLA check needs
  write access to record signatures, to comment on pull requests and to set the commit
  status; it SHALL run in its own workflow with exactly the scopes the design fixes (D7) and
  no others, and SHALL not check out or execute pull-request code.
- **NFR-S2** — New build plugins SHALL enter through the version catalog and the committed
  lockfiles and verification metadata, so the existing OSV and dependency-verification gates
  cover them.

### Non-Functional Cost

- **NFR-C1** — The license job and the CLA job SHALL not double-run per commit (same-repo PR
  runs skipped, as the existing workflows do) and SHALL carry the 30-minute per-job timeout
  the `quality-gates` capability requires of every CI job.

## Operator Experience Criteria

- **UX1** — A red license job reads, in its first lines, which module failed and which
  licenses it declares; the maintainer does not open the HTML report to learn that.
- **UX2** — A contributor sees the CLA request as one comment with one action; after
  signing, the check turns green on that PR without a new push.
- **UX3** — README's License section is under ten lines; the legal detail lives in `NOTICE`,
  `CONTRIBUTING.md` and the ADR.
- **UX4** — A plugin author announces a plugin in one issue form with five fields and no
  git operation; `CONTRIBUTING.md` says in its first paragraph that PRs are closed, so
  nobody prepares one in vain.

## Success Metrics

- **M1** — The gate passes on the current inventory with zero allowlist exceptions
  (no `moduleName`-scoped rules), verified on the change's own CI run.
- **M2** — The FR8 functional test fails the miniature project's LGPL-only module and passes
  its dual-licensed and spelling-variant modules; three scenarios, all green.
- **M3** — Both built jars carry `META-INF/LICENSE` and `META-INF/NOTICE` whose bytes equal
  the root files, asserted by the CI job's comparison step.
- **M4** — A pull request from an account without a signature is blocked by the CLA check;
  one from a signed account is not (verified once, manually, on the first external PR or a
  test account).
- **M5** — The registry exists with its disclaimer and the issue form's fields equal the
  registry's columns one to one; the first entry (the in-repo `gnomish-plugin-api:sample`
  stand-in, or a real plugin) is added by the maintainer from a filled-in form, not by hand.

## Open Questions

- **Q1** — Which CLA text: the Apache Individual CLA adapted to name the project, or a
  shorter Harmony-style individual agreement? The change drafts from the Apache ICLA; the
  human reviews and approves the wording before the check is enabled, since it is a legal
  document.
- **Q2** — Should a Corporate CLA be offered from day one, or only when a company
  contributor appears? Proposed: individual only now; the ADR notes the extension.
- **Q3** — When the license-report plugin gains configuration-cache support, should the gate
  move into `check`? Proposed: revisit when the plugin's changelog says so; the ADR carries
  the trigger.

## Impact

- New files: `NOTICE`, `CONTRIBUTING.md`, `CLA.md`, `docs/adr/0009-project-license.md`,
  `docs/community-plugins.md`, `.github/ISSUE_TEMPLATE/plugin-announcement.yml`,
  `config/allowed-licenses.json`, `.github/workflows/license-gate.yml`,
  `.github/workflows/cla.yml`, two convention plugins in `build-logic`
  (`license-gate-conventions`, `distribution-terms-conventions`) with the functional test
  of the gate.
- Modified: root `build.gradle` (applies the gate convention), `bootstrap/build.gradle`
  (applies the distribution-terms convention),
  `build-logic/src/main/groovy/published-api-conventions.gradle` (same for the published
  jar), `gradle/libs.versions.toml`, `build-logic/build.gradle` (plugin marker), lockfiles and
  `gradle/verification-metadata.xml`, `README.md`, `docs/guides/developer-guide.md`,
  `docs/guides/adapter-author-guide.md` (registry link), `docs/glossary.md` (two terms),
  `openspec/specs/quality-gates`.
- Build-file budget: the root `build.gradle` (195 lines) and `bootstrap/build.gradle`
  (189 lines) sit under the 200-line cap `ModuleBuildFileSpec` enforces; each gains one
  `id` line and at most one comment line, and nothing else.
- Assumption recorded: plugin announcements travel through issues, not pull requests,
  because pull requests are closed (FR13); if PRs open later, the registry may also accept
  them without changing its format.
- Ordering with other active changes: `add-parameter-count-gate` also edits `build-logic`
  and regenerates the lockfiles and `gradle/verification-metadata.xml`; whichever lands
  second regenerates the lock state with the documented combined command after merging.
- No production Java changes; no module edge changes; no runtime behavior changes.
- Repository settings the human performs outside the diff: the `cla-signatures` branch, the
  `plugin-announcement` label, branch protection (tasks section 6).
