# Tasks

The AI agent never commits (`.claude/rules/process-invariants.md`); the last task recommends
one commit message. `LICENSE` itself is added by the human through GitHub (task 6.1) — no
task here creates it.

## 1. Notice and distribution terms

- [ ] 1.1 Write `NOTICE` at the repository root with exactly the FR2 content: product name,
      `Copyright 2026 The Gnomish Factory Authors`, the Apache 2.0 statement, the
      bundled-unmodified statement, and the two EPL-2.0 entries (Logback →
      https://github.com/qos-ch/logback; Jakarta Annotations →
      https://github.com/jakartaee/common-annotations-api) with no versions and no other
      dependencies (FR2, D5). Verify: the file is under 25 lines and `grep -c "EPL-2.0" NOTICE`
      returns 2.
- [ ] 1.2 Add the root `LICENSE` and `NOTICE` to `META-INF/` of the boot jar in
      `bootstrap/build.gradle` and of the published jar in
      `build-logic/src/main/groovy/published-api-conventions.gradle`, both reading
      `rootProject.layout.projectDirectory` (FR3, D6). Verify: `./gradlew :bootstrap:bootJar
      :gnomish-plugin-api:jar` then `unzip -l` on each jar lists `META-INF/NOTICE`; with a
      temporary local `LICENSE` file present, `META-INF/LICENSE` too (the committed file
      arrives in 6.1; the build must not fail when it is absent — use `from` on files that
      may be missing, and let the CI presence step be the gate).

## 2. The license gate

- [ ] 2.1 Add the jk1 plugin (`com.github.jk1.dependency-license-report`, 3.1.4) to
      `gradle/libs.versions.toml` under `[plugins]` and its marker to
      `build-logic/build.gradle` (D3, NFR-S2). Verify: `./gradlew help --write-locks
      --write-verification-metadata sha256` regenerates `build-logic/gradle.lockfile`,
      `buildscript-gradle.lockfile` and `gradle/verification-metadata.xml`, and `./gradlew
      check` still passes on the untouched modules.
- [ ] 2.2 Write `config/allowed-licenses.json` (FR5, D4): `moduleLicense` rules only, using
      the normalizer's canonical names for Apache-2.0, MIT, BSD-2-Clause, BSD-3-Clause,
      EPL-1.0, EPL-2.0, MPL-2.0, CDDL-1.0, CDDL-1.1 and GPL-2.0 with Classpath Exception; no
      LGPL/GPL/AGPL; a header comment (or a sibling `README` if JSON comments are refused)
      stating the "at least one" semantics and the LGPL exclusion. Verify: `grep -i lgpl` on
      the file matches only the explanatory text, and no `moduleName` key exists.
- [ ] 2.3 Write `build-logic/src/main/groovy/license-gate-conventions.gradle` (FR4, FR6, D3):
      applies the jk1 plugin with `projects` = the two distributed modules, `configurations
      = ['runtimeClasspath']`, `LicenseBundleNormalizer(createDefaultTransformationRules:
      true)`, an inventory renderer, and `allowedLicensesFile` = the 2.2 file; apply it in
      the root `build.gradle` with a comment naming D2's reason for not wiring `check`.
      Verify: `./gradlew checkLicense --no-configuration-cache --no-parallel` passes on the
      current inventory and `build/reports/dependency-license/` lists logback under EPL.
- [ ] 2.4 Write `LicenseGateFunctionalSpec` in `build-logic/src/functionalTest` on the
      model of `ApiCompatibilityGateFunctionalSpec` (FR8, M2): a miniature project with a
      file-based Maven repository holding three hand-written POMs — LGPL-2.1-only,
      `EPL-2.0` + `LGPL-2.1`, and `The Apache Software License, Version 2.0` — and three
      scenarios asserting `checkLicense` fails on the first (naming the coordinates and the
      license, NFR-O1, UX1) and passes on the other two. Verify: `./gradlew
      :build-logic:functionalTest` is green with the three new scenarios and each fails when
      its rule is inverted (run once with the allowlist emptied to see all three red).

## 3. CI workflows

- [ ] 3.1 Write `.github/workflows/license-gate.yml` (FR7, FR1, FR3, NFR-S1, NFR-C1, NFR-O2)
      mirroring `gitleaks.yml`'s trigger, same-repo-PR skip, `contents: read`, concurrency
      and `timeout-minutes`: steps for JDK 25 + `setup-gradle`, `test -f LICENSE -a -f
      NOTICE`, `./gradlew checkLicense :bootstrap:bootJar :gnomish-plugin-api:jar
      --no-configuration-cache --no-parallel`, `unzip -l` asserting `META-INF/LICENSE` and
      `META-INF/NOTICE` in both jars, and `upload-artifact` of
      `build/reports/dependency-license/` with `if: always()`. Verify: `actionlint` (or
      `gh workflow view` after push) accepts the file, and the job is red on this branch
      until 6.1 lands — by design.
- [ ] 3.2 Draft `CLA.md` from the Apache Individual Contributor License Agreement with the
      project name substituted and a heading stating that the human approves the wording
      before the check is enabled (FR10, Q1). Verify: the file exists and README/
      CONTRIBUTING link it; mark the approval as the human's action in 6.2.
- [ ] 3.3 Write `.github/workflows/cla.yml` with `contributor-assistant/github-action`
      (FR10, NFR-S1, D7): triggers `pull_request_target` and `issue_comment`, permissions
      exactly `contents: write`, `pull-requests: write`, `statuses: write`; signatures on the
      `cla-signatures` branch; `allowlist` = repository owner and `dependabot[bot]`; no
      checkout step. Verify: `actionlint` accepts it and `grep -c "actions/checkout"` on the
      file returns 0.
- [ ] 3.4 Write `CONTRIBUTING.md` (FR10, FR13, UX2, UX4): first paragraph — pull requests
      are not accepted for now, contributions today are issues and plugin announcements
      (link the registry from 4.5); then the CLA requirement as preparation for when PRs
      open, the link to `CLA.md`, the one-comment signing flow, and the pointer to the
      development workflow in README. Verify: under 60 lines, the closed-PR statement is in
      the first paragraph, and every link resolves (`markdown-link-check` or manual `ls`).
- [ ] 3.5 Write `.github/ISSUE_TEMPLATE/plugin-announcement.yml` (FR14, UX4, D9): a GitHub
      issue form with exactly five required fields — plugin name, link, what tracker or
      check it adapts, maintainer, declared license — a label `plugin-announcement`, and a
      preamble stating the as-is disclaimer. Verify: the field ids equal the registry's
      column names one to one (M5) and `gh issue create --web` (or GitHub's template
      validator on push) accepts the form.

## 4. Documentation

- [ ] 4.1 Write `docs/adr/0009-project-license.md` (FR9, D8) in the format of ADR 0008:
      Status line naming this change; Context (no license, clean inventory, Maven's flat
      license list); Decision — Apache 2.0, the dual-license rule, the EPL choice recorded
      in NOTICE, LGPL excluded from the allowlist with the fat-jar reason, git/docker/agent
      CLIs as undistributed subprocesses, test- and build-only dependencies out of scope,
      CLA over DCO and its reversibility, the trigger for revisiting D2 (Q3); Consequences;
      Alternatives (OSV `--licenses` with the 2026-09-26 evidence, DCO). Verify: under 200
      lines; cites `project-licensing` and `quality-gates` by capability, never a change
      path.
- [ ] 4.2 Add the README License section (FR11, FR14, UX3): Apache 2.0, links to `NOTICE`,
      `CONTRIBUTING.md`, the community plugin registry and the ADR, and the sentence that the agent CLI a sandbox image
      installs is the operator's under its vendor's terms; add the section to the
      Contents list. Verify: the section is at most ten lines and README's "Documentation"
      table lists ADR 0009.
- [ ] 4.3 Add "The license gate" to `docs/guides/developer-guide.md` beside the OSV section
      (FR12, NFR-R1): the one local command (`./gradlew checkLicense
      --no-configuration-cache --no-parallel`), where the report lands, what a violation
      means, and how to add a permissive license to the allowlist with its reason. Verify:
      the command in the guide is the one `license-gate.yml` runs (grep both).
- [ ] 4.5 Write `docs/community-plugins.md` (FR14, D9, M5): the as-is / no-endorsement
      disclaimer, the announcement instruction pointing at the issue form, and a table with
      the five columns; seed it with one row for `gnomish-plugin-api:sample` marked as the
      in-repo stand-in (Apache 2.0) so the format is shown, or leave the table empty with
      a placeholder row if the human prefers — record which. Verify: column headers equal
      the form's field ids; README's Documentation table and the "Adapter authors" paragraph
      link the file.
- [ ] 4.6 Add a registry pointer to `docs/guides/adapter-author-guide.md` (FR14): one
      sentence in its introduction — "when your adapter is ready, announce it through the
      plugin-announcement issue form and it is listed in `docs/community-plugins.md`".
      Verify: `grep -n community-plugins docs/guides/adapter-author-guide.md README.md`
      shows both.
- [ ] 4.4 Add the `quality-gates` main-spec cross-reference the CI section of the
      developer guide already makes for OSV: the CI paragraph names the license job among
      CodeQL, OSV-Scanner and Gitleaks. Verify: `grep -n "license" docs/guides/developer-guide.md`
      shows the CI paragraph.

## 5. Verification

- [ ] 5.1 Run the full local proof: `./gradlew check` (unchanged modules green, the gate is
      not in it), `./gradlew :build-logic:functionalTest`, `./gradlew checkLicense
      :bootstrap:bootJar :gnomish-plugin-api:jar --no-configuration-cache --no-parallel`,
      then `unzip -l` on both jars (FR3, FR4, FR8, M1, M2, M3). Verify: zero violations, no
      `moduleName` rule needed, both jars list `META-INF/NOTICE`.
- [ ] 5.2 Run `openspec validate add-project-license --strict` and confirm every FR/NFR/UX
      in proposal.md is referenced by at least one spec, task or artifact
      (`.claude/rules/traceability.md`). Verify: validate passes and a grep per ID finds a
      hit.

## 6. Human actions (recorded here, performed by the human)

- [ ] 6.1 Add `LICENSE` (Apache License 2.0 text) through GitHub's "Add license" flow on the
      repository root (FR1). Verify: the `License gate` workflow's presence step goes green
      on the next push.
- [ ] 6.3 Decide whether the registry starts seeded with the in-repo sample row or empty
      (task 4.5), and on the first real announcement add the row from the issue (M5).
      Verify: the registry's first real entry traces to an issue opened from the form.
- [ ] 6.2 Review and approve the `CLA.md` wording (Q1) and make the `CLA` and `License gate`
      checks required in branch protection (M4). Verify: a pull request from an unsigned
      test account is blocked; one from the owner is not.

## 7. Hand-off

- [ ] 7.1 Recommend one Conventional Commits message from `git diff HEAD`, e.g.
      `build: adopt Apache 2.0 with NOTICE, license gate and CLA` with a trailer
      `OpenSpec: add-project-license (FR1–FR12)`; do not commit. Verify: the message is in
      the final report and the diff contains no `LICENSE` file (6.1 is the human's).
