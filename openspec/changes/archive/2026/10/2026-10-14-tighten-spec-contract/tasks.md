# Tasks

Sequenced **first** in the queue (proposal, Impact): every archive stage is red until this change
lands. Per-task verification follows `verification-scope.md`; the whole-tree `./gradlew check`
ran in task 7.4, once more in task 8.16, and a last time in task 9.17, after group 9.
Production Java is touched only for a finding sorted into this change at a decision point (proposal NG5, FR11; design D9); such a fix runs the scoped PIT of
`verification-scope.md` for the classes it changes. Group 1 and group 7 run `build-logic`'s
functional tests through the root build (`./gradlew :build-logic:functionalTest` via the root,
never `-p build-logic`).

**Findings** (FR11, D9): `temporary-docs/tighten-spec-contract/findings.md`, one row per
finding — id `F<n>`, capability, scenario or requirement, kind (revealed defect / queued
scenario without a test / no verifier in any test tree / normative remainder without a home /
other), evidence, proposed disposition. Any task may add a row; no task fixes a finding it
adds. Decision points 5.2 and 6.8 sort every open row; the sorted rows are copied under the
`## Decisions` heading at the end of this file. A revealed defect's test stays in place under
`@PendingFeature(reason = "F<n>: …")`; a scenario that moves out is a `pending-test: <change>`
exemption entry. The file has no open row at 7.4.

**Strict preview** (used by groups 3, 4, 6, 7): in a scratch git worktree of the current tree
(`git worktree add <scratch> HEAD`, outside the clone), run
`openspec archive tighten-spec-contract --yes`, then `openspec validate <spec-id> --type spec --strict`
for the capabilities the task touched (or `--specs` for all), then remove the worktree. The
preview never runs in the working clone.

**Inventory tables** live under `temporary-docs/tighten-spec-contract/` (proposal Q3): one file
per capability for group 4 (`requirements-<spec-id>.md`: each normative sentence of a long
requirement, its disposition — stated by scenario `<title>` / new scenario / new requirement /
note) and one per capability for group 5 (`scenarios-<spec-id>.md`: each scenario, its status —
linked `<test file>` / probable / missing / exempt `<mechanism>`). They are working documents;
nothing durable references them.

## 1. The checker, unwired (D5, D6, D7; FR1, FR2, FR5, FR6, NFR-R1, NFR-R3, NFR-O1, NFR-S1, NFR-C1; UX1; M6)

- [x] 1.1 Fix Q1 and write the reader: `SpecContractReader` in
      `build-logic/src/main/groovy/com/github/oinsio/gnomish/buildlogic/` reads one spec file into
      requirement blocks — heading, body lines, metadata lines (`^\*\*[^*]+\*\*:`), scenario
      headings (`^####\s`), with fenced blocks masked — and one test file into its
      `// Scenario: <spec-id> / <title>` markers (also inside block comments). Verify: a Spock
      spec in `build-logic/src/functionalTest` (the module's only test suite; the reader needs
      no TestKit) with fixtures for each shape OpenSpec documents (a
      metadata-only body, a fenced `#### Scenario:` that is not a scenario, a `###` divider
      ending a body, a marker inside `/* */`, a `// Scenario:` line not in the
      `<spec-id> / <title>` form, reported as malformed), one feature per shape, named by
      FR1/FR2/FR5.
- [x] 1.2 Write `SpecContractCheck` (a `@CacheableTask`, inputs: the spec tree — `openspec/specs/**`
      by default, or the directory `specsDir` names, so a previewed tree can stand in for the
      real one — the unarchived deltas when `changes` is set, and the test trees: `*/src/test`,
      `*/*/src/test`, `build-checks/*/src/test` and `build-logic/src/functionalTest`; one
      output marker file): rules FR1 (exactly one
      `**Implements**:` per requirement; no `<!-- implements` in scanned trees), FR2 (notes one
      line, no `SHALL`/`MUST`, ordinary text after a metadata line is a wrapped note), FR5 (both
      directions, a `// Scenario:` line not in the `<spec-id> / <title>` form reported as a
      malformed marker, exemption entries `scenario`, `verifiedBy`, asserted reached;
      `pending-test:` as a `verifiedBy` form per D9), a `spec` filter property for per-task runs, findings in the
      file:line / rule / fix wording of `TestTimeInjectionCheck` and one summary line. Verify:
      `SpecContractCheckFunctionalSpec` (TestKit, a mini project with a spec tree and a test
      tree) — one feature per scenario of the `quality-gates` delta, each shown red with the
      rule removed; wall time of the task over a copy of the real `openspec/specs` and test trees
      under five seconds, recorded in the task report (M6).
- [x] 1.3 Register the task in the root `build.gradle` as `checkSpecContract` with an empty
      exemption block and the `--changes` / `-PspecContractChanges`, `-PspecContractScope`
      (a comma-separated list of spec ids) and `-PspecContractSpecsDir` (the spec tree to read
      instead of `openspec/specs`, used by groups 5 and 7 against the preview) properties, **not** yet a dependency of `check` (D7); add `scripts/check-spec-contract.sh`
      (one `./gradlew -q checkSpecContract` with arguments passed through; bash 3 compatible like
      `uncommitted-files.sh`). Verify: `scripts/check-spec-contract.sh --changes` on the current
      tree runs and reports — this is the first inventory: the count of requirements without the
      line (expected 537), of comment-form hits, of unlinked scenarios (expected close to
      1 457), of malformed markers (expected 20); numbers recorded in the task report. `./gradlew check` unchanged (the task is not
      wired).
- [x] 1.4 Add the verification command to `.gnomish/stages/fix-artifacts/stage.yaml`
      (`./gradlew -q checkSpecContract -PspecContractChanges=true`, after the strict validate)
      and `.gnomish/stages/archive/stage.yaml` (`./gradlew -q checkSpecContract`, after the
      strict validate of the merged specs), each a launcher of one line with the message
      naming the rule file to read. Verify: the functional spec's "manual launcher is the same
      gate" feature extended to run the exact command text of both stage files, parsed from the
      YAML, against the fixture tree and expect the same findings (D5, single-owner row 3).

## 2. Rules and guides (D1, D2, D4; FR7; UX3)

- [x] 2.1 `.claude/rules/traceability.md`: the table row for "Spec requirement (markdown)"
      becomes `**Implements**: FR-X of <change-name>`; a new row "Spec scenario" →
      `// Scenario: <spec-id> / <title>` in the test that exercises it; a sentence that the
      YAML-like forms keep `# implements`. Verify: the file shows the two rows; `grep -n
      "<!-- implements" .claude/rules/traceability.md` is empty.
- [x] 2.2 `.claude/rules/delta-specs.md`: the template shows the finished shape (text within
      OpenSpec's limit, `**Implements**:` line, an optional one-line `**Notes**:`, scenarios);
      a "One behavior per requirement" section stating the limit as a fact to design for, the
      scenario-or-requirement rule for normative text, notes as the exception, the marker
      obligation for every new scenario; the Rules list updated. Verify: the file's example
      passes `checkSpecContract` when dropped into the functional spec's fixture tree (add it as
      a feature).
