# Spec Delta: execution-environment

## MODIFIED Requirements

### Requirement: Denial read position survives the process
The denial findings a round receives are the delta since the previous read, tracked by a cursor the environment advances. Because a denial source outlives the factory process that created it, the `TaskExecutionEnvironment` port SHALL expose that cursor — a read position paired with the identity of the source it was read from — so the factory can commit it with the attempt it delimits, and SHALL accept a cursor committed by an earlier lease before the first read of the current one.

A restored cursor is an offer, not an instruction: the environment SHALL apply the position only when the paired source identity matches its own live denial source, and SHALL ignore it otherwise. Environments without a denial source SHALL expose no cursor and SHALL accept an offer as a no-op.

The offer SHALL be made as part of building a container round environment, by the one component that builds them, from the branch tip as it stands at that moment — on every path that builds one: the first round's open, a segment boundary, and a resume's reattach. No separate offering step SHALL exist on the run-support surface, so no caller can build a box and offer the position in the wrong order; an environment the lease hands out already carries the offer.

Every delegating view of the port SHALL forward the whole denial surface — findings, cursor, restore — to its delegate: a view that answers with the interface's constant defaults while holding a delegate that has real answers violates this requirement. The cursor round-trip SHALL be covered by at least one spec that drives the production delegating view, not a test double.
<!-- implements FR17 of make-checkpoint-gate-durable -->
<!-- implements FR5 of fix-denial-report-attachment -->
<!-- implements FR6 of fix-denial-attribution-durability -->

#### Scenario: A resumed lease reports only its own rounds' denials
- **WHEN** an environment is offered the cursor committed by an earlier lease, naming the denial source it is now attached to, and its first round closes
- **THEN** it reports only the denials recorded after that position, not those the source still holds from earlier rounds

#### Scenario: A cursor from another denial source is ignored
- **WHEN** an environment is offered a cursor whose source identity is not its own live denial source — a resume on another machine, or onto a recreated source
- **THEN** the position is ignored and the environment reads its own source from the beginning, so no real denial is filtered out of the report

#### Scenario: Guard-less environment has no cursor
- **WHEN** a host (non-sandboxed) environment is asked for its denial cursor, or offered one
- **THEN** it exposes none and accepts the offer without failing

#### Scenario: A delegating view forwards the denial surface
- **WHEN** the factory reads the denial cursor, reads findings, or restores a cursor through a delegating view of the port over a guard-backed environment
- **THEN** the delegate's real answers come back — never the interface defaults — and the committed state carries the cursor the delegate reported

#### Scenario: A reattached box carries the recorded position
- **WHEN** a container-mode task is resumed onto a surviving guard whose branch tip records a denial cursor and the identities reported with it, and the resume reattaches the box before anything else runs
- **THEN** the box the lease hands out already holds that offer, and the next round's findings contain no denial the record already reported — the same result the first round of a fresh start gets

#### Scenario: The offer is read when the box is built, not when the run was assembled
- **WHEN** a lifecycle commit that moves the recorded cursor lands between the run's assembly and the first environment build
- **THEN** the environment is offered the position the tip records at build time

### Requirement: Environment lifecycle bound to stage segments
Sandbox binding SHALL be resolved per stage; an environment SHALL live for a contiguous segment of equally-bound stages. A binding change between stages SHALL be executed as harvest → dispose → materialize from the task branch. Reuse across different bindings SHALL be impossible by construction. Within a segment the environment SHALL be reused — no repeated materialization — keeping environment overhead negligible against round duration.

The round-box lease and the fresh judge box SHALL be realized by one live-box mechanism: one materialized box per role, kept while its key (the stage segment; the attempt commit) is unchanged and rebuilt when it changes. That mechanism SHALL hold its lock across no Docker work and no git read: it decides and claims under the lock, materializes and disposes with nothing held, and records under the lock. A reader of the current box SHALL receive the last recorded box without waiting on a build or a disposal in flight; concurrent requests for the same key SHALL share one build; a failed build SHALL release its claim to every waiter so the next request may try again.
<!-- implements FR12, NFR-P1 of add-sandbox-core -->
<!-- implements FR21 of make-checkpoint-gate-durable -->

#### Scenario: Segment switch reuses resume mechanics
- **WHEN** stage N is bound to container A and stage N+1 to a different binding
- **THEN** the branch is harvested, environment A disposed, and a new environment materialized from the branch before stage N+1 starts

