# Design: define-executor-contract

## Context

See proposal.md — Why. Constraints that shape the approach:

- The engine holds five role ports in `EnginePorts` (`StageExecutor`, `BuiltinCheckRunner`,
  `CommandCheckRunner`, `ExternalCheckClient`, `JudgeVoter`); `ExecutorAdapterSelector`
  picks the Claude adapter unconditionally — there is no per-stage dispatch today. This
  change introduces it; `add-command-executor`, sequenced after, registers its `command`
  executor as the first built-in instead of adding a type-keyed arm of its own.
- One process seam already exists (`TaskExecutionEnvironment.exec`, host and container),
  one child-environment composer (`ChildEnvAllowlist.compose`), one supervisor with tree
  kill and named termination (`ProcessSupervisor`). The contract reuses all three.
- Three reads of a child-written file exist, each with its own reader: the findings file
  (`ShellCommandCheckRunner:190`, `readFile`, 256 KiB cap), the branch decision file
  (`BranchDecisionFile:92`, `readFile`, 1 MiB cap) and the host decision file
  (`DecisionFileTransport:143`, `Files.readString`, no cap).
- The agent stdout drain (`StreamJsonParser`) has no size cap; the agent's exit code and
  stderr are never read.
- A child's input is a file or nothing (`StdinSource`; ADR 0017; `lock-scope.md`), a child's
  output is read only through `ChildOutput` and the one `Drain` (ADR 0018; `DrainOwnerGateSpec`,
  `ChildOutputOwnerGateSpec`), and stderr may go to a file (`StderrPolicy.ToFile`);
  `CaptureRunner` has no byte or line cap of its own.
- The law is bound before any execution environment exists (`TaskTierLaw.bind` at
  `TakeFreshClaim:92` and `TakeContainerFreshClaim:84`; the first box is made live only at
  `TakeContainerFreshClaim:111` and `ContainerTerminalDrive:61`), and ADR 0019 (decision 2)
  classifies a refusal met after the claim as an operator precondition through
  `OperatorPreconditionRefusals`, with the first box made live before the engine runs; a
  describe failure is not one of them (D5 as revised, ADR 0019 decision 5).
- The `execution-environment` spec already requires channel content to be inert, bounded
  and read only at factory-chosen paths; the contract inherits that posture rather than
  restating it.
- Prerequisite: `fix-docker-exec-env-argv` (credentials must not travel in `-e K=V` argv).
  `ExecutionResult` is sealed over `Completed | DecisionNeeded` only; the executor-reported
  quality failure is introduced here as `failed/quality`.
- The round record (`AttemptRecord`, `state.json` `attempts[]`) carries no task id, stage or
  round identity; `UsageHistoryWalker` infers the stage from the attempts list's shape
  (`grewFrom`) and walks the whole branch history, base included. The round token
  (`RoundToken`, `CurrentRound`) exists in container mode only (`SandboxRoundEnvironmentSource`
  mints it; `HostRoundEnvironmentSource` does not). `TaskState.totals` folds the executor's
  usage only; judge usage is serialized by three independent DTO trees (`state.json`,
  `status.json`, `usage.json`); `TokenUsageMapper` reads `modelUsage` and drops `costUSD`;
  the usage-report stage re-aggregates `usage.json` in a jq script with no test. FR14–FR22
  and NFR-C1 address these (budget enforcement is NG12); the research behind D13–D17 is summarized in their rationale.

## Goals / Non-Goals

**Goals:** one wire shape and one Java shape that mirror each other (FR1, FR8); one reader,
one classifier, one dispatcher (FR6, FR7, G4); a first real consumer — the `program` stage
executor — so the contract is exercised end to end, not only by the kit (FR10).

**Non-Goals (design level):** no second transport (sockets, JSON-RPC) — stdin plus files is
the whole channel for this version; no in-process SPI for executors; no change to the
tracker SPI; no engine poll loop for `transform` (proposal NG7).

## Decisions

**D1 — Mixed plugin model; the boundary is the process, the contract is JSON.** (G1–G3,
NFR-S1, NFR-S3; principle recorded in ADR 0020.) Built-ins are a closed set compiled into
the factory; everything else is a program spawned through `TaskExecutionEnvironment.exec`.
*Rationale:* three of five mechanisms (shell, agent CLI, remote service) are processes or
network already; a process boundary buys language independence, crash isolation and a
contract that evolves by schema rather than by Java signature — the lesson of Terraform's
go-plugin and of Jenkins' extension-point breakage. *Alternatives rejected:* (a) in-process
`ServiceLoader` SPI like the tracker — couples every executor to the factory's release
cadence and loads third-party code with the factory's credentials; (b) "everything is a
process" (Concourse, Drone) — loses in-process `HttpClient` egress control for the SCM and
HTTP built-ins and spawns a process for `files_exist`; what we borrow from it is D2.

**D2 — Built-ins implement the mirror interface of the JSON contract.** (FR8, G1.) One DTO
set (`ExecutorRequest`, `ExecutorResult`, `Describe`) lives in a new JDK+Jackson module
`executor-contract`; built-ins implement `Executor { Describe describe(); ExecutorResult
start(ExecutorRequest); ExecutorResult poll(ExecutorRequest, PendingKey); }` over those
DTOs; the external adapter implements the same interface by spawning. The conformance kit
drives an `Executor`: the CLI wraps a candidate program in the external adapter, and a
built-in is driven in process through the same cases (FR8), so one kit covers both.
*Rationale:* a
separate Java path for built-ins is the two-taxonomy failure the research named first.
*Alternative rejected:* role-specific Java interfaces for built-ins — reintroduces five
result types.
*The kit's strictness is not the reader's* (added 2026-11-01 before task 8.2, FR24): the DTO
readers are lenient by design — an unknown `enforcementLevel` reads as `PROMPT_ONLY`
(`EnforcementLevel.read`), an unknown capability or role is dropped, an unknown provenance is
`ABSENT`, unknown fields are ignored (the additive-evolution requirement) — so an `Executor` the
kit drives through `ProgramExecutor` cannot see a schema violation, and `ExecutorRequest` is a
fixed record, so the kit cannot send an unknown field through it. The kit therefore judges the
wire where it exists, at the execution-environment port (D19), and the mirror interface stays as
D2 states it.

