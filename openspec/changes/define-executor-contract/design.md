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
  `OperatorPreconditionRefusals`, with the first box made live before the engine runs.
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
  and NFR-C2/C3 address these; the research behind D13–D17 is summarized in their rationale.

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

**D4 — Status plus answer, with `limit` as a third failure class.** (FR2, FR3, FR7.)
`ExecutorResult = Status(completed | failed(class) | pending(key)) + Optional<Answer> +
Usage(provenance) + List<Denial>`. The engine's `ExecutionResult` becomes an alias of this
shape; `DecisionNeeded` is `completed` + `decision-request`; `failed/quality` is new: a
recorded round, attempt burned, findings fed forward, verify chain skipped (the round produced
no product worth verifying — the `DecisionNeeded` precedent).
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
run by law commit. Two failure classes, decided by the classifiers that already exist: a
program that is absent from the image or cannot answer `describe` (exit non-zero, oversized or
malformed output) is an **operator precondition** — `OperatorPreconditionRefusals` classifies
it, the run ends on `TakeResult.OperatorPreconditionUnmet`, exit 8, no attempt burned, no
abort accounting (ADR 0019); a `describe` that disagrees with the law — version mismatch,
undeclared role, settings failing the declared schema, undeclared credential or egress host, a
money bound on an executor that reports no usage — is a **located law error**, `LoadOutcome.
Invalid` carried to the same parked result `TaskTierLaw.bind` yields for an invalid law
(`TaskTierLaw:114`), so the task parks with the located message and no round runs. The engine
never sees a describe failure as a round. `gnomish doctor` does not run `describe` (proposal
NG11). *Rationale:* ACP and go-plugin bump an integer only on breaking change and add
everything else as capabilities; Buildkite and Terraform validate plugin config from a
plugin-supplied schema before work starts; the box is the only place the program exists, and
ADR 0019 already puts the first box before the engine for exactly this kind of check.
*Alternatives rejected:* semver or date strings (MCP) — needs range logic for no benefit at
this scale; `describe` at law load (the first draft of this decision) — refuted by the code:
the law is bound at `TakeContainerFreshClaim:84` and the box exists only at `:111`, so the
program is not reachable at that moment, and running it on the host instead would run a
program the image ships from a machine that does not have it; a second failure classifier
for describe — two spellings of ADR 0019's one class.

