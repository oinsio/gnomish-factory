# execution-environment

## Purpose

The `TaskExecutionEnvironment` port is the single gateway through which every gnome-product process runs and through which the factory exchanges files with the working copy: it owns the working-copy lifecycle (materialize, harvest, dispose), all process execution over it (`exec`), and the factory↔environment file channel (`putFile`/`readFile`). A host adapter (worktree + local `ProcessBuilder`, no isolation) and a container adapter (Docker: cloned volume, internal-only network, resource limits) implement the same host-agnostic contract, so future adapters are swaps, not redesigns. Adapter capability passports, fail-closed reconciliation against repo-declared needs, environment lifecycle bound to stage segments, freshness knobs, a layered positive environment allowlist, resource limits, and orphan cleanup together keep gnome-product execution isolated by construction while remaining resumable by any factory instance from the task branch alone.

## Requirements

### Requirement: Task execution environment port
A `TaskExecutionEnvironment` port SHALL own the working-copy lifecycle, all process execution over it, and the factory↔environment file channel: `materialize(branch, commit?)` prepares the working copy on the task branch, pinned at the optional factory-chosen commit (default: the branch tip) — the single operation behind fresh-box verification, sandboxed judge environments, and `--discard-work`; the pin SHALL always be factory-chosen, never a name or SHA produced inside an environment. `exec(cmd, env, stdin?)` runs a process with optional stdin content yielding streamed stdout/stderr and an exit code, `putFile(path, content)` writes a factory-authored file at a factory-chosen path, `readFile(path, sizeCap)` returns the bounded content of a factory-chosen path, `harvest()` makes the task branch fetchable by the factory, `dispose()` tears everything down. File operations SHALL be valid only between rounds, never mid-round, and their paths SHALL resolve under one of two environment-owned file-channel roots: the working copy, or a per-environment scratch area that `materialize` allocates for factory↔environment protocol files (e.g. findings) — outside the working copy, with its root exposed to the factory by the environment handle; scratch content SHALL never be harvested and SHALL be removed by `dispose()`. The contract SHALL NOT assume a filesystem shared between factory and environment, and SHALL expose no snapshot operation.
<!-- implements FR1 of add-sandbox-core -->

#### Scenario: Contract suite runs against any adapter
- **WHEN** the port-level contract spec suite runs against an adapter
- **THEN** materialize → exec → putFile/readFile → harvest → dispose complete using only streams and git transport, with no adapter-specific calls from the caller

#### Scenario: No snapshot of a gnome-touched environment
- **WHEN** any caller holds a live environment handle
- **THEN** no port operation can persist the environment's state as a reusable image

#### Scenario: Materialize pinned at a commit
- **WHEN** an environment is materialized with a factory-chosen commit pin (e.g. the attempt commit)
- **THEN** the working copy matches that commit's tree, and branch commits after the pin are absent from it

### Requirement: Channel content from the environment is inert and bounded
Everything the factory receives from an environment — exec output, `readFile` content, harvested objects — SHALL be inert data: never executed, never interpolated into commands, refspecs, or filesystem paths, and never materialized as files on factory-owned filesystem (bytes in memory, parsed, discarded; logs carry sanitized capped excerpts only). Reads SHALL happen only at factory-chosen paths with size caps; the host adapter SHALL resolve symlinks and refuse paths escaping the environment's file-channel roots — the working copy and the per-environment scratch area — on reads and writes alike; the container adapter SHALL stream file content through `exec`, never through host-side archive extraction. Factory writes SHALL run as the in-box task user, never root; factory-invoked in-box git SHALL disable hooks via argv-level config.
<!-- implements NFR-S3, FR17 of add-sandbox-core -->

#### Scenario: Symlink escape on the host adapter is refused
- **WHEN** a factory-chosen read path inside a host working copy or scratch area is a symlink resolving outside both roots
- **THEN** the read is refused and reported as a violation, and no file outside those roots is opened

#### Scenario: Oversized protocol file cannot exhaust the factory
- **WHEN** a file at a factory-chosen path exceeds the read cap
- **THEN** the factory reads at most the cap, notes the truncation, and no factory-side file is created

### Requirement: Host adapter mechanics
A host adapter SHALL implement the port over worktree and `ProcessBuilder` mechanics: materialize = git worktree of the factory clone, exec = local subprocess with the working copy as working directory, harvest = no-op (the branch is already in the factory clone), scratch = a factory-private temp directory outside the worktree. Its capability passport SHALL declare no isolation — including that the env allowlist bounds environment variables only, not filesystem access. Host mode differs from sandboxed modes only in these isolation mechanics; mode-independent process discipline (the layered env allowlist, stdin prompts, law binding, in-branch decision files, the findings funnel, the pin-check, factory git hardening) applies through the host adapter unchanged.
<!-- implements FR2 of add-sandbox-core -->