**D3 — Results in factory-named files; stdout and stderr are logs.** (FR5, FR6, NFR-S2.)
The request is the child's standard input, handed to the launch owner as
`ExecCommand.stdin` → `StdinSource.of(bytes)` — a file the child inherits, never a pipe a
factory thread writes into (`lock-scope.md`, "no pipe into a child"); it carries no secret,
credentials go through the environment channel (ADR 0017). `GNOMISH_RESULT_FILE` and
`GNOMISH_ANSWER_FILE` lie under the environment's scratch area. Stdout is read through
`Drain.capped` over `ChildOutput` (the one post-exit-bounded reader, ADR 0018) with a byte cap
and a line cap applied on the captured text; stderr goes to a file through
`StderrPolicy.ToFile`, its tail kept as the agent round keeps it (design D18 of
`fix-terminal-receipt-convergence`). When `streams_progress` is declared, stdout is instead
read live by a `ChildOutput` reader in the shape of `StreamDrain`, not `Drain`, so the silence
bound sees each line; a non-JSON line is logged and skipped. The `program` executor owns no
reader, pump or close of its own. *Rationale:* every stdout-as-protocol system (Ansible, MCP) has stray
prints as its top bug, and our checks (`./gradlew test`) emit megabytes; Bazel's rule that
tool output belongs to the job, not the protocol. The file channel already exists and is
already specified inert and bounded. *Alternative rejected:* NDJSON result on stdout —
forces every executor to capture its children's stdout.
*Bounds and settings, as built (task 7.1, NFR-R2):* the three round bounds (startup, which is also the describe bound; silence; overall) are the factory's, owned by `ProgramBounds` in `application` and handed to the preflight and the executor as a parameter; neither the describe answer nor the law sets them, since an executor must not choose the bound meant to contain it (an operator key is deferred until an operator needs a non-default bound). An expired bound is a factory-raised `failed/infrastructure` whose reason names the bound and its value; it gets no structured `bound` field, because the record's bound column is the executor-reported `failed/limit` bound that ends a stage, and a second spelling of one fact would be two owners (revisit when a reader must classify infrastructure failures by bound without the text). The settings a program receives are one merge, `ExecutorSettings` (`domain/pipeline`): declaration settings, the stage's `executor.settings` over them key by key, then `model`; the preflight judges and the request sends that same map.
*Poll of a program* (added 2026-11-01 before task 8.2, FR23; until then no artifact gave a
program's poll a wire form, and `ProgramExecutor.poll` answered `failed/infrastructure` for
every call): a program whose describe declares `poll` is polled as it is started — the declared
argv with the word `poll` appended (`ProgramChannel.POLL`), the same request on standard input,
the pending key in the variable `GNOMISH_PENDING_KEY` (`ProgramChannel.PENDING_KEY_VARIABLE`),
the result written to `GNOMISH_RESULT_FILE` and read under D10's rules, the same bounds and the
same stderr file. `ProgramExecutor.poll` refuses, with no process spawned, a program whose
describe lacks `poll` (the `Executor` javadoc's precondition, made a result instead of an
assumption) and a key this executor instance did not receive from its own `start` — pending keys
are not persisted in this change (NG7), so a poll across instances has nothing to resume. The
schemas do not change: the request is the same document and the key travels in the environment,
as the result paths do. *Alternative rejected:* the key as a request field — it would make the
request schema differ by operation, and a schema file is the one source of truth
(`conformance-kit`); the environment already carries the per-operation facts.

**D4 — Status plus answer, with `limit` as a third failure class.** (FR2, FR3, FR7.)
`ExecutorResult = Status(completed | failed(class) | pending(key)) + Optional<Answer> +
Usage(provenance) + List<Denial>`. The engine's `ExecutionResult` becomes an alias of this
shape; `DecisionNeeded` is `completed` + `decision-request`; `failed/quality` is new: a
recorded round, attempt burned, findings fed forward, verify chain skipped (the round produced
no product worth verifying — the `DecisionNeeded` precedent). The round is recorded as a `QUALITY_FAILURE` attempt whose one check result is labelled `executor:<stage>` and carries the executor's findings as a `Fail` verdict, so the findings reach `priorFailures` through the path every failed check already takes and `state.json` gains no field (added during task 4.1; a dedicated record field was rejected — it touches every `AttemptRecord` construction and adds a status-report field no spec defines).
One component, `ExecutorOutcomeClassifier` in `domain/engine`, maps status to attempt
accounting and escalation (`BudgetExhausted` is a new report kind). *Rationale:* GitLab's two
exit-code classes and MCP's `isError` vs protocol error are the same split; a limit stop is
neither quality nor infrastructure. *Alternative rejected:* `DecisionNeeded` as a status —
makes the answer slot and the outcome overlap, and the arbiter would insert at a status
transition instead of at "answer present".

