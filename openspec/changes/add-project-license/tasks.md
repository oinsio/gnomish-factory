# Tasks

The AI agent never commits (`.claude/rules/process-invariants.md`); the last task recommends
one commit message. `LICENSE` is already committed at the repository root (2026-09-26); no
task creates, edits or tolerates its absence.

## 1. Notice and distribution terms

- [x] 1.1 Write `NOTICE` at the repository root with exactly the FR2 content: product name,
      `Copyright 2026 The Gnomish Factory Authors`, the Apache 2.0 statement, the
      bundled-unmodified statement, and the two EPL-2.0 entries (Logback →
      https://github.com/qos-ch/logback; Jakarta Annotations →
      https://github.com/jakartaee/common-annotations-api) with no versions and no other
      dependencies (FR2, D5). Verify: the file is under 25 lines and `grep -c "EPL-2.0" NOTICE`
      returns 2.
- [x] 1.2 Write `build-logic/src/main/groovy/distribution-terms-conventions.gradle` (FR3,
      D6): configures every `Jar` task of the applying module with `metaInf { from
      rootProject.layout.projectDirectory.file('LICENSE'),
      rootProject.layout.projectDirectory.file('NOTICE') }`; apply it with one `id` line in
      `bootstrap/build.gradle` and in
      `build-logic/src/main/groovy/published-api-conventions.gradle`. Verify: `./gradlew
      :bootstrap:bootJar :gnomish-plugin-api:jar`, then for each jar and each of the two
      files `unzip -p <jar> META-INF/<file> | cmp - <file>` exits 0; `grep -rn metaInf
      --include='*.gradle' .` matches only the new convention file; `wc -l build.gradle
      bootstrap/build.gradle` stays ≤ 200 (`ModuleBuildFileSpec`).

## 2. The license gate

- [x] 2.1 Add the jk1 plugin (`com.github.jk1.dependency-license-report`, 3.1.4) to
      `gradle/libs.versions.toml` under `[plugins]` and its marker to
      `build-logic/build.gradle` (D3, NFR-S2). Regenerate the lock state with the documented
      combined command `./gradlew check --write-locks --write-verification-metadata sha256`;
      if it reports a lock mismatch inside `:build-logic`, run `./gradlew -p build-logic
      dependencies --write-locks` once and repeat the combined command
      (`docs/guides/developer-guide.md`, "Dependency locking and verification"). Verify:
      `build-logic/gradle.lockfile`, `buildscript-gradle.lockfile` and
      `gradle/verification-metadata.xml` list the plugin, and `./gradlew check` passes on the
      untouched modules.
- [x] 2.2 Write `config/allowed-licenses.json` (FR5, NFR-R2, D4): `moduleLicense` rules
      only, using the default normalizer's canonical names for Apache-2.0, MIT, BSD-2-Clause,
      BSD-3-Clause, EPL-1.0, EPL-2.0, MPL-2.0, CDDL-1.0, CDDL-1.1 and GPL-2.0 with Classpath
      Exception; no LGPL/GPL/AGPL; no version-scoped rule, so a version bump never touches
      the file; a header comment (or a sibling `README` if JSON comments are refused)
      stating the "at least one" semantics and the LGPL exclusion. Verify: `grep -i lgpl` on
      the file matches only the explanatory text, and no `moduleName` or `moduleVersion` key
      exists.
- [x] 2.3 Write `build-logic/src/main/groovy/license-gate-conventions.gradle` (FR4, FR6, D3):
      applies the jk1 plugin with `projects` = the two distributed modules, `configurations
      = ['runtimeClasspath']`, `LicenseBundleNormalizer(createDefaultTransformationRules:
      true)`, an inventory renderer, and `allowedLicensesFile` = the 2.2 file; apply it in
      the root `build.gradle` with the `id` line and one comment line referencing D2.
      Verify: `./gradlew checkLicense --no-configuration-cache --no-parallel` passes on the
      current inventory; `build/reports/dependency-license/` lists logback and
      jakarta.annotation-api with `Eclipse Public License - v 2.0` among their normalized
      licenses (the D4 check that the default bundle maps both dual-licensed modules); `wc -l
      build.gradle` ≤ 200.
- [x] 2.4 Write `LicenseGateFunctionalSpec` in `build-logic/src/functionalTest` on the
      model of `ApiCompatibilityGateFunctionalSpec` (FR8, M2): a miniature project with a
      file-based Maven repository holding three hand-written POMs — LGPL-2.1-only,
      `EPL-2.0` + `LGPL-2.1`, and `The Apache Software License, Version 2.0` — and three
      scenarios asserting `checkLicense` fails on the first (naming the coordinates and the
      license, NFR-O1, UX1) and passes on the other two. Verify: `./gradlew
      :build-logic:functionalTest` is green with the three new scenarios and each fails when
      its rule is inverted (run once with the allowlist emptied to see all three red).

## 3. CI workflows

- [x] 3.1 Write `.github/workflows/license-gate.yml` (FR7, FR1, FR3, NFR-S1, NFR-C1, NFR-O2)
      mirroring `gitleaks.yml`'s trigger, same-repo-PR skip, `contents: read`, concurrency
      and `timeout-minutes: 30`: steps for JDK 25 + `setup-gradle`, `test -f LICENSE -a -f
      NOTICE`, `./gradlew checkLicense :bootstrap:bootJar :gnomish-plugin-api:jar
      --no-configuration-cache --no-parallel`, the identity step — for each of the two jars
      and each of `LICENSE`/`NOTICE`, `unzip -p <jar> META-INF/<file> | cmp - <file>`,
      failing with the jar and entry named — and `upload-artifact` of
      `build/reports/dependency-license/` with `if: always()`. The presence and identity
      steps call `scripts/check-distribution-terms.sh`, whose red paths
      `DistributionTermsScriptSpec` drives (D2). Verify: `actionlint` (or
      `gh workflow view` after push) accepts the file, the spec is green and goes red with
      `cmp` removed from the script, and the job is green on this branch.
- [x] 3.2 Draft `CLA.md` from the Apache Individual Contributor License Agreement with the
      project name substituted, in the seven-section layout D7 names (definitions,
      copyright grant, patent grant, retention of rights, representations, no obligation,
      governing law), ending with the signing sentence `I have read the CLA Document and I
      hereby sign the CLA.`, and a heading stating that the human approves the wording
      before the check is enabled (FR10, Q1, D7). Verify: the file exists, README/
      CONTRIBUTING link it, and the signing sentence contains the one `cla.yml` matches
      on; mark the approval as the human's action in 6.3.
- [x] 3.3 Write `.github/workflows/cla.yml` with `contributor-assistant/github-action`
      `v2.6.1` pinned by commit SHA (FR10, NFR-S1, NFR-C1, UX2, D7): triggers
      `pull_request_target` (`opened`, `synchronize`) and `issue_comment` (`created`); a job `if:` admitting every
      `pull_request_target` event and only those comments whose body is `recheck` or contains
      the signing sentence; workflow-level permissions exactly `contents: write`,
      `pull-requests: write`, `statuses: write`; `timeout-minutes: 30` and the concurrency
      group the other workflows use; `path-to-document` = `CLA.md` on `main`,
      `path-to-signatures` = `signatures/cla.json`, `branch` = `cla-signatures` (created by
      the human in 6.1); `allowlist` = repository owner, `dependabot[bot]`,
      `github-actions[bot]`; `custom-notsigned-prcomment` linking `CLA.md` and pointing at
      the sentence the action appends; `custom-pr-sign-comment` unset (D7); no checkout step.
      Verify: `actionlint` accepts it, `grep -c "actions/checkout"` returns 0, the sentence
      in the `if:` is contained in the one in `CLA.md` and `CONTRIBUTING.md` (grep all three); on the
      test pull request of 6.3, signing through the comment turns the check green — if the
      action fails on permissions, add `actions: write` and record the reason in D7.
- [x] 3.4 Write `CONTRIBUTING.md` (FR10, FR13, UX2, UX4): first paragraph — pull requests
      are not accepted for now, contributions today are issues and plugin announcements
      (link the registry from 4.5); then the CLA requirement as preparation for when PRs
      open, the link to `CLA.md`, the one-comment signing flow as four numbered steps (open
      a PR, the bot comments, reply with the signing sentence quoted verbatim, the check
      turns green; sign once, it covers every later PR), and the pointer to the development
      workflow in README. Verify: under 60 lines, the closed-PR statement is in the first
      paragraph, the signing sentence contains the one in `cla.yml`, and every relative link
      resolves (`ls` each target).
- [x] 3.5 Write `.github/ISSUE_TEMPLATE/plugin-announcement.yml` (FR14, UX4, D9): a GitHub
      issue form with exactly five required fields — plugin name, link, what tracker or
      check it adapts, maintainer, declared license — the label `plugin-announcement`
      (created by the human in 6.2), and a preamble stating the as-is disclaimer. Verify: the
      field ids equal the registry's column names one to one (M5) and GitHub's template
      validator accepts the form on push.

## 4. Documentation

- [x] 4.1 Write `docs/adr/0009-project-license.md` (FR9, D8) in the format of ADR 0008:
      Status line naming this change; Context (license added 2026-09-26, clean inventory,
      Maven's flat license list); Decision — Apache 2.0, the dual-license rule, the EPL
      choice recorded in NOTICE, LGPL excluded from the allowlist with the fat-jar reason,
      git/docker/agent CLIs as undistributed subprocesses, test- and build-only dependencies
      out of scope, CLA over DCO and its reversibility, the trigger for revisiting D2 (Q3),
      the note that switching to the SPDX normalizer bundle is a one-line change plus an
      allowlist rewrite (D4); Consequences; Alternatives (OSV `--licenses` with the
      2026-09-26 evidence, DCO). Verify: under 200 lines; cites `project-licensing` and
      `quality-gates` by capability, never a change path.
- [x] 4.2 Add the README License section (FR11, FR14, UX3): Apache 2.0, links to `NOTICE`,
      `CONTRIBUTING.md`, the community plugin registry and the ADR, and the sentence that
      the agent CLI a sandbox image installs is the operator's under its vendor's terms; add
      the section to the Contents list. Verify: the section is at most ten lines and
      README's "Documentation" table lists ADR 0009.
- [x] 4.3 Add "The license gate" to `docs/guides/developer-guide.md` beside the OSV section
      (FR12, NFR-R1): the one local command (`./gradlew checkLicense
      --no-configuration-cache --no-parallel`), where the report lands, what a violation
      means, and how to add a permissive license to the allowlist with its reason. Verify:
      the command in the guide is the one `license-gate.yml` runs (grep both).
- [x] 4.4 In the "CI" paragraph of `docs/guides/developer-guide.md`, add the license job
      and the CLA check to the list of workflows beside CodeQL, OSV-Scanner and Gitleaks
      (FR7, FR10). Verify: `grep -n "license" docs/guides/developer-guide.md` shows the CI
      paragraph.
- [x] 4.5 Write `docs/community-plugins.md` (FR14, D9, M5): the as-is / no-endorsement
      disclaimer, the announcement instruction pointing at the issue form, and a table with
      the five columns; seed it with one row for `gnomish-plugin-api:sample` marked as the
      in-repo stand-in (Apache 2.0) so the format is shown, or leave the table empty with
      a placeholder row if the human prefers — record which. Verify: column headers equal
      the form's field ids; README's Documentation table and the "Adapter authors" paragraph
      link the file.
      Recorded: seeded with the in-repo `gnomish-plugin-api:sample` row, marked as the
      stand-in (the human confirms or empties it in 6.4).
- [x] 4.6 Add a registry pointer to `docs/guides/adapter-author-guide.md` (FR14): one
      sentence in its introduction — "when your adapter is ready, announce it through the
      plugin-announcement issue form and it is listed in `docs/community-plugins.md`".
      Verify: `grep -n community-plugins docs/guides/adapter-author-guide.md README.md`
      shows both.
- [x] 4.7 Add two glossary entries to `docs/glossary.md` in the plugin-contract context
      (FR14, D9): *community plugin registry* — the `docs/community-plugins.md` list of
      third-party plugins, as-is and unendorsed; *plugin announcement* — the issue opened
      from the plugin-announcement form that asks for a registry row. Verify: `grep -n
      "community plugin registry\|plugin announcement" docs/glossary.md` shows both entries.

## 5. Verification

- [x] 5.1 Run the full local proof: `./gradlew check` (unchanged modules green, the gate is
      not in it, `ModuleBuildFileSpec` green), `./gradlew :build-logic:functionalTest`,
      `./gradlew checkLicense :bootstrap:bootJar :gnomish-plugin-api:jar
      --no-configuration-cache --no-parallel`, then the four `unzip -p … | cmp` comparisons
      of 1.2 (FR3, FR4, FR8, M1, M2, M3). Record the single-owner sweep from the design
      table: `grep -rn metaInf --include='*.gradle' .` hits only
      `distribution-terms-conventions.gradle`. Verify: zero violations, no `moduleName` rule
      needed, all four comparisons exit 0.
- [x] 5.2 Run `openspec validate add-project-license --strict` and confirm every FR/NFR/UX
      in proposal.md is referenced by at least one spec, task or artifact
      (`.claude/rules/traceability.md`). Verify: validate passes and a grep per ID finds a
      hit.

## 6. Human actions (recorded here, performed by the human)

- [x] 6.1 Create the empty `cla-signatures` branch in the repository and leave it out of
      branch protection (D7). Verify: `git ls-remote --heads origin cla-signatures` lists it
      before `cla.yml` is merged.
- [x] 6.2 Create the `plugin-announcement` label in the repository (D9). Verify: `gh label
      list` shows it before the issue form is merged.
- [x] 6.3 Review and approve the `CLA.md` wording (Q1), open one pull request from an
      unsigned test account to exercise the check, then make the `CLA` and `License gate`
      checks required in branch protection (M4). Verify: the unsigned pull request is
      blocked until the comment signature; one from the owner is not.
- [x] 6.4 Exercise the announcement path once with the in-repo sample (M5). Decided: the
      registry starts seeded with the `gnomish-plugin-api:sample` row (task 4.5), not
      empty. Open one issue from the `plugin-announcement` form describing the sample and
      confirm its five fields reproduce the seeded row as it stands. Verify: the issue
      carries the `plugin-announcement` label and each form field equals the row's cell
      under the same column. Replacing the sample row with the first real announcement is
      ordinary registry maintenance after this change, not a task of it.

## 7. Hand-off

- [x] 7.1 Recommend one Conventional Commits message from `git diff HEAD`, e.g.
      `build: adopt Apache 2.0 with NOTICE, license gate and CLA` with a trailer
      `OpenSpec: add-project-license (FR1–FR14)`; do not commit. Verify: the message is in
      the final report and the diff does not touch `LICENSE`.
