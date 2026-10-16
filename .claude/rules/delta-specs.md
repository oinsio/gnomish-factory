---
paths:
  - "openspec/changes/**/specs/**"
  - "openspec/specs/**"
---

# Rule: capability spec format

Delta specs describe what a change adds to or modifies in a capability. They live in `openspec/changes/<change>/specs/<capability>/spec.md` during development and merge into `openspec/specs/<capability>/spec.md` after archive.

## Primary format: Requirement / Scenario (OpenSpec `spec-driven`)

The default format is the one the OpenSpec tooling generates and validates — requirements grouped under ADDED / MODIFIED / REMOVED headers, each with scenarios. The example below is the finished shape; `SpecContractRuleExampleFunctionalSpec` runs `checkSpecContract` over it, so it stays a valid spec:

```markdown
## ADDED Requirements

### Requirement: Claim confirmation
After the claim-confirmation delay, a factory instance SHALL read the task's assignee again,
and SHALL run the task only when that assignee is still itself.
**Implements**: FR2 of add-task-claiming
**Notes**: The delay absorbs the tracker's eventual consistency; its default is in the operator guide.

#### Scenario: The claim is confirmed
- **WHEN** the assignee read after the delay is this instance
- **THEN** the instance runs the task

#### Scenario: Another instance won the claim
- **WHEN** the assignee read after the delay is another instance
- **THEN** the instance does not run the task
- **AND** it moves on to the next task in the queue
```

The parts, in order:

- **The heading** names one behavior; the text under it states that behavior with `SHALL`,
  in a few sentences (see "One behavior per requirement" for the limit).
- **`**Implements**:`** — exactly one line, after the text and before the first scenario:
  `**Implements**: <ids> of <change-name>`, several changes joined by `; `
  (`**Implements**: FR1, FR4 of add-x; FR2 of fix-y`). The form is owned by `traceability.md`.
- **`**Notes**:`** — optional, zero or more, each one physical line, never `SHALL` or `MUST`.
- **Scenarios** — the cases, each with a marker in the test that exercises it.

## One behavior per requirement

**OpenSpec's limit is a fact to design for.** `openspec validate --strict` reports a
requirement whose text is longer than 500 characters. The text is every non-blank line between
`### Requirement:` and the first `####` heading, fenced blocks and metadata lines
(`**Implements**:`, `**Notes**:`) excluded. The limit is OpenSpec's rule, checked by OpenSpec
(`/opsx:archive`, the archive stage); the project does not re-check it. A requirement that does
not fit is two behaviors, or a behavior with cases the text is trying to list — split it, or
move the cases into scenarios.

**Normative text is a scenario or a requirement.** Every sentence that binds behavior —
`SHALL`, `MUST`, or an equivalent (`never`, `only`, `at most`, `at least`, `exactly`) — is
stated by an existing scenario, becomes a new scenario of the same requirement, or becomes an
`ADDED` requirement with its own scenario. It never moves into a note: notes are invisible to
`openspec show --json`, and a scenario is what gets a test.

**Notes are the exception, not the overflow.** A note carries what explains the requirement
and belongs nowhere else — a reason, a definition, a pointer. It is one physical line (a
wrapped continuation is ordinary text, so OpenSpec counts it and the gate reports it) and
carries neither `SHALL` nor `MUST`. A note longer than a sentence or two is text that wanted a
scenario.

**Every new scenario gets a marker.** A scenario added to a spec is matched by
`// Scenario: <spec-id> / <title>`, the title verbatim, in the test that exercises it
(`traceability.md`; marker placement and exemptions in `testing.md`, "Every scenario has a
test"). Write the marker with the test: it already resolves through the unarchived delta, needs
no edit at archive, and the gate requires it once the scenario merges into `openspec/specs/`.

## Domain-model format: Entity / Use case

When a spec describes the domain model itself (entities, fields, invariants), use ADDED / MODIFIED / REMOVED markers with YAML-like notation:

```markdown
## Entity: Task

### MODIFIED
fields:
  + claimed_by: { type: FactoryInstanceId, optional: true }

invariants:
  + a task is claimed by at most one factory instance  # implements FR1 of add-task-claiming
```

## Use case format

```markdown
## Use case: ClaimTask (NEW)

Inputs:
- taskId: TaskId
- instanceId: FactoryInstanceId

Effects:
- task assigned to instanceId in the tracker  # FR1
- claim re-verified after the claim-confirmation delay  # FR2

Errors:
- TaskAlreadyClaimedError — another instance holds the claim  # FR1
```

## Rules

- Every requirement carries exactly one `**Implements**: FR-X of <change-name>` line; every
  invariant and use case in a YAML-like block carries `# implements FR-X of <change-name>`
  (see `traceability.md`). The HTML-comment form survives only in archived changes
- Requirement text stays within OpenSpec's 500-character limit; normative remainder becomes a
  scenario or an `ADDED` requirement, never a note ("One behavior per requirement")
- `**Notes**:` lines are optional, one physical line each, without `SHALL` or `MUST`
- Every scenario has a `// Scenario: <spec-id> / <title>` marker in the test that exercises it
- `./gradlew checkSpecContract` (`--changes` for the unarchived deltas) checks the traceability
  line, the notes and the scenario links
- In the Entity format, use YAML-like notation for field definitions
- Keep specs focused: one capability per spec file
- Delta specs are temporary — they describe the change, not the full state
- **Overlapping MODIFIED requirements are declared and ordered.** Before writing a
  MODIFIED requirement, check whether another active change under `openspec/changes/`
  already MODIFIES the same requirement (`grep -rln "### Requirement: <name>"` over the
  active deltas). If one does: fix the sync order in both changes' proposals (the
  later one names the earlier one), open the later delta with a `Layered on ... as
  modified by <change> (sequenced before this change)` preamble, and write its text
  over the earlier delta's text, not over `openspec/specs/`. Reason: the OpenSpec
  MODIFIED merge is replace-only — a delta records no base, so the second sync
  silently discards the first one's edit. If the order is ever inverted at sync time,
  the later sync merges by hand; the preamble says what must survive
- **A scenario cannot be renamed or dropped through `MODIFIED`.** OpenSpec refuses to archive a
  `MODIFIED` block that lacks any scenario of the current requirement, and rejects `REMOVED` plus
  `ADDED` of one header in one change. A scenario title is therefore permanent unless the whole
  requirement is removed by one change and re-added, under the same header, by the next one,
  the two archived back to back. Choose scenario titles to last.
- After `/opsx:archive`, deltas merge into `openspec/specs/` — never edit archived specs directly
