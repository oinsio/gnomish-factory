# Tasks

Sequenced **first** in the queue (proposal, Impact): every archive stage is red until this change
lands. Per-task verification follows `verification-scope.md`; the whole-tree `./gradlew check`
runs once, in task 7.4. Production Java is touched only for a finding sorted into this change
at a decision point (proposal NG5, FR11; design D9); such a fix runs the scoped PIT of
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

## 1. The checker, unwired (D5, D6, D7; FR1, FR2, FR5, FR6, NFR-R1, NFR-R3, NFR-O1, NFR-S1, NFR-C1; M6)

- [ ] 1.1 Fix Q1 and write the reader: `SpecContractReader` in
      `build-logic/src/main/groovy/com/github/oinsio/gnomish/build/` reads one spec file into
      requirement blocks — heading, body lines, metadata lines (`^\*\*[^*]+\*\*:`), scenario
      headings (`^####\s`), with fenced blocks masked — and one test file into its
      `// Scenario: <spec-id> / <title>` markers (also inside block comments). Verify: a Spock
      spec in `build-logic/src/test` with fixtures for each shape OpenSpec documents (a
      metadata-only body, a fenced `#### Scenario:` that is not a scenario, a `###` divider
      ending a body, a marker inside `/* */`), one feature per shape, named by FR1/FR2/FR5.
- [ ] 1.2 Write `SpecContractCheck` (a `@CacheableTask`, inputs: `openspec/specs/**`, the
      unarchived deltas when `changes` is set, every `src/test` tree and
      `build-logic/src/functionalTest`; one output marker file): rules FR1 (exactly one
      `**Implements**:` per requirement; no `<!-- implements` in scanned trees), FR2 (notes one
      line, no `SHALL`/`MUST`, ordinary text after a metadata line is a wrapped note), FR5 (both
      directions, exemption entries `scenario`, `verifiedBy`, asserted reached; `pending-test:`
      as a `verifiedBy` form per D9), a `spec` filter property for per-task runs, findings in the
      file:line / rule / fix wording of `TestTimeInjectionCheck` and one summary line. Verify:
      `SpecContractCheckFunctionalSpec` (TestKit, a mini project with a spec tree and a test
      tree) — one feature per scenario of the `quality-gates` delta, each shown red with the
      rule removed; wall time of the task over a copy of the real `openspec/specs` and test trees
      under five seconds, recorded in the task report (M6).
- [ ] 1.3 Register the task in the root `build.gradle` as `checkSpecContract` with an empty
      exemption block and the `--changes` / `-PspecContractChanges` and `-PspecContractScope`
      properties, **not** yet a dependency of `check` (D7); add `scripts/check-spec-contract.sh`
      (one `./gradlew -q checkSpecContract` with arguments passed through; bash 3 compatible like
      `uncommitted-files.sh`). Verify: `scripts/check-spec-contract.sh --changes` on the current
      tree runs and reports — this is the first inventory: the count of requirements without the
      line (expected 508), of comment-form hits, of unlinked scenarios (expected close to
      1 369); numbers recorded in the task report. `./gradlew check` unchanged (the task is not
      wired).
- [ ] 1.4 Add the verification command to `.gnomish/stages/fix-artifacts/stage.yaml`
      (`./gradlew -q checkSpecContract -PspecContractChanges=true`, after the strict validate)
      and `.gnomish/stages/archive/stage.yaml` (`./gradlew -q checkSpecContract`, after the
      strict validate of the merged specs), each a launcher of one line with the message
      naming the rule file to read. Verify: the functional spec's "manual launcher is the same
      gate" feature extended to run the exact command text of both stage files, parsed from the
      YAML, against the fixture tree and expect the same findings (D5, single-owner row 3).

## 2. Rules and guides (D1, D2, D4; FR7; UX3)

- [ ] 2.1 `.claude/rules/traceability.md`: the table row for "Spec requirement (markdown)"
      becomes `**Implements**: FR-X of <change-name>`; a new row "Spec scenario" →
      `// Scenario: <spec-id> / <title>` in the test that exercises it; a sentence that the
      YAML-like forms keep `# implements`. Verify: the file shows the two rows; `grep -n
      "<!-- implements" .claude/rules/traceability.md` is empty.
- [ ] 2.2 `.claude/rules/delta-specs.md`: the template shows the finished shape (text within
      OpenSpec's limit, `**Implements**:` line, an optional one-line `**Notes**:`, scenarios);
      a "One behavior per requirement" section stating the limit as a fact to design for, the
      scenario-or-requirement rule for normative text, notes as the exception, the marker
      obligation for every new scenario; the Rules list updated. Verify: the file's example
      passes `checkSpecContract` when dropped into the functional spec's fixture tree (add it as
      a feature).