- [x] 2.3 `.claude/rules/testing.md`: a short section "Every scenario has a test" — the marker
      beside the feature, both directions checked, the exemption form and its bar (a mechanism
      that really verifies the scenario, named). `openspec/config.yaml` `rules.specs`: replace the
      invariant line with three lines (the `**Implements**:` line; one behavior per requirement,
      OpenSpec's 500-character limit; a marker for every scenario). `.claude/commands/fix-uncommitted.md`
      line 104: the exception now names the `**Implements**:` line. Verify: `openspec instructions
      specs --change tighten-spec-contract --json` returns the three rules.
- [x] 2.4 `docs/guides/developer-guide.md`: one subsection under verification — the gate, the
      manual launcher, how to fix each finding, how to exempt a scenario; `docs/glossary.md`: an
      entry "Spec contract" (the project's rules on top of OpenSpec's, and the gate that checks
      them) in the build/quality context. Verify: both files render (headings in the guide's
      table of contents if it has one); `grep -n "Spec contract" docs/glossary.md` hits.

## 3. The migration deltas (D3; FR8, NFR-R2; M2)

- [x] 3.1 Write `scripts/generate-spec-deltas.sh` (bash 3 + awk/sed, no other runtime):
      for every `openspec/specs/**/spec.md`, write
      `openspec/changes/tighten-spec-contract/specs/<spec-id>/spec.md` with `## MODIFIED
      Requirements` and one block per requirement — heading verbatim, comment lines turned into
      one `**Implements**:` line (ids joined by `; ` when a requirement carried several
      comments, placed after the last body line before the first scenario), every other line
      verbatim; `quality-gates` keeps its existing `ADDED` section above the generated
      `MODIFIED` one. Idempotent: a second run is byte-identical; a delta that differs from the
      script's own last output (a `.generated` sidecar hash) is refused unless `--force`.
      Verify: a `verify` subcommand compares, per requirement, the id set extracted from the
      main spec's comments with the id set of the delta's line, the heading set, and the
      scenario count, and exits non-zero on any difference; run on the real tree: 537
      requirements, 0 differences.
- [x] 3.2 Run the generator; commit nothing (the human commits); run
      `openspec validate tighten-spec-contract --strict` (expected: valid — `MODIFIED` length is
      not checked) and `scripts/check-spec-contract.sh --changes`
      (expected: no FR1/FR2 finding under `openspec/changes/tighten-spec-contract/specs`; the
      main specs and the other changes still report, as in 1.3). Verify: both outputs in the
      task report; `grep -rn "<!-- implements" openspec/changes/tighten-spec-contract` empty.
- [x] 3.3 Strict preview of the untouched migration: run the recipe, `openspec validate --specs
      --strict` in the scratch worktree. Expected: still 44 failing specs, now about 271 findings
      (the comments no longer count) and no finding of any other kind — this is the baseline
      group 4 works down. Verify: the per-spec counts recorded in the task report and matched
      against the per-capability counts in the group-4 task headings (32 for
      `git-task-persistence`, 21 for `tracker-take`, …); any new kind of finding is a generator defect fixed here.

## 4. Requirements within the limit (D2; FR3, FR4; UX2; M1)

Each task below edits the generated delta of the named capabilities: for each long requirement,
fill the `requirements-<spec-id>.md` inventory first (every normative sentence past the limit,
its disposition), then edit — heading unchanged, existing scenarios verbatim, body down to the
one behavior, normative remainder into a scenario of the same requirement or an `ADDED`
requirement with its own scenario, explanation into one `**Notes**:` line only when it has no
other place. A new scenario or requirement is listed at the end of the inventory file with the
test that will carry its marker (group 5 takes the list). Verify, for every task of this group:
`openspec validate tighten-spec-contract --strict` valid; the generator's `verify` passes for the
capability (headings intact, scenario count not lower); the strict preview passes
`openspec validate <spec-id> --type spec --strict` for each capability named; the inventory file
has one row per normative sentence.

- [x] 4.1 `git-task-persistence`, requirements 1–17 in file order (the spec's first half).
- [x] 4.2 `git-task-persistence`, requirements 18–34.
- [x] 4.3 `tracker-take` (21 long of 27).
- [x] 4.4 `execution-environment` (15 of 26).
- [x] 4.5 `github-tracker` (15 of 22).
- [x] 4.6 `tracker-port` (14 of 22; 15 before the 3.3 preview).
- [x] 4.7 `observability/dashboard-page` (12 of 29) and `observability/tracker-board` (5 of 8).
- [x] 4.8 `factory-serve` (12 of 23) and `serve-observability` (4 of 26).
- [x] 4.9 `agent-executor` (11 of 19).
- [x] 4.10 `manual-run` (10 of 18), `cli-arguments` (3 of 3), `operator-console` (1 of 3).
- [x] 4.11 `stage-engine` (12 of 17) and `pipeline-config` (6 of 23).
- [x] 4.12 `quality-gates` (8 of 29), `module-layering` (4 of 6), `build-system/sandbox-module-split`
      (1 of 2); `build-system/build-conventions` and `build-system/test-fixtures-module` are already within
      the limit — confirm in the preview and leave their generated deltas untouched.
- [x] 4.13 `lifecycle/claim-heartbeat` (8 of 12), `lifecycle/task-branch-contract` (7 of 8),
      `lifecycle/subprocess-supervision` (1 of 2); `lifecycle/factory-bootstrap`,
      `daemon-supervision` and `time-source` confirmed clean — confirm in the preview and leave
      their generated deltas untouched.
- [x] 4.14 `factory-logging` (7 of 12), `untrusted-text` (4 of 5), `status-report` (4 of 6).
- [x] 4.15 `sandbox-lifecycle` (7 of 10), `sandbox-egress` (5 of 10), `factory-egress-allowlist`
      (1 of 3).
- [x] 4.16 `verification/github-external-check` (5 of 11), `verification/verification-hardening`
      (2 of 8), `verification/check-provider-model` (1 of 8), `verification/http-check-provider`
      (1 of 4), `plugin/plugin-api-contract` (2 of 7), `plugin/adapter-binding-registry` (1 of 8),
      `plugin/github-plugin` (1 of 3), `plugin/plugin-discovery` (1 of 6);
      `verification/dependency-verification` confirmed clean.
- [x] 4.17 `project-licensing` (5 of 8), `base-ref-resolution` (4 of 7), `git-transfer` (3 of 9),
      `task-inspection` (3 of 5).
- [x] 4.18 `project-registry` (3 of 4), `operator-configuration` (3 of 3), `release-distribution`
      (2 of 4), `vendor-connection-profile` (2 of 3), `secrets-provider` (1 of 4).
- [x] 4.19 Whole-corpus preview: the strict preview with `--specs`; expected 50 of 50 passing
      (M1). Verify: the output in the task report; any remaining finding goes back to the task
      that owns the capability, not fixed here.

## 5. Every scenario has a test (D4, D9; FR5, FR9; NFR-C2; M3, M4)

- [x] 5.1 Inventory, automated first pass: a one-off script (scratch, not committed) matches
      every scenario title of the previewed main specs (group 4's result, including the new
      scenarios listed in the group-4 inventories) against feature names and comments of every
      test tree by normalized word overlap, and writes `scenarios-<spec-id>.md` per capability
      with status linked / probable / missing and the candidate file. Verify: the tables exist
      for all 48 capabilities; the totals (linked, probable, missing) are in the task report.
- [x] 5.2 **Decision point (D9, FR11, Q2).** Present to the operator the totals of 5.1, the
      "missing" scenarios grouped by capability, the obvious exemptions (scenarios verified by
      CI workflows, release scripts, or the OpenSpec CLI itself), and every open row of
      `findings.md` so far (group 4's normative remainders without a home among them). The
      operator sorts each item: "this change" or "new change `<name>`". Verify: the sorted
      list with reasons copied under `## Decisions` in this file, every row of `findings.md`
      closed with its disposition, before 5.3 starts.
- [x] 5.2a Text corrections sorted into this change at 5.2: for every finding the Decisions list
      assigns to 5.2a, edit this change's delta of that capability so the requirement or scenario
      states what the code and its tests do (the finding id in the inventory row and in the task
      report); rename one of the two tracker-port `Suite passes on both adapters` scenarios (F1);
      turn the stage-engine classification table into scenarios (F26). No production code. Verify:
      `openspec validate tighten-spec-contract --strict` valid; the strict preview passes for every
      capability touched; the generator's `verify` reports no new difference beyond the accepted
      ones and the renamed scenarios; each finding's row in `findings.md` marked done.
- [x] 5.2b Exemptions move to one data file per capability (operator decision after 5.2: several
      hundred `pending-test:` entries do not fit the root build file's 200-line cap):
      `config/spec-contract-exemptions/<spec-id with / → ->.txt`, one line per scenario,
      `<scenario title> => <mechanism | pending-test: <change>>`, blank lines and `#` comments
      allowed; `SpecContractCheck` reads the directory as an input (cacheable); a line naming no
      scenario of its capability, a malformed line, or a file naming no capability is a finding with
      the file and line; the `exemption scenario:…, verifiedBy:…` DSL and the empty block in the root
      `build.gradle` are removed; `testing.md` ("Every scenario has a test"), the developer guide and
      design D5 describe the new home. Verify: the functional specs of the exemption scenarios
      (`An exempt scenario passes with its reason`, `A stale exemption fails the gate`, the
      pending-test feature) rewritten to the file form and green, each shown red with its rule removed;
      `ModuleBuildFileSpec` green.
- [x] 5.3 Markers and tests for the capabilities of tasks 4.1–4.3 (`git-task-persistence`,
      `tracker-take`): confirm each "probable" by reading the test; place the marker above the
      feature (converting the twenty informal `// Scenario:` comments of `TakeBareAutoSpec`,
      `TakeDispositionSpec` and `serve/TakeSlotRunnerSpec` to the form — the checker reports
      each one left as a malformed marker); write a Spock feature for each "missing" scenario by TDD (red on the behavior the
      scenario states, green, marker placed) or record it as an exemption with its mechanism. A
      feature that stays red because the code does not do what the scenario states is a
      revealed defect: add a `findings.md` row, keep the feature under `@PendingFeature` with
      the finding id, fix nothing here (D9).
      Verify: `./gradlew checkSpecContract -PspecContractScope=git-task-persistence,tracker-take`
      green against the previewed specs (`-PspecContractSpecsDir=<preview>` from 1.3 points the task at the
      preview tree's `openspec/specs`);
      the module specs the task touched run by name; no `.@` field read introduced.
- [x] 5.4 Same for tasks 4.4–4.6 (`execution-environment`, `github-tracker`, `tracker-port`).
- [x] 5.5 Same for tasks 4.7–4.8 (`observability/*`, `factory-serve`, `serve-observability`).
- [x] 5.6 Same for tasks 4.9–4.11 (`agent-executor`, `manual-run`, `cli-arguments`,
      `operator-console`, `stage-engine`, `pipeline-config`).
- [x] 5.7 Same for tasks 4.12–4.13 (`quality-gates`, `module-layering`, `build/*`,
      `lifecycle/*`, `daemon-supervision`, `time-source`) — here most "tests" are `build-logic`
      functional specs, `build-checks` specs, `:bootstrap` architecture specs and the
      `app/daemon` and time-source specs of `:application` and `:domain`; a scenario verified only by CI (`Continuous integration`, `Security
      scanning`) is an exemption naming the workflow file.
- [x] 5.8 Same for tasks 4.14–4.16 (`factory-logging`, `untrusted-text`, `status-report`,
      `sandbox-*`, `factory-egress-allowlist`, `verification/*`, `plugin/*`).
- [x] 5.9 Same for tasks 4.17–4.18 (`project-licensing`, `base-ref-resolution`, `git-transfer`,
      `task-inspection`, `project-registry`, `operator-configuration`, `release-distribution`,
      `vendor-connection-profile`, `secrets-provider`) — `project-licensing` and
      `release-distribution` scenarios verified by `scripts/check-distribution-terms.sh`,
      `scripts/release-preflight.sh` or a workflow are exemptions naming that file.
- [x] 5.10 Exemption review: every entry of the exemption block in the root `build.gradle`
      re-read against its bar (`testing.md`, "Every scenario has a test"); the count and the
      list in the task report (M4); `pending-test:` entries, if any, each name the follow-up
      change from 5.2. Verify: `./gradlew checkSpecContract -PspecContractSpecsDir=<preview>`
      green over all 48 capabilities with zero unlinked and zero dangling.
- [x] 5.11 Fixes sorted into this change at 5.2 (revealed defects, normative remainders that
      needed a home): each by TDD — the `@PendingFeature` removed, the feature red, the
      production fix, green — one finding per sub-step, the finding id in the feature's
      comment and in the task report. Verify: the module's named specs green; the scoped PIT
      (`-PpitScope=<fqcn,…>`) of the classes changed green; no finding fixed that `## Decisions`
      does not list for this change.

## 6. The queue (D8; FR10; M5)

Each task rewrites the named changes' deltas: comment lines to `**Implements**:` lines (the
generator's transformation, applied by hand or by its `--delta <path>` mode added in 3.1 if
cheaper); every `MODIFIED` requirement re-based on this change's delta text with the
`Layered on … as modified by tighten-spec-contract (sequenced before this change)` preamble and
the proposal's sequencing note; every long `ADDED` requirement shortened by the group-4 rules
(a new scenario it creates is the implementing change's to test — NG6). While rewriting, every
scenario of the change's delta that describes behavior the code already has — a `MODIFIED`
block restating current behavior, an `ADDED` block for something already built — and that no
test exercises becomes a `findings.md` row (kind: queued scenario without a test), with the
test file that would carry its marker if one exists. Verify, per task:
`openspec validate <change> --strict` valid for each change named;
`scripts/check-spec-contract.sh --changes` reports nothing for them; the findings rows added
are listed in the task report.

- [x] 6.1 `add-pipeline-entry-precondition`, `add-decision-inheritance`, `add-epic-decomposition`,
      `add-pipeline-routing` (the four with the most long `ADDED` requirements).
- [x] 6.2 `add-stage-finished-event`, `add-subprocess-access-log`, `add-claim-return`,
      `add-sandbox-base-image`.
- [x] 6.3 `add-sandbox-cloud-executor`, `add-sandbox-colima-vm`, `add-sandbox-gha-executor`,
      `add-sandbox-hardening`.
- [x] 6.4 `add-command-executor`, `add-decision-arbiter`, `add-doctor-command`,
      `add-tracker-task-hierarchy`.
- [x] 6.5 `add-artifact-depot`, `define-executor-contract`, `enforce-artifact-contracts`,
      `extract-agent-round-runner`.
- [x] 6.6 `fix-docker-exec-env-argv`, `fix-terminal-receipt-convergence`,
      `harden-verification-integrity`, `own-git-invocation-policy`,
      `migrate-verify-to-executor-contract`.
- [x] 6.7 `add-stage-iteration`; then `openspec list --json` in the task report, proving that
      every change it lists is named in 6.1–6.7 — a change that entered the queue after this
      proposal was written is reworked here too, and added to this list.
- [x] 6.8 **Decision point (D9, FR11).** Present every open row of `findings.md` added since
      5.2 — the queued scenarios without a test from 6.1–6.7, any defect revealed in 5.3–5.10 —
      to the operator, who sorts each into "this change" or "new change `<name>`". For a
      queued scenario sorted into this change: the marker and the test are written now (the
      behavior exists), by the rules of 5.3, and the delta's scenario keeps its title so the
      marker links on merge. For one sorted out: the implementing change's tasks are its
      record — note the finding id in that change's `tasks.md` as a plain bullet. Verify: the
      sorted list under `## Decisions`; `findings.md` has no open row; the tests written here
      run by name and the scoped checker (`-PspecContractScope=<spec-ids>`) is green.
- [x] 6.9 Fixes sorted into this change at 6.8, by the rules of 5.11. Verify: as 5.11.

## 7. Wiring and the single whole-tree gate (D5, D7; FR6; G1, G3)

- [x] 7.1 Final strict preview with `--specs` and with the checker run against the preview tree:
      both green; the generator's `verify` passes for all 48 capabilities. Verify: outputs in the
      task report.
- [x] 7.2 Make root `check` depend on `checkSpecContract` (root `build.gradle`, next to the other
      gate wirings), remove `scripts/generate-spec-deltas.sh` and its sidecar hashes (D3: the
      generator has no second use; its `verify` logic that group 4 relied on is not needed once
      the change is archived — say so in the commit recommendation). Verify:
      `SpecContractCheckFunctionalSpec` gains the "root check runs the gate" feature; `./gradlew
      checkSpecContract` green on the working clone **against the current `openspec/specs`** is
      not expected yet (the comments are still there until archive) — so the wiring is verified
      with `-PspecContractSpecsDir=<preview>`; the task report states this explicitly.
- [x] 7.3 Record the archive-time order in `## Workflow follow-up` below and in the task report:
      the first root `check` on the real tree is green only after `openspec archive` merges the
      deltas; the archive stage's own verification (1.4) is what proves it on the task branch.
- [x] 7.3a Regroup — move (FR12, D10): by one mapping table old → new, `git mv` of the 33 flat
      main specs into their group directories and placement of the 18 new capabilities of queued
      changes (the five existing groups stay); the `specs/<old>` directories of every unarchived
      change, this change's 50 deltas and their `# Spec Delta: <id>` lines;
      `add-subprocess-access-log/specs/subprocess-supervision` to `lifecycle/`. Any
      `spec.md.generated` sidecar still present (7.2 not yet run) moves with its delta or is
      deleted. Verify: `git status` shows only renames under `openspec/specs/`; `find` lists no
      flat spec under `openspec/specs/` or any unarchived change.
- [x] 7.3b Regroup — markers and exemptions: every `// Scenario: <old> /` in every test tree of
      FR5 re-prefixed, anchored on the exact `Scenario: <old> / `, one pass per id, the
      `quality-gates / Root check runs the gate` marker of 7.2 included; the exemption files
      renamed (`/` → `-`) with their `spec:` header. Verify: `scripts/check-spec-contract.sh`
      against the strict preview, no scope, green; the anchored grep of M7 empty.
- [x] 7.3c Regroup — references: `openspec/specs/<old>` paths and ids in `docs/`, `.claude/`, the
      proposals, designs and tasks of unarchived changes, `build-logic` and `bootstrap` sources,
      reviewed hit by hit; the area mapping of decision point 5.2 under `## Decisions` rewritten
      to the new ids; a one-line old → new note at the top of `findings.md` and
      `temporary-docs/scenario-rename-via-two-changes.ru.md` on the new ids. Verify: grep of M7
      over those trees empty.
- [x] 7.3d Regroup — proof: `openspec validate <change> --strict` for every unarchived change;
      strict preview with `--specs`; the layering proof (archive this change, then every queued
      change in order, in a scratch worktree, `openspec validate --specs --strict`). Verify: all
      green, outputs in the task report.
- [x] 7.4 Whole-tree gate: `./gradlew check` once, with `-PspecContractSpecsDir=<preview>` for
      this run only; fix what fails; then `scripts/check-spec-contract.sh --changes` over the
      queue one last time. Verify: `check` green; the summary line of the checker in the report
      (scenarios checked, linked, exempt); `findings.md` has no open row and every row's
      disposition appears under `## Decisions`; every `@PendingFeature` and every
      `pending-test:` entry names a change listed there; the recommended commit message lists
      the change and the requirement ids.

## 8. Tests for the pending scenarios (D9 as revised 2026-10-12; FR5, FR11; NFR-C2; M3, M4, M8)

The six area changes `add-scenario-tests-*` sorted at 5.2 are withdrawn (operator, 2026-10-12):
their scenarios are tested here. Each task takes the capabilities it names and, for every
`pending-test: add-scenario-tests-<area>` entry of their exemption files: writes the feature
that exercises the scenario by the rules of `testing.md` (effects on the real flow, not private
fields; stand-in presets, never generated scripts; virtual time; port fakes above the process
boundary), places the marker, and deletes the entry. A scenario that review shows is verified
by something other than a test becomes an exemption that meets the bar, its mechanism named.
A test that reveals a defect gets a `findings.md` row and stays in place under
`@PendingFeature(reason = "F<n>: …")`; no production edit happens in 8.1–8.13 (NG5) — the row
is sorted at 8.14. Verify per task: the named specs green; `./gradlew checkSpecContract
-PspecContractSpecsDir=<preview> -PspecContractScope=<spec-ids>` green; no
`add-scenario-tests-` left in the touched exemption files.

- [x] 8.1 `git/git-task-persistence` (16), `git/base-ref-resolution` (6), `git/git-transfer` (4);
      F3: rewrite the one-push feature of `PushBestEffortTaskRepositorySpec` to the
      two-operation shape (or delete it as covered by `PushBestEffortTaskLifecycleStoreSpec`)
      and mark `Completion pushes around the tracker write`.
- [x] 8.2 `lifecycle/task-branch-contract` (12), `lifecycle/claim-heartbeat` (11).
- [x] 8.3 `tracker/tracker-take` (20).
- [x] 8.4 `tracker/github-tracker` (16).
- [x] 8.5 `tracker/tracker-port` (13); F10: the two shared-contract features (stale-claim
      removal, index repair) in `:test-fixtures`' tracker contract, run by both adapters.
- [x] 8.6 `sandbox/execution-environment` (27).
- [x] 8.7 `executor/agent-executor` (17), `sandbox/sandbox-egress` (4), `sandbox/sandbox-lifecycle` (3).
- [x] 8.8 `observability/dashboard-page` (17), `observability/serve-observability` (3),
      `observability/tracker-board` (1).
- [x] 8.9 `observability/status-report` (8), `serve/factory-serve` (8),
      `observability/factory-logging` (7), `serve/daemon-supervision` (1).
- [x] 8.10 `core/time-source` (9), `core/untrusted-text` (6), `cli/task-inspection` (5).
- [x] 8.11 `cli/manual-run` (6), `pipeline/*` (8), `operator/*` (8), `verification/*` (5),
      `plugin/*` (3), `build-system/dependency-verification` (2).
- [x] 8.12 `build-system/quality-gates` (25).
- [x] 8.13 `build-system/module-layering` (12), `build-system/build-conventions` (7),
      `build-system/sandbox-module-split` (4), `build-system/test-fixtures-module` (2), `release/*` (5),
      `lifecycle/factory-bootstrap` (1).
- [x] 8.14 **Decision point (D9, FR11).** Present every `findings.md` row added by 8.1–8.13;
      the operator sorts each into this change or a new change; the sorted rows are copied
      under `## Decisions`.
- [x] 8.15 Fixes sorted into this change at 8.14, by the rules of 5.11. Verify: as 5.11.
- [x] 8.16 Exemption review and the whole-tree gate again: `grep -rn "add-scenario-tests-"`
      over the repository empty, outside this change's own records (M8); the exemption count
      and list in the task report (M4); then the checks of 7.4 once more — `./gradlew check`
      with `-PspecContractSpecsDir=<preview>`, `scripts/check-spec-contract.sh --changes`.
      Verify: as 7.4.

## 9. The 8.14 follow-ups, fixed here (D11–D17; FR13–FR16; NFR-S2, NFR-S3; M8, M9)

The sixteen findings 8.14 sorted to new changes are fixed in this change (`## Decisions`,
"Revision 2026-10-12, second"). Each task fixes one finding (or one named pair) by TDD: the
`@PendingFeature` of its test removed, the feature red for the reason the finding records, the
fix, green — the test's assertion unchanged unless the task says why; the finding id in the task
report and in the `findings.md` row's resolution ("done (9.n): …"). Production classes changed
run the scoped PIT of `verification-scope.md`. A single-owner task (marked *single-owner*) ends
its report with the sweep of `implementation.md`: the grep it ran, every hit, what happened to
it. Anything new a task finds is a `findings.md` row, not a fix on the way. Verify per task: the
named specs green; the scoped PIT green; `./gradlew checkSpecContract
-PspecContractSpecsDir=<preview> -PspecContractScope=<spec-ids>` green for the capability the
finding names.

- [x] 9.1 F113 — `build-system/quality-gates` / `An override reaches every configuration that resolves
      the artifact`: carry the jackson-bom override (2.22.3, the catalog's pin) to the
      `build-logic` classpaths and the root buildscript `classpath` (a `gradle.properties`
      buildscript override, as for `tools.jackson`), regenerate `build-logic/gradle.lockfile` and
      `buildscript-gradle.lockfile` (`--write-locks` on those configurations only). Verify:
      `SecurityOverrideLockStateSpec` green without `@PendingFeature`; no lockfile in the tree
      names `jackson-bom` or `jackson-databind` 2.22.1 (`grep -rn "2.22.1" --include='*.lockfile' .`
      empty); OSV-Scanner over the regenerated lockfiles reports none of the three GHSA ids.
- [x] 9.2 F111 — `build-system/quality-gates` / `Dead private code fails compilation`: first find why
      `UnusedMethod` and `UnusedVariable` stay silent under `java-conventions` while NullAway fires
      (the probe of the finding: Error Prone 2.50.0, JDK 25 — check the check names, the
      `disableWarningsInGeneratedCode` / `-XepDisableAllChecks` / `allErrorsAsWarnings` settings,
      and the check's own suppression rules) and record the cause in the `findings.md` row; fix
      the convention; then compile the whole build and delete every dead method, field and local
      the checks now report — a hit that is not dead (reflective or framework entry) is suppressed
      on its own declaration with the reason (`@SuppressWarnings("UnusedMethod") // <reason>`), and
      the list of suppressions is in the task report. Verify: `StaticAnalysisGateFunctionalSpec`
      green without `@PendingFeature` (via the root: `./gradlew :build-logic:functionalTest
      --tests StaticAnalysisGateFunctionalSpec`); `./gradlew compileJava compileTestGroovy`
      green; scoped PIT for each production class that lost code.
- [x] 9.3 F100 — `observability/status-report` / `A breaking change to a released contract bumps
      the version`: write `docs/adr/0016-json-contract-versioning.md` (D17; FR15) — the
      contracts it governs (status report, board, serve snapshot, ledger) and their reference
      files (`status-report-v1.reference.json`, `board-v1.reference.json`,
      `snapshot-v1.reference.json`), what "released" means (shipped in a tagged release), what is
      breaking (a field renamed, removed or retyped; a token's meaning changed) and what is
      additive, and the rule (breaking → `version` bump and a new reference file; additive → same
      version, reference regenerated); add it to `docs/adr/` index if one exists and to the
      glossary only if it coins a term. Replace the scenario's `pending-test:
      document-contract-versioning` entry with `=> docs/adr/0016-json-contract-versioning.md`
      (the documentation-fact form of `The policy ADR defines its three rules`). Verify:
      `checkSpecContract` green for `observability/status-report`.
- [x] 9.4 F90 — `observability/dashboard-page` / `Silent stall becomes loud`: carry the source of
      the last skipped verdict (tracker or runtime, the vocabulary `sandbox-lifecycle` names) in
      `SweepVital` (`SweepVitalAssembler`, `SweepCounts` as needed), write it in
      `SnapshotJsonMapper` and read it in `SnapshotJsonReader` in this task (declared pair; the
      wire token gets a round-trip feature over every constant), regenerate
      `snapshot-v1.reference.json` as an additive field under ADR 0016, and name it in the stall
      alarm (`SandboxHygieneAlertEvaluator`, `DashboardAlertLabels`). Verify:
      `DashboardHygieneBlockSpec` green without `@PendingFeature`; the snapshot mapper/reader
      round-trip and reference specs green.
- [x] 9.5 F109 — `verification/github-external-check` / `A green check is auditable from the
      tracker`: render a passing check's run URL in the text report on the comment plane
      (`TakeFinishReport` → `StatusTextRenderer(ReportPlane.COMMENT)`), the URL neutralized as
      every other untrusted field there; a check with no URL renders as today. Verify:
      `TakeFinishReportRunUrlSpec` green without `@PendingFeature`; the `StatusTextRenderer`
      specs and the status-report reference specs green (the `--json` shape unchanged).
- [x] 9.6 F106 — `operator/operator-configuration` / `List-valued keys are replaced whole`: in
      `OperatorConfigLoader`, a list a higher source sets shadows every indexed key of the same
      list from lower sources before binding (a higher `[project-a]` over a lower `[host-a,
      host-b, host-c]` binds `[project-a]`); maps keep merging key by key. Verify:
      `OperatorConfigCollectionsSpec` green without `@PendingFeature`, its map-merge twin green.
- [x] 9.7 F79 — `tracker/github-tracker` / `Each removed claim gets its own removal marker`: a
      removal re-driven over a claim its own marker already voided (`ClaimFacts.Dead`) scopes its
      marker by the epoch the voided claim comment still carries — so the re-drive finds and
      reuses its own marker instead of posting a second — and deletes that claim comment
      (`GithubStaleClaimRemoval.retire`, `postRemovalMarker`). The two-tenure half stays as is.
      Verify: `GithubTenureMarkersSpec` green without `@PendingFeature`; `GithubStaleClaimRemovalSpec`
      and `GithubTrackerContractSpec` green; the in-memory adapter's contract features green.
- [x] 9.8 F74 — `lifecycle/task-branch-contract` / `A split tenure record cannot be assembled`
      (*single-owner*, design table row `GitTaskBackend.taskGit`): add the public assembly
      `GitTaskBackend.taskGit(…)` in `:adapters:git` over one `ClaimEpochBook`; make the
      constructors of `GitTaskStore`, `GitTaskBranches` and `GitTaskWorktrees` package-private;
      move `ManualRunConfiguration`'s constructions and `TaskGitFixture` to the assembly, and every
      test construction outside `adapters/git`'s package to `TaskGitFixture` or the assembly.
      Rewrite `SplitTenureRecordSpec`'s probe sources to the new surface: the control compiles the
      assembly, the split shape (writers constructed with a second book) must not compile. Sweep:
      `grep -rn "new GitTaskStore(\|new GitTaskBranches(\|new GitTaskWorktrees(" --include='*.java'
      --include='*.groovy' .` — every hit outside `adapters/git/src` gone. Verify:
      `SplitTenureRecordSpec` (both features) green without `@PendingFeature`;
      `ClaimlessGitBoundarySpec`, `TaskGitSpec`, the kill-point specs and the `:application` take
      specs named by the sweep green.
- [x] 9.9 F82 — `sandbox/execution-environment` / `No constructor mixes installation equipment and
      task inputs` (*single-owner*, row `BoxEquipment`): introduce `BoxEquipment` (a record in
      `sandbox/docker/.../sandbox/environment`) holding `DockerCli`, `BoxGitLink`,
      `SandboxProperties`, `BoxTiming`, `ChildEnvAllowlist`, the base `Path` and `ObjectOwnership`
      — exactly the installation half `ContainerEquipmentSplitSpec` types; `ContainerEnvironmentFactory`
      builds it once; `ContainerEnvironmentBuilder`, `ContainerTaskExecutionEnvironment` and
      `ContainerEnvironments` take it plus the task's own inputs. Check the record against the
      three tests of `process-invariants.md` (recurs, names a concept, absorbs behavior); add a
      glossary entry for **box equipment** if none covers it. Sweep: the three constructors' call
      sites (`ContainerEnvironmentFactory`, `ContainerEnvironmentBuilder`) and their specs. Verify:
      `ContainerEquipmentSplitSpec` green without `@PendingFeature`; the `sandbox/docker`
      environment specs green; scoped PIT of the four classes.
- [x] 9.10 F119 — `build-system/module-layering` / `A use case reaches its collaborator through a port`
      (*single-owner*, row `WorkspaceFactory` / `ConsoleIO`): add a `WorkspaceFactory` port owned
      by `:application` (or `:domain`, beside `Workspace`, if the engine consumes it) and hand it,
      with the `ConsoleIO`, to `TakeEngineExecution`, `PipelineStartup`, `GitModeRunner`,
      `GitResumeContinuation` and `ConsoleTakeoverConfirmation` from the composition root; move
      `DirectoryWorkspace` and `SystemConsoleIO` construction to `:bootstrap` (the classes may stay
      in `:application` only if `UseCasePortSpec` names them as adapters it keeps — it does not
      today). Apply the facade/parameter-object clauses of `process-invariants.md` where a
      signature grows (the take chain reads `SlotWiring`). Sweep: `grep -rn "new DirectoryWorkspace(\|new
      SystemConsoleIO(" application/src/main` empty. Verify: `UseCasePortSpec` green without
      `@PendingFeature`; the specs of the five classes and `ServeRuntimeWiringSpec` green.
- [x] 9.11 F107 — `operator/secrets-provider` / `One seam to audit` (*single-owner*, row
      `SecretsProvider`; NFR-S3): `AgentAiSeam` reads the agent credentials
      (`CLAUDE_CODE_OAUTH_TOKEN`, `ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN`) through a
      `SecretsProvider` handed to `ExecutorRoundExecution` and `JudgeRoundExecution`, and
      `ChildEnvAllowlist.of` takes the provider's lookup instead of `System::getenv` at
      `RunAssembler` and `ContainerRunSupportFactory`; the env-file adapter stays the only reader
      of the process environment. Then `Shared agent token from the host folder` holds for an
      agent round too — add or extend its feature. Sweep: `grep -rn "System.getenv" --include='*.java'
      */src/main */*/src/main` — every hit is the secrets adapter or listed in
      `SecretsPortBoundarySpec` with a non-credential reason. Verify: `SecretsPortBoundarySpec`
      green without `@PendingFeature`; `AgentCredentialSeamBoundarySpec`, the `ChildEnvAllowlist`
      specs, the agent round specs green.
- [x] 9.12 F85 — `sandbox/sandbox-egress` / `Guard crash burns no attempt` (D14): give
      `EgressGuard` a liveness probe without the restart; call it at the close of every
      container round and before an in-box retry's next round; a dead guard classifies the round
      as an infrastructure failure (no attempt burned, grading skipped) with an operator-visible
      reason; the next materialize restarts the guard as today. Check the round-close path
      against `crash-consistency.md` items 1–3 (a kill between the probe and the classification
      lands in a named shape) and record the answer in the task report. Verify:
      `EgressGuardRecoverySpec` green without `@PendingFeature`, its restart feature green; the
      sandbox round-outcome specs green.
- [x] 9.13a F122, F135 — the factory register, written (*single-owner*, row "The factory
      register"; D13; NFR-S2; third revision 2026-10-12 replaces the walk over round records): one
      writer of `refs/gnomish-factory/<task>`, the closing push — `git push --atomic origin
      <branch> <register>` built by the one transfer owner (`git-transfer`), for every closing
      commit of both media: STARTED (`GitTaskRepository.createTask`, `GitObjectsTaskRepository`),
      the host round commit (`GitAttemptPersistence`), the snapshot and state harvests
      (`EnvironmentAttemptPersistence`), outcome, approval, appended decision, resumed write,
      salvage (`WorktreeSalvage`, `EnvironmentSalvage`), cleanup (`CleanupCommit`), the
      tracker-write-confirmed commit. The mid-round pushes (`MidRoundPushListener`,
      `MidRoundHarvestListener`) push the branch alone. Every touchpoint fetch
      (`OriginReconciliation`, the take and status reads) fetches both refs through the transfer
      owner. The pair `GitAttemptPersistence` / `EnvironmentAttemptPersistence` is touched at
      both ends; its `manual-sync-pairs.md` row gains "the register moves with the closing push"
      to its invariant. Verify: `FactoryRegisterIdentitySpec` on a bare origin — after every
      closing path of both media, register and branch tip are one commit; a refused push of
      either ref lands neither (the atomic scenario, against a stand-in origin preset that
      refuses one ref).
- [x] 9.13b F122, F135 — the factory register, read (*single-owner*, row "The factory
      register"; D13; NFR-S2): a `RegisteredCommit` value, read once per pickup from the fetched
      register, is the revision every envelope reader takes: `RefTipSource` and `GitShowTip`
      (facts and `cleanupCommit` at the register; the `rev-list --first-parent --grep` walk and
      the STARTED-ownership probe deleted — `Delivered` is "the registered commit is the cleanup
      commit"), `BranchTipFactsReader` → `BranchShapeClassifier` (a new fact: the tip's relation
      to the register — equal, descendant, neither — with `Unknown` plus diagnosis for neither
      or a missing register), `SnapshotTipCheck` (the subject parsed at the register only),
      `DeliveredBranchReader`, `ResumeLawBinding` (the pin at the register), the reconcile of
      `claim-heartbeat`. Sweep: `grep -rn "readAtTip(EnvelopePaths\." adapters/git/src/main
      application/src/main` shows only the register owner; `grep -rn "rev-list.*--grep\|gnomish:
      cleanup" adapters/git/src/main` shows only `ServiceCommitMessages` and `CleanupCommit`. A
      `:bootstrap` architecture spec (`EnvelopeRegisterOwnerSpec`, the `EnvelopeMediumBoundarySpec`
      shape) scans both trees for envelope reads outside the owner, every allowlisted file
      asserted reached. Tests first, red for the finding's reason: `DeliveryAncestrySpec` loses
      its `@PendingFeature` (both message rows); new features for the delta's scenarios — a
      gnome-written `Completed` is not `CompletedUncleaned` and posts no finish (F135, in
      `BranchShapeClassifierSpec` and the take-route spec), a gnome-written gate position opens no
      gate, a gnome-written pin binds no law, a snapshot subject above the register is not a
      snapshot, a non-ancestor register is `Unknown`; the "tip equals register" feature shows every
      existing classifier row unchanged. Verify: those specs green; every existing delivery,
      classifier and kill-point spec green; scoped PIT on the readers.
- [x] 9.13c F135 — the admission gate at the harvest (*single-owner*, row "The harvest
      admission"; D18; NFR-S2): the harvest fetch targets `refs/gnomish-quarantine/<task>` (the
      transfer owner's refspec, nothing else writes it); one range inspection
      (`GnomeRangeInspection`, replacing `RoundBoundaryCheck` and `HarvestedBoundaryCheck` — both
      deleted, the `manual-sync-pairs.md` row removed) judges `register..fetched`: descendant of
      the current branch tip, no second parent, no change under `FactoryOwnedPaths` except this
      round's decision path, no `gnomish: ` subject; clean → `update-ref` the branch, then push;
      violated → the existing boundary-violation report naming the commit, the branch unmoved;
      cannot-verify → the existing infrastructure arm. Callers: `MidRoundHarvestListener`, the
      snapshot and state harvests of `EnvironmentAttemptPersistence`, `EnvironmentSalvage`;
      `BranchPush.pushBestEffort` deleted (the admission pushes what it admitted). The host round
      close (`GitAttemptPersistence.persist`) runs the same inspection over `previousTip..HEAD`
      and restores `FactoryOwnedPaths` from the registered commit before `git add -A`, reporting
      a restored edit as a violation; `recordOutcome` on a violation builds the Aborted envelope
      from the registered commit (`GitTaskRepository`, `GitObjectsTaskRepository`). Sweep:
      `grep -rn "RoundBoundaryCheck\|HarvestedBoundaryCheck\|new BranchPush(" --include='*.java'
      .` empty outside the inspection's own file. Tests first: `GnomeRangeInspectionSpec` (every
      refusal row, the branch tip unchanged after each, cannot-verify on a failing git);
      `HarvestAdmissionSpec` against `ScriptedGit`; the host feature "an uncommitted envelope edit
      does not ride the round commit" (the F135 host route) and "a violation's Aborted outcome is
      built from the registered envelope"; the kill-point row "killed between admission and push"
      (the register unmoved, re-admitted on the next harvest; the second recovery a no-op); the
      `ContainerGitMechanicsSpec` and end-to-end harvest features unchanged and green. Verify:
      those specs green; scoped PIT on the inspection, the admission and both persistences.
- [x] 9.13f F122, F135 — the factory clone on reftable (D13 as amended 2026-10-13; NFR-S2;
      added by the operator after the 9.13c report): the owner that creates the factory clone
      (`GitTransfer` clone argv in `:gittransfer`) passes `--ref-format=reftable`, so the
      branch-and-register transaction of `FactoryRegister` is one table write and atomic on disk;
      every worktree shares that ref store. An existing clone on the files backend is not used
      silently: the factory detects it at the clone's touchpoint (`git rev-parse
      --show-ref-format`) and fails closed with an operator-visible reason naming the remedy
      (re-clone; the project is pre-release, no migration). The test build follows production:
      the shared clone fixtures (`BareGitRepoFixture` and its callers, `TaskGitFixture`, the
      kill-point worlds) create clones through the same owner or with the same format, so every
      spec exercises the reftable store; the adversarial gitconfig stays in force. Sweep:
      `grep -rn '"clone"\|git init\|"init"' --include='*.java' --include='*.groovy' .` — every
      factory-clone creation passes the format or is listed with its reason (a bare origin, a
      box-side clone, a fixture that is not a factory clone). Verify: a spec that the clone the
      owner creates reports `reftable`; a spec that a files-backend clone is refused with the
      operator reason; `FactoryRegisterIdentitySpec`, `RegisterRelationSpec`, the kill-point specs
      and the architecture gates green on the reftable store; scoped PIT on the changed classes.
      *Amended 2026-10-13 (operator decision on the 9.13f report):* the box's seed clone reads
      the reftable factory clone with the image's own git, so the sandbox image contract requires
      git ≥ 2.45: the reference `docs/examples/sandbox-image/Dockerfile` and the test images
      (`JvmProxyEgressImage` and any other noble-based one) install it (git-core PPA or another
      base), the image contract text (capability and operator docs) states the floor, and the
      box's git version is checked when a box is materialized, failing closed with an
      operator-visible reason before the seed clone runs. Verify additionally:
      `JvmProxyEgressE2ESpec` green; a spec that a box image with git older than 2.45 is refused
      with the reason.
      *As built:* the factory does not create its clone (the operator does), so no owner passes
      `--ref-format=reftable`; `FactoryCloneRefFormat` requires reftable at every touchpoint and
      refuses any other backend with `[GF162]` and the remedy in the operator guide, and the test
      build sets `GIT_DEFAULT_REF_FORMAT=reftable` (`AdversarialGitConfig` in `build-logic`), so
      every fixture clone is on it (D13, *As built*).
- [x] 9.13g F135 — the clone's ref backend checked before the first write (D13 as amended
      2026-10-13, check-issue on the 9.13f implementation; 9.13f's "fails closed before it writes
      anything"): `TouchpointFetch.fetch` requires the backend (`FactoryCloneRefFormat`) before
      the fetch — the first write of every task touchpoint, so the take router's
      `classifyShape`, the `status`/`usage` reads that never harden, and the container resume's
      replica reconcile are covered; `GitModeRunner` and `TakeFreshClaim` harden before
      `pruneWorktrees`. Verified: `FactoryCloneRefFormatSpec` (three features: a status read, a
      take routing and a container resume reconcile on a files-backend clone are refused, and no
      tracking ref or register is written), the `ScriptedGit` specs of the touchpoint over
      `ScriptedGit.reftableClone()`, `GitModeRunnerFreshRunSpec` and `TakeFreshClaimSpec` (order
      harden → prune) green; scoped PIT 100% (`:adapters:git` 7/7, `:application` 23/23).
- [x] 9.13h F122, F135 — the git floor 2.51 and the atomic touchpoint fetch (D13 as amended
      2026-10-13, check-issue on the 9.13f implementation): `GitVersion.FLOOR` 2.51.0 and
      `FLOOR_REASON` naming the local-clone protections, reftable lock retry (2.47) and batched
      fetch ref updates (2.51); `BoxGitFloor.REASON` follows; `GitTransfer.fetch` for `Origin`
      with more than one refspec passes `--atomic` (harvest unchanged); the javadoc of
      `GitProcessRunner.run(Path, RefTransaction)` says reftable takes one store-wide lock and that
      concurrency with other writers rests on git's lock retry, not on `CloneMutationLock`. Images
      and texts: `docs/examples/sandbox-image/Dockerfile` and README, `JvmProxyEgressImage` and any
      other test image (the distribution's git if ≥ 2.51, else the git-core PPA),
      `docs/guides/operator-guide-sandbox.md`, `docs/sandbox-threat-registry.md`,
      `docs/adr/0008-git-transfer-policy.md`, both `AdversarialGitConfig` ends, the stand-in data
      `stdout-git`, the javadoc of `FactoryCloneRefFormat`. Verify: `GitVersionSpec`,
      `GitVersionCheckSpec`, `BoxGitFloorSpec` at 2.51; `GitTransferSpec` and
      `NoForcePushGuardSpec` argv expectations with `--atomic`; a spec for the scenario "A
      rejected branch update leaves the register unmoved" (real git, marker);
      `JvmProxyEgressE2ESpec` green; scoped PIT on the changed classes; `grep -rn "2\.45"` outside
      `build/` and the archive lists only historical statements (the reftable readability fact).
- [x] 9.13d D19 — the record: a new row in `docs/sandbox-threat-registry.md` (the next free
      number; envelope and service-subject forgery on the task branch read as the factory's
      record — delivery, cleanup, gate approval, law pin; mitigation: the register and the
      admission, container tier; host tier: no claim, detections as signals); a glossary entry
      "Factory register" under "Crash consistency" and the "Branch shape" entry amended ("classified
      at the registered commit"); crash-consistency item 13's "identity the orchestrator minted"
      list gains the registered commit; `docs/adr/0007-pipeline-law-source.md` amended — resume
      reads the pin at the register, the host limitation named; `manual-sync-pairs.md` rows per
      9.13a and 9.13c. Verify: the doc links resolve; `grep -rn "round-record walk\|bind-delivery-
      to-factory-record" docs/ openspec/changes/tighten-spec-contract .claude/ */src/test` empty.
- [x] 9.13e F122, F135 — the sweep report (`implementation.md`): the consumer lists of both
      single-owner rows checked off with evidence; the three greps of 9.13b and 9.13c with their
      hits and what happened to each; the escape hatches closed (`RefTipSource`, `GitShowTip` and
      `DeliveredBranchReader` take a `RegisteredCommit`, never a bare ref or `HEAD`; the harvest
      refspec names the quarantine ref); `findings.md` rows F122 and F135 marked done with the
      task ids. Verify: `./gradlew :adapters:git:test :application:test` green, the two new
      architecture specs green, `checkSpecContract --changes` green for the three edited deltas.
- [x] 9.14 F93 — `observability/dashboard-page`, six scenarios (`Dead renderer banners the page`,
      `Fresh page shows no banner`, `Refresh does not reset scroll`, `Relative time ticks`,
      `Phone-width render does not overflow`, `Both themes legible`; D15; FR14): add the
      Testcontainers Selenium module to the version catalog and to `:bootstrap`'s end-to-end test
      dependencies (dependency verification metadata and lockfiles updated); write one spec per
      behavior group in the Docker-dependent layer, loading the dashboard file the production
      renderer wrote (no hand-written HTML), driving the page clock the script reads, asserting
      in a headless Chrome; place the six markers; delete the six `pending-test:
      add-dashboard-browser-tests` entries. Check the spec against "Fixtures assemble through
      production owners" and "Test processes never inherit the operator's environment"
      (`testing.md`). Verify: the new specs green; `checkSpecContract` green for
      `observability/dashboard-page`; the dependency-verification and license gates green.
- [x] 9.15 F115, F120 — retire the one-time scenarios (D16; FR16): in this change's deltas, turn
      `Full clean-build time not regressed` (`build-system/build-conventions`) and `Behavior-preserving
      split` (`build-system/module-layering`) into `REMOVED` blocks with **Reason** and **Migration**;
      turn `Source file size cap`, `Shared app-layer assembly fixture` (`build-system/quality-gates`),
      `Use-case dependencies inverted onto ports` (`build-system/module-layering`), `Single-purpose gate
      convention` (`build-system/build-conventions`) and `Sandbox port contract preserved;
      AdapterBinding opened by change C` (`build-system/sandbox-module-split`) into a `REMOVED` block
      plus an `ADDED` requirement under a new heading — the current text (8.15's included) and
      every standing scenario verbatim, the one-time scenario dropped, the `**Implements**:` line
      kept, a `**Notes**:` line naming the archived change whose acceptance the dropped scenario
      was. Delete the seven `pending-test: retire-one-time-scenarios` entries; markers of the
      standing scenarios stay as they are. Verify: the strict preview of the four capabilities
      (`openspec validate <spec-id> --type spec --strict`); `checkSpecContract` green for them;
      `grep -rn "retire-one-time-scenarios" config/ */src/test` empty.
- [x] 9.16 Queued changes: a plain bullet in the `tasks.md` of each change whose planned code group
      9 moved, naming the finding and what the change must keep — `add-claim-return` (F79: the
      epoch-scoped re-drive survives the `GithubClaimRetirement` generalization of its 3.2),
      `extract-agent-round-runner`, `fix-docker-exec-env-argv`, `define-executor-contract` (F107:
      credentials reach the agent and the child environment through `SecretsProvider`; deleting
      `AgentAiSeam` keeps that route), `add-command-executor` and `fix-docker-exec-env-argv` (F82:
      the container chain takes `BoxEquipment`). Verify: `openspec validate <change> --strict` and
      `scripts/check-spec-contract.sh --changes` green for the five.
- [x] 9.17 Final gate: every `findings.md` row of the sixteen carries "done (9.n)"; `grep -rn
      "@PendingFeature" --include='*.groovy' .` (outside `build/`) lists exactly F97 (M9); every
      `pending-test:` entry names `add-subprocess-access-log` (3 entries, M8); none of the twelve
      follow-up change names of 8.14 is left in a test tree or `config/`; then the checks of 7.4
      once more — `./gradlew check` with `-PspecContractSpecsDir=<preview>`,
      `scripts/check-spec-contract.sh --changes`. Verify: as 7.4.

## Workflow follow-up

- Since 7.2 root `check` runs `checkSpecContract`, which is red on the real tree until this
  change is archived (812 retired comments, 537 requirements without the `**Implements**:`
  line, 662 markers naming preview titles); every `check` or CI run on this branch before the
  archive is red by design.
- Archive-time order: (1) `/opsx:archive tighten-spec-contract` merges the 50 deltas;
  (2) `openspec validate --specs --strict`, then `./gradlew checkSpecContract` (no preview
  override), both green on the real tree — the archive stage's verification commands from 1.4
  run exactly these two, so on the task branch that stage is the proof; (3) only then root
  `check`, the first one green on the real tree. If (2) is red at archive, the merge is wrong,
  never the delta.
- The queued changes archive after this one. One order consistent with the declared sequencing
  (the one the 6.7 report used), binding only as far as those declarations go:
  `fix-docker-exec-env-argv`, `own-git-invocation-policy`, `add-subprocess-access-log`,
  `define-executor-contract`, `add-command-executor`, `migrate-verify-to-executor-contract`,
  `extract-agent-round-runner`, `harden-verification-integrity`, `add-pipeline-routing`,
  `enforce-artifact-contracts`, then the rest of the queue named in 6.1–6.7.
- `retire-misnamed-scenarios` and `restore-renamed-scenarios` (F1, F39, F28 titles) follow the
  step-by-step plan agreed with the operator on 2026-10-11 (two changes, archived back to back).
- The new changes still named under `## Decisions` — `retire-misnamed-scenarios` and
  `restore-renamed-scenarios` — are proposed right after archive, with their findings as the
  seed of their task lists. The twelve follow-ups of 8.14 are withdrawn (group 9).

## Decisions

Filled at 5.2 and 6.8 (D9, FR11): one line per finding — `F<n> — <capability> / <scenario or
requirement> — <kind> — this change | new change <name> — <reason>`.

### Decision point 5.2 (2026-10-11, operator)

Rules decided:
- **Scenarios with no test after 5.3–5.9 read the tests**: markers and exemptions are placed in
  this change; each scenario still without a test gets a `pending-test: <change>` exemption naming
  the follow-up change of its area: `add-scenario-tests-git` (git/*, lifecycle/task-branch-contract),
  `add-scenario-tests-tracker` (tracker/*, lifecycle/claim-heartbeat),
  `add-scenario-tests-execution` (sandbox/*, verification/factory-egress-allowlist,
  executor/agent-executor, lifecycle/subprocess-supervision), `add-scenario-tests-serve`
  (serve/*, observability/*, core/*, cli/task-inspection), `add-scenario-tests-run`
  (cli/manual-run, cli/cli-arguments, cli/operator-console, pipeline/*, verification/*
  except factory-egress-allowlist, plugin/*, operator/*) and `add-scenario-tests-build`
  (build/*, release/*, lifecycle/factory-bootstrap). Ids are given after the regroup of
  7.3a (FR12); before it, the same capabilities under their flat names.
- **Spec text that disagrees with the code is corrected here, in this change's deltas** (task 5.2a),
  to what the code and its tests do; a scenario proven wrong against the code and its tests may be
  reworded or renamed, citing its finding id — the one exception to NG4.
- **Normative tables** in a requirement body become scenarios, never a fenced block (F26).
- **Exemptions live in one data file per capability** under `config/spec-contract-exemptions/`, not
  in the root build file (decided after 5.2a; task 5.2b).

Findings:
- F1 — tracker/tracker-port / two scenarios `Suite passes on both adapters` — other — new changes `retire-misnamed-scenarios` + `restore-renamed-scenarios` (re-decided after 5.2a: OpenSpec cannot drop a scenario from a MODIFIED block, so a rename is REMOVED then re-ADDED in two changes archived back to back) — until then both scenarios share one marker identity.
- F2 — build-system/quality-gates / fix-artifacts stage command — other — this change (5.11) — add a per-change delta filter to `checkSpecContract` and use it in the fix-artifacts command.
- F3 — git/git-task-persistence / `PushBestEffortTaskRepositorySpec` one-push feature — other — new change `add-scenario-tests-git` — the test is rewritten where the area's tests are written; no marker goes on it here. (Revised 2026-10-12: this change, 8.1.)
- F6 — tracker/tracker-take / claim-time locate fetch on an unreachable origin — revealed defect — this change (5.11) — the code is aligned with the base rule: release the claim outside the abort accounting (production fix by TDD, scoped PIT).
- F10 — tracker/tracker-port / `Contract suite covers lease maintenance` — other — new change `add-scenario-tests-tracker` — the two shared-contract features are written there. (Revised 2026-10-12: this change, 8.5.)
- F26 — pipeline/stage-engine / `Failure classification` table — other — this change (5.2a) — rows become scenarios, as in `manual-run`.
- F29 — build-system/module-layering / access-log emitter and observer hook — other — new change `add-subprocess-access-log` — the three scenarios named in the finding are `pending-test: add-subprocess-access-log`; they become true when it lands.
- F43 — operator/secrets-provider / permission check scope — other — this change, no edit — the scenario's scope (secrets folders only) matches the code; accepted.
- F4 — see `findings.md` — spec text vs code — this change (5.2a) — the text is aligned with the code.
- F5 — see `findings.md` — spec text vs code — this change (5.2a) — the text is aligned with the code.
- F7 — see `findings.md` — spec text vs code — this change (5.2a) — the text is aligned with the code.
- F8 — see `findings.md` — spec text vs code — this change (5.2a) — the text is aligned with the code.
- F9 — see `findings.md` — spec text vs code — this change (5.2a) — the text is aligned with the code.
- F11 — see `findings.md` — spec text vs code — this change (5.2a) — the text is aligned with the code.
- F12 — see `findings.md` — spec text vs code — this change (5.2a) — the text is aligned with the code.
- F13 — see `findings.md` — spec text vs code — this change (5.2a) — the text is aligned with the code.
- F14 — see `findings.md` — spec text vs code — this change (5.2a) — the text is aligned with the code.
- F15 — see `findings.md` — spec text vs code — this change (5.2a) — the text is aligned with the code.
- F16 — see `findings.md` — spec text vs code — this change (5.2a) — the text is aligned with the code.
- F17 — see `findings.md` — spec text vs code — this change (5.2a) — the text is aligned with the code.
- F18 — see `findings.md` — spec text vs code — this change (5.2a) — the text is aligned with the code.
- F19 — see `findings.md` — spec text vs code — this change (5.2a) — the text is aligned with the code.
- F20 — see `findings.md` — spec text vs code — this change (5.2a) — the text is aligned with the code.
- F21 — see `findings.md` — spec text vs code — this change (5.2a) — the text is aligned with the code.
- F22 — see `findings.md` — spec text vs code — this change (5.2a) — the text is aligned with the code.
- F23 — see `findings.md` — spec text vs code — this change (5.2a) — the text is aligned with the code.
- F24 — see `findings.md` — spec text vs code — this change (5.2a) — the text is aligned with the code.
- F25 — see `findings.md` — spec text vs code — this change (5.2a) — the text is aligned with the code.
- F27 — see `findings.md` — spec text vs code — this change (5.2a) — the text is aligned with the code.
- F28 — build-system/module-layering / enumerations — spec text vs code — this change (5.2a), text aligned; the title `The baseref leaf stays empty of dependencies` goes to `retire-misnamed-scenarios` + `restore-renamed-scenarios` (as F1).
- F30 — see `findings.md` — spec text vs code — this change (5.2a) — the text is aligned with the code.
- F31 — see `findings.md` — spec text vs code — this change (5.2a) — the text is aligned with the code.
- F32 — see `findings.md` — spec text vs code — this change (5.2a) — the text is aligned with the code.
- F33 — see `findings.md` — spec text vs code — this change (5.2a) — the text is aligned with the code.
- F34 — see `findings.md` — spec text vs code — this change (5.2a) — the text is aligned with the code.
- F35 — see `findings.md` — spec text vs code — this change (5.2a) — the text is aligned with the code.
- F36 — see `findings.md` — spec text vs code — this change (5.2a) — the text is aligned with the code.
- F37 — see `findings.md` — spec text vs code — this change (5.2a) — the text is aligned with the code.
- F38 — see `findings.md` — spec text vs code — this change (5.2a) — the text is aligned with the code.
- F39 — verification/verification-hardening / `Interactive client with nothing declared passes the pin` — title only — new changes `retire-misnamed-scenarios` + `restore-renamed-scenarios` (as F1); the scenario text is already true.
- F40 — see `findings.md` — spec text vs code — this change (5.2a) — the text is aligned with the code (the park reason stays INFRA; the text is aligned).
- F41 — see `findings.md` — spec text vs code — this change (5.2a) — the text is aligned with the code (the spec says "the tar archive").
- F42 — see `findings.md` — spec text vs code — this change (5.2a) — the text is aligned with the code (drop "by default").

### Decision point 6.8 (2026-10-11, operator)

- F44, F46, F47, F48, F49, F50, F51, F52, F53, F61 — spec text vs code — this change (6.9) — the 5.2 rule: the THEN is aligned with the code in this change's delta, titles kept; a scenario whose test now matches gets its marker instead of `pending-test`.
- F45 — `docs/guides/adapter-author-guide.md` §7 — doc text vs code — this change (6.9) — WARN becomes INFO, as F8.
- F65 — `add-decision-arbiter` — MODIFIED blocks of absent requirements — this change (6.9) — each block becomes an `ADDED` requirement (new behavior under a new heading), or a `MODIFIED` block of the requirement it really changes, layered on this change's text.
- F71 — `add-stage-iteration` — as F65 — this change (6.9) — as F65.
- F58, F60, F64 — scenario titles left behind by a queued change — new changes `retire-misnamed-scenarios` + `restore-renamed-scenarios` — candidates added to that plan; renamed once the change that made the title stale has landed.
- F59 — `add-claim-return` — the return replaces the release inside `ClaimOutageRelease` — that change (plain bullet in its `tasks.md`).
- F62, F63 — `add-subprocess-access-log` — the composed-environment requirement vs the subtractive client environment; the two dropped deltas accepted — that change (plain bullet in its `tasks.md`).
- F66 — `define-executor-contract` — adds the `State directory with one writer per file` block — that change (plain bullet).
- F67 — `fix-terminal-receipt-convergence` — retarget the no-op marker and test the narrowed no-op — that change (plain bullet).
- F68, F70 — `own-git-invocation-policy` — reach entries and enumerations; narrow task 7.3's first hand-off — that change (plain bullet).
- F69 — `fix-docker-exec-env-argv`, `own-git-invocation-policy` — ADR numbers collide with 0011 — those changes (plain bullet in each).
- F54 — tracker/tracker-take / locate authentication refusal — accepted — the text agrees with the code; no change.
- F55 — remote-outage gate and the locate outage — accepted — the gate keeps its `BaseRefGit` signals only.
- F56 — `TakeClaimAndWork` over the 200-line cap after F6 — this change (6.9) — a responsibility split by the rules of `process-invariants.md`, TDD and scoped PIT.

### Revision 2026-10-12 (operator)

- The six area changes `add-scenario-tests-git`, `-tracker`, `-execution`, `-serve`, `-run` and
  `-build` sorted at 5.2 are withdrawn; their 302 `pending-test:` scenarios are tested in this
  change (group 8), F3 in 8.1 and F10 in 8.5. `pending-test:` stays only for a scenario whose
  behavior a queued change still delivers (`add-subprocess-access-log`, F29).
- `retire-misnamed-scenarios` and `restore-renamed-scenarios` (F1, F28, F39; F58, F60, F64)
  stay follow-up changes.

### Decision point 8.14 (2026-10-12, operator)

Rules decided:
- **Spec text against code** — the 5.2 rule: the THEN (or body sentence) is aligned with the code
  in this change's delta, titles kept; the test then asserts the aligned text and loses its
  `@PendingFeature` (8.15, text pass).
- **A finding sorted to a new change** keeps its test under `@PendingFeature`, whose reason names
  the finding and the change (`"F<n> (<change>): …"`); a scenario with no test keeps its
  `pending-test: <change>` exemption. The changes named here are follow-ups, not yet proposed.

Findings:
- F72 — git/git-task-persistence / `A slow but progressing transfer completes` — revealed defect — this change (8.15, text) — the network deadline is a hard wall-clock bound; the requirement is restated, the operator raises `factory.git-network-timeout` for large transfers.
- F73 — git/git-task-persistence / `Event-driven detection only wakes the poll` — revealed defect — this change (8.15, text) — no event-driven detection exists; the sentence becomes a `**Notes**:` constraint on a future detection and the scenario is dropped (or, where OpenSpec refuses the drop, restated to the polling the code does).
- F74 — lifecycle/task-branch-contract / `A split tenure record cannot be assembled` — revealed defect — new change `close-assembly-escape-hatches` — writers take the book only from `TaskGit`.
- F75 — tracker/tracker-take / `Revoked sandboxed task keeps a stopped environment` — revealed defect — this change (8.15, text) — the sweep stops the box (D3); the THEN is restated.
- F76 — tracker/tracker-take / `Nothing eligible is a clean no-op naming the reason` — revealed defect — this change (8.15, code) — an INFO line names the reason.
- F77 — tracker/tracker-take / `WIP limit blocks a fresh start` — spec text vs code — this change (8.15, text) — no work, reason named, exit 15.
- F78 — tracker/github-tracker / `Every marker kind is written through the upsert` — spec text vs code — this change (8.15, text) — the claim comment is written by POST and verified by read-back (D6).
- F79 — tracker/github-tracker / `Each removed claim gets its own removal marker` — revealed defect — new change `fix-stale-claim-removal-redrive`.
- F80 — tracker/github-tracker / canonical id vs host-less markers — spec text vs spec — this change (8.15, text) — the records carry the repository-scoped id.
- F81 — tracker/tracker-port / `The repair marker names the observed shape` — revealed defect — this change (8.15, code) — the in-memory adapter names the shape, as GitHub does.
- F82 — sandbox/execution-environment / `No constructor mixes installation equipment and task inputs` — revealed defect — new change `close-assembly-escape-hatches`.
- F83 — sandbox/execution-environment / in-branch decision file in host mode — revealed defect — this change (8.15, text) — host mode keeps its temp decision file (the declared pair `DecisionFileTransport` / `BranchDecisionFile`).
- F84 — sandbox/execution-environment / `Container runtime outage` body — spec text vs code — this change (8.15, text) — no retry, immediate escalation.
- F85 — sandbox/sandbox-egress / `Guard crash burns no attempt` — revealed defect — new change `probe-egress-guard-per-round`.
- F86 — executor/agent-executor / telemetry failure — spec text vs code — this change (8.15, text) — wall time is kept.
- F87 — sandbox/sandbox-lifecycle / `Same vocabulary in daemon and one-shot logs` — spec text vs code — this change (8.15, text) — the ledger records acting verdicts only, category spelled as the ledger does.
- F88 — observability/serve-observability / `An outage open at stop writes no line` — revealed defect — this change (8.15, code) — the lookup's outage release opens the gate.
- F89 — observability/dashboard-page / outage alert duration — spec text vs code — this change (8.15, text) — "open since <instant>".
- F90 — observability/dashboard-page / `Silent stall becomes loud` — revealed defect — new change `surface-check-urls-and-sweep-sources`.
- F91 — observability/serve-observability / `Probes advance the section without claims` — spec text vs code — this change (8.15, text) — the count is 4 (the opening failure plus three probes).
- F92 — observability/tracker-board / board writes — revealed defect — this change (8.15, text) — board provisioning of missing labels is stated in the requirement.
- F93 — observability/dashboard-page / six browser-only scenarios — other — new change `add-dashboard-browser-tests` (`pending-test`).
- F94 — observability/dashboard-page / `Hygiene block degrades on its own` — spec text vs code — this change (8.15, text) — the stale hygiene vital alarms in the status card.
- F95 — serve/factory-serve / `Probe intervals grow with jitter and stop at the cap` — revealed defect — this change (8.15, code) — clamp after jitter.
- F96 — serve/factory-serve / `Reaping unblocks a WIP-blocked feed` — spec text vs code — this change (8.15, text) — the returned task is taken first.
- F97 — serve/factory-serve / `Graceful release` — revealed defect — `add-claim-return` (plain bullet in its `tasks.md`).
- F98 — observability/factory-logging / `The local-string bypass fails the gate` — revealed defect — this change (8.15, code; test-side gate) — the log gate scan covers `FindingsSanitizer.forLog`.
- F99 — serve/factory-serve / `Two bases, one broken` — revealed defect — this change (8.15, code) — the `Base ref:` line names the ref.
- F100 — observability/status-report / `A breaking change to a released contract bumps the version` — other — new change `document-contract-versioning` (`pending-test`).
- F101, F102 — core/time-source — spec text vs code — this change (8.15, text) — time is built from the root's parts; the in-memory tracker keeps its own counter.
- F103 — cli/task-inspection / `Freshly created branch renders as pending` — spec text vs code — this change (8.15, text) — `Created` renders as the first stage with no rounds.
- F104 — core/untrusted-text / `GitShowTip` as `@UntrustedParser` — other — this change (8.15, text) — accepted, text clarified.
- F105 — core/untrusted-text / tip shape — revealed defect — this change (8.15, code) — `VerifiedTip` checks 40 or 64 hex characters.
- F106 — operator/operator-configuration / `List-valued keys are replaced whole` — revealed defect — new change `fix-list-config-merge`.
- F107 — operator/secrets-provider / `One seam to audit` — revealed defect — new change `route-agent-credentials-through-secrets`.
- F108 — verification/verification-hardening / `Terminal escape attack is neutralized` — spec text vs code — this change (8.15, text) — field-by-field rendering (D6), no fence.
- F109 — verification/github-external-check / `A green check is auditable from the tracker` — revealed defect — new change `surface-check-urls-and-sweep-sources`.
- F110 — plugin/plugin-api-contract / re-baseline without a bump — other — this change (8.15, text) — accepted, text clarified.
- F111 — build-system/quality-gates / `Dead private code fails compilation` — revealed defect — new change `restore-unused-code-checks`.
- F112 — build-system/quality-gates / `A production-scope advisory is fixed, never suppressed` — spec text vs code — this change (8.15, text) — the scenario is scoped to the allowlisted artifacts; the catalog comment is corrected.
- F113 — build-system/quality-gates / `An override reaches every configuration that resolves the artifact` — revealed defect — new change `fix-buildscript-jackson-lock`.
- F114 — build-system/quality-gates / `Source file size cap` — other — this change (8.15, text) — the cap stays a review rule (`process-invariants.md`); the scenario's THEN is restated to it, or it becomes an exemption naming that review.
- F115, F120 — one-time migration and refactor scenarios — other — new change `retire-one-time-scenarios` (`pending-test`).
- F116, F117 — build-system/quality-gates / `Shared app-layer assembly fixture` — spec text vs code — this change (8.15, text) — one standard site plus named deviations.
- F118 — build-system/quality-gates / `Fresh clone needs no tuning` — spec text vs code — this change (8.15, text) — "no `gradle.properties` sets …".
- F119 — build-system/module-layering / `A use case reaches its collaborator through a port` — revealed defect — new change `close-assembly-escape-hatches`.
- F121 — release/release-distribution / `Release build carries the tag's version` — other — this change (8.15, text) — accepted, text clarified.

### Follow-up 2026-10-12

- F122 — lifecycle/task-branch-contract / delivery read from a commit subject the gnome can
  write — revealed defect — then sent to a new change (withdrawn by the third revision below). The test predates this
  change (#71); its `@PendingFeature` reason now names the finding and the change.

### Revision 2026-10-12, second (operator)

- The twelve follow-up changes named at 8.14 and in the 2026-10-12 follow-up are withdrawn; their
  findings are fixed in this change, group 9 (design D11–D17), each by the first disposition its
  `findings.md` row proposed: F113 (9.1), F111 (9.2), F100 (9.3), F90 (9.4), F109 (9.5), F106
  (9.6), F79 (9.7), F74 (9.8), F82 (9.9), F119 (9.10), F107 (9.11), F85 (9.12), F122 (9.13),
  F93 (9.14), F115 and F120 (9.15).
- F122: delivery is found by walking the factory's round records, not by a signed commit or the
  tracker (D13). F93: Selenium in a Testcontainers browser (D15). F115, F120: `REMOVED` blocks,
  and an `ADDED` requirement under a new heading where standing scenarios remain (D16) — a second
  exception to NG4.
- F97 stays with `add-claim-return` (plain bullet in its `tasks.md`).

### Revision 2026-10-12, third (operator)

- Task 9.13 as written could not be done: the walk over round records of the former D13 has no sound
  starting point (mid-round pushes put gnome commits at the tip, nothing in a commit tells a
  factory commit from a gnome one, host round commits carry no token, a forged snapshot subject
  jumps the walk), and the same root cause admits a forged envelope without any subject (F135,
  found by 9.13; the host `git add -A` carry and the Aborted-over-forged-envelope route found by
  the 2026-10-12 architect audit). The operator decided: host mode carries no integrity claim
  against the gnome (D19); the factory register is a ref pushed atomically with the branch, not a
  tracker record (D13 as revised); and the fix stays in this change rather than opening the
  follow-up change named on 2026-10-12 — 9.13 becomes 9.13a–9.13e (D13, D18, D19; NFR-S2 as
  revised; FR13 gains F135).
- The `@PendingFeature` reason of `DeliveryAncestrySpec` names a change that no longer exists;
  9.13b removes the annotation, so no rename is owed.
