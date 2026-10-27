# Tasks: split-long-spec-requirements

The deltas under `specs/` are written (seven capabilities, ten MODIFIED and five ADDED
requirements, each MODIFIED text measured under 490 — FR1, FR2, design D1, D2). What remains is
the markers, the preview check and the sync.

## 1. Markers for the new scenarios (FR3)

Each task adds one `// Scenario: <spec-id> / <title>` line above the named feature; nothing
else in the test changes. Verify each with
`./gradlew -q checkSpecContract -PspecContractChange=split-long-spec-requirements` green.

- [x] 1.1 `build-logic/src/functionalTest/.../SpecContractScenarioLinkFunctionalSpec.groovy`:
      `build-system/quality-gates / An exemption entry names its mechanism` above the feature
      that carries "An exempt scenario passes with its reason" (line 122), and
      `build-system/quality-gates / An entry naming no scenario fails the gate` above the one
      that carries "A stale exemption fails the gate" (line 143).
- [x] 1.2 `application/src/test/.../app/serve/DaemonConditionsSpec.groovy`:
      `serve/factory-serve / A reported refusal opens the condition of its kind` above
      "FR15: a reported refusal opens the condition of its kind and no other" (line 104), and
      `serve/factory-serve / Probes repeat the condition's own check on the capped cadence`
      above "FR15: an hour of an open box-git-floor condition is its own check on the growing,
      capped cadence" (line 209).
- [x] 1.3 `application/src/test/.../serveobservability/json/SnapshotJsonMapperSpec.groovy`:
      `serve/factory-serve / The conditions section carries every condition's entry` above the
      feature that renders the `conditions` section with an open entry (the one carrying
      "Blocked on the remote is readable without logs", line 220, or the feature at line 223 —
      read both and pick the one whose assertions cover an entry per condition; add an assertion
      only if neither does).
      Done in `serveobservability/ConditionHealthAssemblerSpec`, "one entry per condition, the
      remote's first, each with its token and state": neither feature of `SnapshotJsonMapperSpec`
      touches `conditions` (both render `remote`), and that feature asserts the scenario whole.
- [x] 1.4 `adapters/git/src/test/.../DeliveredBranchReaderSpec.groovy`:
      `tracker/tracker-take / The delivered state never comes from a commit after the cleanup`
      above the feature carrying "Delivered state survives commits after cleanup" (line 159).

## 2. The preview check (FR4, NFR-R1, NFR-R2, NFR-S1, NFR-C1, UX1; design D3–D5)

- [x] 2.1 Stand-in table `test-fixtures/src/main/resources/stand-in/tables/openspec.params`
      with the three presets of D5 (`openspec-preview-pass`, `openspec-preview-long`,
      `openspec-preview-archive-refused`), each `* record PWD` then `archive,*` and
      `validate,*` rows; answers in `data/stdout-openspec` (sections `all-pass`,
      `long-requirement` — the `✗ spec/tracker/github-tracker` block with the >500 warning and a
      `Totals:` line — and `data/stderr-openspec#modified-not-found`). Verify:
      `StandInLibrarySpec` gains the rows and `StandInRowReachSpec` reaches every row
      (`./gradlew :bootstrap:test --tests '*StandIn*'`).
      As built: OpenSpec 1.14.1 prints the archive's refusal on stdout, so the answer is
      `data/stdout-openspec#modified-not-found`; `data/stderr-openspec` holds the validation's
      `✗`/`⚠` lines, which the CLI writes to stderr.
- [x] 2.2 `scripts/check-merged-specs.sh <change>` per D4: `set -euo pipefail`, run from the
      repository root wherever called, the change name validated and required to be a
      directory under `openspec/changes/` (not `archive`), `cp -R openspec` into `mktemp -d`
      removed by a `trap`, `"${OPENSPEC:-openspec}" archive "$change" --yes` then
      `"${OPENSPEC:-openspec}" validate --specs --strict --no-interactive` in the copy, the
      failing specs' lines printed on failure with one closing line ("split the requirement in
      the delta — one behavior per requirement — or, for a failure the change does not touch,
      escalate"), exit 1 on any failure, 0 otherwise. Verify by hand on the branch:
      `scripts/check-merged-specs.sh add-doctor-command` exits 0 (M2) and
      `scripts/check-merged-specs.sh split-long-spec-requirements` exits 0.
      As built: `add-doctor-command` exits 1 until 4.1 syncs the seven specs (the pre-existing
      failures); M2's exit 0 is checked in 4.1. The scratch directory is made from an explicit
      `${TMPDIR:-/tmp}/check-merged-specs.XXXXXX` template: macOS `mktemp -d` ignores `TMPDIR`.
