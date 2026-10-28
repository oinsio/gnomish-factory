# Tasks: define-executor-contract

## 1. Durable record: ADR and glossary

- [ ] 1.1 Write `docs/adr/0020-executor-contract.md` (0012 is taken by `own-git-invocation-policy`) (status accepted; context, the mixed
  model D1, mirror interface D2, file channel D3, status+answer D4, describe D5,
  declaration+verification D6, the permission-prompt decision D7 with its four revisit
  conditions, rejected alternatives, and the usage principles of D13–D15: a round record
  carries the identity its writer held, one usage entry per executor invocation keyed by the
  contract role, `totals` is a checked snapshot of one fold); verify it links no
  `openspec/changes/` path and is referenced from design.md only by number (FR1–FR7, FR14,
  FR15, FR17, NG5)
- [ ] 1.2 Add glossary entries under "Pipeline execution" and a new "Executor contract"
  section: executor, executor role, built-in executor, external executor, describe,
  status, answer slot, pending key, continuation key, policy enforcement level,
  conformance kit, SCM-status reader (forward reference), participant usage, usage snapshot,
  usage fold; update the existing `Executor` entry and the `Round token` entry (both modes,
  three producers); verify every new term used in the specs has an entry (process-invariants
  "No jargon")

## 2. Module `executor-contract`: schema and wire types

- [ ] 2.1 Create module `executor-contract` (JDK + Jackson only, `java-conventions`, PIT
  scoped) with `schemas/executor-request.schema.json`, `executor-result.schema.json`,
  `executor-answer.schema.json`, `executor-describe.schema.json`; verify `:executor-contract:check`
  passes with the empty module and the dependency-analysis gate is green (FR1)
- [ ] 2.2 Add DTOs `ExecutorRequest` (role, workspace, policy, roleInputs, settings, bounds,
  feedback, continuation), `ExecutorResult` (status, answer, usage, denials), `Describe`,
  value types `ExecutorName`, `PendingKey`, `Role`, `Status`, `FailureClass`, `Policy`,
  `EnforcementLevel`, `UsageProvenance`; write `WireSchemaRoundTripSpec` validating DTO
  samples against the schema files and schema examples into DTOs — the D10 identity spec
  (FR1, NFR-R3)
- [ ] 2.3 Write the data-driven enum round-trip spec over `values()` of every wire enum with
  the unknown-token arm pinned (`Status` unknown → `failed/infrastructure` naming the token)
  (FR2, NFR-R3)
- [ ] 2.4 Add `Answer` sealed type: `Verdict` (SARIF document, capped), `Select`, `DecisionRequest`
  (≥ 2 options invariant); write specs for the malformed cases (one option, non-SARIF) and
  for pass derivation from SARIF level vs threshold (FR3)
- [ ] 2.5 Add the `Executor` interface (`describe`, `start`, `poll`) and the additive-evolution
  Jackson configuration (`FAIL_ON_UNKNOWN_PROPERTIES=false` on every DTO reader); spec: a
  result with an unknown field reads, a request with an added optional field serializes; the
  interface is the Java mirror every built-in implements (FR4, FR8)

## 3. Single owner: result-file reader

- [ ] 3.1 Implement `ResultFileReader` in `executor-contract`: reads a factory-chosen path through
  the environment seam with the cap, truncation marker, inert bytes, `UntrustedText` on every
  text field, typed `ReadResult<T>` (Parsed | Malformed(reason) | Absent | Truncated); specs for
  each outcome and for idempotent re-read (FR6, NFR-R1)
- [ ] 3.2 Route the three reads onto the owner: `ShellCommandCheckRunner:190` (findings file,
  `readFile`, 256 KiB), `DecisionFileTransport.readAndClose` (`:143`, host decision file —
  `Files.readString` with no cap today, the one uncapped read), `BranchDecisionFile:92`
  (`readFile`, 1 MiB); delete `FindingsFileReader` and `DecisionFileReader`; keep the tolerant fallbacks (raw text as
  question, synthetic finding) asserted by their existing specs now driven through the owner
  (FR6)
- [ ] 3.3 Old-way sweep: `grep -rn "readFile(\|Files.readString(\|Files.readAllBytes("
  --include=*.java */src/main adapters/*/src/main sandbox/*/src/main` — every hit on a `GNOMISH_*`
  result path or a decision-file path is in the owner; list the exemptions (`WorkingTreeLawSource`,
  `EnvFileSecretSearch`, `AdHocTaskSynthesizer`, `OperatorFile`, `SnapshotReader`, the environment
  implementations of `readFile`) in the task report (FR6, M3)