**D6 — Abstract policy, compiled by the executor, verified by the factory.** (FR11, NFR-S4.)
Request carries `readOnly | writeExactly(path) | workspaceWrite`; describe declares an
enforcement level; after `verdict`/`decision` the factory runs a working-copy-unchanged
check through the environment (`git status --porcelain` over the box) and refuses the
answer on change. *Rationale:* "declaration + verification" is how the sandbox passports
already work; a vendor tool name (`Read,Grep,Glob`) is not a policy the core can reason
about. *Alternative rejected:* trusting the declared level — a self-declared passport is
what the plugin README refuses for the sandbox port.

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
- *Old-way exemption, time-boxed:* `CliStageExecutor`/`CliJudgeVoter` keep constructing
  `Verdict.CannotVerify`/`ExecutorFailure` until `extract-agent-round-runner`; the
  exemption list is the single-owner table below and the follow-up name is written on it.
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
| `ResultFileReader` (`executor-contract`) | `ReadResult<T>` (typed, capped, `UntrustedText` fields) | `ProgramExecutor` (adapters), the describe preflight (D5 phase b), `ShellCommandCheckRunner` (adapters/check), `DecisionFileTransport` (adapters/agent), `BranchDecisionFile` (adapters/git) | Exactly three reads today, each routed through the owner: `DecisionFileTransport.readAndClose` (`Files.readString`, no cap — the one uncapped read), `BranchDecisionFile:92` (`environment.readFile`, 1 MiB), `ShellCommandCheckRunner:190` (`environment.readFile`, 256 KiB); `FindingsFileReader` and `DecisionFileReader` deleted. `ExecutorRoundExecution` reads no file. Exempt: `WorkingTreeLawSource`, `EnvFileSecretSearch`, `AdHocTaskSynthesizer`, `OperatorFile`, `SnapshotReader` (not result paths); the environment implementations of `readFile` itself | `ResultPathReadBoundarySpec` in `:bootstrap`: allowlist of files that may call `readFile(`, `Files.readString(` or `Files.readAllBytes(` on a `GNOMISH_*`-derived or decision-file path; scan asserts it reached every allowlisted file |
| `ExecutorOutcomeClassifier` (`domain/engine`) | `AttemptAccounting` (sealed: `BurnAttempt`, `Escalate(kind)`, `EndStage(kind)`) | `RoundExecution`, `StageAttemptLoop` | `StageAttemptLoop:137` builds `EscalationReport.CannotExecute` from the round outcome — moves into the classifier; `RoundExecution:126` builds `RoundOutcome.CannotExecute` from a thrown failure — becomes the classifier's input (a `failed/infrastructure` result); `ExecutorFailure` thrown by `ExecutorRoundExecution:161` becomes a `failed/infrastructure` result. Exempt as DTO mappers (they rebuild the domain value from the wire and decide nothing): `TaskJsonMapper:263`, `StateJsonMapper:233`. Exempt until `migrate-verify-to-executor-contract`: `ShellCommandCheckRunner`, `FilesExistCheckRunner`, `ExternalPolling`, `VerifyOrchestrator:149`, `JudgeRoundExecution`, `JudgeVerdictExtractor`, `JudgeCriteriaPreflight:76`, `JudgeCannotVerify:131` (check-side verdict construction) | parameter type: `StageAttemptLoop` takes `AttemptAccounting`, no `ExecutionResult` switch outside the classifier; grep gate `new EscalationReport.CannotExecute(` → the classifier plus the allowlisted DTO mappers, nothing else |
| `ExecutorRegistry` (`bootstrap`) | `Map<ExecutorName, Executor>` | dispatching `StageExecutor` (`ExecutorAdapterSelector`), the describe preflight (D5 phase b) | `ExecutorAdapterSelector` unconditional Claude pick replaced by lookup; `command`/`agent-cli` arms exempt until `extract-agent-round-runner` | `ExecutorName` value type; unknown name is a `ConfigError` at load |
| `Describe` (`executor-contract`) | `Describe` record (credentials, egress, schema, version) | the `program` child's credential grant — its **own resolved layer** built from `ExecutorCredentialNames` through the allowlist's `ChildEnvValues` (ADR 0017), never the allowlist's `declaring`/`credentialNames` set, which `compose` and `resolve` strip (`ChildEnvAllowlist:140,161`: that set is the *withheld* names); the describe preflight (D5 phase b); egress allowlist union | `AgentAiSeam` hard-coded names stay for `agent-cli` until `extract-agent-round-runner` (exempt, named); no `System.getenv` and no `ChildEnvAllowlist.of` without a value source | `credentialEnvVars` union takes `Describe`-derived names only for `program` executors; identity spec: the names the law grants equal the names the child environment receives, and a sibling `command` check receives none (`ProgramCredentialDeliverySpec`, real box) |

| `UsageFold` (`domain/engine`) | `UsageSnapshot` (role × model × kind, cost lower bound, `rounds`, `through`) | `TaskState` round writers (`recordRound`, `recordUnburnedRound`, `recordPassAndAdvance`), `UsageHistoryWalker` / `UsageCommand` (re-fold check), `ExecutorOutcomeClassifier` (budget), `UsageTextRenderer` / `UsageMarkdownRenderer` (display sums), the ledger's `taskOutcome` line and run summary (`TaskSummaryAssembler:75`, `RunSummaryAccumulator:70`, `SummaryAccumulatorListener:104` take the snapshot's per-model map — consumers of the fold, never a second sum) | `TaskState.totals.plus(executorUsage)` and `ExecutorUsage.plus`; `UsageTotals.of` deleted; `report.sh` jq `add_tokens` deleted; `UsageTextRenderer`'s own per-row sums routed through the fold. Exempt, named: `LedgerAggregator.sum` (`dashboard`, `:141-158`) adds the ledger lines' own `tokensByModel` for the dashboard's day view over JSON with no domain types — a display over already-folded snapshots, the one `merge(` the gate allowlists | `UsageFoldOwnerBoundarySpec` in `:bootstrap`: no `TokenUsage::plus`, `TokenUsage.plus(`, cost addition, `tokensByModel().merge(` or `LedgerTokenUsage` summation outside `UsageFold` and the allowlisted `LedgerAggregator`; `.gnomish/stages/**` scripts contain no `jq` `reduce` over `tokensByModel`; scan asserts every allowlisted file reached |
| `AttemptRecord` identity (`CurrentRound` → engine → record) | `RoundToken`, `taskId`, `stage` on `AttemptRecord` | `ServiceCommitMessages.round`, `UsageHistoryWalker`, `TraceLineWriter.relativePath`, `UsageFold` (`through`) | `grewFrom` and the `position` read in `UsageHistoryWalker.detectNewRound`; `ServiceCommitMessages.round(String, int)`; `HostRoundEnvironmentSource` opening a round with no token | parameter type: `round(AttemptRecord)`; `UsageHistoryWalker` has no `position()` call on the attribution path (grep gate); `CurrentRound` holds a token in both modes (spec on the real host flow) |
| `usage-wire` DTOs (`executor-contract`) | `ParticipantUsageDto`, `UsageSnapshotDto` | `StateJsonMapper` (adapters/git), `StatusReportJsonMapper` (application/status), `UsageReportJsonMapper` (application/usage) | `StateUsageMapper`, `status/json/UsageMapper`, `usage/json` per-tree copies deleted | one package; `values()` round-trip spec over every wire token; `status-report-v1.reference.json` regenerated from the new shape |

