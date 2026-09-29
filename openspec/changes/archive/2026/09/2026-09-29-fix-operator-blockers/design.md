# Design: fix-operator-blockers

## Context

Driven by FR1–FR17 of the proposal; the defects and their evidence are in
proposal.md, "Why". Current state that shapes the approach:

- The agent argv is assembled in two steps inside `adapters/agent`:
  `AgentInvocationOptions.renderForExecutor` / `renderForJudge` render the
  role's flags (`--model`, `--allowedTools`, ...), and
  `AgentCommandLine.fromRenderedFlags` wraps them with `-p` and the transport
  flags. The only production callers are `ExecutorRoundExecution` and
  `JudgeRoundExecution`; the role-less `AgentInvocationOptions.render` has no
  production caller. The same argv serves host and container mode.
- `AgentAiSeam` is the one place that forwards provider variables
  (`ANTHROPIC_BASE_URL`, `ANTHROPIC_AUTH_TOKEN`, `ANTHROPIC_MODEL`) to agent
  rounds and judge votes as factory-set variables. `ChildEnvAllowlist.compose`
  removes declared credential names from the whole composed environment,
  factory-set layer included.
- `BindingProperties` is a record whose component carries `@Name("default")`,
  but it also declares an explicit canonical constructor; a record component
  annotation propagates to the parameters of an implicit or compact canonical
  constructor only, so Spring binds the parameter name `default-binding`.
- Seven `*ArgumentsParser` classes each read `--dir` with their own
  `Path.of(value)` and none rejects an option it does not know. `status` and
  `usage` refuse an absent `--dir` with a usage error; the other five default
  it to `Path.of(".")`. `ArgumentsParsingSupport` already holds the shared
  flag helpers.
- The relative path does its damage in `gitobjects`: `GitExec` launches git
  with `directory(gitDir)` and also passes `--git-dir=<gitDir>`, so a relative
  `./.git` resolves inside itself (proposal Q2, reproduced in task 1.1).
  `CommitBuilder` hands its temporary index to the same process as
  `GIT_INDEX_FILE`, a path git also resolves against that working directory.
  `GitExec` is a record built only by `GitObjects.open`; the production
  callers are `LawSources.gitObjectsOf` and `ContainerRunSupport`, both fed
  from `--dir`.
- The installed CLI (2.1.283) offers `--permission-mode` with
  `acceptEdits | auto | bypassPermissions | manual | dontAsk | plan`,
  `--strict-mcp-config`, and a print-mode `--permission-prompts none` switch
  ("anything that would prompt is denied automatically").
- `GithubHttpClient.doSend` restores the interrupt but wraps
  `InterruptedException` in `GithubHttpUncheckedIOException` — the one type
  its retry policy retries — and `send` wraps whatever escapes in
  `GithubHttpException`, which `GithubTransport` turns into a retryable
  tracker outage and `GithubWorkflowRunPoll.poll` into a `CannotVerify`
  verdict. `Reaper.reapOnce`, `FinishedDecline.declineObserved`,
  `TrackerHealthTracker.call` and `HeartbeatBeater.beat` catch
  `RuntimeException` without looking at the interrupt. `FeedOutageRetry`
  already checks `Thread.interrupted()` before it logs — the one site that
  gets it right.
- The branch classifier checks delivery first (`BranchShapeClassifier`), and
  the fact comes from `GitShowTip.cleanupCommit`, which ran
  `git rev-list --max-count=1 --fixed-strings --grep="gnomish: cleanup" <tip>`
  over everything reachable from the tip. Every reader of a shape reaches it
  through `RefTipSource`: `BranchStateReader` and `TaskBranchLister`
  (`status`), `GitTaskBranches` (the pickup), `ContainerTipReader` (container
  mode); `DeliveredBranchReader` uses the located commit id itself. The
  cleanup commit is written only on `COMPLETED`
  (`GitObjectsTerminalCommits.cleanUp`, `CleanupCommit.commit`); escalation
  writes none. `GitObjects.historyContains` performs the same whole-history
  search but has no production caller.
- `StreamJsonEventMapper.toResult` skipped every `result` line without a
  `result` field. The CLI's result message carries that field on `success`
  only; its `error_*` subtypes carry `errors[]`, `num_turns` and
  `terminal_reason` instead. The javadoc of `AgentEvent.ResultEvent#subtype`
  already names `error_max_turns` as a subtype the event carries.

## Decisions

