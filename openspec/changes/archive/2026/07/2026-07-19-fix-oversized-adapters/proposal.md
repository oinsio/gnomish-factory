# Proposal: fix-oversized-adapters

## Why

Three production source files carried over from earlier merged changes exceed
the project's 200-line hard cap (`.claude/rules/process-invariants.md`):
`PipelineLoader.java` (229), `AgentProcessLauncher.java` (216), and
`StreamJsonParser.java` (202). Oversized files degrade AI context quality and
violate the "one file = one thing" invariant; this change brings them into
compliance without altering behaviour.

## What Changes

- **MODIFIED**: `PipelineLoader` — extract the model-building tier
  (`mapAndValidate` + `orderedEntries`) into a package-private collaborator so
  the loader keeps only read/parse/aggregate orchestration.
- **MODIFIED**: `AgentProcessLauncher` — extract the repeated command-line
  assembly (binary + transport flags + invocation flags) into a package-private
  command-line builder; the launcher keeps only its `launch*` overloads and
  process start.
- **MODIFIED**: `StreamJsonParser` — extract live-progress emission
  (`emitProgress` + `deliver`) into a package-private collaborator so the parser
  keeps only the tolerant read/parse loop.
- No behaviour change: no existing spec is edited; the full suite passes as-is.
- **Out of scope** (handled elsewhere): `GitResumeRunner`/`GitResumeContinuation`
  size fixes live in the `add-git-workflow` change (its tasks 8.1–8.2).

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `quality-gates`: add a requirement that production Java source files stay
  within the file-size cap, verified in review — the invariant this change
  brings the three legacy adapters into compliance with.

## Goals

- **G1**: Every production Java source file touched here — including any file
  extracted from a split — is within the 200-line hard cap.
- **G2**: Zero observable behaviour change; the existing test suite passes
  without a single spec edit.

## Non-Goals

- **NG1**: No new automated file-size enforcement tooling (no Checkstyle/ArchUnit
  rule) — the cap stays a review-enforced process invariant.
- **NG2**: No file outside the three named adapters (and their new siblings) is
  touched; no logic, API contract, or behaviour is changed.
- **NG3**: The two oversized `add-git-workflow` files are not addressed here —
  they are tracked in that change.

## Users & Scenarios

- **U1**: A developer or AI agent reading `adapter/pipeline` or `adapter/agent`
  opens a file that does one thing and fits in context.
- **U2**: A reviewer confirms cap compliance with a single line-count check and
  a green build, with no behavioural diff to reason about.

## Requirements

### Functional

- **FR1**: Each of the three named files SHALL be split so that it — and every
  file extracted from it — is within the 200-line hard cap.
- **FR2**: The split SHALL be behaviour-preserving: no observable behaviour of
  the loader, launcher, or parser changes, and no existing spec is modified.
- **FR3**: Extracted units SHALL respect module boundaries — package-private
  collaborators in the same package, no new cross-module internal imports and no
  widening of a type's visibility beyond what the split requires.

### Non-Functional — Reliability

- **NFR-R1**: Coverage and PIT mutation score on the affected classes SHALL be
  no lower after the split than before, with the test suite unchanged.

### Non-Functional — Observability

- **NFR-O1**: Log output (levels, messages, and their taskId/stage/round or
  event context) SHALL be byte-for-byte unchanged by the extraction.

### Non-Functional — Security

- **NFR-S1**: Process spawning, environment inheritance/pass-through, and the
  refspec/no-`--force` guarantees SHALL be unchanged; the launcher extraction
  moves command-string assembly only, never the `ProcessBuilder` start seam.

_Performance and Cost NFRs: not applicable — a pure structural refactor changes
no runtime path or token usage._

## Operator Experience Criteria

- **UX1**: No change visible to an operator — same CLI behaviour, same logs,
  same exit codes.

## Success Metrics

- **M1**: `wc -l` reports ≤ 200 for all three files and every newly extracted
  file.
- **M2**: `./gradlew check` (incl. JaCoCo + PIT) is green with zero spec edits.
- **M3**: PIT mutation score on the three affected classes is ≥ its pre-change
  value.

## Open Questions

- **Q1**: For `PipelineLoader`, is the model-building tier the clearest seam, or
  should the parse-tier helpers move instead? (Resolved in design: model-building
  tier — it is the most self-contained cut.)

## Impact

- Affected packages: `adapter/pipeline`, `adapter/agent`. New package-private
  classes only; no public API, dependency, or configuration change.
- No effect on other modules; existing specs exercise the extracted code through
  the unchanged public entry points.
