# Design: tighten-spec-contract

## Context

See proposal.md — Why. The facts the decisions rest on, all verified on 2026-10-09 against
OpenSpec 1.14.1 and a scratch project:

- OpenSpec measures a requirement as every non-blank, non-fenced line between
  `### Requirement:` and the first `####` heading; a physical line matching `**Key**:` is
  metadata and is left out whenever other text remains. A wrapped continuation of a metadata
  line is ordinary text. `openspec show --json` strips metadata lines from the requirement
  text; the plain `openspec show` prints the file. `MODIFIED` and `ADDED` blocks carrying
  `**Implements**:` and `**Notes**:` survive `openspec archive` verbatim.
- `openspec validate <change> --strict` checks the length of `ADDED` requirements only; the
  merged main spec is checked by `validate --specs --strict` at the archive stage.
- 508 requirements in 48 main specs, all traced by `<!-- implements … -->`; 266 exceed the
  limit without the comments; 138 of those carry `SHALL`/`MUST` past the 500th character,
  111 carry explanation only, 17 carry `never`/`only`/`at most` wording. 1 369 scenarios;
  four features in `TakeBareAutoSpec` already carry an informal `// Scenario:` comment; tests
  otherwise cite `FR` ids.
- 28 unarchived changes; 18 carry 62 `MODIFIED` requirement headings; 22 carry a long `ADDED`
  requirement. The OpenSpec `MODIFIED` merge is replace-only (`delta-specs.md`).
- The project's build-side gates are Gradle tasks in `build-logic` with a functional spec each
  (`TestTimeInjectionCheck`, `ParameterCountLimit`, `TestEnvironmentHygiene`); architecture
  specs in `:bootstrap` hold allowlists in code and assert them reached.

## Goals / Non-Goals

**Goals:** one owner for the traceability line; one owner for scenario identity between spec
and test; the migration auditable requirement by requirement through OpenSpec's own merge; the
checker green inside `check` from the moment the migration lands and never before.

**Non-Goals:** reproducing any OpenSpec rule (proposal NG2); any pin of the CLI (NG1); any edit
to archived deltas (NG3); a build gate on scenarios of unarchived deltas (NG6) — they are
findings, not gate subjects (D9).

## Decisions

**D1 — Traceability and notes are OpenSpec metadata lines.** The link is
`**Implements**: <ids> of <change>[; …]`, one line per requirement, after the text and before
the first scenario; explanation that belongs to the requirement and nowhere else is a
`**Notes**:` line, one physical line each, never `SHALL`/`MUST` (FR1, FR2). *Rationale:* a
metadata line is excluded from OpenSpec's measured text by the reader's own rule, survives the
merge in place, renders as text, and sits inside the requirement block so a `MODIFIED` of a
neighbour cannot delete it. *Alternative rejected:* a comment above the `### Requirement:`
heading — it lands in the previous requirement's last scenario, and a `MODIFIED` of that
neighbour erased it in the scratch test; a comment after the last scenario — survives today but
rides on scenarios accepting any text, and a scenario appended later buries it mid-block.

**D2 — Notes are the exception, and the gate says so.** A note may not carry a normative word;
a normative remainder of a shortened requirement is a scenario or an `ADDED` requirement (FR4).
*Rationale:* metadata is invisible to `openspec show --json`; text that binds behavior must stay
where every reader of the contract sees it, and a scenario is what gets a test. *Alternative
rejected:* notes as the general overflow — it clears the warning and hides the contract from
the tool, the "green build, wrong record" shape `implementation.md` exists to stop.

**D3 — The main specs change only through this change's deltas.** A generator writes, per
non-archived main spec, a delta with one `MODIFIED` block per requirement: heading verbatim,
comment turned into the metadata line, every other line verbatim; it proves the id set per
requirement equal before and after and refuses to overwrite a delta that differs from its own
last output (FR8, NFR-R2). The long requirements are then edited inside those deltas. *Rationale:*
the operator's decision — the OpenSpec merge is the audit trail, `/opsx:sync` in a scratch
worktree gives a strict-validated preview, and the change archives like any other. *Alternative
rejected:* editing `openspec/specs/` in place and archiving with `--skip-specs` — fewer files,
but the migration then has no delta to review and no merge to validate, and it sets a precedent
for bypassing the medium.