**D1 — The permission mode is a property of the role, carried by a type.**
A package-private `AgentRole` enum (`EXECUTOR`, `JUDGE`) owns the role's
permission-mode token — `acceptEdits` for the executor, `dontAsk` for the
judge. `AgentCommandLine` takes the role as a parameter and emits
`--permission-mode <token>` together with `--strict-mcp-config` next to the
transport flags, so no argv can be assembled without a role and both policy
flags come from one place (FR1–FR4, NFR-S1, NFR-S2). The role-less
`AgentInvocationOptions.render` is deleted: it is the escape hatch through
which a future caller could launch a round with no mode.
*Rationale:* the mode differs by role and by nothing else, exactly like the
judge's read-only tool set, which the spec already classes as adapter policy.
A manifest key would let the repository under work — which a pull request can
change — widen the gnome's permissions up to `bypassPermissions` (NFR-S1).
*Alternatives rejected:* a manifest `settings.permissionMode` key (repository-
authored widening; the gf-tests finding suggested it, but the security
direction is wrong); a `factory.agent-cli-permission-mode` property (a knob
with no known reason to turn it, and one more key for `add-project-registry`
to classify — NG3); `plan` for the judge (a planning mode that changes how the
agent answers, not a "deny without asking" mode); `--permission-prompts none`
for the judge (it states print mode's default — nobody answers — rather than a
mode; it would be a second flag for what `dontAsk` already says, and it does
not answer Q1 either, which is why task 6.2 records whether the judge round
prompted at all); a hard-coded string in each `*RoundExecution` (two owners of
one policy).

**D2 — MCP exclusion is a transport-level constant, not per role.**
`--strict-mcp-config` with no `--mcp-config` means "no MCP servers at all". It
applies to both roles, so it lives in `AgentCommandLine` beside
`--output-format stream-json --verbose`, not in the per-role renderer (FR3,
NFR-S2, NFR-C1).
*Alternative rejected:* passing a factory-generated empty `--mcp-config` file
— same effect, plus a file to create and clean per round.

**D3 — Agent credentials join the AI seam; they are not declared credentials.**
`AgentAiSeam.NAMES` gains `CLAUDE_CODE_OAUTH_TOKEN` and `ANTHROPIC_API_KEY`
(FR5). The seam is already applied by the two round executions only, which is
precisely the reach NFR-S3 asks for, and it needs no operator configuration.
*Rationale:* the gf-tests operator forwarded the token through
`factory.sandbox.env-passthrough`, which hands it to every exec in the box,
command checks included.
*Alternative rejected:* declaring the two names as credentials so the
passthrough refuses them — `ChildEnvAllowlist.compose` strips declared names
from the factory-set layer too, so the seam's own values would be deleted.
Splitting "refused in passthrough" from "scrubbed everywhere" is a real
improvement but a change to the child-environment contract, larger than this
change; an operator who lists the names in passthrough widens their reach
deliberately (NFR-S3), and the sandbox guide says so.

**D4 — `BindingProperties` uses a compact canonical constructor.**
The explicit constructor becomes a compact one (`public BindingProperties
{ stages = stages == null ? Map.of() : Map.copyOf(stages); }`), so
`@Name("default")` propagates to the parameter and the documented key binds
(FR6). A spec binds the record through a real Spring `Binder` from a
`MapConfigurationPropertySource` holding `factory.bindings.default` — the
existing specs only construct the record directly, which is why nothing went
red (M4).
*Alternative rejected:* putting `@Name` on the explicit constructor's
parameter — works, but leaves two declarations of the same name to drift.

**D5 — One argument owner: `ArgumentsParsingSupport`.**
Two helpers join it: `projectDir(args)` returns the `--dir` value (or the
working directory) as `toAbsolutePath().normalize()` (FR7), and
`rejectUnknownOptions(args, subcommand, accepted, positionalHints)` throws the
usage error of the cli-arguments spec (FR8, NFR-O1, UX2). Whether `--dir` is
required does not change: `projectDir` has a sibling `requiredProjectDir(args)`
that keeps the "`--dir` is required" usage error `status` and `usage` raise
today, and both resolve the same way — the requirement set of each subcommand
is not this change's business. An option name containing a dot is a Spring
property and passes; so do Spring Boot's own `--debug` and `--trace`, which
every subcommand lists as accepted. Each parser declares its accepted set as
one constant. `take` and `serve` keep their existing specific
refusals of run-only flags (`--task`, `--resume`, ...): those names stay
"known" so the specific message wins over the generic one.

