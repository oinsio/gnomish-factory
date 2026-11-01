# Proposal: define-executor-contract

## Why

Five engine ports carry five result types, and the only agent integration is hard-wired to
one vendor: about 1,900 of the 4,330 lines in `adapters/agent` parse Claude Code's event
stream, the round mechanics are duplicated per role, and a run's failure class is decided in
seven files. A second agent CLI, a new verifier or an arbiter role each means a new port, a
new adapter pair and a new place to classify failures. Mature orchestrators converged on one
unit-of-work contract with a declared failure taxonomy, capability-declaring executors and a
process boundary for third-party code. This change defines that contract so every later
executor — built-in or external — is one implementation of one shape.

The round record is also incomplete and unidentified. `state.json`'s `attempts[]` entries carry
no task id, stage or round identity, so `gnomish usage` guesses a round's stage from the shape
of the list and misattributes every stage that passes on its first round; a branch created from
a base that still carries another task's envelope counts that task's rounds as its own. Usage is
split across role-specific slots (executor, judge, soon arbiter), `totals` folds only the
executor's share while the usage report claims to include judges, cost is read from the CLI and
dropped, and the Markdown report is re-aggregated by a shell script with no test, which
collapsed the sum (observed 2026-10-09 on task #92). The contract's `Usage` is the one place to
fix all of it: one usage shape per executor invocation, stamped with its identity, folded by one
function and checked against a recorded snapshot.

## What Changes

- **ADDED**: an **executor contract** — one request/result shape for every mechanism that
  works for the engine, in four roles (`transform`, `verdict`, `decision`, `effect`), with a
  closed status taxonomy (`completed`, `failed{quality | infrastructure | limit}`,
  `pending + key`) and an optional answer (SARIF verdict, selection, decision request).
- **ADDED**: the **mixed plugin model**: a closed set of built-in executors inside the
  factory; every external executor is a program spawned through the sole process seam and
  spoken to over a versioned JSON contract (request on stdin, results in factory-named
  files, logs on stdout/stderr). Trackers keep their in-process SPI.
- **ADDED**: a **describe handshake**: integer protocol version, capabilities, settings JSON
  Schema, credential names, egress hosts, config-directory variable and policy-disabling
  flags, declared policy enforcement level.
- **ADDED**: **one result-file reader** replacing the per-channel readers, and **one
  failure-class mapping** in the engine.
- **ADDED**: a `program` executor type for stages (role `transform`), dispatched by name.
- **ADDED**: a **conformance kit** (`gnomish executor verify <program>`), a reference
  executor and a misbehaving fake; schema files as the single source of truth.
- **MODIFIED**: the engine's execution result becomes status + answer: `failed/quality` burns
  an attempt and skips verification, `limit` ends the stage (budget exhausted), usage has provenance.
- **MODIFIED**: the agent executor's decision file is read through the shared reader.
- **MODIFIED**: the round record (`attempts[]` of `state.json`, mirrored in `status.json` and
  `usage.json`) carries its identity (`taskId`, `stage`, `roundToken`) and one `usage[]` list of
  participants — one entry per executor invocation of the round (role, executor, vote,
  provenance, per-model tokens and cost) — replacing the `executorUsage` / `judgeUsage` slots;
  one shared usage wire vocabulary serves all three documents.
- **MODIFIED**: `totals` becomes a snapshot of one fold over every participant of every round
  (role × model grain, cost as a lower bound) with its own position (`rounds`, `through`);
  `gnomish usage` re-folds and refuses a mismatch.
- **MODIFIED**: `gnomish usage` walks only the task's own history (`baseCommit..tip`, first
  parent), takes the stage from the record, and renders Markdown; the usage-report stage posts
  that rendering instead of aggregating.
- **MODIFIED**: cost figures are no longer scrubbed from reference dumps (supersedes the money
  rule of `harden-reference-dump-scrubber`; tokens and model ids already reveal the figure).
