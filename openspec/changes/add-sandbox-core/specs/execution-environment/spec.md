# execution-environment

## ADDED Requirements

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
The container adapter SHALL create per environment: an internal-only task network, a task volume holding the working copy, and one container from the operator-configured image (`factory.sandbox.image`), honoring the `factory.sandbox.runtime` knob. `exec` SHALL run inside that container; `dispose()` SHALL remove container, volume, and network as one idempotent operation. All factory-created Docker objects SHALL carry factory-owned labels.
<!-- implements FR3, NFR-R2 of add-sandbox-core -->

#### Scenario: One task, one box
- **WHEN** an environment is materialized for a task in container mode
- **THEN** a labeled container, volume, and internal network exist for it, and dispose removes all three

#### Scenario: Dispose is idempotent
- **WHEN** dispose is called twice, or after a partial teardown
- **THEN** the second call succeeds and no task objects remain

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
The container adapter SHALL apply operator-configured limits at container creation: CPUs, memory, PID count, and working-volume disk size, with documented defaults.
<!-- implements FR10 of add-sandbox-core -->

#### Scenario: Runaway build is contained
- **WHEN** a process inside the box exceeds the memory limit or forks past the PID limit
- **THEN** only in-box processes are killed; the factory observes a failed round, and the host stays healthy

### Requirement: Orphan cleanup at startup
Factory startup SHALL find Docker objects carrying factory labels that belong to no live task and remove them, mirroring worktree pruning.
<!-- implements FR11, NFR-R2 of add-sandbox-core -->

#### Scenario: Crash leaves nothing permanent
- **WHEN** a factory dies mid-task and another instance starts
- **THEN** the dead task's container, volume, and network are detected by label and removed

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