The entrypoint is a consumer too. `ManualRunRunner` (`:bootstrap`) decides
whether to call `RunArgumentsParser` at all, and today it does so from its
own `RUN_FLAGS` list — a second copy of the parser's accepted set — treating
a `run` command line with none of those flags as the FR12 no-op of
add-manual-run. That is the hole the 2026-09-28 review found: `gnomish run
--tsk=x`, `gnomish --dirr=.` and `gnomish run --debug` never reach the parser
and exit 0 having done nothing, while `ManualRunRunnerSpec` pins the
`run --debug` no-op as correct. The no-op exists for one reason only — a
`@SpringBootTest` context boots `FactoryApplication` with no arguments and
the runner must not drive a pipeline — so its criterion becomes exactly that:
`args.getSourceArgs().length == 0`. Every non-empty `run` command line goes to
the parser, which rejects an unknown option (FR8) or a missing task (UX1 of
add-manual-run) before anything else; `RUN_FLAGS` is deleted with the
`run --debug` no-op scenario, which turns into the usage error it always
should have been.
*Rationale:* seven parsers each doing `Path.of` is how a relative path got
through; one helper and one data-driven spec over every `Subcommand` make a
new subcommand that skips the check fail the build. A parser-level contract
cannot see a gate in front of the parser, so the entrypoint contract is
driven through `ManualRunRunner`, and the raw-option reads are pinned by a
source scan over both modules that share the `app` package — the task 5.4
sweep was run over `application` only and missed the `bootstrap` copy.
*Alternatives rejected:* switching to a CLI-parsing library (a new dependency
and a rewrite of seven parsers for two rules); validating in each command
after parsing (the check would run after some commands already started work —
NFR-R1); keeping the entrypoint out of test contexts with a profile or
`@ConditionalOnProperty` instead of the empty-arguments criterion (seven
context specs and `ManualRunConfigurationSpec` need the runner bean present,
and the empty command line is the only form they ever start with).

**D6 — Documentation is the fix for three findings.**
The sandbox guide gains an "Agent authentication in the box" section and the
reference image README a matching paragraph: set `CLAUDE_CODE_OAUTH_TOKEN`
(from `claude setup-token`) or `ANTHROPIC_API_KEY` in the factory's
environment, add the provider host (`api.anthropic.com`) to
`factory.sandbox.egress-allowlist`, no passthrough needed (FR9, UX1). The
operator guide's deliver-stage note recommends a fine-grained token scoped to
contents and pull requests, passed through under `GH_TOKEN`, instead of the
tracker token (NG6). Every command example under `docs/` switches to
`--key=value` (NG5): `operator-guide-dashboard.md`'s recipes, the `board`
example in `operator-guide.md` (which does not work as written today — Spring
reads `--dir <path>` as a valueless option plus a positional) and the resume
line in `operator-guide-run.md`. Wrapper-script advice is removed wherever it
appears under `docs/` (UX3) — the guides and the "known gaps" note of
`docs/reference-pipelines.md`, which names the `gnomish` and `claude-gnome`
wrappers this change makes unnecessary.
*Alternative rejected:* accepting `--out path` in the parser — Spring's
`ApplicationArguments` defines options as `--key=value`; a second syntax in
one parser would make the rest inconsistent.

**D7 — The slot runner's late sinks are `volatile`; the forcing cycle is named.**
`TakeSlotRunner` receives its ledger writer (`ServeRuntimeAssembly`) and, under
`--drain`, its drain report and run-summary accumulator (`ServeShutdownWiring`)
after construction, into plain fields read on slot threads (FR10, NFR-R2). The
three fields become `volatile`; the class javadoc names what forces each late
write, as `process-invariants.md` ("Immutable after construction") requires,
and `run` keeps its explicit not-yet-attached (`null`) handling.
The ledger writer is forced by an assembly cycle: the slot runner is built
before the feed automaton, and the observability wiring that owns the ledger
appender needs the automaton. The two drain sinks are created by the drain
path itself, after assembly, because only that path exists in drain mode.
*Rationale:* every write happens today before the feed thread or the drain
call starts any slot thread, so the values are visible through `Thread.start`
ordering. That safety lives only in the call order of two classes;
`volatile` makes it a property of the field, and costs one read per slot run.
*Alternatives rejected:* constructor injection of the two drain sinks (the
rule's preferred shape, but it moves the drain-mode decision into the
assembly and changes the signatures of `ServeRuntimeAssembly`, `ServeRuntime`
and `ServeShutdownWiring`, which is outside an operator-blockers change); a
`final` holder filled later (the same late write behind one more type);
leaving the fields plain and documenting the start order (a convention, which
`implementation.md` does not accept as enforcement).