**D5 — Describe handshake with integer version and capabilities; validated in two phases.**
(FR4, FR9, FR13, UX1.) *Phase (a), at law load:* the `executors:` section is checked
structurally and by the catalog-free sanity rules (type known, `program` argv present, a stage's
`executor.name` declared) — located `ConfigError`s like every other law error. No process runs:
the law is bound before any execution environment exists (Context). *Phase (b), the describe
preflight:* once the task's execution environment is live and before the engine starts — on
the container paths right after `FirstBoxStep.makeLive`, the point ADR 0019 (decision 2)
already names; on the host paths on the host environment after the law is bound — `describe`
runs once per declared `program` executor through `TaskExecutionEnvironment.exec`, bounded by
the startup read timeout, its output read under the result-reader rules (D10), cached for the
run by law commit. *How describe is invoked* (added 2026-10-31; no artifact said it): the
program's declared argv with the word `describe` appended as its last argument, no request on
stdin (stdin is the launch owner's empty source, ADR 0018), the answer written to the file the
`GNOMISH_RESULT_FILE` variable names under the environment's scratch area, read under the
result-reader rules (D10); stdout and stderr are logs, capped and never parsed.

*Every describe failure is the task's, never the daemon's* (revised 2026-10-31, after task 6.2
found the first draft unmeetable: `TakeResult.OperatorPreconditionUnmet(UntrustedText reason,
DaemonConditionKind kind)` requires a kind, `OperatorPreconditionRefusals.kindOf` is a closed
switch over four refusal types with none for describe, and under `serve`
`DaemonConditions.report` throws on a kind with no condition — the draft's "no new classifier"
and "exit 8" could not both hold). The rule, now ADR 0019 decision 5: the level of a
precondition is decided by the owner of its truth. Whether a program answers `describe` depends
on the task's law (which names the program) as much as on the image, so it is a **task-level
failure** in two classes, after GitLab Runner 19.1's split of `runner_configuration_error`
from `runner_external_dependency_failure`:

- **Configuration error** — the program is absent, exits non-zero, or writes an oversized,
  malformed or missing answer; and every disagreement with the law (protocol version mismatch,
  undeclared role, settings failing the declared schema, undeclared credential or egress
  host). Deterministic: no retry helps. Each is a located `ConfigError` naming the executor and
  the failure, carried as `LoadOutcome.Invalid` to the same parked result `TaskTierLaw.bind`
  yields for an invalid law (`TaskTierLaw:114`, `park(String baseRef, ObjectId lawCommit,
  List<ConfigError> errors, TakeOrder order)`): the task parks with the located message, no
  round runs, no attempt is burned, no abort accounting, no daemon condition opens.
  `OperatorPreconditionRefusals` and `DaemonConditionKind` are unchanged; the preflight's own
  refusal type (`ExecutorDescribeRefusedException`) is converted to that `ConfigError` by the
  preflight itself and reaches no classifier.
- **External failure** — the environment refuses to start the process (`ProcessStartException`)
  or `describe` does not exit within the startup bound and is killed. Possibly transient, but
  the park is already the bounded end of the run and the human's return is the retry, so the
  preflight adds no retry mechanism of its own (alternative zero): it parks the task the same
  way, with the `ConfigError` naming the class as external, so the operator reads "could not
  run" rather than "wrong". An automatic retry or return is proposal NG15's deferral.

A describe version that differs from the task's pin is not a law error: the engine pre-flight
escalates `PipelineMismatch` (D11). `gnomish doctor` does not run `describe` (proposal NG11).
The daemon-level conditions of ADR 0019 are unchanged for what the operator's image contract
guarantees: a program the **image contract** names and lacks is still `IMAGE_CONTRACT`.
*Rationale:* ACP and go-plugin bump an integer only on breaking change and add everything
else as capabilities; Buildkite and Terraform validate plugin config from a plugin-supplied
schema before work starts; the box is the only place the program exists, and ADR 0019 already
puts the first box before the engine for exactly this kind of check. For the level: every
comparable system scopes a plugin, image or tool that the job's own spec names to the job —
GitLab Runner (job failure reason), GitHub Actions ("Unable to resolve action" fails the job),
Jenkins ("Cannot find executable" fails the build; only an offline agent queues jobs, and only
those with its label), Buildkite, Tekton (`TaskRunImagePullFailed`), Kubernetes
(`ImagePullBackOff` is per pod and taints no node; only node-owned conditions do), Nomad (a
failing driver makes the node ineligible for tasks needing that driver only), Terraform and
go-plugin (a handshake failure is the run's error, the host never dies), Temporal (a task
needing a build the worker lacks waits; the worker is unaffected). No cited system holds a
whole worker for a job-spec defect. *Alternatives rejected:* semver or date strings (MCP) —
needs range logic for no benefit at this scale; `describe` at law load (the first draft) —
refuted by the code: the law is bound at `TakeContainerFreshClaim:84` and the box exists only
at `:111`, so the program is not reachable at that moment, and running it on the host instead
would run a program the image ships from a machine that does not have it; **an operator
precondition with a new kind `EXECUTOR_DESCRIBE` whose condition records the refusing task's
law commit and whose probe reruns describe for that law in a helper container** (option C′ of
the 2026-10-31 review) — fits ADR 0019's mechanics but one task on a stale law, naming an
executor the current image lacks, holds the whole daemon's feed off the tracker until the
operator rebuilds an image for a law nobody current needs: a stall of every other task,
ranked above any churn (`spec-mismatch.md`, item 4), with no industry precedent, plus a new
wire token, operator events, a branch in every switch over the kind and a container start
per probe tick; **the same kind probed on the daemon's startup law** (option C) — the probe
passes at once whenever the daemon's law does not name the executor, the condition closes,
the task is reclaimed and refuses again: claim churn, and the shape `CONTAINER_RUNTIME`'s
probe already has (proposal NG13); **reusing `IMAGE_CONTRACT`** — its probe inspects the
image contract, which holds, so the condition closes at once: the same churn; **no
classification at all**, the throw reaching `ClaimedRunFailureDispatch`'s crash arm —
bounded and visible (K aborts, then an INFRA park), not the reaper loop of ADR 0019's
incident, but it burns the abort fuse on a configuration defect and reaches the human only
after K claims.

**D6 — Abstract policy, compiled by the executor, verified by the factory.** (FR11, NFR-S4.)
Request carries `readOnly | writeExactly(path) | workspaceWrite`; describe declares an
enforcement level; after `verdict`/`decision` the factory runs a working-copy-unchanged
check through the environment (`git status --porcelain` over the box) and refuses the
answer on change. *Rationale:* "declaration + verification" is how the sandbox passports
already work; a vendor tool name (`Read,Grep,Glob`) is not a policy the core can reason
about. *Alternative rejected:* trusting the declared level — a self-declared passport is
what the plugin README refuses for the sandbox port.
*A pending run is judged when it ends* (added 2026-11-01, FR23): for a `verdict` or `decision`
run that returns `pending`, the view of the working copy is taken before `start` as today, kept
by the executor instance beside the pending key (`PendingRuns`, one entry per key issued by this
run, removed at the poll that ends it), and judged at that poll against the view taken then; a
pending answer itself is not judged. Judging at `start` only would let a polling judge change the
working copy between its `start` and its last `poll` unobserved.

**D7 — Mid-round permission prompts are answered by the executor from the policy.** (NG5.)
No `decision-request` is raised for a tool-permission question. Revisit when any of: a
backend whose best level is `AGENT_ASKS`; a law needs a policy not expressible statically;
the ACP adapter arrives; the arbiter is asked to answer tool questions. Recorded in ADR 0020.
*Alternative rejected:* pause-and-ask now — needs continuation to work first and doubles the
decision path before the arbiter exists.

**D8 — Dispatch is name-keyed at wiring, outside the engine.** (FR9, FR10.) `EnginePorts`
keeps one `StageExecutor`; a dispatching implementation assembled in `ExecutorAdapterSelector`
resolves `stage.executor()` against an `ExecutorRegistry` (`Map<ExecutorName, Executor>`: law
`executors:` plus built-ins). `agent-cli` keeps one type-keyed arm until
`extract-agent-round-runner` makes it a named executor; `add-command-executor` registers
`command` as a built-in and adds no arm. Interactive substitution wraps the agent arm only.
*Alternative rejected:* a second port in `EnginePorts` — the proposal's G1 forbids it.

**D9 — Sync surfaces.** Scout: `grep -rn "Kept in sync with" */src/main` and the registry.
- *Touched here:* none of the declared pairs change behavior in this change.
  `ExecutorRoundExecution` ↔ `JudgeRoundExecution` and `HostRoundEnvironmentSource` ↔
  `SandboxRoundEnvironmentSource` are dissolved by `extract-agent-round-runner`;
  `DecisionFileTransport` ↔ `BranchDecisionFile` (registry) keeps both ends, but the
  *reading* side of `DecisionFileTransport` moves onto the shared reader (D10) — the
  marker text is updated to name the reader as the owner of the read, the registry row
  stays until the transport pair is collapsed; `RoundTimeout` ↔ `AgentSettingsValidator`
  untouched.
- *No new parallel implementation:* the Java `Executor` interface and the JSON schema are
  one artifact by construction — the DTOs are the only Java image of the schema and a
  round-trip spec pins them (D2); the built-in and external paths share `ExecutorResult`
  and the classifier, so no writer/reader pair is created.
- *Old-way exemption, time-boxed:* `CliJudgeVoter` keeps constructing `Verdict.CannotVerify`
  until `extract-agent-round-runner`; the exemption list is the single-owner table
  below and the follow-up name is written on it. `CliStageExecutor` constructs no failure class
  since task 7.4: an agent-cli round reports `failed/infrastructure` (`AgentRoundFailure`) and
  `ExecutorFailure` is deleted.
- *Touched by D13–D16:* `HostRoundEnvironmentSource` ↔ `SandboxRoundEnvironmentSource`
  (declared, no shared classpath) — both ends change: the host end starts minting the round
  token; the registry row's invariant gains "both mint the round token from the tip the round
  opens on through `RoundToken.of`". The three usage DTO trees (`adapters/git/state`,
  `status/json`, `usage/json`) are not declared as a pair; they are dissolved into one
  `usage-wire` package (rule of three). The round commit subject is derived from the record,
  so no subject ↔ record pair arises. `report.sh` stops aggregating, so no script ↔ fold pair
  arises.

