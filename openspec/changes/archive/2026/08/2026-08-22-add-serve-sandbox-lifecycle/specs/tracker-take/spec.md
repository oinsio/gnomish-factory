# tracker-take — delta

## ADDED Requirements

### Requirement: Container-bound stages run through take
`gnomish take` (explicit ref, bare auto-take, and batch) SHALL execute container-bound stages through the same container assembly as `gnomish run`: fresh claim, tracker-driven resume with decision collection, salvage on takeover and revocation, keep-on-non-completed-exit, and `--discard-work` all function in container mode. Host-mode behavior SHALL be unchanged. Objects created by take SHALL be labelled `tracked`.
<!-- implements FR1, FR2, NFR-R4 of add-serve-sandbox-lifecycle -->

A resumed branch SHALL be routed by one shared routing table regardless of execution mode: a delivered branch whose tracker finish never landed reconciles the deferred finish, a park whose tracker write never landed reconciles the deferred park, an escalation-kind park enters the decision dialog, and any other recorded outcome resumes on the return alone — each with the same tracker effect and the same number of engine rounds in host and container mode.
<!-- implements FR1 of add-serve-sandbox-lifecycle -->

#### Scenario: A delivered container task with a pending finish reconciles by ref
- **WHEN** a container-mode task records `Completed` — its cleanup commit having stripped `.gnomish-task/` from the branch tip — while its tracker finish never landed, and the task is taken again by ref
- **THEN** the deferred finish is posted from the branch's own delivered state and the task ends `Finished`, with zero engine rounds and no environment reattached — identical to the host-mode reconcile

#### Scenario: Tracker task completes in a container
- **WHEN** take claims a task whose stages are container-bound
- **THEN** the pipeline runs in the box, the branch is harvested and pushed, the outcome is recorded, and the environment is disposed — identical to the run-mode container path

#### Scenario: Takeover salvages from the kept box
- **WHEN** take seizes a task whose previous holder's claim went stale, and that holder's stopped box survives
- **THEN** resume reattaches (or recreates over the surviving volume), salvages un-harvested work, and continues from the recorded pipeline position

### Requirement: Startup sweep pass with a reported summary
Each `take` invocation SHALL run one sweep pass at startup, evaluating the shared `sandbox-lifecycle` policy, and SHALL log verdicts in the uniform vocabulary plus one summary line with the per-category counts. The summary belongs to the invocation's log, not to the task's finish report: a finish report describes ONE task, while the sweep is project-wide and mostly concerns objects of other tasks. A tracker error during the pass degrades to skipped-no-verdict; a runtime error aborts the pass with a logged line. Neither ever blocks the take.
<!-- implements FR6, FR9, NFR-O4 of add-serve-sandbox-lifecycle -->

#### Scenario: Take reports its sweep
- **WHEN** a take run's startup pass stops one abandoned box and disposes two aged remnants
- **THEN** the invocation logs a summary line naming one stopped and two disposed objects, and each action is a structured log line with object, task key, and reason

#### Scenario: A failing sweep never fails the take
- **WHEN** the container runtime is unreachable when the startup pass runs
- **THEN** the pass is abandoned with one logged line, and the take proceeds to claim and work exactly as it would with nothing to sweep