- **ADDED**: host-mode rounds mint a round token like container-mode rounds.
- **ADDED**: ADR 0020 and glossary terms. **BREAKING** (deferred to the follow-up):
  `CheckClientFactory` retires; no third-party implementation exists today.

## Goals

- G1: one contract shape for built-in and external executors; later executor changes add
  zero role-specific ports.
- G2: a new external executor changes no factory source file: a program, its `describe`
  output, a law declaration.
- G3: a new agent CLI is one external executor program, not a Java module in the factory.
- G4: every failure-class decision for executor results is made in one engine component.
- G5: every figure the usage report prints is produced by the typed program from one fold; no
  second summation of tokens or cost exists in scripts or renderers.

## Non-Goals

- NG1: migrating the four verify-check kinds onto the contract — follow-up
  `migrate-verify-to-executor-contract` (retires `CheckClientFactory`, adds the SCM-status
  built-in, wires `pending + key` into the poll loop).
- NG2: extracting the Claude dialect out of `adapters/agent` — follow-up
  `extract-agent-round-runner`; this change leaves `CliStageExecutor` in place.
- NG3: sealing verification assets from the gnome-editable tree — follow-up
  `harden-verification-integrity`.
- NG4: implementing the `effect` role; this change reserves the vocabulary only.
- NG5: mid-round permission questions as a `decision-request` answer: the executor answers
  tool prompts itself from the abstract policy (ADR 0020 records when to revisit).
- NG6: an ACP adapter; the raw-CLI dialect stays the reference, ACP is a later adapter.
- NG7: `pending + key` handling for the `transform` role in the engine (a transform
  executor returning `pending` is an infrastructure failure in this change; NG1 wires it).
- NG8: re-attributing or refusing branches recorded before the round identity existed — no
  release has shipped and no such branch is supported; the new readers assume the new record.
- NG9: a server-side or CI barrier against task envelopes reaching the default branch — an
  operator process matter, not this change.
- NG10: pricing tokens the executor did not price — cost is recorded only as reported; the
  factory holds no price table.
- NG11: running `describe` from `gnomish doctor`. The doctor has no task, so it has neither a
  law nor an execution environment to run a declared program in; the describe preflight is
  reached only through a claimed run (ADR 0019, decision 2). Pickup condition: the change that
  gives the doctor a law source and an execution environment of its own.
- NG12: budget bounds declared in the law and enforced by the factory — a token or money bound
  judged on the folded usage snapshot, a money bound failing closed on incomplete cost, the
  verdict riding the round's own commit, a money bound refused on an executor whose describe
  reports no usage. The law declares no budget today (`AutonomyLimits` carries the attempt
  limit only) and `pipeline/pipeline-config` keeps monetary budgets out of the project's scope;
  an executor that stops itself at a bound still reports `failed/limit` and escalates
  `BudgetExhausted` (FR7). Pickup condition: the change that declares a budget in the law
  (`add-usage-budgets`), which revisits that scope decision and settles whether turns are
  counted. Cut during task 5.8.
- NG13: the `CONTAINER_RUNTIME` daemon condition's probe, which reruns the mode plan on the
  daemon's startup definition while the slot that opened it judged by the task's own law
  (`ServePreconditions.check`): a second instance of the class task 6.2 found (a precondition
  judged by one law and probed by another), left as it is. A daemon whose startup law is
  host-bound, serving a task whose pinned law is container-bound, would open the condition,
  have the probe pass at once, and reclaim the task. Pickup condition: the first observed
  open-close churn of that condition, or the change that lets a task's law bind a mode its
  daemon's law does not.
- NG14: a startup or `doctor` check for the host agent CLI binary (`factory.agent-cli-binary`),
  an installation-level setting the daemon owns and today meets only inside a round
  (`ExecutorRoundExecution.launch`: a `ProcessStartException` escalates that task). Pickup
  condition: the first host task parked for an absent agent binary, or the change that adds
  the next host-mode startup check.