**D10 — Single-owner mechanisms.**

| Owner                                         | Value (type)                                                                    | Consumers                                                                                                                                               | Old way removed                                                                                                                                                                                                                                                                                                                                                                                    | Enforced by                                                                                                                                                                                                               |
|-----------------------------------------------|---------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `ResultFileReader` (`executor-contract`) | `ReadResult<T>` (typed, capped, `UntrustedText` fields) | `ProgramExecutor` (adapters), the describe preflight (D5 phase b), `ShellCommandCheckRunner` (adapters/check, through `CheckFindings`), `DecisionFileTransport` (adapters/agent), `BranchDecisionFile` (adapters/git), `SnapshotTipCheck` (adapters/git: the decision blob read from a snapshot on resume, through the live path's parse — crash-consistency item 14; added during task 3.2). The two decision-file media share one tolerant read, `DecisionRequestRead` (`application`, `app/port/agent`), so their cap and fallback agree by construction | Exactly three file reads today, each routed through the owner (a fourth read, the snapshot's decision blob on resume in `SnapshotTipCheck`, is a `git show` and is routed through the same tolerant read): `DecisionFileTransport.readAndClose` (`Files.readString`, no cap — the one uncapped read), `BranchDecisionFile:92` (`environment.readFile`, 1 MiB), `ShellCommandCheckRunner:190` (`environment.readFile`, 256 KiB); `FindingsFileReader` and `DecisionFileReader` deleted. `ExecutorRoundExecution` reads no file. Exempt: `WorkingTreeLawSource`, `EnvFileSecretSearch`, `AdHocTaskSynthesizer`, `OperatorFile`, `SnapshotReader`, `RoundScratchDirectory` (stderr tail), `ExecutableLookup` (`#!` line), `LedgerLineReader`, `DashboardResources`, the envelope `readBlob`/`git show` readers of `state.json`/`task.json` (not result paths); the environment implementations of `readFile` itself | `ResultPathReadBoundarySpec` in `:bootstrap`: allowlist of files that may call `readFile(`, `Files.readString(` or `Files.readAllBytes(` — and the stream and git-blob read forms — on a `GNOMISH_*`-derived or decision-file path, and of files that construct the owner's entry points (`new ResultFileReader(`, `ResultSource.hostFile(`, an implementation of `ResultSource`); scan asserts it reached every allowlisted file |
| `ExecutorOutcomeClassifier` (`domain/engine`) | `AttemptAccounting` (sealed: `Proceed` — a completed round with no answer, the verify chain decides; `BurnAttempt`, `Escalate(kind)`, `EndStage(kind)`) | `RoundExecution`, `StageAttemptLoop` | `StageAttemptLoop:137` builds `EscalationReport.CannotExecute` from the round outcome — moves into the classifier; `RoundExecution:126` builds `RoundOutcome.CannotExecute` from a thrown failure — becomes the classifier's input (a `failed/infrastructure` result); `ExecutorFailure` thrown by `ExecutorRoundExecution:161` becomes a `failed/infrastructure` result. Exempt as DTO mappers (they rebuild the domain value from the wire and decide nothing): `TaskEscalationMapper` (the escalation half of `TaskJsonMapper`, split out during task 4.2), `StateStopMapper` (`Stop.BudgetExhausted`), `StateJsonMapper` (`Verdict.CannotVerify` read back); exempt as a record mapping: `StopEscalation` (the escalation of a recorded stop); exempt as wire spelling: `RoundOutcomeWords` (the round status in the contract's tokens for the log and ledger line, task 4.5). Exempt until `migrate-verify-to-executor-contract`: `ShellCommandCheckRunner`, `FilesExistCheckRunner`, `ExternalPolling`, `VerifyOrchestrator:149`, `JudgeVerdictExtractor`, `JudgeCriteriaPreflight:76`, `JudgeCannotVerify:131` (check-side verdict construction) | parameter type: `StageAttemptLoop` takes `AttemptAccounting`, no `ExecutionResult` switch outside the classifier; grep gate `OutcomeClassifierOwnerSpec` in `:bootstrap`: `new EscalationReport.CannotExecute(`/`BudgetExhausted(`, `new Stop.BudgetExhausted(`, a read of an `ExecutionResult` status variant, `new Verdict.CannotVerify(` and `new ExecutorFailure(` → the classifier plus the allowlisted files, each tagged with its pickup |
| `ExecutorRegistry` (`bootstrap`) | `Map<ExecutorName, StageExecutor>` (as built in task 7.3: a `ProgramExecutor` is bound to one round's environment, so each entry builds it inside the round) | dispatching `StageExecutor` (`ExecutorDispatch`, assembled in `ExecutorAdapterSelector`); the describe preflight (D5 phase b) is its producer, not a consumer — it yields the answers (`DescribedLaw.answerOf`) each entry is built from, and reading the registry would be a cycle | `ExecutorAdapterSelector` unconditional Claude pick replaced by lookup; `command`/`agent-cli` arms exempt until `extract-agent-round-runner` | `ExecutorName` value type; unknown name is a `ConfigError` at load |
| `ProgramChannel` (`application`) | the operation words of a program (`describe`, `start`, `poll`) and the `GNOMISH_*` variable names it is handed (`RESULT_FILE`, `ANSWER_FILE`, `PENDING_KEY`); added 2026-11-01 with the poll wire form (FR23) | the describe preflight (D5 phase b, `ProgramDescribe`), `ProgramExecutor.start` and `ProgramExecutor.poll` (both through `ProgramSpawn`, the one spawn of an operation, task 8.1b); the kit's CLI reaches the program only through `ProgramExecutor`, never with an argv of its own | no operation word or `GNOMISH_*` program variable is spelled outside the owner; `ProgramExecutor.poll`'s unconditional refusal replaced by the spawn | `ProgramChannelOwnerSpec` in `:bootstrap`: each operation-word constant's literal and each program variable literal appears in exactly one production file (the `TerminalRenderOwnerSpec` shape), apart from a named list of files where the same word belongs to another vocabulary (docker's `start` subcommand, the `poll` capability token in `executor-contract`, a component name), each with its reason and asserted still matching (as built, task 8.1b) |
| `WireSchemas` (`executor-contract`) | the four schema files loaded once, and the verdict of a raw document against one (`WireViolation` list, each with the validator's path, keyword and message as `UntrustedText`; a text that is not one JSON value is one `NOT_JSON` violation); `document(kind)` hands a copy of a parsed schema file to the specs that read its `$id` and `examples` (as built, task 8.1c); added 2026-11-01 (FR24, D19) | the kit's `WireObservation` (`:bootstrap`), `WireSchemaRoundTripSpec` (which drops its own factory) | no second `JsonSchemaFactory` over the contract's files: `SettingsSchema` keeps its own for a program's settings schema (a different document, untrusted); the round-trip spec's local factory removed | `WireSchemaOwnerSpec` in `:bootstrap`: `JsonSchemaFactory.getInstance(` appears in exactly two production files, `SettingsSchema` and `WireSchemas`, and no test loads `schemas/executor-*.schema.json` except through the owner (four forms scanned in every test tree and `test-fixtures/src/main`; one exemption, `SettingsSchemaSpec`, whose `classpath:schemas/…` is a `$ref` a program writes and the loader refuses, not a read) |
| `Describe` (`executor-contract`) | `Describe` record (credentials, egress, schema, version) | the `program` child's credential grant — its **own resolved layer** built from `ExecutorCredentialNames` through the allowlist's `ChildEnvValues` (ADR 0017), resolved by `ChildEnvAllowlist.programGrant()` (`ProgramCredentialGrant`, which exposes no value-source accessor; added by task 6.4), never the allowlist's `declaring`/`credentialNames` set, which `compose` and `resolve` strip (`ChildEnvAllowlist:140,161`: that set is the *withheld* names); the describe preflight (D5 phase b); egress allowlist union (`ExecutorEgress.union`, from the law's declarations, checked against describe before the first round; host grammar single-owned by `EgressEntries` in `:sandbox:core`) | `AgentAiSeam` hard-coded names stay for `agent-cli` until `extract-agent-round-runner` (exempt, named); `AgentSettingsValidator` keeps the load-time key check of `agent-cli` stages and judge checks only — `agent-cli` has no describe until `extract-agent-round-runner` (exempt, named); `program` settings are judged by `SettingsSchema` (`executor-contract`) alone; no `System.getenv` and no `ChildEnvAllowlist.of` without a value source | `credentialEnvVars` union takes `Describe`-derived names only for `program` executors; identity spec: the names the law grants equal the names the child environment receives, and a sibling `command` check receives none (`ProgramCredentialDeliverySpec`, real box) |
| Describe preflight (`RunAssembly.describe`, `application`; added by task 6.2) | `LawDescription` (sealed: `DescribedLaw` carrying `DescribedExecutors`, or `Refused(List<ConfigError>)`) | every run assembly: take host and container, fresh and resume; `run` git-mode host, container, in-place, resume (serve slots through the take paths); `EnginePorts` and the round record take the obtained versions from `DescribedLaw` | `RunAssembler`'s `DescribedExecutors.none()` removed; `RunAssembly.assemble` no longer takes a `LawBinding` | `assemble(DescribedLaw, …)` parameter type with a package-private `DescribedLaw` constructor; `DescribedLawOwnerSpec` (`:bootstrap`) pins `new DescribedLaw(` and `DescribedExecutors.none()` in production to the owner; identity spec `ExecutorVersionPinFlowSpec` (bare origin) |

