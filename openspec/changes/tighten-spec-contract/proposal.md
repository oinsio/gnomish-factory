# Proposal: tighten the spec contract — short requirements, traced scenarios

## Why

OpenSpec 1.14.1 (installed by Homebrew on 2026-10-08) raised its "requirement text is longer
than 500 characters" finding from `INFO` to `WARNING`, which `--strict` treats as an error. The
factory's archive stage runs `openspec validate --specs --strict` and its fix-artifacts stage runs
`openspec validate <change> --strict`, so every task now stops at one of them: 43 of the 48 main
specs fail (305 findings over 508 requirements), and 22 of the 28 queued changes carry a long
`ADDED` requirement of their own. Task #92 (`kill-expensive-mutants`) was the first to escalate
and was finished by hand.

The limit is not configurable and is the upstream's deliberate direction, and it agrees with
this project's own rule that one unit states one thing. The length is a symptom: a requirement
that runs to 1 000 or 2 000 characters carries behaviors its scenarios already state, or
behaviors no scenario states at all — and a scenario no test exercises is a contract nobody
checks. So this change does not pin the CLI and does not trim text to fit. It moves the
traceability comments out of the measured text, shortens every requirement to the one behavior
it names, turns the normative remainder into scenarios or requirements of their own, and makes
"every scenario has a test" a gate rather than an intention — for all 1 369 scenarios the
specs hold today.

## What Changes

- **MODIFIED** — the traceability link of a Requirement/Scenario spec is a metadata line,
  `**Implements**: FR1, FR4 of <change>; FR2 of <other-change>`, written after the requirement
  text and before the first scenario. The HTML-comment form is retired outside
  `openspec/changes/archive/`, which stays immutable.
- **ADDED** — `**Notes**:` as the one place for explanatory text that belongs to a requirement
  and nowhere else: one physical line per note, no `SHALL`/`MUST`, used as an exception.
- **MODIFIED** — every requirement of every main spec is rewritten through a `MODIFIED` delta
  of this change: traceability moved, text within OpenSpec's limit, heading unchanged, existing
  scenarios kept verbatim. Normative text that no scenario states becomes a scenario; a
  behavior that is not the requirement's own becomes an `ADDED` requirement.
- **ADDED** — a scenario ↔ test link: every `#### Scenario:` of a main spec is named by a
  `// Scenario: <spec-id> / <title>` marker in one test source, and every marker names a
  scenario that exists. Scenarios verified by something other than a test (a CI workflow, a
  release script) are listed as exemptions with a reason.
- **ADDED** — one checker for the project's own spec-contract rules (traceability line,
  notes, retired comment form, scenario ↔ test link), implemented once in `build-logic`,
  run as a Gradle task inside root `check`, by a `scripts/` wrapper by hand, and by the
  fix-artifacts and archive stages. The 500-character limit itself stays OpenSpec's.
- **MODIFIED** — the rules that tell authors and gnomes how to write specs
  (`traceability.md`, `delta-specs.md`, `testing.md`, `rules.specs` in the OpenSpec
  configuration, `fix-uncommitted.md`) and the developer guide.
- **MODIFIED** — the 28 queued changes under `openspec/changes/`: their deltas are rewritten to
  the new format, their `MODIFIED` requirements layered on this change's text
  (`delta-specs.md`, "Overlapping MODIFIED requirements"), and their long `ADDED` requirements
  shortened by the same rules.

## Goals

- **G1** — `openspec validate --specs --strict` passes for every main spec after this change
  is archived, on the OpenSpec version installed at that time, with no pin.
- **G2** — Every scenario of every main spec is linked to the test that exercises it, or
  listed as an exemption with the mechanism that verifies it instead.
- **G3** — Every rule this project adds on top of OpenSpec's is checked by the build, by the same
  code a human runs by hand.
- **G4** — Every queued change passes `openspec validate <change> --strict` and the project's
  checker before it is picked up by the factory.

## Non-Goals