Identity claimed: "Java DTOs and JSON schema are one" → `WireSchemaRoundTripSpec` validates
every DTO sample against the schema files and every schema example into the DTOs. Identity
claimed: "the fold of the walked rounds and the tip's `totals` are one" →
`UsageSnapshotIdentitySpec` on a bare origin with the real adapters (D15).

**D11 — Crash consistency.** No new durable step: the executor's name and model are law
content, pinned by the law pin at creation; its describe version is recorded in the round
record (the `executor=<name>@<version>` of UX3), so it rides the round's own commit, and a
later `describe` that reports a different version is judged against that record
(`PipelineMismatch`). The describe preflight writes nothing durable: its two outcomes are the
parked result of an invalid law and the operator-precondition result, each already a shape of
its medium (ADR 0019). `pending` keys are not persisted in this change (NG7); `migrate-verify-to-
executor-contract` designs that window. Result files live in the scratch area and are read
before the attempt commit; a kill between read and commit re-runs the round, which is the
existing behaviour for `Completed`. The folded `totals` and, when a bound is crossed, the
`limit` verdict ride the round's own commit (D15, D17; item 12): no window separates "the round
is recorded" from "the budget is exhausted". Recovery that re-folds a round already named by
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
the stage is the record's. The walk is bounded to `baseCommit..tip` on the first-parent line
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
and tasks 7.1–7.2, which are withdrawn there). Host rounds mint a round token
(`HostRoundEnvironmentSource` over the worktree tip, the same `RoundToken.of` parse), so both
modes stamp the same identity; this is a third producer of `RoundToken.of` beside the two
`CurrentRoundWriterSpec` admits today (`SandboxRoundEnvironmentSource` minting, `SnapshotTipCheck`
reusing): that allowlist widens to `HostRoundEnvironmentSource`, the glossary's "Round token"
entry records both modes and three producers (the archived `make-checkpoint-gate-durable` is
not edited), and the declared source pair gets a mirrored edit. *Rationale:* a role-named slot per
participant (`executorUsage`, `judgeUsage`, `arbiterUsage`) grows one field and one DTO tree
per role; a list keyed by the contract's role grows nothing, and every aggregation (by stage,
role, model, all) is a fold over it. Cost sits beside tokens per model because that is the
grain the CLI reports (`modelUsage[].costUSD`) and the grain any cross-cut sums at.
*Alternatives rejected:* (a) role-named slots — the fourth tree; (b) a new "stage visit"
counter as the record identity — a new identity to mint when the round token already is one
per round; (c) `taskId + stage + round + startedAt` — the round number restarts per visit and
a timestamp is not an identity the orchestrator minted (item 13).