**D4 — Scenario identity is the title, spelled once in the spec and once in a test marker.**
`// Scenario: <spec-id> / <title>`, title verbatim, on the line above the feature (or inside it
when one feature covers several scenarios); the checker requires both directions (FR5).
*Rationale:* the title is the only identity a scenario has; a one-directional check lets a
rename leave a dangling marker that excuses nothing. The spec id prefix is needed because titles
repeat across capabilities (`Works`, `Empty queue`). *Alternative rejected:* naming the Spock
feature exactly as the scenario — Spock feature names are prose chosen for the test's reader,
and 1 369 renames would touch every spec file for no behavior; an annotation — Groovy specs
could carry one, but shell and functional-test fixtures could not.

**D5 — One checker class, three callers.** `SpecContractCheck` in `build-logic`, a cacheable
task whose inputs are `openspec/specs/**`, the unarchived deltas when `--changes` is set, and
the test trees; root `check` depends on it; `scripts/check-spec-contract.sh` is
`./gradlew -q checkSpecContract` with its arguments passed through; the fix-artifacts and
archive stages call the task in their verification (FR6). The exemption list is configured on the
task in the root build file, each entry a scenario and the mechanism that verifies it, asserted
reached. *Rationale:* the operator's decision — one logic, two launchers; the build file is where
a reviewer reads every other gate's exemptions. *Alternative rejected:* a standalone shell or
Python script the build execs — two runtimes to keep installed, and the project's checks are
Groovy with functional specs; a second copy in the stage YAML — the sync pair the rule forbids.

**D6 — The checker reads structure, not OpenSpec's rules.** It recognises headings, fenced
blocks, metadata lines and scenario headings — the shapes OpenSpec documents — and judges only
the project's rules on them (NFR-R3). It never counts characters, scenarios or `SHALL`s for
OpenSpec's sake. *Rationale:* proposal NG2 — a copy of an upstream rule drifts with the next
patch and nobody owns the pair. *Alternative rejected:* running `openspec validate` from the
task — a subprocess whose version the build does not control (NG1) inside a cacheable task.

**D7 — Order: checker first, red; wiring into `check` last.** The checker is built with its
functional spec before any spec is touched, so its first run over the real tree is the
inventory (FR9); it joins root `check` in the final group, after the deltas, the markers and the
queue are done. *Rationale:* a gate wired in while the tree is being migrated makes every
intermediate `check` red and hides the real failures. *Alternative rejected:* wiring it in from
the start with a growing allowlist — the allowlist would be the whole tree, and shrinking it is
the same work with a worse record.

**D8 — The queue is layered on this change, after this change.** Each of the 18 changes with a
`MODIFIED` requirement rewrites it over this change's delta text with the
`Layered on … as modified by tighten-spec-contract (sequenced before this change)` preamble;
each of the 22 changes with a long `ADDED` requirement shortens it by D2 (FR10). *Rationale:*
`delta-specs.md` already prescribes this for overlapping `MODIFIED`s, and this change overlaps
every requirement there is. *Alternative rejected:* leaving the queue to the fix-artifacts stage
— it would fix the `ADDED` length but not the layering, and the first archive after this one
would silently reinstate 62 long requirements.

**D9 — Findings are listed and sorted at decision points, never fixed in passing or dropped.**
One findings file under the change's working documents (`findings.md`, one row per finding:
id, capability, scenario or requirement, kind, evidence, proposed disposition) collects
everything the letter of the change does not cover (FR11): a defect a new test reveals, a
queued delta's scenario that describes behavior the code already has with no test, a scenario
with no verifier in any test tree, a normative remainder with no home. Two decision points
sort every open row — 5.2 after the scenario inventory, 6.8 after the queue rework — into
"this change" or "new change `<name>`"; the sorted rows are copied into `tasks.md` under a
"Decisions" heading, and the file must have no open row at archive. What goes to a new change
is carried meanwhile without hiding the gap: a scenario by a `pending-test: <change>` exemption
entry, a revealed defect by its test kept in place under Spock's `@PendingFeature` with the
finding id in its reason (the marker stays, the gate stays green, the red is recorded, not
deleted). What stays is done here by the ordinary rules — a production fix by TDD with the
scoped PIT run of `verification-scope.md`. *Rationale:* tests cite `FR` ids today, so neither
the missing count nor the defects are knowable before the inventory; `process-invariants.md`
bounds a change at four weeks; a finding fixed on the way is an edit nobody reviews, and one
dropped is the silent gap this change exists to close. *Alternative rejected:* a flat non-goal
("no production code, no queue scenarios") — it decides before the evidence exists and loses
the list; writing tests and fixes until time runs out — unbounded, with no record of what is
left.