**D8 — An interrupt leaves the GitHub HTTP core as its own type.**
`doSend` throws a new public `GithubCallInterruptedException` (shared HTTP
core, `adapter.github`, unchecked) with the interrupt restored; `send`
rethrows it unwrapped. It extends `RuntimeException`, not
`GithubHttpException`, so the retry predicate (which matches only
`GithubHttpUncheckedIOException`) never retries it, and neither
`GithubTransport` nor any other `catch (GithubHttpException …)` reclassifies
it (FR11, NFR-R3). An interrupted verify-read in `GithubClaimLease` therefore
skips the best-effort delete of its own claim comment — which would fail at
once on the set interrupt anyway; the comment is resolved away by the next
reader, as for any undeleted claim. `GithubWorkflowRunPoll.poll` lets the
cancellation propagate unwrapped, deliberately: today an interrupted poll
becomes a `CannotVerify` verdict — an infrastructure escalation blaming a
GitHub that was reachable — and the polling loop's `Sleeper` swallows
interrupts, so nothing else would end the loop. Escaping `poll` ends the
slot through the engine's existing shutdown-caused classification
(`ShutdownPhase`), which is what a stop mid-poll is.
*Alternatives rejected:* a subclass of `GithubHttpException` (every existing
catch of it would turn the stop into an outage again); keeping the wrapping
and excluding it by cause inspection in the retry predicate (the escape
still reaches `GithubTransport` as a transport failure).