| `UsageFold` (`domain/engine`) | `UsageSnapshot` (role × model × kind, cost lower bound, `rounds`, `through`) | `TaskState` round writers (`recordRound`, `recordUnburnedRound`, `recordPassAndAdvance`), `UsageHistoryWalker` / `UsageCommand` (re-fold check), `ExecutorOutcomeClassifier` (budget), `UsageTextRenderer` / `UsageMarkdownRenderer` (display sums), the ledger's `taskOutcome` line and run summary (`TaskSummaryAssembler:75`, `RunSummaryAccumulator:70`, `SummaryAccumulatorListener:104` take the snapshot's per-model map — consumers of the fold, never a second sum) | `TaskState.totals.plus(executorUsage)` and `ExecutorUsage.plus`; `UsageTotals.of` deleted; `report.sh` jq `add_tokens` deleted; `UsageTextRenderer`'s own per-row sums routed through the fold. Exempt, named: `LedgerAggregator.sum` (`dashboard`, `:141-158`) adds the ledger lines' own `tokensByModel` for the dashboard's day view over JSON with no domain types — a display over already-folded snapshots, the one `merge(` the gate allowlists | `UsageFoldOwnerBoundarySpec` in `:bootstrap`: no `TokenUsage::plus`, `TokenUsage.plus(`, cost addition, `tokensByModel().merge(` or `LedgerTokenUsage` summation outside `UsageFold` and the allowlisted `LedgerAggregator`; `.gnomish/stages/**` scripts contain no `jq` `reduce` over `tokensByModel`; scan asserts every allowlisted file reached |
| `AttemptRecord` identity (`CurrentRound` → engine → record) | `RoundToken`, `taskId`, `stage` on `AttemptRecord` | `ServiceCommitMessages.round`, `UsageHistoryWalker`, `TraceLineWriter.relativePath`, `UsageFold` (`through`) | `grewFrom` and the `position` read in `UsageHistoryWalker.detectNewRound`; `ServiceCommitMessages.round(String, int)`; `HostRoundEnvironmentSource` opening a round with no token | parameter type: `round(AttemptRecord)`; `UsageHistoryWalker` has no `position()` call on the attribution path (grep gate); `CurrentRound` holds a token in both branch modes, in-place none (spec on the real host flow) |
| `usage-wire` DTOs (`executor-contract`) | `ParticipantUsageDto`, `UsageSnapshotDto` | `StateJsonMapper` (adapters/git), `StatusReportJsonMapper` (application/status), `UsageReportJsonMapper` (application/usage), all through the one domain ↔ wire mapper `ParticipantUsageWire` (`application`, `usage.wire`; the contract cannot see the domain); the engine's `ExecutionFinished` event carries the domain `ParticipantUsage` (added during task 5.2) | `StateUsageMapper`, `status/json/UsageMapper`, `usage/json` per-tree copies deleted | one package; `values()` round-trip spec over every wire token; `status-report-v1.reference.json` regenerated from the new shape |

