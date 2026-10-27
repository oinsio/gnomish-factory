# Design: split-long-spec-requirements

## Context

See `proposal.md` — Why. The constraints the decisions below rest on, each verified on the
branch of #112 on 2026-10-27:

- OpenSpec 1.14.1 measures a requirement as the trimmed non-blank lines between the heading
  and the first `####` heading, fenced blocks and `**Key**:` lines excluded, joined by line
  breaks (`dist/core/parsers/requirement-text.js`, `extractRequirementBody`), and warns above
  500 (`MAX_REQUIREMENT_TEXT_LENGTH`); `--strict` turns the warning into a failure.
- `openspec validate <change> --strict` measures `ADDED` blocks only; `MODIFIED` is left alone
  by upstream's own comment ("its text is the existing requirement, which the specs instruction
  says to keep whole", `validator.js:257`). So the fix-artifacts stage's existing check cannot
  see a MODIFIED block that grew.
- `openspec archive` refuses a MODIFIED block that drops any scenario the main spec holds
  (seen on this branch while splitting `github-tracker`), and rejects REMOVED plus ADDED of one
  header in one change (`delta-specs.md`). A scenario therefore cannot move between
  requirements.
- `openspec archive <change> --yes` proceeds with unticked tasks (probed in a scratch copy on
  `add-claim-return`, 22 unticked) and needs no git: the archive is a file move plus a merge.
- NG1 and NG2 of `tighten-spec-contract` (archived) stand: no CLI pin, no copy of an upstream
  rule in `SpecContractCheck`; its D6 rejected a subprocess inside the cacheable Gradle task.
- No active change under `openspec/changes/` modifies any of the ten requirements (grep of
  their headings over the active deltas, 2026-10-27), so no MODIFIED overlap needs ordering.
- The archive stage of `.gnomish/stages/archive/stage.yaml` locates the archived change by the
  pattern `…-<change>/proposal.md` for the task's own change, so a second change archived by
  hand on the same branch is invisible to its first check.

## Decisions