#### Scenario: Host adapter keeps worktree mechanics
- **WHEN** a pipeline runs through the host adapter
- **THEN** the working copy is a git worktree of the factory clone, processes run as local subprocesses, rounds close with the single round commit, and harvest performs no fetch

### Requirement: Container adapter
The container adapter SHALL create per environment: an internal-only task network, a task volume holding the working copy, and one container from the operator-configured image (`factory.sandbox.image`), honoring the `factory.sandbox.runtime` knob. `exec` SHALL run inside that container; `dispose()` SHALL remove container, volume, and network as one idempotent operation. All factory-created Docker objects SHALL carry, atomically from creation, the ownership labels defined by `sandbox-lifecycle`: factory ownership, the sanitized task environment key, the ownership mode (`tracked` | `manual`), and the project identity.
<!-- implements FR3, NFR-R2 of add-sandbox-core -->
<!-- implements FR2 of add-serve-sandbox-lifecycle -->

#### Scenario: One task, one box
- **WHEN** an environment is materialized for a task in container mode
- **THEN** a labeled container, volume, and internal network exist for it — each carrying key, mode, and project labels from birth — and dispose removes all three

#### Scenario: Dispose is idempotent
- **WHEN** dispose is called twice, or after a partial teardown
- **THEN** the second call succeeds and no task objects remain

### Requirement: Container realization of lifecycle roles
The container adapter SHALL realize the lifecycle roles of the `sandbox-lifecycle` decision matrix through its object naming: environments are keyed `<key>` (main), `<key>-j` (fresh judge box), `<key>-v` (fresh verification box), and object names carry the adapter's factory name prefixes (box, volume, network, and the per-environment-key egress guard). The one-shot seed-clone helper runs as an anonymous container carrying the factory and task labels but no factory-named identity; a factory-labelled container matching no factory name pattern SHALL classify as the seed-helper role. Sweep classification SHALL derive an object's role and task key from its labels and this naming — a `-j`/`-v`-suffixed environment key resolves to its base task key, so a live task's judge and verification objects classify alive — never by recomputing expected names from a live-task snapshot.
<!-- implements FR4 of add-serve-sandbox-lifecycle -->

#### Scenario: Role recovered from the object itself
- **WHEN** the sweep classifies a listed factory-labelled container it did not create
- **THEN** the object's role (main box, judge, verification, guard, seed helper) and task key are derived from its own labels and name, with no reference to any in-memory key set

### Requirement: All gnome-product processes go through the port
Agent-CLI rounds, CLI judge votes, and command checks SHALL execute exclusively via `exec()` of the bound task environment; no factory code path may spawn a process over a working copy directly.
<!-- implements FR4 of add-sandbox-core -->

#### Scenario: Command check runs in the box
- **WHEN** a stage's command check executes in container mode
- **THEN** the process runs inside the task container against the volume working copy, and its exit code and output tail reach the engine unchanged

### Requirement: Layered positive environment allowlist
The child environment of every `exec()` SHALL be composed of exactly three layers, with nothing inherited implicitly: (1) the adapter's base set — host: a fixed documented minimum (`PATH`, `HOME`, `TMPDIR`, locale variables, `TERM`, `USER`, `SHELL`; deliberately no agent sockets such as `SSH_AUTH_SOCK`); container: empty, the image's own `ENV` supplies the runtime environment; (2) operator-configured passthrough variables — exact names only, no patterns; values SHALL be read from the factory process environment at exec time, never stored in config; (3) factory-set protocol variables (the AI base-url/auth-token seam, findings/decision file paths). A passthrough name declared as a credential SHALL be a startup configuration error. The names (never the values) of the applied allowlist SHALL be logged at debug level per exec.
<!-- implements FR9 of add-sandbox-core -->

#### Scenario: Host secrets never reach the box
- **WHEN** the factory process holds a tracker token and unrelated cloud keys in its environment
- **THEN** the environment observed inside `exec()` contains only the three allowlist layers, and the unrelated cloud keys are absent

#### Scenario: Typical host project needs no env configuration
- **WHEN** a host-bound command check runs with an empty passthrough list
- **THEN** its environment contains exactly the host base set plus factory-set protocol variables, and toolchains resolvable via `PATH` work without operator configuration

#### Scenario: Passthrough carries live values by name
- **WHEN** the operator lists `JAVA_HOME` in passthrough and its value in the factory's environment later changes
- **THEN** the next `exec()` child observes the current value with no config change

