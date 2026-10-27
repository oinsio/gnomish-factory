# Proposal: split-long-spec-requirements

## Why

OpenSpec's strict validation reports a requirement whose measured text exceeds 500
characters, and the archive stage of the factory pipeline requires `openspec validate --specs
--strict` to pass over every main spec. On `main` today ten requirements in seven main specs
measure 513 to 758 characters, so no change can be archived by the pipeline: the first task to
reach the archive stage, `add-doctor-command` (issue #112, 2026-10-27), escalated on exactly
this, with seven failing specs it was forbidden to touch.

The backlog has one cause with no gate: `openspec validate <change> --strict` measures only the
`ADDED` blocks of a delta and leaves `MODIFIED` blocks alone (upstream's choice, so that a
MODIFIED block can carry the whole existing requirement), so a sentence added to an existing
requirement is measured for the first time after the merge, at the archive stage, by a round
that is forbidden to edit the delta. The project's own checker does not measure length either,
by decision (NG2 of `tighten-spec-contract`: no copy of an upstream rule in the project's
checker).

This change clears the backlog and closes the gap where it opens: before the artifacts are
frozen, with OpenSpec's own validator run over a preview of the merge.

## What Changes

- **MODIFIED**: the ten requirements over the limit are brought under it. Each keeps its
  heading and every scenario; the text that leaves it goes to an `ADDED` requirement of the
  same capability (a second behavior that was sharing the block) or, for a parenthetical list
  of cases the scenarios already enumerate, to a `**Notes**:` line. No promise is dropped or
  narrowed.
- **ADDED**: five requirements that carry the extracted behaviors, each with scenarios of its
  own, linked to the tests that already exercise them.
- **ADDED**: a preview check of the merged main specs, `scripts/check-merged-specs.sh
  <change>`: it archives the change in a scratch copy of `openspec/` with the OpenSpec CLI
  and runs `openspec validate --specs --strict` there, so a MODIFIED block grown past the
  limit, or a delta that no longer applies, fails while the delta can still be edited. The
  fix-artifacts stage runs it as a verification check; `/review-artifacts` runs it as a
  freshness check.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `build-system/quality-gates`: "Scenario-to-test link" (633) — the exemption-list obligation
  becomes the ADDED requirement "Scenario exemption list"; a new requirement "Merged main specs
  are previewed before the archive" owns the preview check.
- `cli/manual-run`: "Exit codes by outcome family" (560) — the list of code-8 preconditions
  moves to the note that already explains code 8.
- `lifecycle/task-branch-contract`: "Total branch-shape classification" (513) — the
  classifier's inputs move to a note.
- `observability/serve-observability`: "taskOutcome lines for terminal results carrying a
  final state" (523) — the `stage` null case moves to a note; the crash sentence is tightened.
- `sandbox/execution-environment`: "Runtime outage is an infrastructure failure" (515) and
  "The box's git meets the floor before the seed clone" (538) — one explanatory clause each
  moves to a note.
- `serve/factory-serve`: "Remote outage gate holds the feed off the tracker" (758) — the
  opening signals become the ADDED requirement "A condition opens on the refusal that met it",
  the probing and the working slots become the ADDED requirement "An open condition is probed
  by its own check"; "Gate transitions are the outage's log and snapshot signal" (575) — the
  snapshot section becomes the ADDED requirement "Open conditions are a snapshot section".
- `tracker/tracker-take`: "Exit codes by take result" (559) — the list of code-8 preconditions
  moves to the note that already explains code 8; "A Completed-without-cleanup tip is
  finished, never re-executed" (541) — the delivered-state read becomes the ADDED requirement
  "Delivered state is read from the Completed commit".

## Goals

- **G1**: `openspec validate --specs --strict` passes over every main spec, so the archive
  stage can run for any change.
- **G2**: a requirement that outgrows the limit is reported to the round that can still edit
  the delta, not to the archive round.
- **G3**: the split loses nothing: every heading, every scenario title and every obligation
  of the ten requirements survives, so no test marker and no design reference moves.

## Non-Goals

- **NG1**: no length rule in `SpecContractCheck`, and no other copy of an upstream OpenSpec
  rule in the project's checker — NG2 of `tighten-spec-contract` stands; the preview runs
  OpenSpec itself.
- **NG2**: no pin of the OpenSpec CLI version and no OpenSpec in CI — NG1 of
  `tighten-spec-contract` stands; the preview uses whatever `PATH` offers, as the archive stage
  does. Pickup condition: the change that first puts the OpenSpec CLI on the CI runner wires
  the preview there too.
- **NG3**: no rewording of any scenario and no renaming of any requirement heading (NG4 of
  `tighten-spec-contract`); no scenario moves between requirements — OpenSpec refuses a
  MODIFIED block that drops one, and a title is an identity the test markers reference.
- **NG4**: no edit under `openspec/changes/archive/`; the misplaced amendment paragraph that
  closes the "Scenario-to-test link" block (about the Docker client variables) is kept
  verbatim in its MODIFIED block, not relocated. Pickup condition: the next change that
  modifies "Forked test JVMs carry no operator-process variables" moves it there.
- **NG5**: no preview of a change whose deltas another active change's deltas layer on
  (`delta-specs.md`, "Overlapping MODIFIED requirements"): the preview archives one change
  over the current main specs, exactly as the archive stage will.

## Users & Scenarios

- **U1**: the archive round of a task reaches `openspec validate --specs --strict` and it
  passes, because no main spec carries an overlong requirement.
- **U2**: a gnome in the fix-artifacts round adds a sentence to a MODIFIED requirement that
  pushes it past the limit; the round's own verification names the spec and the requirement,
  and the gnome splits it in the delta it is editing.
- **U3**: a human running `/review-artifacts` on a change sees, among the freshness findings,
  that the merge would leave a main spec invalid, with OpenSpec's own words.
- **U4**: a developer runs `scripts/check-merged-specs.sh <change>` by hand before
  `/opsx:archive` and gets the archive stage's verdict in seconds, with the repository
  untouched.

## Requirements

### Functional

- **FR1**: every requirement of every main spec SHALL measure at most 500 characters by
  OpenSpec's measure (the trimmed non-blank lines between the heading and the first `####`
  heading, fenced blocks and metadata lines excluded, joined by line breaks), with a margin: no
  requirement this change writes measures above 490.