**D1 — Scenarios stay where they are; an obligation that leaves a block goes to an ADDED
requirement with scenarios of its own.** Five requirements are extracted this way ("Scenario
exemption list", "A condition opens on the refusal that met it", "An open condition is probed
by its own check", "Open conditions are a snapshot section", "Delivered state is read from the
Completed commit"). Each existing scenario keeps its requirement, even where it describes the
extracted behavior (the exempt-scenario and stale-exemption scenarios stay under
"Scenario-to-test link"); the ADDED requirement states the obligation and gets one or two new
scenarios, each with a marker in the spec that already drives that behavior (named per scenario
in `tasks.md`). The MODIFIED block gains a `**Notes**:` line naming the requirement the
obligation moved to, so a reader of the old block finds the new one. *Rationale:* a scenario
title is an identity the test markers reference, and OpenSpec refuses to move one through
MODIFIED; the only transformation that keeps every title, every marker and every obligation is
extraction with new scenarios. *Alternative rejected:* REMOVED plus ADDED of the same heading
across two changes archived back to back — two changes for a text edit, and the heading would be
gone from the main spec between them.

**D2 — Only an enumeration the scenarios already name may become a note.** Five requirements
are shortened without extraction: the code-8 precondition list of `manual-run` and
`tracker-take` (each case is named in a scenario of the block), the classifier's inputs of
`task-branch-contract`, the `stage` null case of `serve-observability`, the "consistent with
the existing failure classes" clause and the "fresh materialize or a reattach" enumeration of
`execution-environment`. The `SHALL` sentence each belongs to stays whole; the note restates
the enumeration without a normative word, as `delta-specs.md` requires of a note. *Rationale:*
the enumeration is explanation of an obligation the scenarios pin; moving it changes no promise.
*Alternative rejected:* extracting these too — five more requirements that would each restate
one sentence, and a note is what `delta-specs.md` names for examples.

**D3 — The guard is OpenSpec's own validator over a preview of the merge, run where the delta
can still be edited.** `scripts/check-merged-specs.sh <change>` copies `openspec/` to a scratch
directory, archives the change there with the CLI on `PATH`, validates every main spec strictly
there, and reports. The fix-artifacts stage runs it after `openspec validate <change> --strict`;
`/review-artifacts` runs it as a freshness check. *Alternative zero — no guard:* the archive
stage's own strict validation keeps catching an overlong MODIFIED block, at the cost of one
escalation per occurrence by a round that is forbidden to edit the delta, and a human
`/opsx:update` — the third escalation of #112, every time. *Rejected:* a length rule in
`SpecContractCheck` — NG2 of `tighten-spec-contract`, a sync pair with upstream's measure;
`openspec validate --specs --strict` in CI — the runner has no OpenSpec, NG1 forbids pinning
one, and it would still fire after the merge; relying on `openspec validate <change>` — it leaves
MODIFIED alone by upstream's design.

**D4 — The script's contract.** The CLI is `${OPENSPEC:-openspec}`, the one seam a spec uses to
hand it a stand-in; the change name is validated as `[a-z0-9][a-z0-9-]*` and must be a directory
under `openspec/changes/`, as `SpecContractCheck.requireChange` validates it; the copy is
`cp -R openspec` into `mktemp -d`, removed by a `trap` on every exit; the archive runs with
`--yes` (unticked tasks are the fix-artifacts norm); the validation runs with `--specs --strict
--no-interactive`; on failure the script prints the lines of the failing specs and one line
saying what to do (UX1); exit 0 only when both commands succeeded. *Rationale:* `--yes` is the
one flag that makes the archive non-interactive without `--no-validate`, which asks for
confirmation and would hide a delta that no longer applies. *Alternative rejected:* a `git
worktree` as the copy — needs git and a clean index for a check that reads files.

**D5 — The script is specified against a stand-in CLI, not the real one.** A bootstrap spec,
`MergedSpecsPreviewScriptSpec`, runs the script with `OPENSPEC` pointing at a preset of a new
stand-in table `tables/openspec.params` (ADR 0015): `openspec-preview-pass` (archive exits 0,
validation answers the all-passed totals), `openspec-preview-long` (validation answers a
`github-tracker` failure with the >500 warning, exit 1) and `openspec-preview-archive-refused`
(the archive refuses with the "MODIFIED … not found" words). Each preset records `PWD`, so the
spec asserts the archive ran inside a copy outside the repository, and asserts afterwards that
`openspec/` is byte-identical and the copy is gone. The fail-closed cases need no preset:
`OPENSPEC` naming a path that does not exist, and a change name that is not a directory. The
rows are reached by `StandInLibrarySpec`. *Rationale:* CI has no OpenSpec and NG1 forbids
pinning one, so a spec on the real CLI would be green only on a workstation; the script's own
logic — the copy, the arguments, the exit codes, the cleanup — is what the spec pins, and the
real CLI's behavior is what the fix-artifacts round meets. *Alternative rejected:* `@Requires`
on the CLI's presence — a scenario unverified exactly where it must hold.

**D6 — The stage check has one shape, pinned like the spec-contract launcher.** The new
verification command in `.gnomish/stages/fix-artifacts/stage.yaml` repeats the stage's preamble
that names the task's change, runs `scripts/check-merged-specs.sh "$change"`, and on failure
prints the tail of its output and one literal `echo` naming the script and
`.claude/rules/delta-specs.md`. A build-logic functional spec, `StageMergedSpecsPreviewCommandSpec`,
reads the manifest with the YAML parser and asserts that exactly one check names the script and
that its text is the pinned shape, as `StageSpecContractCommand` pins the launcher. The check
never spells `checkSpecContract`, so `StageSpecContractCommand.gradleArguments` still finds one
launcher. *Rationale:* a stage check that drifts into holding a rule of its own is the shape
`tighten-spec-contract` D5 removed. *Alternative rejected:* folding the preview into the
existing `openspec validate "$change" --strict` check — two verdicts in one message.

**D7 — Sequencing on the branch of #112.** This change is applied, synced and archived by hand
on the task branch before the gnome's archive round of `add-doctor-command`: that round's
strict validation then meets 52 valid specs, its first check sees only its own archive directory,
and its last check ignores committed history. *Rationale:* the gnome cannot edit the seven
specs (its stage forbids it) and `main` cannot receive this change faster than the branch.
*Alternative rejected:* a separate branch merged to `main` first — the task branch would then
need a merge of `main` before its archive round, one more human step for the same result.

**Sync surfaces: none** — this change adds no parallel implementation and touches no declared
pair. The preview has one implementation, the script; the stage check and the command are
callers.

**Single-owner mechanisms:**

| Owner | Value (type) | Requires | Consumers | Old way removed | Enforced by |
|-------|--------------|----------|-----------|-----------------|-------------|
| `scripts/check-merged-specs.sh`: the archive-stage verdict over a change's merge, before the archive | the script's exit status with OpenSpec's own lines on stdout (a shell boundary: every consumer is a shell check or a markdown instruction, so no typed value crosses it) | the OpenSpec CLI runnable as `${OPENSPEC:-openspec}`, a readable `openspec/`, a writable temporary directory; no git, no network | `.gnomish/stages/fix-artifacts/stage.yaml` (one verification command), `.claude/commands/review-artifacts.md` (the freshness step), `docs/guides/developer-guide.md` (the manual run) | none existed: the archive stage's own `openspec validate --specs --strict` stays as the final gate and is not a way of previewing | `StageMergedSpecsPreviewCommandSpec` (the manifest runs the script and nothing else); no other file under `.gnomish/` or `.claude/commands/` spells `openspec archive` (asserted by the same spec) |

## Risks / Trade-offs

- [Upstream changes what `openspec archive --yes` does with unticked tasks, or its output] →
  the stand-in spec stays green while the real check misbehaves; the fix-artifacts round sees
  it first, with the CLI's own words, and NG1 accepts that an upstream change is its own change.
- [A change whose delta layers on another active change's MODIFIED block previews over the
  current main spec, not over the earlier delta] → NG5: the preview reports what the archive
  stage would, which is the right verdict only in sync order; the preamble `delta-specs.md`
  requires for layered deltas tells the reviewer.
- [The copy of `openspec/` includes `changes/archive/`, some hundreds of files] → seconds
  (NFR-C1); the script copies the tree whole rather than selecting, so an archive that reads a
  sibling change behaves as it will in the repository.
- [The misplaced amendment paragraph at the end of "Scenario-to-test link" is carried over
  verbatim] → NG4 names the change that moves it.
- [Seven markers are added to tests this change does not otherwise touch] → each is one
  comment line above a feature the task names; `checkSpecContract` fails on a title typo.

## Migration Plan

Apply on the task branch of #112 (D7): tasks in order, then `/opsx:sync`, the gate run, the
hand archive under `openspec/changes/archive/2026/10/`, one commit by the human, then the
tracker reply that returns the task to ready. No rollback is needed: every edit is a text edit
of specs, tests' comment lines, a script and a manifest; reverting the commit restores the
previous state.