- **NG1** — No pinning of the OpenSpec CLI version anywhere (the operator's decision of
  2026-10-09): the factory runs whatever `PATH` offers; a later upstream change that breaks
  validation is its own change.
- **NG2** — No re-implementation of OpenSpec's length rule, scenario counting or any other
  upstream check in the project's checker: a copy would be a sync pair with code this project
  does not own.
- **NG3** — No edit under `openspec/changes/archive/`; archived deltas keep the comment form.
- **NG4** — No rewording of existing scenarios and no renaming of existing requirement
  headings: both are the identities other artifacts and the queued deltas reference.
- **NG5** — No undecided production code change. A production edit happens in this change only
  for a finding the operator sorted into it at a decision point (FR11); nothing is fixed on
  the way, and nothing found is dropped.
- **NG6** — No build gate on the scenarios inside delta specs of unarchived changes: a scenario
  of an unimplemented change has no test yet by definition, so the checker links them only once
  the delta is merged. They are still inventoried as findings (FR11) and sorted, not ignored.

## Users & Scenarios

- **U1 — the gnome at the fix-artifacts or archive stage.** Its strict validation goes green
  on the merged specs; a long `ADDED` requirement it writes is reported by OpenSpec, a missing
  `**Implements**:` line or a note with `SHALL` by the project's checker, with the file, the
  line and the fix.
- **U2 — the author of a new scenario.** Writes the scenario, writes the Spock feature, puts
  the marker above it; the build tells them if they forgot either side.
- **U3 — the reviewer of a test rename or deletion.** A deleted feature whose marker was the
  only link to a scenario turns the build red, naming the scenario that lost its test.
- **U4 — the reader of a spec.** Sees one behavior per requirement, the scenarios that pin it,
  and in `**Notes**:` the few things that explain it; the diagram and the example stay where
  they were.

## Requirements

### Functional

- **FR1 — Traceability line.** In Requirement/Scenario specs under `openspec/specs/` and in the
  deltas of unarchived changes, every requirement carries exactly one line of the form
  `**Implements**: <ids> of <change-name>[; <ids> of <change-name>…]`, placed after the
  requirement text and before its first scenario. The `<!-- implements … -->` form is absent
  from those trees. The YAML-like Entity and Use-case formats keep their `# implements` form.
- **FR2 — Notes.** A requirement may carry `**Notes**:` lines, each one physical line, each
  free of the words `SHALL` and `MUST`. A line of ordinary text directly after a metadata line is
  reported as a wrapped note. Notes hold what explains a requirement and belongs nowhere else;
  normative text is a requirement or a scenario.
- **FR3 — Requirement within the limit.** After this change's deltas merge, every requirement of
  every main spec passes `openspec validate --specs --strict` on the installed OpenSpec, with its
  heading unchanged, its scenarios kept verbatim, and the capability's scenario count not lower
  than before. Fenced blocks (diagrams, examples) are not counted by OpenSpec and stay in place.
- **FR4 — Normative remainder.** Each sentence of a shortened requirement that carried `SHALL`,
  `MUST` or an equivalent (`never`, `only`, `at most`, `at least`, `exactly`) is either stated by
  an existing scenario, turned into a new scenario of the same requirement, or turned into an
  `ADDED` requirement with its own scenario. The decision for every such sentence is recorded in
  the change's inventory table before the edit is made.
- **FR5 — Scenario ↔ test link.** For every `#### Scenario: <title>` under a requirement of a
  main spec `<spec-id>`, at least one file under a module's `src/test` (or `build-logic`'s
  functional-test tree) contains the marker `// Scenario: <spec-id> / <title>` with the title
  verbatim; and every marker in those trees names a scenario that exists. A scenario verified
  by something that is not a test is listed in the checker's exemption list with the mechanism
  that verifies it; the list is asserted reached, so a stale entry fails.