### Requirement: Read-only control surfaces inside the box
Injection-persistence surfaces inside the container SHALL be read-only for the gnome: agent-CLI configuration, shell rc files, and the baked proxy/CA/build configs SHALL be root-owned or `:ro`-mounted so in-box writes to them fail. The gnome's own clone (including its hooks) stays writable — hooks execute only inside the box and never cross the harvest boundary.
<!-- implements FR20, NFR-S2 of add-sandbox-core -->

#### Scenario: Cage rules cannot be rewritten from inside
- **WHEN** a process inside the box attempts to modify the agent-CLI config, a shell rc file, or a baked proxy/CA config
- **THEN** the write fails, and the surface's content is unchanged for every later process in the same environment

### Requirement: Resource limits
The container adapter SHALL apply operator-configured CPU, memory, and PID-count limits at container creation, with documented defaults. Working-volume disk-size enforcement (`--storage-opt size=`) SHALL be applied when the operator enables it (`factory.sandbox.enforce-disk-quota`, default off); it does not default on because it requires a quota-capable storage driver (overlay2 on xfs with `pquota`) that most daemons lack — enabling it without such a driver fails every container start, so the safe default is to leave disk uncapped and let the operator opt in on a capable host.
<!-- implements FR10 of add-sandbox-core -->

#### Scenario: Runaway build is contained
- **WHEN** a process inside the box exceeds the memory limit or forks past the PID limit
- **THEN** only in-box processes are killed; the factory observes a failed round, and the host stays healthy

#### Scenario: Disk quota is enforced only when opted in
- **WHEN** the operator sets `factory.sandbox.enforce-disk-quota` on a daemon whose storage driver supports quotas
- **THEN** the container is created with `--storage-opt size=` at the configured working-volume size; left at its default, no disk cap is applied and container creation does not depend on a quota-capable driver

### Requirement: Runtime outage is an infrastructure failure
When the container runtime is unavailable — the daemon is down at materialize, or dies mid-task — the affected operation SHALL classify as an infrastructure failure: retried per existing policy, no stage attempt burned; if the runtime stays down, the task escalates with a "cannot execute" report, consistent with the existing failure classes.
<!-- implements NFR-R1 of add-sandbox-core -->

#### Scenario: Docker daemon dies mid-task
- **WHEN** the daemon becomes unreachable between rounds
- **THEN** the round classifies as an infrastructure failure, the attempt counter is unchanged, and the task escalates as cannot-execute if the outage persists

### Requirement: Environment lifecycle bound to stage segments
Sandbox binding SHALL be resolved per stage; an environment SHALL live for a contiguous segment of equally-bound stages. A binding change between stages SHALL be executed as harvest → dispose → materialize from the task branch. Reuse across different bindings SHALL be impossible by construction. Within a segment the environment SHALL be reused — no repeated materialization — keeping environment overhead negligible against round duration.
<!-- implements FR12, NFR-P1 of add-sandbox-core -->

#### Scenario: Segment switch reuses resume mechanics
- **WHEN** stage N is bound to container A and stage N+1 to a different binding
- **THEN** the branch is harvested, environment A disposed, and a new environment materialized from the branch before stage N+1 starts

#### Scenario: Reuse within a segment avoids repeated clones
- **WHEN** two consecutive stages share the same binding and neither declares `requires-fresh`
- **THEN** the second stage runs in the same environment with no new clone or container creation

### Requirement: Freshness knobs
A stage MAY declare `requires-fresh`, forcing a new environment even within a segment. A command check MAY declare `verify-in: fresh-box`, running in a new environment materialized from the attempt commit. Defaults SHALL be segment reuse and same-box verification.
<!-- implements FR13 of add-sandbox-core -->

#### Scenario: Fresh box resets out-of-branch poisoning
- **WHEN** a stage with `requires-fresh` starts after a prior stage modified files outside the working copy (PATH shims, init scripts)
- **THEN** the new environment contains only image content plus the branch state

#### Scenario: fresh-box verification proves branch self-sufficiency
- **WHEN** a command check declared `verify-in: fresh-box` runs
- **THEN** it executes in an environment materialized from the attempt commit, so uncommitted work cannot influence the verdict

### Requirement: Adapter passports and fail-closed reconciliation
Each adapter SHALL expose a machine-readable capability passport (isolation boundary, egress control, task↔task boundary, docker-inside support). Before starting a stage, the factory SHALL reconcile the repo-declared needs against the bound adapter's passport and SHALL refuse with an infrastructure failure on mismatch. Repo declarations may only tighten; adapter binding and any weakening are operator-only.
<!-- implements FR14 of add-sandbox-core -->

#### Scenario: Mismatch refuses fail-closed
- **WHEN** a stage declares a need the bound adapter's passport does not satisfy
- **THEN** the task does not start the stage and the error names the unmet need