Identity claimed: "Java DTOs and JSON schema are one" → `WireSchemaRoundTripSpec` validates
every DTO sample against the schema files and every schema example into the DTOs. Identity
claimed: "the fold of the walked rounds and the tip's `totals` are one" →
`UsageSnapshotIdentitySpec` on a bare origin with the real adapters (D15).

**D11 — Crash consistency.** No new durable step: the executor's name and model are law
content, pinned by the law pin at creation; its describe version is pinned in the task
state: `TaskState.executorVersions` holds, per executor name, the version of the first round
that ran it, written by the round writer in that round's own commit and carried unchanged by
every lifecycle rewrite (advancement, decision reset, resume), as `totals` is. The attempts list
cannot carry it: `advanceTo` and `resetAttempts` empty it, so a judgement against it alone would
skip every resume after a park (amended during task 4.4). The engine pre-flight, before any
round, compares the pin with the version the describe preflight obtained (`ExecutorVersionCheck`)
and escalates `PipelineMismatch` naming the executor and both versions. *Rejected:* walking
`baseCommit..tip` per run to collect the versions, which D15 already rejects for resume. The describe preflight writes nothing durable: its two outcomes are the
parked result of an invalid law and the operator-precondition result, each already a shape of
its medium (ADR 0019). `pending` keys are not persisted in this change (NG7); `migrate-verify-to-
executor-contract` designs that window. Result files live in the scratch area and are read
before the attempt commit; a kill between read and commit re-runs the round, which is the
existing behaviour for `Completed`. The folded `totals` and, when the executor reports a
`limit` stop, that stop ride the round's own commit (D4, D15; item 12): no window separates "the
round is recorded" from "the stage is stopped". Recovery that re-folds a round already named by
`through` is a no-op (item 8). The round token in host mode is minted by the round source and
carried in `CurrentRound` exactly as in container mode (item 14: one producer per path, the same
parse).

**D12 — Lock scope.** No lock is added. The registry is built once at wiring and is
immutable; describe runs in the preflight on the run's own thread, before the engine starts,
and holds no lock across the child.