- **FR2**: every heading, scenario title and obligation of the ten requirements SHALL survive
  the split: an obligation leaves a block only into an `ADDED` requirement of the same
  capability, and only a parenthetical enumeration of cases the block's scenarios already name
  may become a `**Notes**:` line.
- **FR3**: every scenario this change adds SHALL carry a `// Scenario:` marker in a test that
  already exercises the behavior, named per scenario in the tasks; no new exemption entry.
- **FR4**: `scripts/check-merged-specs.sh <change>` SHALL copy `openspec/` to a scratch
  directory, archive the named change there with `openspec archive <change> --yes`, run
  `openspec validate --specs --strict --no-interactive` there, print the failing specs with
  OpenSpec's own lines, exit 0 when the validation passes and 1 otherwise, and remove the
  scratch directory on every exit.
- **FR5**: the fix-artifacts stage SHALL run the preview check as a verification command after
  `openspec validate <change> --strict`, failing the round with a message that names the
  script, the spec and the rule file to read (`.claude/rules/delta-specs.md`).
- **FR6**: `/review-artifacts` SHALL run the preview check beside `openspec validate <name>
  --strict` and report a failure as a freshness finding; `docs/guides/developer-guide.md` and
  `.claude/rules/delta-specs.md` SHALL name the script as the way to see the archive stage's
  verdict before it.

### Non-Functional Reliability

- **NFR-R1**: the preview SHALL leave the repository untouched: nothing under `openspec/` is
  written, no change is moved, whatever the outcome.
- **NFR-R2**: the preview SHALL fail closed: a missing `openspec` on the `PATH`, a change
  name that is not an active change, or an archive that refuses (a delta no longer applying)
  each exit 1 with the cause, never 0.

### Non-Functional Security

- **NFR-S1**: the preview SHALL make no network call and run no tool but `openspec` and POSIX
  utilities; the change name is validated as `[a-z0-9][a-z0-9-]*` before it reaches a command
  line, as `SpecContractCheck.requireChange` validates it.

### Non-Functional Cost

- **NFR-C1**: the preview SHALL finish within seconds on the current tree (one copy of
  `openspec/`, one archive, one validation).

### Operator Experience Criteria

- **UX1**: a failing preview prints the failing spec ids and OpenSpec's own warning lines, then
  one line saying what to do: split the requirement in the delta (one behavior per
  requirement), or, for a pre-existing failure the change does not touch, escalate.

## Success Metrics

- **M1**: `openspec validate --specs --strict --no-interactive` on the branch after this
  change reports 0 failed items (52 before `add-doctor-command` is archived, 53 after).
- **M2**: `scripts/check-merged-specs.sh add-doctor-command` on the branch exits 0; the same
  script over the delta as it stood on 2026-10-27 before the split (the 593-character MODIFIED
  block of `github-tracker`) exits 1 naming `tracker/github-tracker`.
- **M3**: `./gradlew checkSpecContract` is green with every new scenario linked and no new
  exemption line.

## Open Questions

None.

## Impact

- `openspec/specs/`: seven main specs edited through the sync of this change's deltas.
- Tests: one `// Scenario:` marker line added in each of the named specs (`build-logic`
  functional specs, `application`, `adapters/git`); no production code.
- `scripts/check-merged-specs.sh` (new), `.gnomish/stages/fix-artifacts/stage.yaml` (one
  verification check), `.claude/commands/review-artifacts.md`,
  `docs/guides/developer-guide.md`, `.claude/rules/delta-specs.md`.
- Test fixtures: a stand-in table `openspec.params` with its data file, for the script's spec
  in `:bootstrap`.
- Sequencing on the task branch of #112: this change is synced and archived by hand before
  the gnome's archive round of `add-doctor-command`, which then passes the strict validation.
