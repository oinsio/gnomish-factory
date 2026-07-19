# Design: fix-oversized-adapters

## Context

Three production files exceed the 200-line hard cap (FR1) and must be split
behaviour-preservingly (FR2), respecting module boundaries (FR3). Each is
oversized for a different reason, so each gets its own seam. The guiding rule:
cut along a cohesive responsibility, not merely to shave lines — a genuine
collaborator, not a javadoc trim. All extracted types are package-private in the
existing package (FR3); existing specs exercise them through the unchanged public
entry points, so no spec is edited (FR2).

## Goals / Non-Goals

**Goals:**
- Each file and every extracted unit within the cap (G1, M1).
- No behaviour, log, or exit-code change (G2, FR2, NFR-O1, NFR-S1); no spec edits.
- Coverage and mutation score held on the affected classes (NFR-R1, M3).

**Non-Goals:**
- No new enforcement tooling (NG1); no changes outside the three files (NG2);
  the `add-git-workflow` oversized files are out of scope (NG3).

## Decisions

**D1 — `PipelineLoader`: extract the model-building tier.** Move
`mapAndValidate` and `orderedEntries` into a new package-private
`PipelineModelBuilder` returning `@Nullable PipelineDefinition` (appending its
errors to the shared list, same signature semantics). `PipelineLoader` keeps the
read → parse → structural → consistency orchestration and the aggregation order,
delegating the "build a domain model when the tree is clean enough" tier. *Rationale:*
the model-building tier is the most self-contained cut — it depends only on the
parsed DTOs and the error list, and owns the D6 "skip when unbuildable" logic as
one concept. *Alternative rejected:* extracting the parse-tier helpers
(`parseStages`/`collectParse`/`structural`) — they are more entangled with the
orchestration order (NFR-R1 aggregation determinism) and cutting them would
scatter that contract across two files (resolves Q1).

**D2 — `AgentProcessLauncher`: extract command-line assembly.** Move the repeated
`binary + -p prompt + <invocation flags> + --output-format stream-json --verbose`
construction into a package-private `AgentCommandLine` with static builders
(transport-only, from-model-and-settings, from-rendered-flags). The launcher keeps
its four `launch*` overloads (unchanged signatures) and the private `start(...)`
ProcessBuilder seam. *Rationale:* the four overloads duplicate command assembly;
consolidating it removes the duplication and the bulk in one move, and keeps the
security-sensitive `ProcessBuilder` start untouched (NFR-S1). *Alternative rejected:*
splitting the overloads across two launcher classes — it would fracture one public
API surface and force callers to know which class to call.

**D3 — `StreamJsonParser`: extract progress emission.** Move `emitProgress` and
`deliver` into a package-private `AgentProgressEmitter` constructed with the
`AgentProgressListener` and a `TokenUsageMapper`, exposing `emit(event, roundInit)`.
The parser keeps the tolerant read/parse loop (`parse`, `parseLine`) and calls the
emitter inside the loop, preserving the live-before-next-line ordering (D10). *Rationale:*
parsing and progress-fan-out are two concerns already only coupled by the loop; the
emitter is a clean collaborator and the DEBUG raw-event log stays in the parser.
*Alternative rejected:* moving the whole parse loop out instead — the loop is the
class's reason to exist and carries the read-time `Clock` stamping (NFR-O3); the
progress side is the detachable half.

## Risks / Trade-offs

- A split could silently change behaviour → mitigated by editing no spec (FR2) and
  requiring `./gradlew check` green plus held mutation score (NFR-R1, M2, M3); any
  behaviour drift surfaces as a red existing spec.
- Over-cutting into too-small fragments → each seam is one cohesive responsibility
  (D1–D3), not a mechanical line-count cut; new files target the 100–120 line sweet
  spot, not merely < 200.
- NullAway/Error Prone friction on the new `@Nullable` seam in `PipelineModelBuilder`
  → keep the exact nullability contract the inlined methods already had.