**D13 — The round record is self-identifying; readers never infer.** (FR14, FR19.)
`AttemptRecord` gains `taskId`, `stage` and `roundToken`, stamped by the engine from the run's
`CurrentRound` and the attempt key; `ServiceCommitMessages.round` takes the record and renders
the subject from it, so the subject and the record cannot spell different stages.
`UsageHistoryWalker` loses `grewFrom` and every read of `position` on the attribution path:
the stage is the record's. The walk is bounded to `baseCommit..register` (the factory register, the walk's end under `tighten-spec-contract` D13; `baseCommit` read from `task.json` at the register's envelope revision, the Completed commit when the register is a cleanup) on the first-parent line
(`baseCommit` is the identity the orchestrator recorded at creation; `merge-base` recomputes
and moves after a squash merge; the first-parent line drops what "merge main into the task
branch" brought in), and a record whose `taskId` is not the task's is rejected with a warning —
the content check is the second defence, independent of the range. *Rationale:* every surveyed
engine (Temporal, Airflow, Buildkite, Argo, Dagster) keys a record by (unit, step, attempt) at
write time and none infers the step from neighbouring records; CloudEvents makes `id`/`source`
mandatory for the same reason; `crash-consistency.md` items 13–14 say it for this project.
*Alternative rejected:* repairing the heuristic to `previous.position()` — it is right today
(every position change is itself a state commit), but it is a derivation from an invariant
`TaskRepository` owns, and the next lifecycle write that moves the position without a commit
breaks it silently; the record is the owner.

**D14 — One usage shape per executor invocation, keyed by the contract role.** (FR15, FR16,
FR21.) D4's `Usage` is the one type. A round holds `List<ParticipantUsage>`: role
(`transform` | `verdict` | `decision` | `effect`), executor name, vote index for `verdict`,
provenance, optional wall time and tool aggregates, `Map<model, ModelUsage(tokens, cost?)>`.
"Gnome", "judge" and "arbiter" are render labels for the roles, not record vocabulary. The
three DTO trees collapse into one `usage-wire` package in `executor-contract`, consumed by the
state, status and usage mappers (the rule of three; this absorbs `add-decision-arbiter`'s D10
and tasks 7.1–7.2, which are withdrawn there). Git-mode host rounds mint a round token
(`MidRoundPushRounds` over the worktree tip, the same `RoundToken.of` parse; D13), so both
branch modes stamp the same identity; this is a third minting producer of `RoundToken.of` beside
the two `CurrentRoundWriterSpec` admitted (`SandboxRoundEnvironmentSource` minting,
`SnapshotTipCheck` reusing), with `StateJsonMapper` re-parsing the recorded token as the record's
reader: that allowlist widens to `MidRoundPushRounds` and `StateJsonMapper`, the glossary's "Round
token" entry records both modes and the producers (the archived `make-checkpoint-gate-durable` is
not edited), and the declared source pair gets a mirrored edit, with `MidRoundPushRounds` ↔
`SandboxRoundEnvironmentSource` declared beside it. *Rationale:* a role-named slot per
participant (`executorUsage`, `judgeUsage`, `arbiterUsage`) grows one field and one DTO tree
per role; a list keyed by the contract's role grows nothing, and every aggregation (by stage,
role, model, all) is a fold over it. Cost sits beside tokens per model because that is the
grain the CLI reports (`modelUsage[].costUSD`) and the grain any cross-cut sums at.
*Alternatives rejected:* (a) role-named slots — the fourth tree; (b) a new "stage visit"
counter as the record identity — a new identity to mint when the round token already is one
per round; (c) `taskId + stage + round + startedAt` — the round number restarts per visit and
a timestamp is not an identity the orchestrator minted (item 13). The token exists in every
branch mode and is absent in in-place mode, which needs no git (`cli/manual-run`, "In-place mode
needs no git") and persists nothing. `RoundToken` moves into `domain/engine` so the engine can
stamp it, read after the round through a domain port the round source fills; the git-mode host
decoration, `MidRoundPushRounds` (the one per-run host piece holding git, applied only on the
git paths), mints it over the worktree tip and owns the run's `CurrentRound`; the bare host
source is the in-place source and names no token (amended during task 5.1).
*Rejected:* a non-commit id for in-place — a `RoundToken` that is not a commit misleads every
reader that takes it for one.

**D15 — `totals` is a snapshot of one fold, checked, with a position.** (FR17, FR18, NFR-O3.)
`UsageFold` in `domain/engine` is the one pure fold: `fold(snapshot, round)` and
`foldAll(rounds)`. `TaskState`'s round writers (`recordRound`, `recordPassAndAdvance`, the
unburned variant) compute `totals = fold(totals, round)` through it; the lifecycle rewrites
(advance, decision reset, resume) carry the snapshot unchanged. The snapshot is role × model ×
token kind, `cost = {priced, unpricedTokens (role × model), complete}`, `rounds` (count folded)
and `through` (the last folded round token). `through` is the fold's idempotency key: folding a
round whose token equals `through` is a no-op, so a re-persisted round after a kill cannot
double-count; a round with no token — in-place only — is folded without a key, since nothing in
that mode is re-persisted. `gnomish usage` re-folds the walked rows and errors when the result differs from
the tip's `totals`; `UsageSnapshotIdentitySpec` asserts the identity on a bare origin after a
pass, a failure, a decision reset and a resume. Tokens are never null; cost is a lower bound and
the renderers say so. An `ABSENT` entry makes `complete` false as an unpriced model does, with nothing added to
`unpricedTokens`: an unreported round has an unknown cost, and a complete figure over it would
read that unknown as zero and silence a money bound once one exists (NG12; decided during task 5.4). *Rationale:* three readers have nothing but the tip — `status` (one
`git show` by spec), a resume on another instance, a budget check after every round (NG12) — so the
figure must be recorded (TigerBeetle keeps `debits_posted` on the account and checks limits
against it; LiteLLM checks budgets against stored spend); the fold is the only truth (Fowler:
state is derivable from the log; Kurrent: a snapshot is not the foundation); and a recorded
figure with nothing to compare against is the one-balance-column failure that let the jq
collapse pass unnoticed (Stripe's clearing metric exists so one wrong row is detectable).
*Alternatives rejected:* derive-only — `status` would need a history walk and the budget could
not be enforced on resume; record-only — the defect this change fixes.

**D16 — Rendering belongs to the typed program.** (FR20, G5, M6.) `UsageMarkdownRenderer` in
`application` beside `UsageTextRenderer`, behind `gnomish usage --markdown`; the usage-report
stage's `report.sh` runs it and posts the output, aggregating nothing. The stage now needs the clone's factory launcher, so it runs under the host binding only (a box
has no launcher and the script exits naming it); the project's one binding is host (noted
during task 5.7). *Rationale:* the jq
program was a second reader of `usage.json` with no round-trip spec — exactly what
`testing.md`'s wire-vocabulary rule forbids — and its parameter semantics (`def f(a; b)` binds
filters, not values) produced the collapsed sum. *Alternative rejected:* keeping the jq
program under `jq --run-tests` and a bats suite — a second aggregation to keep in step with
the fold, a `jq` dependency in the sandbox image, and no mutation gate.

**D17 — Budget enforcement by the factory is out of this change.** (NG12.) Cut during task 5.8:
the law declares no budget (`AutonomyLimits(int attemptLimit)`, its javadoc citing NG8 of the
stage engine), the stable `pipeline/pipeline-config` text keeps monetary budgets out of the
project's scope, no task of this change adds a declaration, and the snapshot counts no turns.
What stays: an executor that stops itself at a bound reports `failed/limit`, which the
classifier escalates as `BudgetExhausted` with the round recorded on its own commit (D4), and
the snapshot already marks an unknown cost (`complete = false`) for the follow-up to read.
The research carried to that follow-up: a money bound fails closed on an incomplete cost
(LiteLLM treats a missing price as "not free"), and its verdict rides the round's commit
(`crash-consistency.md` item 12). *Alternative rejected:* building the check over a bound
nothing fills — a budget that can never fire while every spec stays green.

**D18 — The reference executor and the misbehaving fake are stand-in presets.** (FR12, M1;
ADR 0015.) Both are sections of a new table `test-fixtures/src/main/resources/stand-in/tables/
executor.params`, run by the one interpreter `stand-in.sh` and selected through `StandIn`
(`StandIn.executor('reference')`, `StandIn.executor('break-<case>')` — one preset per broken
case, taking the case from its link's name, never a marker file). The reference preset answers
`describe`, `start` for the three live roles and `pending` then `poll`; it depends on no factory
jar. *Revised 2026-11-01 before task 8.2:* the preset's `cancel` answer and its `cancel`
capability are removed, and the `break-cancel` preset with them — the contract defines no cancel
operation (D2, NG16), so the kit cannot drive the case through `Executor` nor in process, and a
reference that answered it would demonstrate a wire form the contract does not name. The kit keeps
twelve cases (M4): `cancel`'s place is taken by `unknown-role` — the kit requests a role the
candidate's describe does not list, and the case passes when the candidate writes no `completed`
result for it (the reference refuses with exit 2 and a stderr line; `break-unknown-role` answers
`completed`). The `pending` then `poll` case needs the candidate to go pending, which the kit
cannot ask of an arbitrary settings schema: the case runs only when the describe declares `poll`,
with the settings the command's `--pending-settings` option carries (the reference's
`{"defer":true}`), and is reported not applicable otherwise, as an undeclared role is; the
in-process double of task 8.2a goes pending by construction. *Alternative rejected:* a reserved
request field or variable every executor must honour to go pending — a conformance-only
obligation on the production wire; the setting stays the candidate's own. The packaged-jar end-to-end suite copies the committed library into its sandbox image
(`ReferenceExecutorSandboxImage`), since the factory has no setting for an extra mount (as built, task 8.3). Behavioural specs of `ProgramExecutor` (silence bound,
malformed result, oversized stderr, stray stdout) drive `FakeProcess` through `ProcessLaunch`,
asserting no scheduling order (`testing.md`). *Alternative zero:* nothing generated per test —
what varies per case is the case name, carried by the link. *Alternatives rejected:* a
committed shell script under `test-fixtures/.../executor/` with its own shebang — the shape
`StandInOwnerSpec` fails on outside `stand-in/` and the exemption list, with no reason the list
accepts; a fake "with case switches" written per spec — the per-test generation ADR 0015
removed. Executor authors outside the repository (U2) run the kit against their own program; the
reference executor is the factory's test subject, not a shipped artefact.