### Requirement: Container is the default binding
With no operator binding configured, stages SHALL bind to the container adapter. If Docker is unavailable, the factory SHALL refuse with an error naming the two options (install Docker or explicitly bind host); it SHALL never fall back to host silently.
<!-- implements G2, G4, FR14 of add-sandbox-core -->

#### Scenario: No silent weakening
- **WHEN** the factory starts with default binding on a machine without Docker
- **THEN** the run refuses with the explanatory error and no process executes on the host

### Requirement: Denial findings readable through the port
The `TaskExecutionEnvironment` port SHALL expose the environment's egress denial findings as structured findings, so consumers reach denials through the contract without knowing the adapter. Environments without an egress guard SHALL return an empty list. Read-back SHALL be best-effort: an unreadable or missing denial source yields an empty list and SHALL never fail the round, the attempt, or the report.
<!-- implements FR1, NFR-R1 of fix-denial-report-attachment -->

#### Scenario: Sandboxed environment surfaces its guard denials
- **WHEN** a consumer holding the port type asks a sandboxed environment for denial findings after a round with a denied request
- **THEN** it receives the structured denial findings recorded by the guard, without downcasting to any adapter type

#### Scenario: Guard-less environment reports no denials
- **WHEN** a consumer asks a host (non-sandboxed) environment for denial findings
- **THEN** it receives an empty list

#### Scenario: Unreadable denial log degrades to empty
- **WHEN** the guard's denial log is missing or unreadable at read-back time
- **THEN** the port returns an empty list and the round completes normally

### Requirement: Denial read position survives the process
The denial findings a round receives are the delta since the previous read, tracked by a cursor the environment advances. Because a denial source outlives the factory process that created it, the `TaskExecutionEnvironment` port SHALL expose that cursor — a read position paired with the identity of the source it was read from — so the factory can commit it with the attempt it delimits, and SHALL accept a cursor committed by an earlier lease before the first read of the current one.

A restored cursor is an offer, not an instruction: the environment SHALL apply the position only when the paired source identity matches its own live denial source, and SHALL ignore it otherwise. Environments without a denial source SHALL expose no cursor and SHALL accept an offer as a no-op.
<!-- implements FR5 of fix-denial-report-attachment -->

#### Scenario: A resumed lease reports only its own rounds' denials
- **WHEN** an environment is offered the cursor committed by an earlier lease, naming the denial source it is now attached to, and its first round closes
- **THEN** it reports only the denials recorded after that position, not those the source still holds from earlier rounds

#### Scenario: A cursor from another denial source is ignored
- **WHEN** an environment is offered a cursor whose source identity is not its own live denial source — a resume on another machine, or onto a recreated source
- **THEN** the position is ignored and the environment reads its own source from the beginning, so no real denial is filtered out of the report

#### Scenario: Guard-less environment has no cursor
- **WHEN** a host (non-sandboxed) environment is asked for its denial cursor, or offered one
- **THEN** it exposes none and accepts the offer without failing

### Requirement: Docker management commands are bounded
Every docker management command the factory issues (create, run, inspect, remove, list, and the
rest of the non-streaming CLI surface) SHALL be bounded by a configured deadline and have its
output drained concurrently with the running process — including `docker run` when the image is
absent locally and the CLI reaches a registry over the network. Expiry SHALL be reported as a
distinct timed-out outcome, never as an ordinary non-zero exit; the existing classification of an
unreachable daemon as an infrastructure failure is unchanged.
<!-- implements FR10, NFR-R1, G1 of bound-subprocess-commands -->

#### Scenario: A wedged registry does not hang the take
- **WHEN** a box's image is absent and the registry accepts the connection but never answers
- **THEN** the management command returns within the configured docker deadline and the failure is
  classified as infrastructure, not quality

### Requirement: Environment process termination is tree-wide and interruption is named
When an execution-environment process is killed on a timeout, the kill SHALL terminate the process
and every descendant it spawned, cooperatively first and forcibly after a short grace, and reap
them — an agent CLI's own children do not outlive the round that launched them. An interrupted
wait SHALL be a named outcome of the environment's wait contract, distinct from any exit code, on
every wait path the environment exposes.
<!-- implements FR11, NFR-R2, G2, G5 of bound-subprocess-commands -->

#### Scenario: A timed-out round leaves no orphaned agent children
- **WHEN** an agent round expires on its round timeout and the agent CLI had spawned subprocesses
- **THEN** the CLI and all its descendants are terminated and reaped

#### Scenario: Interruption is not an exit code
- **WHEN** a wait on an environment process is interrupted by shutdown
- **THEN** the caller observes a named interrupted outcome, not a sentinel exit code