**Sync surfaces.** `scripts/check-spec-contract.sh` and the two stage YAML verification commands
are launchers only (one line each invoking the task); no rule lives in them. The four informal
`// Scenario:` comments in `TakeBareAutoSpec` are converted to the marker form in task 5.3, so
no second spelling of the link remains. The three documents that describe the format
(`traceability.md`, `delta-specs.md`, `rules.specs` in `openspec/config.yaml`) are prose, each
with one example, and the checker is what keeps them honest. No code of this change implements
a rule that exists elsewhere, and no declared pair of `manual-sync-pairs.md` is touched.

**Single-owner mechanisms.**

| Owner                           | Value (type)                                             | Consumers                                                                                                                                                                                                  | Old way removed                                                                                                                                                                                                                             | Enforced by                                                                                                                  |
|---------------------------------|----------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------|
| The `**Implements**:` line (D1) | the requirement's traceability ids, as one metadata line | every requirement under `openspec/specs/`; every requirement in `openspec/changes/<unarchived>/specs/`; `traceability.md`, `delta-specs.md`, `rules.specs` (the three places that tell an author the form) | `<!-- implements … -->` in Requirement/Scenario specs, deleted outside the archive by the generated deltas (D3) and the queue rework (D8); the YAML-like `# implements` form is not replaced — it lives in blocks OpenSpec does not measure | `SpecContractCheck`: a comment-form hit in the scanned trees is a failure; a requirement with zero or two lines is a failure |
| The scenario title (D4)         | the identity of a scenario, as `<spec-id> / <title>`     | the spec's `#### Scenario:` heading; the `// Scenario:` marker in the test that exercises it; the exemption list entry for a scenario verified elsewhere                                                   | the informal `// Scenario:` comments in `TakeBareAutoSpec` (converted); the implicit "the FR id covers it" link (kept for FR traceability, no longer the only link)                                                                         | `SpecContractCheck` in both directions, exemption list asserted reached                                                      |
| `SpecContractCheck` (D5)        | the verdict on the project's spec-contract rules         | root `check`; `scripts/check-spec-contract.sh`; the fix-artifacts and archive stage verification commands                                                                                                  | none existed; the launchers are forbidden to hold a rule by the functional spec, which runs the script and the stage command against the same fixture tree and expects the same findings                                                    | `SpecContractCheckFunctionalSpec`                                                                                            |

## Risks / Trade-offs

- [OpenSpec changes how it reads metadata lines, and `**Notes**:` counts again] → the archive
  stage goes red on the first task after the upgrade, exactly as on 2026-10-08, and the fix is
  a change against the new reader; the project chose this over a pin (NG1). The checker's
  functional spec pins the shapes this design relies on, so the difference is named, not guessed.
- [A note is a long physical line; its diff is one replaced line] → notes are the exception
  (D2); a note longer than a sentence or two is a sign the text wanted a scenario.
- [The generated deltas copy the whole corpus into the change] → it is the medium the operator
  chose (D3); the generator's id-set proof and `/opsx:sync` in a scratch worktree make the copy
  reviewable by `diff`, not by reading.
- [Missing tests or revealed defects outnumber what one change can carry] → D9: the decision
  points at 5.2 and 6.8; the `pending-test` exemption and the `@PendingFeature` test carry what
  moves out, so the record is complete and the gate is honest.
- [A finding gets fixed on the way because it looked small] → every production edit cites a
  finding id sorted into this change; `/audit-implementation` reads the Decisions heading
  against the diff.
- [A scenario's "test" is a functional spec, an architecture spec or a shell fixture] → all
  live under a scanned tree (`src/test`, `build-logic/src/functionalTest`); the marker is a
  comment and works in every language there.
- [The queue rework touches 28 changes other work may be editing] → sequenced first, and each
  change's rework is one task validated by `openspec validate <change> --strict`.

## Migration Plan

1. Checker and functional spec (group 1), unwired. 2. Rules and guides (group 2). 3. Generated
deltas, id set proven (group 3). 4. Editorial pass per capability (group 4). 5. Inventory,
decision point, markers, tests (group 5). 6. Queue rework (group 6). 7. Scratch-worktree sync,
strict validation, wiring into `check`, full `check` once (group 7). Rollback before archive is
deleting the change; after archive, the comment form is gone and a rollback is a new change.
