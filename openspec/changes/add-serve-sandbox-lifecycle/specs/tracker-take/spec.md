# tracker-take — delta

## ADDED Requirements

### Requirement: Container-bound stages run through take
`gnomish take` (explicit ref, bare auto-take, and batch) SHALL execute container-bound stages through the same container assembly as `gnomish run`: fresh claim, tracker-driven resume with decision collection, salvage on takeover and revocation, keep-on-non-completed-exit, and `--discard-work` all function in container mode. Host-mode behavior SHALL be unchanged. Objects created by take SHALL be labelled `tracked`.
<!-- implements FR1, FR2, NFR-R4 of add-serve-sandbox-lifecycle -->

#### Scenario: Tracker task completes in a container
- **WHEN** take claims a task whose stages are container-bound
- **THEN** the pipeline runs in the box, the branch is harvested and pushed, the outcome is recorded, and the environment is disposed — identical to the run-mode container path

#### Scenario: Takeover salvages from the kept box
- **WHEN** take seizes a task whose previous holder's claim went stale, and that holder's stopped box survives
- **THEN** resume reattaches (or recreates over the surviving volume), salvages un-harvested work, and continues from the recorded pipeline position

### Requirement: Startup sweep pass with a reported summary
Each `take` invocation SHALL run one sweep pass at startup, evaluating the shared `sandbox-lifecycle` policy, and SHALL log verdicts in the uniform vocabulary; the finish report SHALL include a one-line sweep summary (per-category counts). A tracker or runtime error during the pass degrades to skipped-no-verdict and never blocks the take.
<!-- implements FR6, FR9, NFR-O4 of add-serve-sandbox-lifecycle -->

#### Scenario: Take reports its sweep
- **WHEN** a take run's startup pass stops one abandoned box and disposes two aged remnants
- **THEN** the finish report carries a summary line naming one stopped and two disposed objects, and each action is a structured log line with object, task key, and reason