**D9 — Catch sites read their own thread's interrupt.**
`Reaper.reapOnce`, `FinishedDecline.declineObserved`,
`TrackerHealthTracker.call` and `HeartbeatBeater.beat` check
`Thread.currentThread().isInterrupted()` in their failure branch and, when it
is set, return quietly (DEBUG at most), skip their outage bookkeeping, and
leave the interrupt set for the loop above them (FR12, NFR-O2). The heartbeat
site is outside the idle scenario of G4 — an idle daemon holds no claims and
beats nothing — but a stop that arrives while claims are held interrupts the
heartbeat worker mid-`heartbeat` call exactly like the reaper, and its first
failure is a WARN with a stack; `beat` returns `UNCONFIRMED` without logging,
and the worker's loop above it ends on the interrupt as it does today. The interrupt is port-neutral — the
`tracker-port` requirement added here obliges every adapter to leave it set
— so `application` names no adapter type, and a spec provokes the case
without touching global state. `FeedOutageRetry` is the precedent and stays
as it is.
*Alternatives rejected:* reading `ShutdownPhase` (a process-global "the stop
began" flag, meant for killed subprocesses where no interrupt exists; it
would also silence a real outage that happens to coincide with a stop);
catching `GithubCallInterruptedException` in `application` (a layering
breach); letting the reaper rethrow to `StandingReaper.loop`, whose
`stopping` check would swallow it (fixes the reaper only, and ties quietness
to one caller's loop).

**D10 — The git-objects library refuses a relative path; it does not resolve one.**
`GitExec`'s compact constructor rejects a `gitDir` that is not absolute, and
`CommitBuilder`'s constructor a `tempDir` that is not, each with an
`IllegalArgumentException` naming the path (FR13). The argument owner of D5
stays the only place that turns an operator's directory into an absolute
path; the library only refuses the form it cannot use, so the signature of
`GitObjects.open` stops being an escape hatch (`implementation.md`, item 3)
without becoming a second resolver.
*Rationale:* the failure is a property of the library's own launch shape
(working directory = git dir), not of any one caller, so it is guarded where
that shape lives; a caller added later that skips the parsers fails at once
and names the path, instead of reporting "not a git repository" for a clone
that is one.
*Alternatives rejected:* `toAbsolutePath()` inside `GitExec` (correct today,
but a second owner of path resolution that would resolve against whatever
the JVM's working directory is, silently); dropping `directory(gitDir)`
(changes how git discovers configuration for every call the library makes —
outside an operator-blockers change); checking in `GitObjects.open` only
(the record's constructor is the one site no future factory method can
bypass).

**D11 — Delivery is searched in the task's own history only.**
`GitShowTip.cleanupCommit` first locates the task's STARTED commit — the
nearest commit on the first-parent line whose message carries
`gnomish: task started` — and then searches for the cleanup commit with
`rev-list --first-parent ... <tip> ^<started>`. No STARTED commit means no
delivery of its own (FR14, NFR-R4). The nearest STARTED commit counts only when
the `task.json` it carries names the task whose branch the revision is
(`StartedCommitOwnership`): a base that fast-forwards or rebase-merges an
earlier task holds that task's STARTED and cleanup commits on its own
first-parent line, and a branch with no STARTED commit of its own then points
at the very commit the earlier task's branch points at — only the branch name
tells them apart. The branch is the revision's full ref name (`git rev-parse
--symbolic-full-name`), so a named ref and a worktree's `HEAD` resolve alike;
a revision that names no ref, and a STARTED commit whose `task.json` is absent
or unreadable, own nothing and answer "not delivered". Nearest, because an earlier task's STARTED
commit can sit further back in the base; first-parent in the second search,
because a base merged into the task branch after it started brings its
cleanup commits in on a second parent. The fix sits in the one owner of the
fact, so `status` (both modes), the pickup and container mode change
together, and branches already classified wrongly need no migration: the
shape is read afresh on every call.
*Rationale:* the defect is a scope error of one query, not of the
classifier's rules; the classifier's "delivery first" order is contract
(`task-branch-contract`) and stays.
*Alternatives rejected:* a first-parent search with no lower bound (a base
that fast-forwards or rebase-merges an earlier task puts that task's cleanup
commit on the first-parent line); reading the task's start point from
`task.json` (the cleanup commit deletes `.gnomish-task/`, so a delivered tip
has no envelope to read it from); detecting cleanup by content — the commit
that removed `.gnomish-task/` — (a larger rewrite of a fact that already has a
message-based owner, and an earlier task's cleanup commit removed the same
path).

**D12 — A limit-ended result line is a result event with empty text.**
`StreamJsonEventMapper` accepts a `result` line without a `result` field when
its subtype starts with `error_`, as a `ResultEvent` with empty result text
and the subtype verbatim (FR15). The decision lives in two small methods
(`isResultEvent`, `resultTextOf`) outside the `@DoNotMutate` method, so the
mutation gate covers it. A `success` line without `result` is still skipped,
as the existing parser spec pins.
*Rationale:* the adapter's own contract already treats `error_max_turns` as a
subtype of the result event; dropping the line misreported a named limit as
"the agent emitted no result", an infrastructure escalation.
*Alternative rejected:* carrying `subtype`, `errors[]` and `terminal_reason`
into `AgentRoundResult` and giving a limit-ended round its own stage outcome —
that decides what such a round means for the stage, which is NG8 and needs a
change of its own; this change only restores the result event.

**D13 — A failure already reported is claimed from Spring Boot by the same
classification.** `RunExceptionReporting.calmLine(Throwable)` is the one
classification of a failure that ends a command: the operator's line, the
empty string when the callee already reported it, or `null` for an
unclassified fault (the WARN-backed fallback). `RunExceptionReporting.run`
prints from it; `ReportedFailureExceptionReporter`, a
`SpringBootExceptionReporter` registered in `:bootstrap`'s
`META-INF/spring.factories`, returns `true` for exactly the failures it
classifies, so `SpringApplication.reportFailure` registers them as logged and
writes no `Application run failed` record (FR16, NFR-O1). The exit code is
unaffected: Spring Boot maps it before reporting. `TakeExitCodeException` and
`ServeExitCodeException` join the "already reported" group — they carry an
exit code, not a fault (D16 of add-tracker-port). The classes sit in three
explicit lists rather than a multi-pattern `switch`: javac's pattern-switch
bytecode left two unkillable mutants in its restart checks.
*Rationale:* the duplicate is a reporting-ownership gap — the factory reports
and rethrows for the exit code, and the framework cannot tell — so the fix
tells the framework through its own extension point, from the owner of the
classification.
*Alternatives rejected:* setting the `SpringApplication` logger to `OFF`
(also hides a context that fails to refresh, the one failure only the
framework can report); catching the exception and calling `System.exit`
(project convention: exit codes travel as exceptions to the mappers); a
second list of types inside the reporter (a hand-synced pair of one rule).

### Sync surfaces

Sync surfaces: none — host and container mode already share one argv
assembly and one AI seam; this change adds no parallel implementation and
touches no pair in `manual-sync-pairs.md` (the `RoundTimeout` ↔
`AgentSettingsValidator` pair is untouched: the settings key set does not
change).

D13 adds no pair either: `ReportedFailureExceptionReporter` holds no list of
its own and asks `RunExceptionReporting.calmLine`, the classification the
console reporting prints from.

The interrupt check of D9 appears at five sites (four new, plus
`FeedOutageRetry`) but is not a synchronized pair: the shared part is one
JDK call, and each site's reaction is its own (a quiet return, a stopped
loop, an uncounted failure, an unconfirmed beat, an `InterruptedException`). There is no common
behaviour to drift, so no marker and no extracted helper.

The delivery search of D11 has a dormant second form:
`GitObjects.historyContains` (gitobjects) runs the same whole-history
`rev-list --grep` and has no production caller. It is not a pair today —
nothing reads delivery through it — and this change leaves it untouched; if
it is ever wired to a delivery question it must adopt D11's scope or the
defect returns (Risks).

### Single-owner mechanisms

| Owner                                                                                   | Value (type)                                                                                                    | Consumers                                                                                                                                                                                                                                                                                                                       | Old way removed                                                                                                                                                                                                                     | Enforced by                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
|-----------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `AgentRole` + `AgentCommandLine` (adapters/agent)                                       | permission-mode and MCP-exclusion flags (`AgentRole` enum → argv tokens)                                        | `ExecutorRoundExecution`, `JudgeRoundExecution`                                                                                                                                                                                                                                                                                 | the role-less `AgentInvocationOptions.render` is deleted; `fromRenderedFlags(binary, flags)` gains the required `AgentRole` parameter                                                                                               | parameter type: no argv without a role; `AgentCommandLineSpec` asserts both tokens for every `AgentRole.values()`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| `AgentAiSeam`                                                                           | agent provider variables (`Map<String,String>`, names from `NAMES`)                                             | `ExecutorRoundExecution`, `JudgeRoundExecution`                                                                                                                                                                                                                                                                                 | none survives: no other production source names the two credential variables                                                                                                                                                        | `AgentAiSeamSpec` iterates `NAMES`; `AgentCredentialSeamBoundarySpec` in `:bootstrap` (the module that owns source scans, `ClaimlessGitBoundarySpec` precedent) asserts that no production file under `adapters/` or `sandbox/` other than `AgentAiSeam` spells `CLAUDE_CODE_OAUTH_TOKEN` or `ANTHROPIC_API_KEY`, and that the scan reached both trees                                                                                                                                                                                                                                                                                                    |
| `ArgumentsParsingSupport.projectDir` / `requiredProjectDir`                             | the project directory (`Path`, always absolute and normalized)                                                  | `RunArgumentsParser`, `TakeArgumentsParser`, `ServeArgumentsParser`, `BoardArgumentsParser`, `DashboardArgumentsParser` (optional form); `StatusArgumentsParser`, `UsageArgumentsParser` (required form)                                                                                                                        | each parser's own `Path.of(value)` / `Path.of(".")` for `--dir` is deleted; the two "is required" checks move into `requiredProjectDir`                                                                                             | the `dir` component of every `*Arguments` record rejects a non-absolute path in its compact constructor; `CliArgumentsContractSpec` iterates `Subcommand.values()`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| `GitExec` compact constructor, `CommitBuilder` constructor (gitobjects)                 | "this path is absolute" for the git dir and the temporary-index dir (`Path`)                                    | `GitObjects.open` (the only builder of `GitExec` and `CommitBuilder`), reached from `LawSources.gitObjectsOf` and `ContainerRunSupport`                                                                                                                                                                                         | none to remove: no caller absolutizes today; both production callers receive an absolute path once D5 lands                                                                                                                         | the constructors throw on a relative path; a `gitobjects` spec pins both refusals, and `ManualRunRunnerSpec` runs git mode from a relative `--dir` end to end (task 5.5)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| `ArgumentsParsingSupport.rejectUnknownOptions`                                          | the usage error (`UsageException`)                                                                              | the same seven parsers; `ManualRunRunner` (`:bootstrap`), which must hand every non-empty `run` command line to `RunArgumentsParser`                                                                                                                                                                                            | none in the parsers: no parser rejects unknown options today. In the entrypoint: `ManualRunRunner.RUN_FLAGS` and its `noneMatch(args::containsOption)` gate are deleted; the FR12 no-op keeps only the empty-command-line criterion | `CliArgumentsContractSpec` feeds an unknown option to every `Subcommand` at the parser; `CliEntrypointContractSpec` (`:bootstrap`) drives an unknown option for every `Subcommand` through `ManualRunRunner.run` and asserts the usage error, plus the empty-command-line no-op; `RawOptionReadBoundarySpec` (`:bootstrap`, `ClaimlessGitBoundarySpec` precedent) scans production sources of both `application` and `bootstrap` and allows `containsOption` / `getOptionNames` only in the seven `*ArgumentsParser` files, `ArgumentsParsingSupport`, `GitFlagsValidator` and `InteractiveModeParser`, asserting the scan reached every allowlisted file |
| `GithubHttpClient.doSend`                                                               | "this call was cancelled" (`GithubCallInterruptedException`)                                                    | `GithubHttpClient.send` (rethrows unwrapped), `GithubRetryConfig`'s predicate (never retries it), `GithubTransport` (does not translate it), `GithubClaimLease` (skips the best-effort delete), `GithubWorkflowRunPoll` (lets it propagate, no `CannotVerify`)                                                                  | the `InterruptedException` → `GithubHttpUncheckedIOException` wrapping in `onInterrupted` is deleted                                                                                                                                | parameter type: the new class is not a `GithubHttpException`, so no existing catch matches it; `GithubHttpClientSpec` asserts one request and the type; `GithubWorkflowRunPollSpec` asserts the propagation                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| `GitShowTip.cleanupCommit` (adapters/git)                                               | this task's own cleanup commit (`Optional<String>` commit id)                                                   | `RefTipSource.cleanupCommitInHistory` → `BranchStateReader`, `TaskBranchLister`, `GitTaskBranches`, `ContainerTipReader` (`:bootstrap`); `DeliveredBranchReader` (the located id)                                                                                                                                               | the whole-history `rev-list --grep` in `cleanupCommit` is replaced by the scoped search; `GitObjects.historyContains` survives with no production caller (Sync surfaces, Risks)                                                     | one owner by construction: every production reader of the fact goes through `GitShowTip`; `DeliveryAncestrySpec` pins the scope on a real clone (forked base, base merged in after start, bare branch on a `--no-ff` and on a fast-forwarded base, a STARTED commit whose `task.json` names another task or cannot be read, the earlier task still delivered). No gate stops a new whole-history search from appearing elsewhere — recorded in Risks                                                                                                                                                                                                                                                                                                                           |
| `RunExceptionReporting.calmLine` (application)                                          | how a command-ending failure is reported (`@Nullable String`: line, `""` already reported, `null` unclassified) | `RunExceptionReporting.run` (prints), `ReportedFailureExceptionReporter` (claims from Spring Boot)                                                                                                                                                                                                                              | the per-family multi-catch branches of `RunExceptionReporting.run` are replaced by the one classification                                                                                                                           | `ReportedFailureExceptionReporterSpec` (claims every classified family, leaves an unclassified fault); `RunExceptionReportingSpec` (already-reported families print and log nothing); `ExitCodeMatrixSpec` on the packaged jar (one stderr line, no framework record). No gate stops a new exit-carrier type from being thrown without joining the list — recorded in Risks                                                                                                                                                                                                                                                                               |
| the calling thread's interrupt (set by the adapter, per the `tracker-port` requirement) | "the failure is the stop" (`boolean`)                                                                           | `Reaper.reapOnce` (the sweep listing, and the per-task repair — found by the task 8.3 sweep: same reaper thread, same WARN shape; an interrupted repair ends the sweep without re-arming the latch), `FinishedDecline.declineObserved`, `TrackerHealthTracker.call`, `HeartbeatBeater.beat`; `FeedOutageRetry` already complies | each site's unconditional WARN / bookkeeping in its `catch (RuntimeException)`                                                                                                                                                      | no mechanical gate: a `catch (RuntimeException)` that should read the interrupt cannot be told apart by a scan. Each consumer's spec carries an interrupt-set row and a control row, and `StandingReaperResilienceSpec` pins the whole stop on a real thread; the task 8.3 sweep records every other catch of a tracker call                                                                                                                                                                                                                                                                                                                              |

The directory stays a `Path` rather than a new value type: `add-project-registry`
replaces "a directory" with "a registered project resolved from a directory",
so a `ProjectDir` type introduced here would be deleted one change later. The
record-level absoluteness check is the enforcement until then.

## Risks / Trade-offs

- [`dontAsk` may still let the judge call tools that need no permission
  (sub-agents, scheduling) — proposal Q1] → verified in the paid smoke layer
  (task 6.2); if they still run, the result is recorded and the hard deny list
  stays a follow-up (NG2). The change does not claim to fix it.
- [`acceptEdits` auto-approves edits to any file under the working directory,
  including `.claude/settings.json`, which is the known settings-widening
  path] → this change does not widen it: host-mode rounds could already edit
  that file through `Bash` when allowed, and the fix (isolating setting
  sources) is sequenced after the agent backend SPI design (NG1). Stated in
  the sandbox guide's threat notes.
- [The pinned CLI renames a mode token] → the paid smoke layer (the only layer
  that runs the real CLI) asserts a round edits a file; the fake agent ignores
  flags, so unit and E2E layers cannot catch it.
- [The argv capture reads nothing in container mode] → the fake agent's
  `GNOMISH_FAKE_CAPTURE_ARGV` hook exists in `fake-agent.sh` but no spec used
  it, and inside the box a path on the host filesystem is not a path in the
  box. The box's working copy is a Docker volume, not a bind mount, and a
  judge vote runs in a fresh box disposed before the run returns, so the two
  roles are read back through two channels in `ContainerModePipelineE2ESpec`
  (`:bootstrap`): the executor round captures its argv into a file in its
  working copy, which is harvested into the snapshot commit and read back
  with git; the judge vote runs an in-box wrapper that checks its own argv and
  returns a passing verdict only for `dontAsk` with `--strict-mcp-config`,
  otherwise a failing verdict quoting the argv — with one attempt allowed, a
  wrong judge argv fails the run. Neither can pass vacuously: an empty
  executor capture fails the feature, and the judge's check is what lets the
  stage pass.
- [Rebase with `remove-interactive-console` and `make-run-headless`, which
  edit `ServeArgumentsParser` (`--interactive` goes) and `RunArgumentsParser`
  (`--decision` arrives)] → whichever lands second updates the accepted-option
  constant; `CliArgumentsContractSpec` fails until it does. After
  `remove-interactive-console`, a stale `--interactive` is rejected by this
  change's rule automatically.
- [An operator already lists the agent token in passthrough] → still works,
  with wider reach; the guide recommends removing it.

- [Another catch of a tracker call, outside the four named sites, still
  logs an interrupt as a fault] → task 8.3's sweep lists every
  `catch (RuntimeException` over a tracker call in `application`; a hit on a
  thread the stop interrupts joins the consumer list in the same task.
  `InstanceHeartbeat.tickGuarded` is the known remaining candidate: its tick
  runs on the same heartbeat worker, and the sweep records whether its
  `tickLog.failed` path is reached by a tracker failure or only by the beater's
  already-handled outcome.

- [The subject `gnomish: task started` became a parsing contract (D11),
  while `ServiceCommitMessages`' javadoc still says only the snapshot subject
  is one] → follow-up: state it in that javadoc and give the subject a
  package-visible constant beside `SNAPSHOT_PREFIX`, so a rename fails a spec
  instead of silently widening delivery again.
- [`GitObjects.historyContains` is a second, unscoped form of the delivery
  search] → follow-up: delete it, or give it D11's scope and declare it; not
  wired anywhere today.
- [Delivery is still read from commit messages the gnome can write] → a
  gnome commit on its own branch carrying `gnomish: cleanup` — in the body,
  or as the exact subject — reads as delivery, and the committer identity
  does not separate the two in host mode. Existed before this change; D11
  narrows where the search looks, not who can write what it finds.
  Follow-up change: bind delivery to something only the factory writes.
  `DeliveryAncestrySpec` holds the reproduction as a `@PendingFeature`.
- [D11 assumes the STARTED commit sits on the task branch's first-parent
  line] → true for `createTask` in both modes and for a rebase of the branch;
  a history in which the task branch was merged *into* another branch and work
  continued there is not covered by a spec.
- [A limit-ended round now ends as a normal round and is judged by the
  stage's verification (D12)] → intended for this change; whether it should
  instead be a quality failure or an outcome of its own is NG8.
- [A new exit-code carrier or already-reported failure type that does not
  join `calmLine`'s lists (D13)] → it takes the generic fallback again: a WARN
  "unhandled exception", a `gnomish run failed:` line and Spring Boot's ERROR
  record. No gate catches it; the lists sit beside `RunExitCodeMapper`'s
  table, which a new exit code already has to join.

## Migration Plan

No data or layout migration. Operators remove the agent-CLI wrapper, the
per-binding `agent-cli-binary`, and the agent token from `env-passthrough`;
these keep working if left in place (a wrapper that adds its own
`--permission-mode` duplicates the flag — task 6.2 records which value the CLI
honours). One spelling stops working: `factory.bindings.default-binding`, the
undocumented key that bound by accident, binds nothing after D4 — an operator
using it switches to `factory.bindings.default`. The release notes of the
first release say so.