#### Scenario: Reuse within a segment avoids repeated clones
- **WHEN** two consecutive stages share the same binding and neither declares `requires-fresh`
- **THEN** the second stage runs in the same environment with no new clone or container creation

#### Scenario: A reader never waits on a materialize in flight
- **WHEN** a materialize for a new key is blocked inside Docker and another thread asks for the current box
- **THEN** the reader returns promptly with the last recorded box, or with none if no box was ever recorded

#### Scenario: Two requests for one key share one build
- **WHEN** two callers ask for the same key while no box is recorded for it
- **THEN** exactly one materialize runs and both callers receive the box it produced

#### Scenario: A failed build releases its claim
- **WHEN** the materialize of a claimed build fails
- **THEN** every caller waiting on that build receives the failure, no box is recorded, and the next request for the key starts a fresh build

### Requirement: Layered positive environment allowlist
The child environment of every `exec()` SHALL be composed of exactly three layers, with nothing inherited implicitly: (1) the adapter's base set — host: a fixed documented minimum (`PATH`, `HOME`, `TMPDIR`, locale variables, `TERM`, `USER`, `SHELL`; deliberately no agent sockets such as `SSH_AUTH_SOCK`); container: empty, the image's own `ENV` supplies the runtime environment; (2) operator-configured passthrough variables — exact names only, no patterns; values SHALL be read from the factory process environment at exec time, never stored in config; (3) factory-set protocol variables (the AI base-url/auth-token seam, findings/decision file paths). A passthrough name declared as a credential SHALL be a startup configuration error. The names (never the values) of the applied allowlist SHALL be logged at debug level per exec.

Every spawner of a gnome-product process in the test tree — the fixture box that stands in for the host adapter, the fake-agent invocation, the end-to-end harness — SHALL compose its child environment the same way: over a cleared environment, through the production allowlist where one exists, from an explicit list otherwise. A test double SHALL never be laxer than the production owner it stands in for; in particular no test-spawned process SHALL observe a `GNOMISH_*` variable the test itself did not set.
<!-- implements FR9 of add-sandbox-core -->
<!-- implements FR22 of make-checkpoint-gate-durable -->

#### Scenario: Host secrets never reach the box
- **WHEN** the factory process holds a tracker token and unrelated cloud keys in its environment
- **THEN** the environment observed inside `exec()` contains only the three allowlist layers, and the unrelated cloud keys are absent

#### Scenario: Typical host project needs no env configuration
- **WHEN** a host-bound command check runs with an empty passthrough list
- **THEN** its environment contains exactly the host base set plus factory-set protocol variables, and toolchains resolvable via `PATH` work without operator configuration

#### Scenario: Passthrough carries live values by name
- **WHEN** the operator lists `JAVA_HOME` in passthrough and its value in the factory's environment later changes
- **THEN** the next `exec()` child observes the current value with no config change

#### Scenario: The fixture box inherits nothing from the test process
- **WHEN** the test JVM's own environment carries a `GNOMISH_DECISION_FILE` naming a file, and a fake-agent round whose scenario carries a decision runs through the fixture box with no decision path set by the spec
- **THEN** the fake agent observes no such variable and the named file is left untouched

## ADDED Requirements

### Requirement: Container environment factory
Container round environments SHALL be built by a factory constructed once per ownership mode with the installation's box equipment — the sandbox settings, the box timing, the guard config root and the ownership mode — whose one method takes only what varies per task: the environment key, the box git link, the child-env allowlist, the project identity and the denial restoration read. No constructor or static factory SHALL mix the installation's equipment and a task's inputs in one parameter list, and the run-support factory SHALL hold the installation's settings as fixed state rather than receive them per run.
<!-- implements FR19, FR20 of make-checkpoint-gate-durable -->

#### Scenario: One factory per ownership mode serves every run
- **WHEN** two container runs of the same ownership mode are created by one factory process
- **THEN** both are built through the same environment factory, each with its own key, link, allowlist and restoration read, and every object they create carries that mode's label

#### Scenario: A per-run caller cannot pass installation settings
- **WHEN** a runner creates a run's container support
- **THEN** it passes the clone, the task, the segment plan, the pipeline and the credential names to scrub, and nothing else — the sandbox and factory settings are not on the call