- **FR6 — One checker.** The rules of FR1, FR2 and FR5 are implemented once, in `build-logic`, as
  a Gradle task `checkSpecContract` that root `check` depends on. `scripts/check-spec-contract.sh`
  runs that task and nothing else; a `--changes` flag includes the unarchived deltas for FR1 and
  FR2. The fix-artifacts and archive stages run the task in their verification.
- **FR7 — Rules and guides.** `traceability.md` (table rows for the traceability line and the
  scenario marker), `delta-specs.md` (template, notes, one behavior per requirement, the
  OpenSpec limit as a fact to design for), `testing.md` (the marker beside the feature),
  `rules.specs` in `openspec/config.yaml`, `fix-uncommitted.md` (its exception for repeated
  traceability lines), the developer guide and the glossary describe the new contract.
- **FR8 — Migration deltas.** This change carries, for every non-archived main spec, a delta
  with a `MODIFIED` block per requirement in the new format. The deltas are generated by a
  script from the main specs, so the traceability move is mechanical and the ID set per
  requirement is proven equal before and after; the long requirements are then edited inside
  the generated deltas by hand (FR3, FR4).
- **FR9 — Scenario inventory.** Before any marker is placed, an automated pass matches every
  scenario title against the feature names and comments of the test trees and writes a table
  per capability: linked, probable, missing. The missing ones are what the test-writing tasks
  take; the probable ones are confirmed or rejected by reading the test.
- **FR10 — Queue rework.** Every unarchived change under `openspec/changes/` is brought to the
  new format: traceability lines, `MODIFIED` blocks layered on this change's text with the
  preamble `delta-specs.md` prescribes, long `ADDED` requirements shortened by FR4. Each change
  then passes `openspec validate <change> --strict` and `checkSpecContract --changes`.
- **FR11 — Findings list and decision points.** Everything the work uncovers that the change's
  letter does not cover is recorded as a finding, never fixed in passing and never dropped: a
  defect a new test reveals (the test stays, marked pending with the finding's id); a scenario
  of an unarchived change's delta that describes behavior the code already has but no test
  exercises; a scenario whose only verifier is outside any test tree; a requirement whose
  normative remainder has no home in its capability. Each finding names the capability, the
  scenario or requirement, the evidence and a proposed disposition. At each decision point the
  operator sorts every open finding into "this change" or "new change `<name>`"; the sorted
  list, with its reasons, is the record, and nothing is left unsorted when the change is
  archived.

### Non-Functional — Reliability

- **NFR-R1** — The checker reads files and nothing else: no `git`, no network, no OpenSpec
  subprocess; it is a cacheable task whose inputs are the spec and test trees.
- **NFR-R2** — The delta generator is idempotent: running it twice over the same main specs
  produces byte-identical deltas, and it refuses to overwrite a delta that was edited by hand.
- **NFR-R3** — The checker's structural reading (requirement heading, metadata line, scenario
  heading, fenced block) follows what OpenSpec documents, and a functional spec pins each
  element with a fixture, so a difference from the installed CLI is found by that spec, not by
  a red archive stage.

### Non-Functional — Observability

- **NFR-O1** — Every finding names the file, the line, the rule and the fix, in the wording the
  parameter-count and time-injection gates use; the scenario link names the spec id and the
  title that is missing or dangling. The task ends with one summary line: scenarios checked,
  linked, exempt.

### Non-Functional — Security

- **NFR-S1** — The checker treats spec and test text as data: nothing from a file is executed,
  evaluated or interpolated into a command.

### Non-Functional — Cost

- **NFR-C1** — `checkSpecContract` finishes in under five seconds on the reference machine over
  the whole tree, and its inclusion in `check` is invisible against the test and mutation phases.
- **NFR-C2** — The editorial tasks are cut per capability so each stays inside one gnome round;
  the inventory of FR9 is produced before the test-writing tasks are sized, and the proposal is
  revised if the missing count exceeds what one change can carry (Q2).

## Operator Experience Criteria

- **UX1** — A red `checkSpecContract` reads like the other gates: what was found, where, what to
  write instead; never a stack trace.