**D15 — `totals` is a snapshot of one fold, checked, with a position.** (FR17, FR18, NFR-O3.)
`UsageFold` in `domain/engine` is the one pure fold: `fold(snapshot, round)` and
`foldAll(rounds)`. `TaskState`'s round writers (`recordRound`, `recordPassAndAdvance`, the
unburned variant) compute `totals = fold(totals, round)` through it; the lifecycle rewrites
(advance, decision reset, resume) carry the snapshot unchanged. The snapshot is role × model ×
token kind, `cost = {priced, unpricedTokens (role × model), complete}`, `rounds` (count folded)
and `through` (the last folded round token). `through` is the fold's idempotency key: folding a
round whose token equals `through` is a no-op, so a re-persisted round after a kill cannot
double-count. `gnomish usage` re-folds the walked rows and errors when the result differs from
the tip's `totals`; `UsageSnapshotIdentitySpec` asserts the identity on a bare origin after a
pass, a failure, a decision reset and a resume. Tokens are never null; cost is a lower bound and
the renderers say so. *Rationale:* three readers have nothing but the tip — `status` (one
`git show` by spec), a resume on another instance, the budget check after every round — so the
figure must be recorded (TigerBeetle keeps `debits_posted` on the account and checks limits
against it; LiteLLM checks budgets against stored spend); the fold is the only truth (Fowler:
state is derivable from the log; Kurrent: a snapshot is not the foundation); and a recorded
figure with nothing to compare against is the one-balance-column failure that let the jq
collapse pass unnoticed (Stripe's clearing metric exists so one wrong row is detectable).
*Alternatives rejected:* derive-only — `status` would need a history walk and the budget could
not be enforced on resume; record-only — the defect this change fixes.

**D16 — Rendering belongs to the typed program.** (FR20, G5, M6.) `UsageMarkdownRenderer` in
`application` beside `UsageTextRenderer`, behind `gnomish usage --markdown`; the usage-report
stage's `report.sh` runs it and posts the output, aggregating nothing. *Rationale:* the jq
program was a second reader of `usage.json` with no round-trip spec — exactly what
`testing.md`'s wire-vocabulary rule forbids — and its parameter semantics (`def f(a; b)` binds
filters, not values) produced the collapsed sum. *Alternative rejected:* keeping the jq
program under `jq --run-tests` and a bats suite — a second aggregation to keep in step with
the fold, a `jq` dependency in the sandbox image, and no mutation gate.

**D17 — A money budget fails closed on incomplete cost.** (NFR-C2, NFR-C3.) The classifier
reads the folded snapshot after the round; a money bound with `complete = false` yields
`failed/limit` with reason "cost unknown", and the verdict lands in the round's own commit
with the snapshot (item 12 of `crash-consistency.md`: a pickup that sees the round and nothing
after it must not be allowed another paid round). Token and turn bounds read the same snapshot
and are unaffected by cost completeness. *Rationale:* LiteLLM treats a missing price as "not
free"; a budget checked against a lower bound is a budget that cannot fire. *Alternative
rejected:* warn and continue on token bounds — the money budget goes silent exactly where it
is supposed to speak.

**D18 — The reference executor and the misbehaving fake are stand-in presets.** (FR12, M1;
ADR 0015.) Both are sections of a new table `test-fixtures/src/main/resources/stand-in/tables/
executor.params`, run by the one interpreter `stand-in.sh` and selected through `StandIn`
(`StandIn.executor('reference')`, `StandIn.executor('break-<case>')` — one preset per broken
case, taking the case from its link's name, never a marker file). The reference preset answers
`describe`, `start` for the three live roles, `pending` then `poll`, and `cancel`; it depends on
no factory jar. The packaged-jar end-to-end suite mounts the library into the box, as
`ContainerGitMechanicsSpec` does. Behavioural specs of `ProgramExecutor` (silence bound,
malformed result, oversized stderr, stray stdout) drive `FakeProcess` through `ProcessLaunch`,
asserting no scheduling order (`testing.md`). *Alternative zero:* nothing generated per test —
what varies per case is the case name, carried by the link. *Alternatives rejected:* a
committed shell script under `test-fixtures/.../executor/` with its own shebang — the shape
`StandInOwnerSpec` fails on outside `stand-in/` and the exemption list, with no reason the list
accepts; a fake "with case switches" written per spec — the per-test generation ADR 0015
removed. Executor authors outside the repository (U2) run the kit against their own program; the
reference executor is the factory's test subject, not a shipped artefact.

## Risks / Trade-offs

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
  to redefine later; the cut line, if the change overruns, is D16–D17 (Markdown rendering and
  the money budget), which depend on D13–D15 and nothing depends on them.
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