- NG15: automatic return of a task parked on a describe failure — a janitor pass that reruns
  `describe` for the parked task's law and returns it when the answer holds, or a retry of an
  external describe failure before the park. The park with a located message is the bounded
  end this change delivers; the human's return is the retry. Pickup condition: an operator
  returning such tasks by hand after one image fix for the second time.
- NG16: `cancel` as an operation of the contract. The describe capability `cancel` stays in
  the vocabulary, but this version defines no request or result shape for it, no program
  operation word and no kit case: no factory path sends a cancel (an expired bound kills the
  process tree, NFR-R2) and the mirror interface declares none, so a cancel case could be
  driven neither through `Executor` nor in process. Pickup condition: the first change whose
  engine path prefers a cooperative stop to a kill (a stage abort on a claim loss, a bound of a
  `verdict` run whose executor declares `cancel`). Decided 2026-11-01 before task 8.2, when the
  kit's artifacts (FR12, D18) were found to promise a case the contract (D2) could not carry.

## Users & Scenarios

- U1 — Pipeline author: declares `executors:` in `.gnomish/`, references one by name from a
  stage; a settings typo is a located load error, not a failed round.
- U2 — Executor author: writes a program in any language, runs the conformance kit, ships it
  in the sandbox image; never compiles against a factory jar.
- U3 — Operator: sees executor, version and model per round in the ledger, and "budget
  exhausted" as its own escalation reason.
- U4 — Engine maintainer: adds a role or status once, in schema and engine; every executor
  inherits it.
- U5 — Reviewer: reads one Markdown comment on the pull request with time, tokens and cost per
  stage, per participant role and per model, and trusts it because the program checked it
  against the branch's own totals.

## Requirements

### Functional

- FR1: The contract defines a request (role, workspace, policy, inputs by role, settings,
  bounds, prior feedback, continuation) and a result (status, answer, usage, denials) as
  JSON Schema files in the repository; Java DTOs mirror them one to one.
- FR2: Status is one of `completed`, `failed` with class `quality | infrastructure | limit`,
  `pending` with an opaque key; a `pending` result from a role whose caller cannot poll is
  an infrastructure failure naming the limitation.
- FR3: Answer is optional and one of `verdict` (SARIF 2.1.0 findings plus pass/fail derived by
  the engine from level and threshold), `select` (chosen option, optional ranking,
  rationale), `decision-request` (question, ≥ 2 options, continuation key).
- FR4: `describe` returns protocol version (integer), roles, capabilities, settings schema,
  credential names, egress hosts, config-directory variable, policy-disabling flags, and
  enforcement level; unknown fields are ignored on both sides; version mismatch is a located
  error of the law, reported by the preflight before the executor's first round.
- FR5: External executors are spawned only through `TaskExecutionEnvironment.exec`; the request
  is the child's standard input, handed to the launch owner as a file (never a pipe the factory
  writes into); results are written to factory-named paths handed over as `GNOMISH_*`
  variables; stdout and stderr are drained through the factory's one bounded child-output reader,
  capped and never parsed, except NDJSON progress on stdout when `streams_progress` is declared.
- FR6: One result-file reader owns every read of a factory-named result path: size cap,
  truncation marker, inert bytes, `UntrustedText` wrapping of every text field; parse failure
  of an answer is `failed/infrastructure` for `transform` and cannot-verify for `verdict`,
  never a pass.
- FR7: The engine maps `status` to attempt accounting once: `quality` burns an attempt,
  `infrastructure` does not and escalates after retries, `limit` does not and ends the stage
  with a budget-exhausted escalation; vendor exit codes carry no meaning to the engine.
- FR8: Built-ins implement the Java mirror of the JSON contract and pass the same conformance
  kit; no built-in has a result type of its own.
- FR9: The law declares executors by name (`executors:` section: type, program or built-in id,
  settings, credentials, egress); a stage names its executor; the section is validated
  structurally at law load, and settings are validated against the executor's declared schema
  before the executor's first round, once the task's execution environment is live and before
  the engine runs.
- FR10: A `program` executor type runs a stage in role `transform` end to end through the
  contract (stdin request, file result, environment denials, recorded usage).