**D19 — The kit judges the wire at the execution-environment port.** (FR12, FR24, M4; added
2026-11-01 before task 8.2.) Every document a `program` candidate writes reaches the factory
through `TaskExecutionEnvironment.readFile` on a path the adapter handed the child in
`GNOMISH_RESULT_FILE` or `GNOMISH_ANSWER_FILE`, and every request leaves through
`TaskExecutionEnvironment.exec` as the command's standard input. The kit wraps the host
environment it builds per case in one decorator, `WireObservation` (`:bootstrap`, the kit's
package): on `exec` it notes, from the command's own variables (the `ProgramChannel` constants,
never a parsed path), which path will hold a describe (the operation word is `describe`), a
result or an answer, and for `start` and `poll` it rewrites the standard input once, adding the
probe field `x-gnomish-conformance-probe` to the rendered request tree; on `readFile` of a noted
path it judges the raw bytes against the matching schema file through `WireSchemas` and keeps the
verdict for the case; every other call passes through unchanged (the working-copy check's `git
status` among them). `WireSchemas` (`executor-contract`, beside `SettingsSchema`, the same
networknt factory with fetching disallowed) is the one place the four schema files are loaded
and a document is judged against one; `WireSchemaRoundTripSpec` moves onto it. A case fails on
a wire violation even when the adapter's lenient read produced a result; the detail printed is
the validator's first message. *Alternative zero:* judge the DTOs only — a candidate whose
describe the schema refuses passes the kit and runs in production as `PROMPT_ONLY`; the case
`describe` could not go red (M4) and the duty the kit delta promises stops silently. *Rejected:*
observation seams inside the adapter (a raw-document listener on `ResultFileReader`, a
request-rendering seam in `ProgramSpawn`, both threaded through `ProgramRoundEquipment` and the
preflight's constructor) — the same observation, obtained by widening three production
signatures for one consumer, where the port already carries every byte; any-fields and raw nodes
on the DTOs — touches every reader and the drift spec, and makes the lenient arms the kit's
problem; the kit spawning the program with an argv of its own — D2 and the `ProgramChannel`
row. *What the kit builds per run:* a scratch root with one directory per case, each initialised
as a git repository through the factory's `GitRunner` (the working-copy check fails closed on a
non-repository, and the fake breaks a case by the request's workspace path ending in
`/<case>`), one `WireObservation` over one host environment and one `ProgramExecutor` per case
(the pending case starts and polls on the same instance, `PendingRuns` is per instance), and a
synthetic law — a `PipelineDefinition` with one declaration named `candidate` (the command's
argv, the `--pending-settings` object or `{}` as its settings, no credentials, no egress) and no
stages — for the describe preflight, which also carries the settings-schema case. *In process*
(task 8.2a): the four cases that exist only on the wire — unknown request fields, oversized
output, stderr noise, non-JSON stdout — are reported not applicable, since no document and no
process exist to break; the describe case judges the double's `Describe` written back through
the contract's writer against the describe schema, so a double that declares no role fails it.

- [The contract is defined before its heaviest consumer (Claude) uses it] → the `program`
  executor and the kit exercise every field now; `extract-agent-round-runner` is the next
  change in the sequence, and D9 names its exemptions so they cannot be forgotten.
- [The describe preflight spawns a process per declared executor per run] → bounded by the
  startup read timeout; cached for the run by law commit; it runs once per claimed run, in the
  box the run already owns.
- [JRE or runtime needed in the image for a Java-written executor] → the reference executor
  is a POSIX stand-in preset (D18); image contents belong to the sandbox image contract
  (`add-sandbox-base-image`, archived).
- [SARIF is verbose for a pass/fail check] → the reader accepts a minimal SARIF with one
  run and zero results; richer formats normalize at the edge.
- [Two taxonomies during the transition (check verdicts vs executor status)] → the exemption
  rows are explicit and expire with a named change; `/audit-implementation` checks them.
- [The change grows by the usage work (D13–D17)] → the usage group is self-contained (one
  task group with its own gate specs) and sits on D4's `Usage`, which it would otherwise have
  to redefine later; the cut line, if the change overruns, was D16–D17 (Markdown rendering and
  the money budget); D17 was cut during task 5.8 (NG12), D16 shipped.
- [`status-report-v1.reference.json` and the reference dumps are regenerated] → the status
  equivalence spec and the scrubber spec are re-anchored in the same tasks; the versions stay 1
  as pre-release amendments.

## Migration Plan

1. Land after `fix-docker-exec-env-argv`; `add-command-executor` follows and rebases onto
   the registry and the `failed/quality` status.
2. Ship the module, schemas, reader, classifier and kit; wire the `program` executor;
   migrate the two existing readers onto the owner.
3. Follow-ups in order: `add-command-executor`, `migrate-verify-to-executor-contract`, `extract-agent-round-runner`,
   `harden-verification-integrity`; `add-decision-arbiter` rebases onto the answer slot.
   Rollback: the `program` type is additive; removing it restores today's behaviour.

## Open Questions

- Pinning an external program's binary by path or by image digest (proposal Q2) — open, with
  the pickup condition stated there. This change does not decide it: the pin it writes is the
  law's executor name and model plus the describe `version` recorded by the first round (FR13,
  D11); the binary itself is reached only through the image. `add-sandbox-base-image` (archived)
  decided nothing about program pinning.
