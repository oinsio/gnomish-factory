# Tasks: extract-agent-round-runner

## 1. Dialect seam and runner

- [x] 1.1 Define `AgentDialect` (describe fields, `render(request) → argv`, `parse(line)`,
  `answer(events, answerFile)`, `status(exit, events)`, `continuation(events)`) and the
  vendor-neutral progress events (model, tool name, summary, usage); drop the session id from
  `RoundStarted` and adapt its consumers (`LoggingAgentProgressListener`, `AgentProgressEmitter`,
  `AgentRoundResult`, `AgentRoundResultExtractor`, `StreamJsonEventMapper`,
  `MissingResultEventException`); specs green (FR2, FR6, D7)
- [x] 1.2 Implement `AgentRoundRunner implements Executor`: launch via `RoundEnvironment`,
  a capped stdout drain through the one drain owner (byte + line caps), stderr through
  `StderrPolicy.ToFile` into the round scratch directory read capped after the exit (no stderr
  pump; `DrainOwnerGateSpec` stays green), three bounds on virtual time,
  `ResultFileReader` for result and answer, denials, post-hoc working-copy check for
  `verdict`/`decision`; specs with the fake dialect for all three roles, exit-without-result,
  turn-limit → `failed/limit` with the escalation report `budget exhausted: <bound>`, oversized
  stdout, silence bound (FR1, FR8, NFR-R1, NFR-S2, UX2)
- [x] 1.3 Add the fake dialect to `test-fixtures` and run the conformance kit against the runner
  with it (M4)

## 2. Claude dialect

- [x] 2.1 Move `AgentCommandLine`, `AgentInvocationOptions`, `StreamJson*`, `AgentEvent`,
  `ContentBlock`, `TokenUsageMapper`, `ToolTraceBuilder`, `AgentRoundResultExtractor`,
  `ModelIdSyntax` into `adapters/agent/claude` behind `ClaudeDialect`; reference stream-json
  fixtures replay identically (FR3)
- [x] 2.2 `ClaudePolicyCompiler`: `Policy` → permission mode, tool sets, `--strict-mcp-config`,
  answer-path allowance; delete `AgentRole`, `JUDGE_READ_ONLY_TOOLS`; specs: no argv carries
  `bypassPermissions`, settings cannot widen read-only; turn and budget hints rendered as
  `--max-turns` and the budget flag (scenario "Turn and budget hints reach the argv") (FR5, D5,
  NFR-C1)
- [x] 2.3 Describe: roles, capabilities, `TOOL_GATE`, credential names, egress host from base
  URL, `CLAUDE_CONFIG_DIR`, repo-policy-disabling flags, settings schema (exactly the four legacy
  keys — D6 as amended 2026-11-21: no key for a bound but `roundTimeout`); delete `AgentAiSeam`
  and the Claude list in `AgentSettingsValidator`; the
  `roundTimeout` shape judge stays one owner in `:adapters`, called by the Claude schema,
  `CommandSettingsValidator` and the command executor's `roundTimeout` resolution, with the shapes
  accepted today (command-stage validation specs stay green); located-error spec for a settings
  typo (FR7, NFR-S1, D6)
- [x] 2.3a `RoundBounds` — the one resolution of a round's three bounds (FR7, NFR-R1, D6 as
  amended, D8 row). TDD on virtual time. `RoundBounds.of(ProgramBounds factory, settings)` in
  `adapters/agent`: the factory's startup-read and silence, `overall` from the settings'
  `roundTimeout` when declared, the factory's otherwise; `RoundTimeout` (`:adapters`) keeps the
  shapes and the parse, loses `DEFAULT`, and answers "declared or not" instead of falling back.
  Consumers, each shown to take the value from the owner: (1) `AgentRound`'s `ProgressWait`
  (`AgentRound.java:127-132` today reads `equipment.bounds()` — the old way) for every role;
  (2) the time hints of the request an agent executor receives, the same value the runner waits
  on; (3) `CommandStageExecutor` (`CommandStageExecutor.java:92`), with the factory's bounds it is
  constructed with; (4) until 4.1 and 5.1 delete them, `ExecutorRoundExecution.java:119` and
  `JudgeRoundExecution.java:115`. Old-way sweep, reported with its hits:
  `grep -rn -e 'RoundTimeout.resolve(' -e 'RoundTimeout.DEFAULT' -e 'bounds().overall()' --include='*.java' */src/main adapters/*/src/main`
  — every hit is the owner, routed through it, or gone. Gate `RoundBoundsOwnerSpec` (`:bootstrap`,
  scan asserted reached); identity spec `RoundBoundIdentitySpec`: a stage declaring
  `roundTimeout` runs past the factory's overall bound and is killed at exactly its own value,
  and the request's overall hint equals that value; scenario markers for the three `roundTimeout`
  scenarios and "A liveness line during a long tool call keeps the round alive" of
  `agent-round-runner`. Must land before 4.1: after it no vote reads `roundTimeout` any other way