- [x] 2.3 `bootstrap/src/test/.../MergedSpecsPreviewScriptSpec.groovy` (beside
      `ReleasePreflightScriptSpec`): a copy of a small `openspec/` tree in `@TempDir` with one
      change; features, each with its marker under `build-system/quality-gates`: "A MODIFIED
      requirement grown past the limit fails the preview" (preset `long`: exit 1, output holds
      the spec id and the warning line), "A passing preview leaves no trace" (preset `pass`:
      exit 0, the tree byte-identical, no copy left, the recorded `PWD` outside the tree), "A
      delta that no longer applies fails the preview" (preset `archive-refused`: exit 1 with the
      stderr words), "A preview without the CLI fails closed" (`OPENSPEC=/nonexistent`, and a
      change name with no directory: exit 1 each, the cause printed). Verify:
      `./gradlew :bootstrap:test --tests '*MergedSpecsPreviewScriptSpec'`, and
      `StandInOwnerSpec` green (the script lives under `scripts/`, outside the scanned trees).

## 3. Wiring (FR5, FR6; design D6)

- [x] 3.1 `.gnomish/stages/fix-artifacts/stage.yaml`: one `command` check after the
      `openspec validate "$change" --strict` check, with the stage's preamble naming the change,
      `if ! out=$(scripts/check-merged-specs.sh "$change" 2>&1); then`, `printf '%s\n' "$out" |
      tail -40`, one literal `echo` naming the script and `.claude/rules/delta-specs.md` ("the
      merged main specs would not validate strictly — split the requirement in this change's
      delta, one behavior per requirement; a failure in a spec this change does not touch is not
      this round's to fix — escalate through \$GNOMISH_DECISION_FILE naming it"), `exit 1`.
      Verify: the stage manifest still loads (`./gradlew :bootstrap:test --tests
      '*StageManifest*'` or the loader spec that reads `.gnomish/stages`), and 3.2.
      As built: no spec loads the project's own manifests through the production loader; the
      new entry has the shape of its neighbours, and 3.2 parses the manifest with snakeyaml.
- [x] 3.2 `build-logic/src/functionalTest/.../StageMergedSpecsPreviewCommandSpec.groovy`: reads
      the manifest with snakeyaml, asserts exactly one `verify` command names
      `check-merged-specs.sh`, that its text matches the pinned shape (preamble, the one script
      call, the tail, one literal echo, `exit 1`), that it does not spell `checkSpecContract`,
      and that no other file under `.gnomish/` or `.claude/commands/` spells `openspec archive`
      outside `archive.md` and the archive stage. Marker: `build-system/quality-gates / The
      fix-artifacts round runs the preview after the change's own validation`. Verify:
      `./gradlew :build-logic:functionalTest --tests '*StageMergedSpecsPreviewCommandSpec'`
      (run through the root build, see the build-task note in memory: never `-p build-logic`).
      As built: the allowlist of files that may spell `openspec archive` is
      `.claude/commands/opsx/archive.md`, `.claude/commands/opsx/sync.md` (prose on what the archive
      does to a main spec; an OpenSpec-managed command) and `.gnomish/stages/archive/instructions.md`,
      each asserted reached; the two trees are `functionalTest` inputs
      (`functional-test-inputs.gradle`).
- [x] 3.3 `.claude/commands/review-artifacts.md`, the freshness step: after `openspec validate
      <name> --strict`, run `scripts/check-merged-specs.sh <name>`; a failure is a freshness
      finding quoting OpenSpec's lines, a pre-existing failure in a spec the change does not
      touch is reported as such. Verify by reading the step.
- [x] 3.4 `docs/guides/developer-guide.md`, section "The spec-contract gate": a paragraph on
      `scripts/check-merged-specs.sh <change>` — what it runs, that the repository is untouched,
      when to run it (before `/opsx:archive`). `.claude/rules/delta-specs.md`, "One behavior per
      requirement": replace "the project does not re-check it" with the preview — the
      fix-artifacts stage and `/review-artifacts` run `scripts/check-merged-specs.sh`, which
      measures the merge with OpenSpec itself, MODIFIED blocks included. Verify by reading.

## 4. Sync and the gate (G1, M1, M3)

- [x] 4.1 `/opsx:sync split-long-spec-requirements`: the seven deltas into `openspec/specs/`
      (MODIFIED blocks replace their requirements whole, ADDED blocks appended under
      `## Requirements`); then `openspec validate --specs --strict --no-interactive` reports 0
      failed (52 items, M1) and `scripts/check-merged-specs.sh add-doctor-command` exits 0 (M2).
- [x] 4.2 `./gradlew checkSpecContract` green over the merged specs: every new scenario linked,
      no exemption line added (M3).
- [x] 4.3 Root `./gradlew check` once (`verification-scope.md`): no production class changed on
      this change, so the mutation gate mutates nothing; the specs of 2.3, 3.2 and the stand-in
      library run in it.

## Workflow follow-up

- Archive by hand on the task branch: `mkdir -p openspec/changes/archive/2026/10` and move the
  change to `openspec/changes/archive/2026/10/2026-10-27-split-long-spec-requirements`
  (`archive-path.md`); `openspec validate --specs --strict` still 0 failed; one human commit
  together with the `add-doctor-command` delta split already in the working tree (design D7).
- Reply in the tracker thread of #112 and move the task back to ready: both blockers are
  cleared on the branch; re-run archive.