- [ ] 2.3 `.claude/rules/testing.md`: a short section "Every scenario has a test" — the marker
      beside the feature, both directions checked, the exemption form and its bar (a mechanism
      that really verifies the scenario, named). `openspec/config.yaml` `rules.specs`: replace the
      invariant line with three lines (the `**Implements**:` line; one behavior per requirement,
      OpenSpec's 500-character limit; a marker for every scenario). `.claude/commands/fix-uncommitted.md`
      line 104: the exception now names the `**Implements**:` line. Verify: `openspec instructions
      specs --change tighten-spec-contract --json` returns the three rules.
- [ ] 2.4 `docs/guides/developer-guide.md`: one subsection under verification — the gate, the
      manual launcher, how to fix each finding, how to exempt a scenario; `docs/glossary.md`: an
      entry "Spec contract" (the project's rules on top of OpenSpec's, and the gate that checks
      them) in the build/quality context. Verify: both files render (headings in the guide's
      table of contents if it has one); `grep -n "Spec contract" docs/glossary.md` hits.

## 3. The migration deltas (D3; FR8, NFR-R2; M2)

- [ ] 3.1 Write `scripts/generate-spec-deltas.sh` (bash 3 + awk/sed, no other runtime):
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
      scenario count, and exits non-zero on any difference; run on the real tree: 508
      requirements, 0 differences.
- [ ] 3.2 Run the generator; commit nothing (the human commits); run
      `openspec validate tighten-spec-contract --strict` (expected: valid — `MODIFIED` length is
      not checked) and `scripts/check-spec-contract.sh --changes -PspecContractScope=changes`
      (expected: no FR1/FR2 finding inside this change's deltas). Verify: both outputs in the
      task report; `grep -rn "<!-- implements" openspec/changes/tighten-spec-contract` empty.
- [ ] 3.3 Strict preview of the untouched migration: run the recipe, `openspec validate --specs
      --strict` in the scratch worktree. Expected: still 43 failing specs, now 266 findings
      (the comments no longer count) and no finding of any other kind — this is the baseline
      group 4 works down. Verify: the per-spec counts recorded in the task report and matched
      against the table in design Context (31 for `git-task-persistence`, 21 for
      `tracker-take`, …); any new kind of finding is a generator defect fixed here.

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

- [ ] 4.1 `git-task-persistence`, requirements 1–17 in file order (the spec's first half).
- [ ] 4.2 `git-task-persistence`, requirements 18–34.
- [ ] 4.3 `tracker-take` (21 long of 27).
- [ ] 4.4 `execution-environment` (15 of 26).
- [ ] 4.5 `github-tracker` (15 of 22).
- [ ] 4.6 `tracker-port` (14 of 22).
- [ ] 4.7 `observability/dashboard-page` (12 of 26) and `observability/tracker-board` (5 of 8).
- [ ] 4.8 `factory-serve` (11 of 19) and `serve-observability` (4 of 24).
- [ ] 4.9 `agent-executor` (11 of 19).
- [ ] 4.10 `manual-run` (10 of 18), `cli-arguments` (3 of 3), `operator-console` (1 of 3).
- [ ] 4.11 `stage-engine` (11 of 17) and `pipeline-config` (6 of 23).
- [ ] 4.12 `quality-gates` (8 of 23), `module-layering` (4 of 6), `build/sandbox-module-split`
      (1 of 2); `build/build-conventions` and `build/test-fixtures-module` are already within
      the limit — confirm in the preview and leave their generated deltas untouched.
- [ ] 4.13 `lifecycle/claim-heartbeat` (8 of 12), `lifecycle/task-branch-contract` (7 of 8),
      `lifecycle/subprocess-supervision` (1 of 2); `lifecycle/factory-bootstrap` confirmed clean.
- [ ] 4.14 `factory-logging` (7 of 12), `untrusted-text` (4 of 5), `status-report` (4 of 6).
- [ ] 4.15 `sandbox-lifecycle` (7 of 10), `sandbox-egress` (5 of 10), `factory-egress-allowlist`
      (1 of 3).
- [ ] 4.16 `verification/github-external-check` (5 of 11), `verification/verification-hardening`
      (2 of 8), `verification/check-provider-model` (1 of 8), `verification/http-check-provider`
      (1 of 4), `plugin/plugin-api-contract` (2 of 7), `plugin/adapter-binding-registry` (1 of 8),
      `plugin/github-plugin` (1 of 3); `verification/dependency-verification` and
      `plugin/plugin-discovery` confirmed clean.
- [ ] 4.17 `project-licensing` (5 of 8), `base-ref-resolution` (4 of 7), `git-transfer` (3 of 9),
      `task-inspection` (3 of 5).
- [ ] 4.18 `project-registry` (3 of 4), `operator-configuration` (3 of 3), `release-distribution`
      (2 of 4), `vendor-connection-profile` (2 of 3), `secrets-provider` (1 of 4).
- [ ] 4.19 Whole-corpus preview: the strict preview with `--specs`; expected 48 of 48 passing
      (M1). Verify: the output in the task report; any remaining finding goes back to the task
      that owns the capability, not fixed here.

## 5. Every scenario has a test (D4, D9; FR5, FR9; M3, M4)

- [ ] 5.1 Inventory, automated first pass: a one-off script (scratch, not committed) matches
      every scenario title of the previewed main specs (group 4's result, including the new
      scenarios listed in the group-4 inventories) against feature names and comments of every
      test tree by normalized word overlap, and writes `scenarios-<spec-id>.md` per capability
      with status linked / probable / missing and the candidate file. Verify: the tables exist
      for all 48 capabilities; the totals (linked, probable, missing) are in the task report.
- [ ] 5.2 **Decision point (D9, FR11, Q2).** Present to the operator the totals of 5.1, the
      "missing" scenarios grouped by capability, the obvious exemptions (scenarios verified by
      CI workflows, release scripts, or the OpenSpec CLI itself), and every open row of
      `findings.md` so far (group 4's normative remainders without a home among them). The
      operator sorts each item: "this change" or "new change `<name>`". Verify: the sorted
      list with reasons copied under `## Decisions` in this file, every row of `findings.md`
      closed with its disposition, before 5.3 starts.
- [ ] 5.3 Markers and tests for the capabilities of tasks 4.1–4.3 (`git-task-persistence`,
      `tracker-take`): confirm each "probable" by reading the test; place the marker above the
      feature (converting the four informal `// Scenario:` comments of `TakeBareAutoSpec` to the
      form); write a Spock feature for each "missing" scenario by TDD (red on the behavior the
      scenario states, green, marker placed) or record it as an exemption with its mechanism. A
      feature that stays red because the code does not do what the scenario states is a
      revealed defect: add a `findings.md` row, keep the feature under `@PendingFeature` with
      the finding id, fix nothing here (D9).
      Verify: `./gradlew checkSpecContract -PspecContractScope=git-task-persistence,tracker-take`
      green against the previewed specs (the task takes the preview tree's `openspec/specs` as
      an input override for this purpose — add `-PspecContractSpecsDir` in 1.2 if not present);
      the module specs the task touched run by name; no `.@` field read introduced.
- [ ] 5.4 Same for tasks 4.4–4.6 (`execution-environment`, `github-tracker`, `tracker-port`).
- [ ] 5.5 Same for tasks 4.7–4.8 (`observability/*`, `factory-serve`, `serve-observability`).
- [ ] 5.6 Same for tasks 4.9–4.11 (`agent-executor`, `manual-run`, `cli-arguments`,
      `operator-console`, `stage-engine`, `pipeline-config`).
- [ ] 5.7 Same for tasks 4.12–4.13 (`quality-gates`, `module-layering`, `build/*`,
      `lifecycle/*`) — here most "tests" are `build-logic` functional specs and `:bootstrap`
      architecture specs; a scenario verified only by CI (`Continuous integration`, `Security
      scanning`) is an exemption naming the workflow file.
- [ ] 5.8 Same for tasks 4.14–4.16 (`factory-logging`, `untrusted-text`, `status-report`,
      `sandbox-*`, `factory-egress-allowlist`, `verification/*`, `plugin/*`).
- [ ] 5.9 Same for tasks 4.17–4.18 (`project-licensing`, `base-ref-resolution`, `git-transfer`,
      `task-inspection`, `project-registry`, `operator-configuration`, `release-distribution`,
      `vendor-connection-profile`, `secrets-provider`) — `project-licensing` and
      `release-distribution` scenarios verified by `scripts/check-distribution-terms.sh`,
      `scripts/release-preflight.sh` or a workflow are exemptions naming that file.
- [ ] 5.10 Exemption review: every entry of the exemption block in the root `build.gradle`
      re-read against its bar (`testing.md`, "Every scenario has a test"); the count and the
      list in the task report (M4); `pending-test:` entries, if any, each name the follow-up
      change from 5.2. Verify: `./gradlew checkSpecContract -PspecContractSpecsDir=<preview>`
      green over all 48 capabilities with zero unlinked and zero dangling.
- [ ] 5.11 Fixes sorted into this change at 5.2 (revealed defects, normative remainders that
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

- [ ] 6.1 `add-pipeline-entry-precondition`, `add-decision-inheritance`, `add-epic-decomposition`,
      `add-pipeline-routing` (the four with the most long `ADDED` requirements).
- [ ] 6.2 `add-stage-finished-event`, `add-subprocess-access-log`, `add-claim-return`,
      `add-sandbox-base-image`.
- [ ] 6.3 `add-sandbox-cloud-executor`, `add-sandbox-colima-vm`, `add-sandbox-gha-executor`,
      `add-sandbox-hardening`.
- [ ] 6.4 `add-command-executor`, `add-decision-arbiter`, `add-doctor-command`,
      `add-tracker-task-hierarchy`.
- [ ] 6.5 `add-artifact-depot`, `define-executor-contract`, `enforce-artifact-contracts`,
      `extract-agent-round-runner`.
- [ ] 6.6 `fix-docker-exec-env-argv`, `fix-terminal-receipt-convergence`,
      `harden-verification-integrity`, `own-git-invocation-policy`,
      `migrate-verify-to-executor-contract`.
- [ ] 6.7 `kill-expensive-mutants` and `supervise-daemon-loops-and-embed-dashboard` — each only
      if it is still under `openspec/changes/` when this task runs (both may have been archived
      by hand meanwhile; then record "already archived" and skip). Verify as above, plus
      `openspec list --json` in the task report showing which of the two remained.
- [ ] 6.8 **Decision point (D9, FR11).** Present every open row of `findings.md` added since
      5.2 — the queued scenarios without a test from 6.1–6.7, any defect revealed in 5.3–5.10 —
      to the operator, who sorts each into "this change" or "new change `<name>`". For a
      queued scenario sorted into this change: the marker and the test are written now (the
      behavior exists), by the rules of 5.3, and the delta's scenario keeps its title so the
      marker links on merge. For one sorted out: the implementing change's tasks are its
      record — note the finding id in that change's `tasks.md` as a plain bullet. Verify: the
      sorted list under `## Decisions`; `findings.md` has no open row; the tests written here
      run by name and the scoped checker (`-PspecContractScope=<spec-ids>`) is green.
- [ ] 6.9 Fixes sorted into this change at 6.8, by the rules of 5.11. Verify: as 5.11.

## 7. Wiring and the single whole-tree gate (D5, D7; FR6; G1, G3)

- [ ] 7.1 Final strict preview with `--specs` and with the checker run against the preview tree:
      both green; the generator's `verify` passes for all 48 capabilities. Verify: outputs in the
      task report.
- [ ] 7.2 Make root `check` depend on `checkSpecContract` (root `build.gradle`, next to the other
      gate wirings), remove `scripts/generate-spec-deltas.sh` and its sidecar hashes (D3: the
      generator has no second use; its `verify` logic that group 4 relied on is not needed once
      the change is archived — say so in the commit recommendation). Verify:
      `SpecContractCheckFunctionalSpec` gains the "root check runs the gate" feature; `./gradlew
      checkSpecContract` green on the working clone **against the current `openspec/specs`** is
      not expected yet (the comments are still there until archive) — so the wiring is verified
      with `-PspecContractSpecsDir=<preview>`; the task report states this explicitly.
- [ ] 7.3 Record the archive-time order in `## Workflow follow-up` below and in the task report:
      the first root `check` on the real tree is green only after `openspec archive` merges the
      deltas; the archive stage's own verification (1.4) is what proves it on the task branch.
- [ ] 7.4 Whole-tree gate: `./gradlew check` once, with `-PspecContractSpecsDir=<preview>` for
      this run only; fix what fails; then `scripts/check-spec-contract.sh --changes` over the
      queue one last time. Verify: `check` green; the summary line of the checker in the report
      (scenarios checked, linked, exempt); `findings.md` has no open row and every row's
      disposition appears under `## Decisions`; every `@PendingFeature` and every
      `pending-test:` entry names a change listed there; the recommended commit message lists
      the change and the requirement ids.

## Workflow follow-up

- `/opsx:archive tighten-spec-contract` merges the 48 deltas; immediately after the merge,
  `openspec validate --specs --strict` and `./gradlew checkSpecContract` (now without the
  preview override) are green on the real tree — the archive stage's verification commands from
  1.4 run exactly these. If either is red at archive, the merge is wrong, never the delta.
- `supervise-daemon-loops-and-embed-dashboard`, if still in progress, is archived by hand with
  the comment form (its deltas were rewritten in 6.7 only if it was still queued) — or waits.
- Every new change named under `## Decisions` is proposed right after archive, with its
  findings (the `pending-test:` entries, the `@PendingFeature` tests, the queued scenarios) as
  the seed of its task list.

## Decisions

Filled at 5.2 and 6.8 (D9, FR11): one line per finding — `F<n> — <capability> / <scenario or
requirement> — <kind> — this change | new change <name> — <reason>`. Empty until then.