- **UX2** — A spec reads better after the change than before: the requirement states the
  behavior, the scenarios list the cases, the diagram is still there, and nothing a reader
  relied on has disappeared.
- **UX3** — The rules an author reads (`delta-specs.md`) show the finished shape in one
  example, so a new requirement is written right the first time.

## Success Metrics

- **M1** — `openspec validate --specs --strict`: 48 of 48 main specs pass (baseline 5 of 48,
  2026-10-09, OpenSpec 1.14.1).
- **M2** — `grep -rn "<!-- implements" openspec/specs openspec/changes --exclude-dir=archive`
  returns nothing (baseline: every one of 508 requirements).
- **M3** — Every scenario of every main spec (1 369 at baseline) is either linked by a marker
  or listed as an exemption; `checkSpecContract` is green inside root `check`.
- **M4** — The exemption list holds only scenarios verified outside a test; its size is reported
  at the end of the change and justified entry by entry.
- **M5** — All 28 queued changes pass `openspec validate <change> --strict` and
  `checkSpecContract --changes` (baseline 6 of 28 pass strict).
- **M6** — The checker's wall time over the whole tree is under five seconds.

## Open Questions

- **Q1 — Marker spelling.** `// Scenario: <spec-id> / <title>` is proposed because four
  features in `TakeBareAutoSpec` already carry `// Scenario:` informally. Fixed at task 1.1.
- **Q2 — Size of the missing-test work.** Unknown until the inventory (FR9) runs: tests cite
  FR ids today, not scenarios. The missing tests and every other finding (FR11) are sorted at
  the decision points: what stays here is done here; what goes to a new change is carried
  meanwhile by a `pending-test: <change>` exemption or a pending-marked test, so the gate is
  green without hiding the gap. The operator decides at tasks 5.2 and 6.8.
- **Q3 — Where the inventory tables live.** Proposed under `temporary-docs/gnomish/<task>/` as
  working documents of the tasks; nothing durable references them.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `quality-gates`: adds the spec-contract gate (traceability line, notes, retired comment
  form), the scenario ↔ test gate and the one-checker-two-launchers rule as behaviors of the
  single verification command.
- Every other non-archived capability under `openspec/specs/` — the 47 remaining spec files,
  from `agent-executor` to `verification/verification-hardening` — gets a delta whose
  `MODIFIED` blocks carry its requirements in the new format and within the limit (FR8). These
  deltas are generated and edited during apply (tasks 3 and 4), not written at proposal time:
  they are the medium of the migration, and the change is not valid for archive until all of
  them exist.

FR6, FR7, FR9 and FR10 describe the build, the rules and the queue; they are carried by tasks
and by the functional spec of the checker.

## Impact

- `build-logic/src/main/groovy/com/github/oinsio/gnomish/build/` — `SpecContractCheck` and its
  reader; `build-logic/src/functionalTest` — its functional spec; the root `build.gradle` wires
  the task into `check`.
- `scripts/check-spec-contract.sh` — the manual launcher; `scripts/generate-spec-deltas.sh` —
  the migration generator, removed in the last task once the deltas exist.
- `.gnomish/stages/fix-artifacts/stage.yaml`, `.gnomish/stages/archive/stage.yaml` — one
  verification command each.
- `.claude/rules/traceability.md`, `delta-specs.md`, `testing.md`; `.claude/commands/fix-uncommitted.md`;
  `openspec/config.yaml`; `docs/guides/developer-guide.md`; `docs/glossary.md`.
- `openspec/changes/tighten-spec-contract/specs/` — 48 deltas; `openspec/changes/*/specs/` — the
  28 queued changes' deltas.
- Every module's `src/test` — markers above features; new specs where a scenario had no test.
- No production Java; no dependency change.
- Sequenced **first** in the queue: until it is archived, the archive stage is red for every
  task, and `supervise-daemon-loops-and-embed-dashboard` (in progress, 33 of 47 tasks) is
  either archived by hand like #92 or waits for this change.