- [ ] 3.4 Write `ResultPathReadBoundarySpec` in `:bootstrap`: allowlist of files permitted to read
  a result path, a scan over all production sources that fails on any other `readFile(`,
  `Files.readString(` or `Files.readAllBytes(` of a `GNOMISH_*`-derived or decision-file path,
  and an assertion that the scan reached every allowlisted file (M3; testing.md "Fixtures
  assemble through production owners")
- [ ] 3.5 Update the `DecisionFileTransport` ↔ `BranchDecisionFile` markers and the
  manual-sync-pairs registry row to name `ResultFileReader` as the owner of the read; verify
  `grep -rn "Kept in sync with"` still lists both ends (D9)

## 4. Single owner: outcome classifier in the engine

- [ ] 4.1 Reshape `ExecutionResult` in `domain/engine` to status + answer + usage + denials
  (`Completed` → `completed`, `DecisionNeeded` → `completed` + `DecisionRequest`); add the
  `failed/quality` arm in `RoundExecution` (recorded quality-failure round, findings join
  `priorFailures`, verify chain not invoked); adapt `StageAttemptLoop`, state mappers
  (`StateJsonMapper`, `TaskJsonMapper`) and their round-trip specs; engine specs: attempt
  burned, strict persistence ordering, no check port invoked, existing specs green (FR7)
- [ ] 4.2 Add `ExecutorOutcomeClassifier` returning sealed `AttemptAccounting`; add
  `EscalationReport.BudgetExhausted(bound)` as the sixth report kind with its DTO branches
  (`EscalationReportDto` + `TaskJsonMapper` in adapters/git, `EscalationDto` + `EscalationMapper`
  in application/status), its `StatusTextRenderer` line, its ledger `taskOutcome` token and its
  line in `status-report-v1.escalations.reference.jsonl` (`StatusReportJsonMapperSpec`'s corpus
  completeness check counts six; `StatusReportSpec` names the kinds); move `StageAttemptLoop:137`'s `EscalationReport.CannotExecute` construction into the
  classifier and make `RoundExecution:126`'s `RoundOutcome.CannotExecute` its input; data-driven
  spec: one row per status/class including `pending` for `transform` → infrastructure; the
  `values()`/sealed round-trip specs of every escalation mapper cover the new kind (FR7, FR2)
- [ ] 4.3 Old-way sweep: `grep -rn "new EscalationReport.CannotExecute(" --include=*.java */src/main
  adapters/*/src/main` → the classifier plus the two DTO mappers the D10 row allowlists
  (`TaskJsonMapper`, `StateJsonMapper`), nothing else — the grep gate in the architecture spec
  carries that allowlist; `grep -rn "new Verdict.CannotVerify(\|new ExecutorFailure("` → every
  hit is on the D10 exemption list naming its follow-up change (today: `ShellCommandCheckRunner`,
  `FilesExistCheckRunner`, `ExternalPolling`, `VerifyOrchestrator`, `JudgeVerdictExtractor`,
  `JudgeCriteriaPreflight`, `JudgeCannotVerify`, `StateJsonMapper`); record the list in the task
  report (G4, M2)
- [ ] 4.4 Executor identity: name and model are law content and need no pin of their own (the law
  pin at creation fixes them); the round record carries the describe `version` the preflight
  obtained (the `executor=<name>@<version>` of UX3, in the round's own commit), and the preflight
  of a later run compares its `describe` with the version the task's recorded rounds carry,
  escalating `PipelineMismatch` naming both on a difference; spec on a real bare origin: a round
  recorded with version 1, a resume whose stand-in describes version 2 escalates, no new durable
  step (FR13, D11). The program binary itself is not pinned (proposal Q2)
- [ ] 4.5 Ledger and MDC: the round's log line carries `executor`, `protocolVersion`, `role`,
  `status`, `class`, `bound` under the task's MDC; the ledger gains a per-round line kind
  (`roundOutcome`, beside `taskOutcome`, `runSummary` and `sweepAction` — today no line is written
  per round, `LedgerJsonMapper.java:83-96` carries usage on `taskOutcome` only) with
  `executor=<name>@<version> role=<role> status=<status>[/<class>]`; `LedgerJsonMapper` ↔
  `LedgerAggregator` (declared pair) updated together, the aggregator ignoring the new kind's
  tokens — the day view sums `taskOutcome` snapshots only; spec asserts the line for a `program`
  round (NFR-O2, UX3)

## 5. Round record identity and usage

- [ ] 5.1 `AttemptRecord` gains `taskId`, `stage`, `roundToken`, stamped by the engine from the
  attempt key and the run's `CurrentRound`; `ServiceCommitMessages.round(AttemptRecord)`
  replaces `round(String, int)`; `HostRoundEnvironmentSource` mints the token over the worktree
  tip through `RoundToken.of` (mirrored edit of the declared source pair, both markers and the
  registry row updated; the `CurrentRoundWriterSpec` allowlist widens from two producers to
  three — `HostRoundEnvironmentSource` joins `SandboxRoundEnvironmentSource` and
  `SnapshotTipCheck` — and the glossary's "Round token" entry records it, task 1.2; the archived
  `make-checkpoint-gate-durable` is not edited); the subject's `round` is the record's `round`
  as recorded — the attempt key's number, non-negative, the same value `key.attempt()` passes
  today — and the javadocs of `ServiceCommitMessages.round` ("1-based") and `AttemptRecord.round`
  are aligned to that one statement; specs: the record names its stage on a first-round pass,
  two host rounds carry distinct tokens, subject equals record (FR14, FR21, D13, D14)
- [ ] 5.2 `ParticipantUsage` (role, executor, vote, provenance, wall, tools, `ModelUsage` with
  optional cost) in `domain/engine` and the `usage-wire` DTO package in `executor-contract`;
  `StateJsonMapper`, `StatusReportJsonMapper` and `UsageReportJsonMapper` map through it;
  `StateUsageMapper`, `status/json/UsageMapper` and the `usage/json` copies deleted;
  `executorUsage`/`judgeUsage` removed from every DTO; `values()` round-trip spec over every
  wire token; `status-report-v1.reference.json` regenerated and the equivalence contract spec
  re-anchored (FR15, FR16, NFR-R3)
- [ ] 5.2a Delta spec, text only: the MODIFIED block of `git-task-persistence` / `State directory
  with one writer per file` (every scenario copied from the stable text, the field list of
  `state.json carries the position and the current attempts` reworded to the identity, the
  `usage[]` list and the snapshot) is in `specs/git/git-task-persistence/spec.md`; the
  scenario's two markers (`StateJsonMapperSpec.groovy:36,112`) resolve through the delta and their
  features assert the new field list (FR15, FR17; F66 of tighten-spec-contract)
- [ ] 5.3 `TokenUsageMapper` maps `modelUsage[].costUSD` beside the counts for rounds and judge
  votes (absent stays absent, flat fallback carries none); `ReferenceDumpScrubber` deny-list
  drops `total_cost_usd` and `costUSD`, its spec asserts money is kept and its marker
  `// Scenario: executor/agent-executor / Money is stripped` (`ReferenceDumpScrubberSpec.groovy:22`)
  becomes `Money is kept` so the REMOVED scenario leaves no dangling marker, the committed
  reference dumps are refreshed with cost (FR22, NG10)
- [ ] 5.4 `UsageFold` and `UsageSnapshot` (`byRole` × model × kind, `cost {priced,
  unpricedTokens, complete}`, `rounds`, `through`) in `domain/engine`; `TaskState` round
  writers fold through it, lifecycle rewrites carry the snapshot unchanged, a round with no
  entry is refused; `UsageTotals` and `ExecutorUsage.plus` deleted; specs: judge votes folded,
  re-fold by `through` is a no-op, missing cost yields a lower bound; `UsageFoldOwnerBoundarySpec`
  in `:bootstrap` (no token or cost summation outside the owner — `TokenUsage` and
  `LedgerTokenUsage` alike — with `LedgerAggregator` the one allowlisted exemption per the D10
  row, no jq reduce over tokens under `.gnomish/stages`, every allowlisted file reached) (FR17,
  NFR-O3, D15)
- [ ] 5.5 `UsageHistoryWalker`: `git log --first-parent <baseCommit>..<ref> -- state.json` with
  `baseCommit` read from the tip's `task.json` through the existing task-json reader; stage and
  task from the record; a foreign `taskId` rejected with `USAGE_HISTORY_FOREIGN_RECORD` (new
  operator event, catalog updated); `grewFrom` and the position read deleted; specs rebuilt
  with consecutive `recordPassAndAdvance` rounds (three first-round passes billed to their
  stages), a base carrying another task's envelope, a merge of the base into the branch
  (FR14, FR19, M5)
- [ ] 5.6 `UsageCommand` re-folds the walked rows and fails with both figures on a mismatch;
  `UsageSnapshotIdentitySpec` on a bare origin with the real adapters: pass, failure, decision
  reset, resume by a second instance (FR18, D15)
- [ ] 5.7 `UsageMarkdownRenderer` and `gnomish usage --markdown` (elapsed, per-stage, per-role,
  per-model tables, lower-bound cost wording); `UsageTextRenderer` sums through the fold;
  `.gnomish/stages/usage-report/report.sh` reduced to running the command and posting its
  output, `stage.yaml` comment updated; spec on canonical rows with a multi-round stage and
  two judge votes asserting the per-stage sum equals the per-role sum equals the snapshot;
  `grep -c jq .gnomish/stages/usage-report/report.sh` → 0 aggregation (FR20, G5, M6, D16)
- [ ] 5.8 Budget: `ExecutorOutcomeClassifier` reads the folded snapshot; a money bound with
  `complete = false` → `failed/limit("cost unknown")`; the verdict and the snapshot ride the
  round's commit; kill-point spec after the round commit shows the next pickup escalates
  `BudgetExhausted` and runs no round (NFR-C2, NFR-C3, D17)
- [ ] 5.9 `gnomish status` text and JSON carry the snapshot's `rounds`, `complete` and unpriced
  tokens; `StatusTextRenderer` spec (UX4)
- [ ] 5.10 Old-way sweep: `grep -rn "TokenUsage::plus\|TokenUsage.plus(\|\.plus(.*Usage\|tokensByModel().merge\|executorUsage()\|judgeUsage()" --include=*.java */src/main adapters/*/src/main`
  and `grep -rn "jq" .gnomish/stages` — every hit routed through the fold or the participant
  list, or deleted; the list goes into the task report (G5, M5)

## 6. Describe handshake and law declarations

- [ ] 6.1 Add the `executors:` section to the pipeline DTOs and `PipelineDefinition`
  (`ExecutorDeclaration`: name, type, program argv, settings, credentials, egress) and the
  `program` executor type with `executor.name`; structural + sanity rules per the
  pipeline-config delta — D5 phase (a), the only check the loader runs, no process spawned;
  `ApiExecutorRule` message names `agent-cli` and `program`; located-error specs for unknown
  name, missing name, unknown type, missing argv, model optional on `program` (FR9, FR10)
- [ ] 6.2 Implement the describe preflight (`DescribePreflight` in `application`, D5 phase b): it
  runs after the task's execution environment is live and before the engine — on the container
  paths right after `FirstBoxStep.makeLive` (`TakeContainerFreshClaim:111`,
  `ContainerTerminalDrive:61`), on the host paths after `TaskTierLaw.bind` on the host environment
  — `describe` for each declared `program` executor through `TaskExecutionEnvironment.exec` with
  the startup bound, output read through `ResultFileReader` rules (cap, untrusted), cached for the
  run by law commit. Two outcomes, no new classifier: a program absent or failing `describe`
  (exit non-zero, oversized, malformed) is classified by `OperatorPreconditionRefusals` and ends
  the run as `TakeResult.OperatorPreconditionUnmet` (exit 8); version mismatch, undeclared role
  and the checks of 6.3/6.4 are located `ConfigError`s carried to the parked result
  `TaskTierLaw.bind` yields for an invalid law. Specs on the stand-in presets of D18: each
  outcome, no round run, no attempt burned, the cache hit on the second executor of one law (FR4,
  FR13, UX1)
- [ ] 6.3 Validate `settings` against the describe-provided JSON Schema in the preflight (one
  schema validator dependency, pinned, licence-checked); spec: typo in a property name is a
  located error at the settings path naming the unknown property, reported before the first
  round (FR9, UX1)
- [ ] 6.4 Cross-check credentials and egress against describe in the preflight, and refuse a money
  bound on an executor whose describe reports no usage (a located error naming the executor and
  the bound); derive `ExecutorCredentialNames` and the egress-host union for `program` executors.
  The grant is the child's **own resolved layer**: values resolved through the allowlist's
  `ChildEnvValues` role interface (ADR 0017) and added to the `program` exec's factory-set
  variables — never through `ChildEnvAllowlist.of`'s second argument or `declaring`, whose set
  `compose` and `resolve` strip (`ChildEnvAllowlist.java:140,161`), never `System.getenv`, never
  an allowlist built without a value source; `ProgramCredentialDeliverySpec` on a real box asserts
  the granted names equal the names the child receives and that a sibling `command` check
  receives none (D10 identity, NFR-S1, NFR-C1)
- [ ] 6.5 Reject operator-config `executors` keys at load with the unknown-key error, and in the
  preflight refuse a stage or check whose executor's describe lists only `effect` for the role the
  use site needs (a stage needs `transform`, a check `verdict`) with a located error naming the
  role as reserved — the law has no role field, the use site implies the role; specs (NFR-S3,
  FR1, NG4)

## 7. First consumer: the `program` stage executor

- [ ] 7.1 Implement `ProgramExecutor` (adapters) over the `Executor` interface: builds the request
  and hands it as `ExecCommand.stdin` → `StdinSource.of(bytes)` (a file the child inherits; no
  thread writes into a pipe), hands `GNOMISH_RESULT_FILE`/`GNOMISH_ANSWER_FILE` paths, spawns
  through `TaskExecutionEnvironment.exec`, reads stdout through `Drain.capped` over `ChildOutput`
  (byte cap, line cap on the captured text) — or, when `streams_progress` is declared, through a
  live `ChildOutput` reader in the shape of `StreamDrain` so the silence bound sees each line —
  sends stderr to `StderrPolicy.ToFile` with the agent round's tail rule, owns no reader, pump or
  close of its own (`DrainOwnerGateSpec`, `ChildOutputOwnerGateSpec` stay green), applies the three
  bounds (startup, silence, overall), reads the result through `ResultFileReader`, attaches
  denials via the environment; specs: completed, failed/quality and failed/limit against the D18
  presets; malformed result, oversized stderr, silence bound and stray stdout on `FakeProcess`
  through `ProcessLaunch`, asserting no scheduling order (FR5, FR10, NFR-R2, D3, D18)
- [ ] 7.2 Implement the working-copy-unchanged check for `verdict`/`decision` roles through the
  environment and wire it after `start`; spec: a modified file → `failed/infrastructure`
  naming the path (FR11, NFR-S4)
- [ ] 7.3 Add `ExecutorRegistry` (built-ins + law-declared programs) and the name-keyed
  dispatching `StageExecutor` in `ExecutorAdapterSelector`, replacing the unconditional Claude
  pick; keep one `agent-cli` arm with interactive substitution on it only; wiring spec on the
  real flow: a mixed pipeline reaches `ProgramExecutor` and the agent adapter per stage (FR9, D8)
- [ ] 7.4 Ensure `agent-cli` rounds report the contract status instead of throwing
  `ExecutorFailure` for timeout/interrupt (adapter side), leaving classification to the
  engine; existing agent specs adapted (agent-executor delta)

## 8. Conformance kit, reference executor, fake

- [ ] 8.1 Add the stand-in table `test-fixtures/src/main/resources/stand-in/tables/executor.params`
  with the preset `reference` (describe, start for the three live roles, pending then poll,
  cancel) and one `break-<case>` preset per kit case, each taking its case from the link's name;
  `StandIn.executor(name)` selects them; rows covered by `StandInLibrarySpec` and reached per
  `StandInRowReachSpec`; `StandInOwnerSpec` green with no new exemption; no factory dependency
  (FR12, D18)
- [ ] 8.2 Implement `gnomish executor verify <program>` in `:bootstrap` driving the ≥ 12 cases
  from the kit spec, one line per case, non-zero exit on failure; the kit's core takes an
  `Executor` and the CLI wraps the program in the external adapter; spec: reference passes all,
  fake fails exactly the case it is told to break (FR12, UX2, M4)
- [ ] 8.2a Drive a built-in through the same kit in process: an in-process `Executor` double
  (test-fixtures) run through every case by the kit's core, with the misbehaving variant failing
  the case it breaks; this is the path `add-command-executor` runs its `command` built-in through
  (FR8, D2)
- [ ] 8.3 Point the packaged-jar end-to-end suite's `program` stage at the reference executor;
  verify the round completes in container mode with the egress guard active (FR10)

## 9. Integration checks

- [ ] 9.1 Run `./gradlew check` including PIT per module; justify any `excludedClasses` for the
  kit's out-of-process driver per testing.md
- [ ] 9.2 Produce the sweep report: the greps of 3.3, 4.3 and 5.10 with every hit and its disposition
  (routed, deleted, exempt with follow-up name); verify the exemption list equals D10
- [ ] 9.3 Recommend a commit message (no commit) referencing define-executor-contract FR ids

## Follow-ups from tighten-spec-contract

- F66 is task 5.2a (the MODIFIED block is written in the delta).
- F107 is folded into the design's `Describe` row (D10) and task 6.4: the grant is the child's own resolved layer through `ChildEnvValues`; the `AgentAiSeam` exemption stays until `extract-agent-round-runner` deletes the class, and that deletion keeps the same route (`SecretsPortBoundarySpec` stays the gate on process-environment reads).
