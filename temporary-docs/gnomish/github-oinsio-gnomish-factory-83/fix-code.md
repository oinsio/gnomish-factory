## Audit Resolution: make-checkpoint-gate-durable

Source: temporary-docs/gnomish/github-oinsio-gnomish-factory-83/review-code.md

### 1 — applied
- Recommendation: WARNING — delta scenario "The machine's tooling variables survive" has no test
- Re-verified: `openspec/changes/make-checkpoint-gate-durable/specs/quality-gates/spec.md:17-19` requires `DOCKER_HOST` and `TESTCONTAINERS_RYUK_DISABLED` of the launching process to reach the forked test JVM; `build-logic/src/functionalTest/groovy/com/github/oinsio/gnomish/build/TestEnvironmentHygieneFunctionalSpec.groovy` asserted only `GNOMISH_DECISION_FILE` absent and `GNOMISH_BUILD_OWNED == 'kept'`, and its helpers planted only `GNOMISH_` keys — the scenario had no covering assertion.
- Edit: `TestEnvironmentHygieneFunctionalSpec.groovy` — the generated `FooSpec` now also asserts `TESTCONTAINERS_RYUK_DISABLED == 'true'` and `DOCKER_CONFIG == <temp path>`; a new `toolingVariables()` helper supplies both and is merged into **both** `withOperatorVariables` and `withoutOperatorVariables` (the latter made an instance method). This differs from the proposed Fix on one point: the audit's Fix risk said the configuration-cache feature's first run goes through the same helper, but it goes through `withoutOperatorVariables`, so adding the keys only to `withOperatorVariables` would have failed that feature's first run. `DOCKER_CONFIG` stands in for `DOCKER_HOST` as the audit proposed (keeps the inner build Docker-free); class javadoc names the scenario. The spec covers behaviour the code already has (a missing assertion), so there is no production change; it was shown red by temporarily widening `TestEnvironmentHygiene.applyTo`'s filter to also strip `TESTCONTAINERS_` — all three features failed — then the production file was restored byte-for-byte (`git diff` empty) and all three pass.

### 2 — rejected
- Recommendation: WARNING — `design.md` records a signature and a shape set the code contradicts
- Re-verified: `openspec/changes/make-checkpoint-gate-durable/design.md:139-140` gives `approveCheckpoint(taskId, TaskState approved)` while `TaskRepository.java:127` takes `(taskId, Position.AwaitingApproval gate, TaskState approved)`; `design.md:307` reads "after it, `InProgress` at the reset state", while the `Escalated` return writes `resetAttempts()` and `ResumedKillPoints.groovy:64-66` asserts `frozenShapes: ['Created']`. The finding is real.
- Evidence: the fix is an edit to the change's `design.md`, which this stage may not touch (`.gnomish/stages/fix-code/instructions.md`, "Never … the change's proposal, design or delta specs"; `fix-from-audit.md` step 2: "A recommendation that the plan is wrong … is not fixed here"). Because it is right and blocks archiving, it is escalated to a human through `$GNOMISH_DECISION_FILE` with the audit's proposed wording, not only rejected.

### 3 — rejected
- Recommendation: SUGGESTION — proposal M8 states a stricter criterion than FR20 and task 10.1 grant
- Re-verified: `ContainerTakeSupport.java:37` keeps `sandboxProperties`, read by `TakeWorkRouter.plan` for its own decision, as task 10.1 permits; M8 in `proposal.md` omits that exception.
- Evidence: the edit is to `proposal.md`, outside what this stage may change, and it alters a success metric — not a trivially safe local edit (`review-recommendations.md`: a SUGGESTION is applied only when it changes no "requirement, task or public surface"). Included in the escalation as plan text for the human.

### 4 — rejected
- Recommendation: SUGGESTION — `OutcomeConsumptionGateSpec` permits two files it never reaches
- Re-verified: `bootstrap/src/test/groovy/com/github/oinsio/gnomish/architecture/OutcomeConsumptionGateSpec.groovy:48-51` lists `HostResumeMechanics.java` and `ContainerResumeMechanics.java` in `PERMITTED`; neither file names `resetAttempts`.
- Evidence: `openspec/changes/make-checkpoint-gate-durable/tasks.md:191` asks for "the two mechanics; allowlist by file, asserted reached". Dropping the two entries makes the gate disagree with the task text, and the audit's own Fix requires rewriting that task text, which this stage may not do (tasks.md: ticks only). A SUGGESTION that changes a task is not trivially safe; included in the escalation so the human can reconcile task and gate.

### 5 — rejected
- Recommendation: SUGGESTION — split the third feature out of the 351-line `ContainerModeResumeE2ESpec`
- Re-verified: `bootstrap/src/test/groovy/com/github/oinsio/gnomish/app/ContainerModeResumeE2ESpec.groovy` is 351 lines; the denial-cursor feature sits at `:289-337` with `roundState` at `:339-350`.
- Evidence: fails "Worth the cost" — the audit's own Fix risk states the split starts a second `@Shared` Gitea container per Docker run and copies the private `stage`/`pipeline`/`segments`/`sandbox` helpers plus `setup`/`cleanup` into a second class ("two copies to keep in step"), while the Impact is "context quality only … no behaviour at stake". A SUGGESTION applied only when trivially safe; this is not.

### 6 — rejected
- Recommendation: SUGGESTION — a sibling design still says `Position` has no gate variant
- Re-verified: `openspec/changes/add-stage-iteration/design.md:20` reads "`Position` stays `AtStage | PipelineEnd`".
- Evidence: the edit is to another change's `design.md`; this stage edits only code, tests, `docs/` and this change's `tasks.md` ticks (`.gnomish/stages/fix-code/instructions.md`, "Rules of this medium"). Impact is clarity only. Included in the escalation as plan text.

### 7 — rejected
- Recommendation: SUGGESTION — the writer sentence's parenthetical omits the decision commit
- Re-verified: `openspec/changes/make-checkpoint-gate-durable/specs/git-task-persistence/spec.md:72` names the approval and resumed writes but not the decision commit, which also rewrites `state.json` (`GitTaskRepository.java:133-139`).
- Evidence: the edit is to this change's delta spec and to `add-pipeline-entry-precondition`'s delta, both outside what this stage may change ("Never … the change's proposal, design or delta specs"). Impact is clarity only. Included in the escalation as plan text.

### Other edits
None.

### Verification
- `TestEnvironmentHygieneFunctionalSpec` (`./gradlew :build-logic:functionalTest --tests …`): 3/3 pass; red with the strip widened to `TESTCONTAINERS_` (3/3 fail), restored.
- `./gradlew check`: not run in this round — the round ends through the escalation exit for recommendation 2 (plan edits only a human may make); the round after the answer runs it.