- FR11: Policy in the request is abstract (`readOnly`, `writeExactly(path)`, `workspaceWrite`);
  the executor compiles it; the factory verifies after `verdict` and `decision` roles that the
  working copy is unchanged.
- FR12: A conformance kit drives a candidate through `describe`, settings schema, each declared
  role, an undeclared role, oversized output, stderr noise, non-JSON stdout, `pending` then
  `poll`, unknown fields, malformed answers; a reference executor passes it and a misbehaving
  fake fails each case. The `pending` case runs only against a candidate whose describe declares
  `poll`, driven by the settings the command's `--pending-settings` option carries (the
  reference's `{"defer":true}`); a candidate without `poll` sees it reported as not applicable
  (revised 2026-11-01 before task 8.2: `cancel` left the list, NG16).
- FR13: Preflight before an executor's first round, on the live execution environment: a
  program that is absent, cannot answer `describe`, or whose `describe` disagrees with the law
  (version, roles, schema, credentials, egress) is a located configuration error of the task
  that parks it without burning an attempt, as an invalid law does; a program the environment
  cannot start, or that does not answer within the bound, parks it the same way with the
  failure named as external. Neither is an operator precondition and neither opens a daemon
  condition (ADR 0019, decision 5: the level of a precondition follows the owner of its
  truth, and the law that names the program is the task's). Executor name and
  model are law content, pinned with the law at creation; the describe version is recorded by
  the first round that ran the executor and carried in the task state across lifecycle rewrites,
  and a later `describe` reporting a different version
  escalates `PipelineMismatch`.
- FR14: Every round record carries the identity the orchestrator minted for it — `taskId`,
  `stage` and `roundToken` (the tip the round opened on) — written by the round's writer; no
  reader derives a round's stage or task from the position, the list shape or neighbouring
  records. The round commit's subject is rendered from the record.
- FR15: A round record holds one `usage[]` list with one entry per executor invocation of the
  round: contract role (`transform`, `verdict`, `decision`, `effect`), executor name, vote
  index for `verdict`, provenance (`REPORTED | ABSENT`), optional wall time and tool
  aggregates, and per-model entries of the four token counts plus optional cost in USD;
  `executorUsage`, `judgeUsage` and any role-named slot are removed from every document.
- FR16: One usage wire vocabulary (DTOs and tokens) serves `state.json`, `status.json` and
  `usage.json`; a `values()` round-trip spec covers every token.
- FR17: `totals` is a snapshot of one pure fold over every participant of every round: role ×
  model × token kind, cost as `priced` plus `unpricedTokens` per role × model plus `complete`,
  and the snapshot's position (`rounds` folded, `through` = the last folded round token). The
  fold is one domain function used by the state writer, `gnomish usage` and the identity spec;
  no other code sums tokens or cost.
- FR18: `gnomish usage` re-folds the rounds it walked and compares with the tip's `totals`; a
  mismatch is an error naming both figures, never a silent report. A real-medium spec asserts
  the identity after a pass, a failure, a decision reset and a resume.
- FR19: `gnomish usage` walks `baseCommit..tip` on the first-parent line only and rejects any
  record whose `taskId` differs from the task's, with a warning naming the commit.
- FR20: `gnomish usage --markdown` renders elapsed time, a per-stage table (rounds, agent
  time, checks time, tokens, cost), a per-participant-role table and a per-model table; an
  incomplete cost is printed as a lower bound ("at least …; N tokens unpriced"); the
  usage-report stage posts that output verbatim.
- FR21: Host-mode rounds mint a round token on the tip they open on through the same parse as
  container-mode rounds; the run's `CurrentRound` holds it; the record takes it from there.
- FR22: The agent adapter maps per-model `costUSD` beside the token counts for rounds and judge
  votes; absent cost stays absent. Reference dumps keep cost figures.
- FR23: A `program` executor whose describe declares `poll` is polled the way it is started:
  the declared argv with the word `poll` appended, the same request on standard input, the
  pending key in `GNOMISH_PENDING_KEY`, the result in `GNOMISH_RESULT_FILE`; a poll of a program
  that declares no `poll`, or with a key this run did not receive, is `failed/infrastructure`
  with no process spawned. The working-copy check of a `verdict` or `decision` run (FR11) is
  judged at the poll that ends a pending run, against the view taken before its `start`. Added
  2026-11-01 before task 8.2, when the kit's `pending` then `poll` case (FR12) was found to have
  no wire form to pass through.
- FR24: The conformance kit judges every document the candidate writes (describe, result,
  answer) against the schema files on the raw bytes the factory read, independently of the
  readers' lenient unknown-token arms (FR4), and sends the candidate a request carrying one field
  the contract does not define; it observes and shapes the wire at the execution-environment
  port only, with the production readers and the DTOs unchanged. Added 2026-11-01 before task
  8.2, when the kit was found unable to fail a describe outside the schema (read as
  `PROMPT_ONLY`) or to send an unknown request field (a fixed record).

### Non-Functional Reliability

- NFR-R1: Result read and status mapping are idempotent over a re-read; recovery re-reads,
  never re-runs, a completed round.
- NFR-R2: Three bounds per run (startup read, progress silence, overall); expiry of any is
  `failed/infrastructure` naming the bound.
- NFR-R3: Every wire enum has a `values()` round-trip spec and a documented unknown-token arm.

### Non-Functional Observability

- NFR-O1: Usage carries provenance `REPORTED | ABSENT`; absent is never rendered as zero.
- NFR-O2: Every run logs executor, protocol version, role, status, class and bound hit under
  the task's MDC; tool trace is optional telemetry, denials are a contract field.
- NFR-O3: A round without usage entries is an error, not an empty total; tokens are never null;
  cost is optional and its absence propagates to the snapshot's `complete = false`.

### Non-Functional Security

- NFR-S1: No credential travels in argv or inherited environment; declared credential names are
  resolved through `SecretsProvider` and delivered only to the declaring executor's process
  (`fix-docker-exec-env-argv`, archived 2026-10-26; ADR 0017).
- NFR-S2: Everything an executor writes is untrusted: result files are inert and capped, text
  fields are `UntrustedText`, findings pass the sanitizer at every sink, and nothing from a
  result reaches an LLM prompt unlabeled.
- NFR-S3: An executor program is law: declared in `.gnomish/` at the base-ref commit, run only
  through the environment seam, never from operator configuration or from the working copy.
- NFR-S4: Declared enforcement level is verified, not trusted: `verdict` and `decision` roles
  require the working-copy-unchanged check to pass.

### Non-Functional Cost

- NFR-C1: Bounds (turns, tokens, money, three time bounds) travel in the request as hints to
  the executor; the factory enforces the time bounds itself. Budget enforcement by the factory
  is NG12.

## Operator Experience Criteria

- UX1: An executor declaration error — at law load or at the describe preflight — names the
  executor, the settings path and the schema rule, in the located format of other `.gnomish/`
  errors.
- UX2: `gnomish executor verify <program>` prints one line per case: pass/fail, first detail.
- UX3: A round's ledger line shows `executor=<name>@<version> role=<role> status=<status>[/<class>]`.
- UX4: `gnomish status` prints the snapshot's `rounds`, `complete` and unpriced token count
  beside the totals.

## Success Metrics

- M1: `adapters/agent` gains no vendor-specific class; reference executor and fake live
  outside production modules.
- M2: Failure class is constructed in exactly one production class (grep-verifiable); the
  seven sites in the design are removed or on the follow-ups' exemption list.
- M3: Result files are read through one class; a `:bootstrap` architecture spec fails on any
  other `readFile` of a `GNOMISH_*` path.
- M4: The conformance kit has ≥ 12 cases, each shown red against the misbehaving fake.
- M5: The three 2026-10-09 defects (a first-round pass billed to the next stage, a foreign
  base's rounds counted, a collapsed sum) are each reproduced by a spec on a real clone and
  green after the change; an architecture spec finds no token or cost summation outside the
  fold owner.
- M6: The usage-report stage's script contains no `jq` aggregation; it posts what
  `gnomish usage --markdown` prints.

## Open Questions

- Q1: Shape and cap of the `pending` key in the state file — `migrate-verify-to-executor-contract`.
- Q2: Pinning an external program's binary: by path like `pinPaths`, or by image digest. Open.
  This change pins the executor's name and model with the law and records the describe version
  (FR13); the binary is reached only through the sandbox image (ADR 0019). Pickup condition: the
  change that lets a law name a program outside the image, or that versions the image contract
  independently of the law.
- Q3: Whether `judge` becomes a built-in `llm-api` executor before or with
  `extract-agent-round-runner`.

## Capabilities

### New Capabilities
- `executor/wire-contract`: roles, request/result schema, status taxonomy, answer kinds,
  describe handshake and its evolution rules.
- `executor/result-channel`: factory-named result files, the single reader, caps and
  untrusted-text posture, stdout/stderr handling.
- `executor/conformance-kit`: the verification CLI, reference executor and misbehaving fake.

### Modified Capabilities
- `stage-engine`: execution result becomes status + answer with the `limit` class and one
  failure-class mapping; budget-exhausted escalation.
- `pipeline-config`: named executor declarations, `program` executor type, settings validated
  against the executor's declared schema.
- `agent-executor`: decision file read through the shared result reader; per-model cost
  mapping; reference dumps keep cost.
- `git-task-persistence`: round record identity and participant usage in `state.json`; `totals`
  snapshot shape; round commit subject from the record; host rounds mint a round token.
- `task-inspection`: `usage` bounded to the task's history, stage from the record, re-fold
  check, Markdown rendering.
- `status-report`: participant usage and the totals snapshot in contract v1.

## Impact

- New module `executor-contract` (JDK + Jackson): schemas, DTOs, reader, handshake parsing.
- `domain/engine`: result becomes status + answer; one classifier; `limit` class;
  `AttemptRecord` identity and participants; the usage fold and snapshot.
- `adapters/git`: state DTOs on the shared usage vocabulary; `UsageHistoryWalker` bounded and
  inference-free; round commit subject from the record; host round source mints the token.
- `adapters/agent`: cost mapping for rounds and judge votes; both round sources mint tokens.
- `application`: status and usage DTOs on the shared vocabulary; Markdown renderer; snapshot
  fields in `status`. `test-fixtures`: scrubber keeps cost; reference dumps regenerated.
- `.gnomish/stages/usage-report/report.sh`: posts `gnomish usage --markdown`, aggregates nothing.
- `adapters`: `program` executor; `FindingsFileReader` and `DecisionFileReader` removed.
- `bootstrap`: name-keyed executor registry and dispatch (replaces the unconditional Claude
  pick); reader-owner architecture spec; conformance CLI. `test-fixtures`: reference executor
  and misbehaving fake as stand-in presets (ADR 0015).
- `docs/adr/0020-executor-contract.md`, `docs/glossary.md`.
- Sequencing: after `fix-docker-exec-env-argv` (archived 2026-10-26); before `add-command-executor` (its `command`
  executor becomes the first built-in of the contract), `migrate-verify-to-executor-contract`,
  `extract-agent-round-runner`, `harden-verification-integrity`; `add-decision-arbiter` rebases
  and takes its usage shape from here (its own usage accounting is withdrawn).
  `supervise-daemon-loops-and-embed-dashboard` (archived) landed before this change; its
  `stage-engine` wording is in the stable text the telemetry requirement here is layered on.
- `tighten-spec-contract` (archived 2026-10-14) is synced: every MODIFIED block (`pipeline-config`,
  `git-task-persistence`, `stage-engine`, `status-report`, `task-inspection`) is layered on the
  stable text it left; `add-command-executor`'s `pipeline-config` block is layered on this
  change's.