- [x] 2.3b Measure the CLI's liveness cadence where it is still unmeasured (D6, "The silence
  bound, measured"; `design-decisions.md`, "A platform fact is measured on every platform CI
  runs"): with the argv the dialect renders, record the largest gap between two stdout lines
  (a) during one foreground Bash call of at least 7 minutes — on the Linux CLI of the sandbox
  base image, or of the paid-smoke container when no image is built for the repository; (b) while
  a subagent runs one Bash call of at least 7 minutes, on macOS and on that Linux CLI. Record the
  CLI version and the gaps in D6. A gap above 60 s (a fifth of the factory's silence bound) is a
  blocker reported under `spec-mismatch.md`, never a raised constant. Must land before the first
  consumer is switched onto the runner — task 4.1 (the judge) or task 5.1 (the stages),
  whichever is reached first; tasks 4.0, 4.0a and 4.2 do not wait for it. Done 2026-11-22 in a
  decision review: 30.0 s on Linux (main agent and subagent) and on macOS (subagent), recorded
  in D6 with the conditions
- [x] 2.3c The silence bound follows the dialect's declaration (FR16, D6 "The silence bound
  follows the dialect's declaration", D8 row). TDD on virtual time. One predicate beside
  `ProgramBounds` (`:application`, `app/describe`) answers, from a describe's capabilities,
  whether the bounds on a quiet stdout apply. Consumers, each shown to take the answer from it:
  (1) `ProgramExecutor` (`ProgramExecutor.java:70`, which reads the capability itself today —
  the old way); (2) `RoundBounds`, which takes the dialect's describe with the request: with
  `streams_progress` the wait is as today, without it the wait is under the overall bound alone
  (no startup-read bound, no silence bound) and `hinted` carries no startup-read and no silence
  hint. `RoundBounds.waitOn` is the only place an agent round's wait is built. Old-way sweep,
  reported with its hits:
  `grep -rn -e 'STREAMS_PROGRESS' -e 'new ProgressWait(' --include='*.java' */src/main adapters/*/src/main`
  — every hit is the predicate, a describe that declares the capability, or a wait built from
  the predicate's answer. Extend `RoundBoundsOwnerSpec` with that scan (asserted reached). Specs:
  a fake dialect without `streams_progress` stays quiet past the silence bound and completes,
  and is killed at its overall bound; the fake dialect with it keeps today's three scenarios;
  `ProgramExecutor`'s specs stay green unchanged. Scenario markers for the two scenarios of
  "The bounds on a quiet stdout follow the dialect's declaration" of `agent-round-runner`.
  Update the `ProgramBounds` and `RoundBounds` javadoc, which say "always". Must land before
  4.1
- [x] 2.3d The liveness measurement as a spec of the paid smoke layer (FR17, D6 "A declared
  liveness is tested, per dialect"). `PaidSmokeLivenessSpec` in
  `bootstrap/src/test/groovy/…/e2e/paidsmoke/`, run by `paidSmokeTest` only (the layer is
  already excluded from `test` and from PIT). A data table: one row per dialect whose describe
  declares `streams_progress` — today `ClaudeDialect` alone — crossed with the platforms: macOS
  or Linux on the host the task runs on, and Linux in a container of the image named by
  `-PpaidSmoke.linuxImage=<image>` (an image that carries the CLI; the credential reaches the
  container by variable name, never on the argv; the image's proxy variables are unset, since
  the container runs outside a factory network). With no image named the container rows fail
  with a message naming the property — never a silent skip. Each row starts the real CLI with
  the argv the dialect renders for a `transform` request, with the prompt asking for one quiet
  tool call of 420 s (`perl -e 'select(undef, undef, undef, 420)'`; the Claude CLI refuses a
  bare `sleep`) — once in the main agent and, where the row says the dialect has subagents,
  once inside a subagent — timestamps every stdout line on arrival, and asserts: the call ran
  to its end (the final answer arrived after 420 s), and the largest gap between two lines is
  at most `ProgramBounds.DEFAULTS.silence()` divided by five. The failure message names the
  dialect, the platform, the CLI version and the gap. Scenario markers for the two scenarios of
  "Claude's liveness cadence" of `claude-dialect`. Add to `docs/guides/adapter-author-guide.md`,
  where describe's capabilities are explained: an executor declares `streams_progress` only
  when it writes to stdout at least every fifth of the silence bound during its longest quiet
  step, and a dialect proves it with a green row of this spec on both platforms before the
  declaration is merged. This task writes the spec and the guide text; it is not a gate for 4.1
  (the measurement of 2.3b holds that gate). The human runs `paidSmokeTest` on both platforms
  before the change is archived (task 7.3a)
- F107 (carried from `tighten-spec-contract`): since that change, the agent CLI's credentials and
  every child-environment value reach a round only through `SecretsProvider`: the run's
  `ChildEnvAllowlist` resolves them through the `ChildEnvValues` role interface the root binds at
  `CheckEquipment` (`childEnv` for the run path, `installationChildEnv` for the container path),
  `AgentAiSeam.variables(childEnv)` reads the five seam names through `ChildEnvAllowlist.resolve`,
  and `EnvFileSecretsProvider` is the only production reader of the process environment
  (`SecretsPortBoundarySpec`). Deleting `AgentAiSeam` in 2.3 keeps that route: the names
  `ClaudeDialect.describe` declares are resolved through the run's allowlist, never through
  `System.getenv` and never through a new `ChildEnvAllowlist.of` overload without a value source;
  `AgentCredentialSeamBoundarySpec` moves its one allowed file from `AgentAiSeam` to the dialect,
  and `AgentSecretsFolderRoundSpec` (a host-folder token reaching every round and vote) stays
  green. Note that the allowlist's declared credential names are a withheld set — `compose` and
  `resolve` strip them — so the dialect's credential names must not be added through `declaring`.
- [x] 2.4 `--json-schema` for `verdict` and `decision` answers with final-text fallback at WARN;
  `JudgeVerdictExtractor` reduced to SARIF conversion; malformed → malformed answer (FR3, FR4)
- [x] 2.5 No session continuation in the agent runner (FR9, NFR-R2, FR6; D4 as amended
  2026-11-21 — read D4 first: the resume half built in the earlier round is withdrawn, not
  finished). Remove, with their specs: `--resume` rendering and the refusal recognition
  (`claude/ClaudeSession`), the refused-resume fallback and its WARN (`runner/SessionContinuation`,
  `runner/RoundAttempt`, the retry in `AgentRound`, the refusal arm of `RoundJudgement`),
  `AgentDialect.resumeRefused` and `AgentDialect.continuation`, `ContinuationKey`, the
  continuation branch of `claude/ClaudePrompt`, the operator event `AGENT_RESUME_REFUSED` (GF192)
  with its catalog entry, and `Capability.RESUMABLE` in `ClaudeDialect.describe`; `FakeDialect`
  and the `UntrustedTextGateSpec` lists follow. Keep one thing of `SessionContinuation`: the
  runner writes a `decision-request` answer with no `continuationKey`, removing one the agent
  wrote. Delete `AgentRoundContinuationSpec` and `ClaudeDialectContinuationSpec` together with
  their markers `executor/agent-round-runner / Unknown session falls back` and
  `executor/claude-dialect / Resume flag on continuation` (both scenarios are gone from the
  deltas; a marker left behind is dangling). New features, markers included:
  `executor/agent-round-runner / An agent-written continuation key is removed`,
  `executor/agent-round-runner / No agent executor declares resumable` (fake dialect),
  `executor/claude-dialect / No argv carries a session flag` (every role, with and without a
  continuation on the request), and `executor/wire-contract / A returned continuation key is not
  sent back` (a `program` round whose answer carries a key, then the next request built by
  `ProgramRequests`). The old-path `AgentRoundResult.sessionId` stays until 5.1 deletes its
  class. Sweep, reported with its hits:
  `grep -rn -e '"--resume"' -e RESUME_FLAG -e resumeRefused -e ContinuationKey -e AGENT_RESUME_REFUSED -e GF192 -e 'RESUMABLE' --include='*.java' --include='*.groovy' . | grep -v /build/`
  — hits only in `executor-contract` (the wire vocabulary, `Capability.RESUMABLE` among it), the
  conformance kit, and the new features asserting absence; `GitFlagsValidator`'s `gnomish
  --resume` is unrelated
- [x] 2.5a A decision records and renders the question it answers (FR9, NFR-R2; D4 as amended,
  D8 row "The question a decision answers", D9). TDD. (1) `Decision` (`domain/engine`) gains the
  optional question and options it answers, carried as the `UntrustedText` they were minted in.
  (2) One builder of a decision from the position, the consumed outcome's report, the reply, the
  author and the instant; `ResumeDecisionCommit.decisionFor` (`ResumeDecisionCommit.java:36`,
  author `tracker`) and `EscalationResume.appendDecision` (`EscalationResume.java:127`, author
  `operator`) both call it and lose their own copy of the position switch; the question is set
  only when the consumed outcome is `escalated` with a decision-request report, read from the
  registered commit's `task.json` through the parse the resume already uses — never from
  `lastEscalation` after a later write. (3) `TaskJsonMapper`: the `decisions` entries carry the
  question and options; an entry without them reads as before and is written back unchanged
  (features for the three scenarios of `git/git-task-persistence`, "A decision records the
  question it answers", markers included, host and container mode). (4)
  `BriefingSections.renderDecisions` renders question, options, reply for such a decision and
  author and body otherwise — the one renderer, so the stage round prompt and the judge prompt
  follow (scenarios of `executor/agent-executor`, "A decision is rendered with the question it
  answers"; the third, "Any instance starts the round after an answer alike", is the identity
  spec below). Consumers that read the body only and stay as they are: `StatusTextRenderer`,
  `StatusReport`, `DecisionAck`. Gate `DecisionOwnerSpec` (`:bootstrap`): `new Decision(` in main
  sources only in the builder and `TaskJsonMapper`, the allowlist asserted reached. Identity spec
  `DecisionQuestionIdentitySpec` on a real bare origin, host and container mode: park on a
  decision request, answer, resume from a second clone — the question and options of the park
  commit's report equal those of the decision in the decision commit and those in the next
  round's prompt. Sweep, reported with its hits:
  `grep -rn -e 'new Decision(' -e 'renderDecisions(' -e 'decisions()' --include='*.java' */src/main adapters/*/src/main`.
  Before 4.2 and 5.1
- [x] 2.6 Old-way sweep: `grep -rn "stream-json\|ANTHROPIC_\|acceptEdits\|dontAsk"
  --include=*.java */src/main adapters/*/src/main` → hits only under `adapters/agent/claude`;
  ArchUnit rule pinning it (M2, D8)

## 3. Answer file replaces the decision file

- [x] 3.1 Add `EnvelopePaths.answerFile`; runner hands `GNOMISH_ANSWER_FILE`; `BranchDecisionFile`
  reads it through `ResultFileReader`; delete `DecisionFileTransport`; remove the
  `GNOMISH_DECISION_FILE` literal everywhere (grep → zero); envelope-paths gate extended;
  kill-point specs of the decision path re-pointed (FR9, D3, D9)
- [x] 3.2 Remove the `DecisionFileTransport` ↔ `BranchDecisionFile` registry row and markers; the
  `agent-executor` requirement "Decision file is read through the shared result reader" is
  REMOVED by this change's delta (superseded by "Decision request is the answer file"): its two
  scenario markers retire with the others in the section-5 note, and the feature for the new
  scenario "Answer file read is capped" carries its marker (FR9)

## 4. Judge on the contract

Rebuilt 2026-11-22 after the decision review of task 4.1 (design D2, D12, D13). The order is
provider, then consumers, then deletion, and the build is green after each task. Work the tasks
of sections 4 and 5 **in the order they are written here**, which is not the order of their
numbers: 4.0, 4.0a, 4.2, then — only once 2.3b is ticked (done 2026-11-22) and 2.3c has landed — 4.1, 4.3, 5.1, 5.1a, 5.2, 5.3. The
numbers 4.1, 4.2 and 5.1 are kept because earlier tasks and code comments cite them. If you
reach 4.1 while 2.3c is still open, stop and report that; do not switch a consumer.

- [x] 4.0 One owner of the guard probe (FR15, D13, M6). **Do this first; nothing else in
  sections 4 and 5 starts before it is done.** A decorator over the contract's `Executor`, in
  `adapters/agent`, that calls `environment.probeGuard()` before `start` and `poll` and after
  each returns, and answers `failed/infrastructure` naming the guard when the probe throws
  (the throwable's message as container text, as `JudgeCannotVerify.ifGuardDead` renders it
  today). Applied where an executor is bound to an environment: the stage half
  (`RoundExecutorFactory.executor(environment)` as `ProgramStageExecutor` uses it) and the check
  half (`ProgramCheckBinding.bind`, over the fresh box). Consumers, each visited: `program`
  stage rounds, `program` check rounds in a fresh box (unprobed on `main` — the defect this task
  fixes), and, when 4.0a builds it, both halves of the `claude` entry. Old-way sweep:
  `grep -rn "probeGuard(" --include='*.java'` over main sources; the calls at
  `ProgramStageExecutor.java:63,65` are replaced by the decorator in this task; the calls at
  `ExecutorRoundExecution.java:111,138` and `JudgeCannotVerify.java:68` are listed in the gate's
  allowlist with the tasks that delete them (5.1, 4.1) and leave it there. Gate:
  `GuardProbeOwnerSpec` (`:bootstrap`) — `probeGuard(` in main sources only in the decorator,
  the environment types that declare or forward it (`TaskExecutionEnvironment`,
  `SelfCheckedEnvironment`, `LeasedEnvironment`, `WireObservation`) and the two dated rows, the
  allowlist asserted reached. Specs, TDD: the decorator on a fake environment whose probe
  throws before the request (no `start` is issued) and after it (the executor's answer is
  replaced); a `program` verdict check bound in a fresh box with a dead guard is cannot-verify
  and burns no attempt. Scenario markers for the two scenarios added to "Guard outage is an
  infrastructure failure" (`sandbox/sandbox-egress`)
- [x] 4.0a The `claude` registry entry, with no consumer (FR14, D12). Reserve the name: `claude`
  joins `BuiltInExecutors`, so a law declaring an executor of that name is refused at load by
  the existing rule. Build the entry in `ExecutorRegistry` in the shape of
  `RegistryEntry.program`: describe from `ClaudeDialect`; a stage half — a
  `RoundExecutorFactory` whose executor is `AgentRoundRunner` over the Claude dialect bound to
  the round's environment, behind the 4.0 decorator; a check half — the runner bound to the
  check's fresh box from `FreshBoxSource` in a run that has boxes, and to the host environment
  in a run that has none (design D2: the judge's stated exemption; `ProgramCheckPlacement`
  keeps refusing a law's `program` there), behind the same decorator. This is the first
  production construction of `AgentRoundRunner`. Nothing resolves the entry yet:
  `ExecutorDispatch`'s agent arm and `CliJudgeVoter` are untouched, and no behaviour changes.
  Specs: the registry resolves `claude` to an entry whose describe lists the three roles; a law
  naming an executor `claude` is a located load error; both halves bind and answer through the
  fake dialect. Scenario marker for "An agent block resolves to the reserved executor name"
  (`pipeline/pipeline-config`)
- [x] 4.2 The `judge` check declares the stage's executor block (FR4, FR13, UX1, D12).
  Consumers of the block, each visited: `StageDefinition.Executor` (a stage), `VerifyCheck.Judge`
  (the check now carries `criteriaFile` and that same executor type, and no `model`,
  `settings` or `votes` of its own), the DTO and mapper of both (`VerifyCheckDto`,
  `VerifyCheckMapper` — one mapping function for the block, called from the stage mapper and
  the check mapper), `StageMechanismRule` (the one rule: an agent block pins its model; called
  for the judge's block too, located at `verify[i].executor.*`), `ExecutorReferenceRule` (a
  judge's `program` block names a declared executor), `AgentSettingsValidator` (the judge's
  settings are the block's), and a role rule: the executor a judge block resolves to lists
  `verdict` in its describe, else a located load error. Old way removed in this task:
  `StageSanityRule.validateJudge`'s own model rule and vote rule; `votes`, `model` and
  `settings` as fields of the judge DTO, so each is refused as an unknown field by
  `StructuralParse`. Until 4.1, `CliJudgeVoter` reads the model and settings from the check's
  block, so the judge's behaviour is unchanged and `JudgeVoting` runs its loop once (4.1
  deletes it). `JudgePromptBuilder` renders the criteria and the briefing as the `verdict` role
  input, the text `CheckRound` will hand the request in 4.1. Gate: `ExecutorBlockOwnerSpec`
  (`:bootstrap`) — the manifest keys `model` and `settings` of an executor are read in main
  sources only by the one block mapper, the scan asserted reached. Fixtures and example
  pipelines under the test trees and `docs/` move to the new form; `.gnomish/` of this
  repository is not touched (proposal NG11). Specs and scenario markers: the other five scenarios of
  "A judge check declares the stage's executor block" (`pipeline/pipeline-config`); the marker
  of "Judge vote count must be positive and odd" stays on a feature that now asserts any
  `votes` is refused
- [x] 4.1 The judge runs as a check round; the judge path is deleted (FR4, M3, M7, D2). **Gate:
  2.3b is ticked and 2.3c has landed.** The engine's verify loop hands a `judge` check to the check-round path it
  already uses for an executor check (`checkRounds.answer` in `VerifyOrchestrator`), and
  `CheckRoundRoute` → `CheckDispatch` → the registry entry's check half → `CheckRound` →
  `VerdictDerivation` serve it. The check-round chain is typed on `VerifyCheck.Executor` today
  (`StageExecutor.Request.check()`, `CheckRoundRoute.java:37`, `CheckDispatch.bind`,
  `CheckRound.java:54`, `RegistryEntry.bound`, `CheckBinding.bind`, `PinCheck.guarding`): give
  the two check kinds what the chain reads — the executor name, the settings, the threshold,
  the pin paths, the role input — through one type, and name in the report which one you chose
  and why; do not add a second chain. `CheckRound` takes the `verdict` role input from the
  check (the criteria prompt for a judge, today's `check '<id>'` text otherwise), and the
  criteria preflight runs ahead of the request as it does ahead of a vote today. Deleted, with
  their specs: `JudgeVoter` (from `EnginePorts` and the ports package), `JudgeVoting`,
  `CliJudgeVoter`, `JudgeRoundExecution`, `JudgeCannotVerify`, `FreshJudgeEnvironments`,
  `HostJudgeEnvironmentSource`, `JudgeEnvironmentSource`, the `judgeVoter` wiring of
  `ExecutorAdapterSelector` and `RunAssembler`, and the `JudgeCannotVerify.java:68` row of
  `GuardProbeOwnerSpec`. Old-way sweep, reported with its hits:
  `grep -rn "JudgeVoter\|JudgeVoting\|judgeVoter\|FreshJudgeEnvironments\|\.votes()" --include='*.java' --include='*.groovy'`
  → zero in main sources (the usage type's `votes` list and the `vote` ordinal stay: design
  D2). Specs, TDD: a judge check's one verdict is the check's verdict; a malformed answer is
  cannot-verify; a stage with two `judge` checks runs them in order and never requests the
  second after the first fails; two judge checks on one attempt commit each get a box of their
  own; `EnginePorts` holds one port. Scenario markers: the four scenarios of "A judge check is
  one verdict request" (`pipeline/stage-engine`); "Two judge checks do not share a box" and
  "Poisoned round box cannot grade itself" (`verification/verification-hardening`, the second
  moved from `FreshJudgeEnvironmentsSpec`); "A judge vote is bounded by its check's
  roundTimeout" moved from `CliJudgeVoterSpec`; "A judge check classifies by its votes" stays on
  `VerifyClassificationSpec` with its body following the delta; "Judge votes are participants"
  (`observability/status-report`) on a round with two judge checks. The markers of the four
  scenarios of the removed "Judge majority voting" go with `JudgeVotingSpec` and
  `JudgeVotingCannotVerifySpec`
- [x] 4.3 Judge exemptions and the judge participant (carried from D10 of
  `define-executor-contract` and task 2.4 of `migrate-verify-to-executor-contract`):
  `OutcomeClassifierOwnerSpec` (`:bootstrap`) allowlists the judge's `new Verdict.CannotVerify(`
  sites (`JudgeVerdictExtractor`, `JudgeCriteriaPreflight`, `JudgeCannotVerify`) under this
  change's pickup; each row leaves with its class's last such construction (2.4, 4.1, D2: the
  verdict derives through `VerdictDerivation`), and the pickup leaves the spec's `PICKUPS` once
  no row names it. `Participants.JUDGE_EXECUTOR` (`domain/engine`) names every judge
  participant by the `agent-cli` type word; with 4.1 the participant is named by the registry
  entry the check's block resolved to, its `vote` the ordinal of the request among the round's
  judge checks, and the constant goes with its javadoc (which names this change)
  (FR4)

## 5. Deletions and pair dissolution

Amended 2026-11-23 after the decision review of task 5.1 (design D14, D15; D3, D7, D8, D9 as
amended). Read those first. Work in the order written here, not by number:
5.1, 5.1a, 5.2, 5.4, 5.5, then 5.6 and 5.7 once the human has ticked 5.0, then 5.3.
This stage edits no file under `.claude/`: where a task names a row of
`.claude/rules/manual-sync-pairs.md` or a line of another rule file, record it in
`implement.md` under "Open (for the human)" and tick the task without it.

- [x] 5.0 (human) Measure the schema-enforced answer on real rounds (FR22, D15 "A platform fact
  to measure"): on macOS and in the Linux image, a `transform` round of at least fifty tool
  calls that ends by asking a decision, and a `verdict` on prose-inviting acceptance criteria,
  each with `--json-schema`; record the CLI version and the outcome per row in D15. **The gnome
  does not run this and does not tick it.** Tasks 5.1–5.5 do not wait for it; 5.6 and 5.7 do. If
  you reach 5.6 while 5.0 is open, stop and report that
- [x] 5.1 The round source stays; its duties are pinned (FR18, M9, D14). The deletions of the
  first text that are already in HEAD stand: `CliStageExecutor`, `ExecutorRoundExecution` and
  their helpers, with the `ExecutorRoundExecution.java:111,138` row gone from
  `GuardProbeOwnerSpec` (M6). `HostRoundEnvironmentSource` and `SandboxRoundEnvironmentSource`
  are **not** deleted, and the stage half of the `claude` entry keeps opening its round through
  the run's one round source, as every entry does (`ExecutorDispatch.java:67`).
  Write the port contract spec of `RoundEnvironmentSource` in `:test-fixtures` (an abstract
  spec, as the tracker contracts are) and run it from the module of each implementation:
  `HostRoundEnvironmentSource`, `MidRoundPushRounds` over it, `SandboxRoundEnvironmentSource`.
  One feature per row of D14's duty table that holds for every medium: an open round names an
  answer path and an identity; a close ends the round's environment duties; a discard after an
  open leaves no scratch area behind. An architecture spec in `:bootstrap` lists the main-source
  implementations of the port and asserts each is a subject of the contract spec (M9).
  `Round.readDecision`: its value has no reader (`ProgramStageExecutor.java:91` discards it) —
  remove it from the port and its three implementations, or report the consumer you found; the
  sweep is `grep -rn "readDecision" --include='*.java'` over main sources.
  `ResumeVerificationStageExecutor` is **not** deleted: it stays the one
  recovery owner of an interrupted verification (`task-branch-contract`, "An interrupted
  verification is InProgress with one recovery owner") and decorates the chain over the `claude`
  executor as it did the old arm; its pending-round subject is task 6.5. The `claude` entry's
  `transform` request carries, as its instructions, the round prompt `ExecutorPromptBuilder`
  composes (`executor/agent-executor`, "Round prompt composition"), so the question and the
  reply of 2.5a reach the CLI's stdin unchanged; no request carries a continuation (D4, NG8)
  (D7, D8, D14)
- [x] 5.1a Dispatch arm (carried from D8 and the `ExecutorRegistry` row of D10 of
  `define-executor-contract`): `ExecutorDispatch` (`:bootstrap`) sends a `program` stage to the
  registry by `executor.name` and every other stage to one type-keyed `agent-cli` arm, kept "until
  `extract-agent-round-runner` makes the agent a named executor"; with the `claude` entry of
  4.0a serving stage rounds the agent arm is deleted and an agent stage resolves through
  `ExecutorRegistry` like any other, under the reserved name (D12); the `COMMAND` arm stays
  (design D6 of `add-command-executor`). The switch is already in HEAD: verify it against this
  text and tick (D8)
- [x] 5.2 Pairs (D7 as amended, M1). Remove the code markers of the dissolved pairs
  (`ExecutorRoundExecution` ↔ `JudgeRoundExecution`, `RoundTimeout` ↔ `AgentSettingsValidator`)
  where any is left. Collapse `MidRoundPushRounds` ↔ `SandboxRoundEnvironmentSource` into one
  round-token minting step in `adapters/git` that the host decoration and the sandbox round
  source both call (`RoundToken.of` over a `VerifiedTip`-checked tip, into `CurrentRound`), and
  remove both `Kept in sync with` markers between those two. Keep the markers of
  `HostRoundEnvironmentSource` ↔ `SandboxRoundEnvironmentSource` and reword both to what the
  port contract spec of 5.1 does not hold. `grep -rn "Kept in sync with" adapters/agent` → the
  one file `HostRoundEnvironmentSource`; no marker names a deleted class. The registry rows in
  `.claude/rules/manual-sync-pairs.md` are the human's: list the rows to remove and the row to
  reword in `implement.md`
- [x] 5.4 One read of the answer file, live and at recovery (FR21, NFR-R5, M8, D15 part 4; D8
  row "The answer read"). Consumers, each visited: `RoundJudgement` (the live agent round),
  `ProgramResultRead` (a `program` and a built-in), `BranchDecisionFile`, `SnapshotTipCheck`
  (recovery at the register), `HostRoundEnvironmentSource`'s read at close. One function in
  `:application`, over `ResultFileReader` and the contract's `Answer.read`, returns absent, a
  contract answer, or refused with its cause; one function maps a refused reading to the round's
  result, given whether the round's executor is an agent (5.5) or a program (unchanged:
  `failed/infrastructure`), and the live path and the recovery both call it. Delete `DecisionRequestRead` with its
  raw-text and empty-file fallbacks, and the operator events only they raise
  (`DECISION_FILE_NOT_JSON`, `DECISION_FILE_EMPTY`) if the sweep shows no other writer. Old-way
  sweep: `grep -rn "DecisionRequestRead\|RoundDecisionRequest\|readJson(" --include='*.java'`
  over main sources, each hit routed, removed or reported. Extend `ResultPathReadBoundarySpec`
  to the one function. Identity spec `AnswerReadIdentitySpec` on a real bare origin: the same
  bytes at a round's answer path give the same round outcome live and from a snapshot resumed by
  a second instance — one row a valid decision request, one row text that is not JSON
- [x] 5.5 An invalid `transform` answer is a quality failure (FR20, D15 part 3). In the runner's
  judgement of an exited round: a refused reading of a `transform` answer — unparseable, empty,
  over the cap, refused by the contract, or the dialect reporting its structured-output retries
  exhausted — becomes `failed/quality` with one finding naming the cause and carrying a bounded
  excerpt minted as agent text; `verdict` and `decision` keep the malformed answer of today (a
  cannot-verify); a round with no result event, a kill on a bound, a dead process stay
  `failed/infrastructure`. No second request is started for the round. One feature per scenario
  of `executor/agent-round-runner`, "An invalid transform answer is a quality failure", markers
  included, and one **flow-level** spec through the composition root's own wiring (host and
  container) for "An unparseable answer file burns one attempt": the next attempt's prompt
  carries the finding. The seven `Container*Spec` features red at HEAD
  (`ContainerGitModeRunnerSpec` ×4, `ContainerResumeRunnerSpec` ×2, `ContainerTerminalDriveSpec`
  ×1) are brought onto a contract-shaped answer or onto the new outcome, whichever the feature's
  own subject is; none is deleted, skipped or weakened, and each is named in `implement.md` with
  what changed
- [x] 5.6 The answer form is the dialect's declaration; Claude answers every role by schema
  (FR19, FR3, D15 parts 1 and 2; D3 as amended; D8 row "How an answer is obtained"). **Gate: 5.0
  is ticked.** Premise check first, with `file:line`: the admission of a box round permits the
  answer path whoever wrote the file (`AdmissionRule`), and a file put into the box's working
  copy before the close is carried by the snapshot commit; if either fails, stop under
  `spec-mismatch.md`. Then: a sealed declaration on `AgentDialect`, per role — schema-enforced,
  or a file the agent writes; `ClaudeAnswerSchema` gains the `transform` reply schema (done, or a
  decision request with its question and options) and all three schemas are written in the
  common subset (object root, closed objects, every property required, no combinator at the
  root); `ClaudeAnswer` judges a `transform` reply as it judges the other two; the runner writes
  a valid schema-enforced answer to the round's answer path (`putFile` in a box) before the
  round closes; `ClaudePolicyCompiler` grants no write under `.gnomish-task/`, `writeExactly`
  reaching only a file-form dialect (the fake dialect keeps the file form, so both forms stay
  under spec); the prompt's answer instruction comes from the dialect and
  `ExecutorPromptBuilder` spells neither form. One feature per scenario of
  `executor/agent-round-runner`, "Answers are obtained as the dialect declares",
  `executor/agent-executor`, "The answer instruction is the dialect's", and
  `executor/claude-dialect`, "Schema-enforced answers", markers included
- [x] 5.7 The measurement as a spec of the paid smoke layer (FR22): `PaidSmokeStructuredAnswerSpec`
  beside `PaidSmokeLivenessSpec`, one row per dialect declaring a schema-enforced answer, the
  two scenarios of `executor/claude-dialect`, "Claude's schema-enforced answer holds on real
  rounds", on macOS and in the Linux image, markers included. **Gate: 5.0 is ticked.** The spec
  is written and compiled here; its paid run is task 7.3b
- [x] 5.3 Glossary: agent dialect, agent round runner, answer file; the "continuation key"
  entry updated to D4 as amended (reserved vocabulary; names state any factory instance reaches;
  no agent executor returns one; *Not:* a vendor session id); the "Decision" entry gains the
  question it answers; update the `Executor` and `Judge` entries; the "answer file" entry says who
  writes it under each answer form and that it has one read; new entry "answer form" (D15)
- Spec contract (`tighten-spec-contract`): this change REMOVES seven `agent-executor`
  requirements, the `stage-engine` requirement "Judge majority voting" and the
  `executor/result-channel` requirement "Decision and findings files are result files" (its
  markers: `grep -rn "Scenario: executor/result-channel / Garbage"`). Each REMOVED reason of the
  two decision-file requirements and of the result-channel one names the fate of every scenario:
  a marker of a scenario moved with its title kept stays where it is; a marker of a dropped
  scenario goes with its test or is retargeted to the scenario named as its replacement. Every
  scenario this amendment ADDs gets its marker with its test (tasks 5.1, 5.4–5.7). The scenarios
  of the removed requirements carry
  `// Scenario: executor/agent-executor / <title>` and `// Scenario: pipeline/stage-engine / <title>` markers
  (find every one with grep for `Scenario: executor/agent-executor /` and
  `Scenario: pipeline/stage-engine / <removed title>`). In this same change, every marker of a
  removed scenario goes with the test it sits on (or is retargeted to a scenario this change
  adds), and any exemption line naming a removed scenario is deleted, so `checkSpecContract`
  reports no marker or exemption naming a scenario that no longer exists.

## 6. Transform `pending` through the one pending-poll owner

Human decision 2026-11-02; design D11. Consumers of the record (D8 row `PendingPoll`):
`StageAttemptLoop` (hands a `transform` `pending` to `PendingPoll`), `PendingPoll` (live and
resumed), the `persistPending` implementations of `GitAttemptPersistence` and
`EnvironmentAttemptPersistence` (write the pending round commit), `StateJsonMapper`/`TaskState`
(the `pendingPolls` list), `ResumeVerificationStageExecutor` (the recogniser and one recovery
owner). Old way: the `transform` arm "a
`pending` the stage cannot poll" in `RoundFailures`, `AttemptAccounting`, `RoundOutcome`.

- [x] 6.1 Premise check (`spec-mismatch.md`, item 1): confirm with `file:line` that
  `migrate-verify-to-executor-contract` landed `PendingPoll`, `PendingPollRecord` as entries of
  `TaskState.pendingPolls`, `AttemptPersistence.persistPending` and the recogniser in
  `ResumeVerificationStageExecutor`, and task 4.3a's resumable `program` poll (no per-instance `PendingRuns` membership on
  the poll a persisted record drives, superseded and ended keys included), its key rotation
  (FR10 there, "A changed pending key is persisted before the next poll"), and its wire
  obligation with the kit's `pending-repoll` case (FR11, FR12 there). If any is missing, stop and
  report under that rule before 6.2 (FR10, FR11, FR12)
- [x] 6.2 Record subject: `PendingPollRecord` gains the sealed subject `Check(CheckRef)` |
  `Round` (the round token is already on the record); `StateJsonMapper` carries it in the
  `pendingPolls` list; wire round-trip spec iterating every subject
  and pinning the unknown-token arm; a record whose round token does not name the round the
  registered commit holds open is refused, never polled (FR10, NFR-R3, D11)
- [ ] 6.3 Engine path, TDD on virtual time with a fake `transform` executor declaring `poll`:
  narrow the refusal in `RoundFailures`/`AttemptAccounting`/`RoundOutcome` to executors without
  `poll`, re-point `ExecutorOutcomeTableSpec`'s marker feature to the reworded scenario and add the
  pollable row; `StageAttemptLoop` hands `pending` to `PendingPoll`; pending interval and ceiling
  as factory-owned bounds beside `ProgramBounds`, and a `PendingWait` construction from them beside
  the check-scoped one (pace `max(interval, pollAfter)`, no in-poll wait bound on the request,
  NG13 of `migrate-verify-to-executor-contract`); ceiling counted from `since`; no verify and no
  advancement on a pending round commit; closing commit drops the record; one feature per
  scenario of the `pipeline/stage-engine` and `executor/wire-contract` deltas, markers included
  (FR10, FR11, NFR-O2, UX3)
- [ ] 6.4 Pending round commit at both ends of the persistence pair: the `Round` subject on the
  entry `persistPending` already writes (the pending commit of `git-task-persistence`, "Pending
  commits of an open round"); the host snapshot trigger (D22 of
  `migrate-verify-to-executor-contract`, `completed` only) widened to a `pending` `transform`
  result; the container's round close given a pending-round subject; the registry row of the pair
  names the pending commit's subject rule; specs per medium (FR10, D7, D11)
- [ ] 6.5 One path, live and resumed, on the landed rule of `PendingPoll` (D11): the live instance
  continues from the entry it persisted, a resuming instance from the entry parsed at the register,
  both through `PendingPoll`'s one wait path; `ResumeVerificationStageExecutor` gains the
  pending-round subject — an entry with subject `Round` resumes through `PendingPoll` with no
  `start` and is never answered `Completed`, and a pending-round snapshot with no pending commit
  above it is handed back to the round (K1), never re-verified; the resume materializes the
  environment at the registered commit; identity spec `PendingRoundIdentitySpec`
  on a real bare origin — the round token, the usage participants and the ledger lines of a round
  closed by a second instance equal those of the round that went pending (FR11, NFR-R4, D11)
- [ ] 6.6 Key rotation for a round (FR12, reuse): `migrate-verify-to-executor-contract` owns the
  rule (its FR10, D12; proposal Q3, closed 2026-11-02); this task adds only the `Round` subject's
  case — the new key lands in a pending round commit through the persistence pair of 6.4, the
  attempt stays open — with a feature for the `pipeline/stage-engine` scenario "A changed key of a
  pending round lands in a pending round commit", marker included; no second rotation path
- [ ] 6.7 Kill-point specs on a real bare origin for windows K1–K4 and after the round close
  (design D11), each resumed by a second instance, each followed by a second recovery pass that
  changes nothing; K1 — killed before the snapshot, and with the pending-round snapshot registered
  but no pending commit — asserts the re-driven `start` is in the ledger; K3 and K4 assert no `start`
  (the fixture executor conforms to "A pending key stays pollable until its run ends"), K4 also
  the scenario "Kill after the terminal poll closes the round without a start", marker included
  (NFR-R3, NFR-C2)
- [ ] 6.8 Owner gate and sweep: `ExecutorPollOwnerSpec` (`:bootstrap`) — `.poll(` on an `Executor`
  in `domain`/`application` main sources only in `PendingPoll`, the allowlist asserted reached;
  sweep `grep -rn "cannot poll\|PendingRuns\|\.poll(" --include='*.java' */src/main
  adapters/*/src/main` and report each hit as routed, removed or exempt with its reason (D8)
- [ ] 6.9 Glossary: "pending key" entry (from `migrate-verify-to-executor-contract`) extended to
  the `transform` round; new entry "pending round commit"

## 7. Integration

- [ ] 7.1 Packaged-jar end-to-end of M5: a `program` transform executor declaring `poll` against a
  fixture service, killed after the pending commit and resumed by a second instance (one
  `start`, the round closes with the service's changes, second recovery a no-op), and killed
  before it (two `start`s, both in the ledger) (M5, G5)
- [ ] 7.2 Packaged-jar end-to-end with the fake agent binary through the Claude dialect: transform
  round, judge vote, decision request → answer → a fresh round started by a second instance whose
  recorded stdin carries the question, its options and the reply and whose recorded argv carries
  no session flag; ledger asserts
  `executor=claude@<version>` (NFR-O1); and a round whose fake agent leaves an invalid
  answer: one attempt burned, the next round's recorded stdin carries the finding (FR20)
- [ ] 7.3 `./gradlew check` with PIT; sweep report for D8 rows; recommend a commit message
- [ ] 7.3a (human) `./gradlew :bootstrap:paidSmokeTest --tests '*PaidSmokeLivenessSpec*' -PpaidSmoke.linuxImage=<image>`
  green on macOS and on the Linux container rows; record the CLI version and the largest gap
  per row in D6 (FR17). A gap above the threshold is reported under `spec-mismatch.md`
- [ ] 7.3b (human) `./gradlew :bootstrap:paidSmokeTest --tests '*PaidSmokeStructuredAnswerSpec*' -PpaidSmoke.linuxImage=<image>`
  green on macOS and on the Linux container rows; record the CLI version per row in D15 (FR22).
  A red row is reported under `spec-mismatch.md`
