# Tasks: fix-oversized-adapters

Pure behaviour-preserving refactor: no spec is edited and no new spec is written
against the extracted units — the existing suites for `PipelineLoader`,
`AgentProcessLauncher`, and `StreamJsonParser` are the regression net (FR2). Work
each file, keep its suite green, confirm the cap. FR/NFR ids are proposal.md;
D-references are design.md.

## 1. PipelineLoader split (D1)

- [x] 1.1 Extract `mapAndValidate` + `orderedEntries` into a package-private `PipelineModelBuilder` (same `@Nullable PipelineDefinition` return, same error-append semantics, same D6 skip logic); `PipelineLoader.load` delegates the model tier — FR1, FR3
- [x] 1.2 Confirm `PipelineLoader` and `PipelineModelBuilder` are each ≤ 200 lines and the loader keeps the deterministic aggregation order (NFR-R1) — FR1
- [x] 1.3 Run the existing pipeline-config loader specs unchanged; all green — FR2

## 2. AgentProcessLauncher split (D2)

- [x] 2.1 Extract command-line assembly into a package-private `AgentCommandLine` (transport-only, from-model+settings, from-rendered-flags builders); the four `launch*` overloads delegate, `start(...)` ProcessBuilder seam untouched — FR1, FR3, NFR-S1
- [x] 2.2 Confirm both files ≤ 200 lines; overload signatures and env inheritance/pass-through unchanged — FR1, NFR-S1
- [x] 2.3 Run the existing agent-executor launcher specs unchanged; all green — FR2

## 3. StreamJsonParser split (D3)

- [x] 3.1 Extract `emitProgress` + `deliver` into a package-private `AgentProgressEmitter` (constructed with the listener + `TokenUsageMapper`, `emit(event, roundInit)`); the parse loop calls it inline, preserving live-before-next-line order (D10) and the DEBUG raw-event log — FR1, FR3, NFR-O1
- [x] 3.2 Confirm both files ≤ 200 lines; read-time `Clock` stamping stays in the parser (NFR-O3) — FR1
- [x] 3.3 Run the existing stream-json parser / progress specs unchanged; all green — FR2

## 4. Verification

- [x] 4.1 `wc -l` on all touched + new files ≤ 200 (M1); grep the repo for any remaining production file over the cap and note it out of scope if from another change — FR1, M1
- [x] 4.2 `./gradlew check` green (JaCoCo + PIT) with zero spec edits; PIT mutation score on the three affected classes ≥ pre-change value — FR2, NFR-R1, M2, M3
- [x] 4.3 Recommend a commit message (agent never commits) summarizing the split and referencing `fix-oversized-adapters` FR1/FR2
