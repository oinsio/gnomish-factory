# Design: migrate-verify-to-executor-contract

## Context

See proposal.md — Why. Constraints:

- After `define-executor-contract`: `ExecutorRegistry` (name → `Executor`), `ExecutorResult`
  (status + answer), `ExecutorOutcomeClassifier`, `ResultFileReader`, describe-validated
  settings. Its D10 lists check-side verdict construction (`ShellCommandCheckRunner`,
  `FilesExistCheckRunner`, `ExternalPolling`, `VerifyOrchestrator:153`) as exemptions that
  expire here.
- `VerifyOrchestrator` dispatches on the sealed `VerifyCheck` (`Builtin | Command | External |
  Judge`) to three ports; `ExternalPolling` owns interval/timeout/timeout-class and the
  attempt-commit delivery precondition (`AttemptDelivery`), and wraps the client in
  `PinCheckedExternalCheckClient`.
- Check providers: `CheckClientFactory` SPI (`ServiceLoader`, `CheckClientDiscovery`,
  `CheckProviderSeam`, `ProviderDispatchingExternalCheckClient`), bundled `github` (workflow
  runs by commit, job-log findings, pin of the workflow file) and `http` (probe with
  conditions, run-context interpolation). Both are stateless per poll (NFR-R2 of their change).
- `add-command-executor` generalizes `CommandProcessRunner` into a shared command-run core
  consumed by `ShellCommandCheckRunner`; this change builds the `shell` built-in on it.
- State file (`StateJsonMapper`, `StateAttemptDto`, `StateCheckDto`) has no pending record.
- `remove-interactive-console` deletes `InteractiveExternalCheckClient` and modifies the
  pin-check requirement; it is sequenced before this change.

## Goals / Non-Goals

**Goals:** checks are verdict executors (FR1–FR7); one pending/poll owner with a durable key
(FR2, FR3); the plugin-api loses its check SPI (FR8); D10 exemptions of the contract change
close (G1, M2).

**Non-Goals:** `judge` (NG1), severity (NG2), sealed assets (NG3), webhooks (NG4),
`pending` for `transform` (NG5, taken up by `extract-agent-round-runner` on this change's
`PendingPoll`; keep the record's identity extensible to a round subject).

## Decisions

**D1 — A check is a `verdict` request to a named executor; the four tokens are sugar.** (FR1,
UX2.) `VerifyCheck` collapses to one record (`executorName`, `checkId`, `settings`,
`interval`, `timeout`, `timeoutClass`, `pinPaths`, `verifyIn`, `threshold`, `expected`) and
the mapper translates `builtin`/`command`/`external` into executor names. `Judge` stays a
sealed sibling until NG1. *Rationale:* the manifest grammar is law in many repositories; the
executor name is what the engine needs. *Alternative rejected:* a breaking manifest change
to `executor:` only — forces every reference pipeline to edit for no behavior gain.

**D2 — The engine owns pending/poll; the executor owns the key.** (FR2, FR3.) `ExternalPolling`
becomes `PendingPoll`: `start` → on `pending` persist `PendingPollRecord(checkIdentity,
attempt, key, since)` into the attempt's state (one commit with the attempt state, pushed)
→ `poll(key)` at `interval` with the interval passed as the in-poll wait bound → terminal or
timeout. Resume reads the record and polls; it never re-`start`s a persisted key.
*Rationale:* Argo's `Running + requeue`, Snakemake's `run_job/check_active_jobs`, our own
stateless-instance rule. *Alternative rejected:* executor-side waiting (`qualitygate.wait`)
— holds the box, loses the quality/infrastructure split, Airflow's slot lesson.

**D3 — `scm-status` is a built-in, belongs to the git-remote connection, needs `expected`.**
(FR4, FR9, NFR-S2.) Reads Checks + Statuses on the attempt SHA (Gitea: statuses), latest per
context, pending while any expected context is absent/running, SHA verified, back-off on
rate limit. Its token resolves by the connection the operator configures for the executor —
`factory.check.<name>`, the operator subtree that today holds `factory.check.<provider>`, now
keyed by the built-in executor's name, inline or by `connection: <profile>` into
`factory.connections` — never by the tracker's subsection, so Jira + GitHub needs no GitHub
tracker config. An operator subsection under `factory.executors.<name>` was rejected: NFR-S3 of
`define-executor-contract` makes every `executors` key in operator configuration an unknown-key
error (main spec `pipeline/pipeline-config`, "Executor declared in operator config is ignored";
`OperatorConfigExecutorsSpec.groovy:36,55`), and only the law declares executors. The
`factory.check.*` subtree keeps its declared level (`operator/operator-configuration`, "A plugin
subtree takes its root's level") and carries a connection, never a declaration.
*Rationale:* "named verdicts on a SHA" is the common contract of every SCM, covering any CI
without per-CI code; expected contexts are the only cure for "pending forever". *Alternative
rejected:* folding it into the GitHub tracker adapter — ties CI reading to the issue tracker,
wrong under Jira + GitHub.

**D4 — `github-actions` and `http` move as built-ins with unchanged behavior; `github-actions`
leaves the vendor bundle.** (FR6, FR13; amended 2026-11-06 by the human decision on the
escalation of task #121, after a decision review under `.claude/rules/spec-mismatch.md`.) The
`http` provider becomes the `http` built-in in `adapters/check`. The GitHub provider becomes the
`github-actions` built-in in the new core-side module `adapters/scm`, beside `scm-status` (D3),
registered by core like `command`; the vendor bundle `adapters/github` keeps the tracker only.
Subsection and params validators become describe schemas; the pin contribution becomes the
describe flag of D17. *Why the bundle cannot keep it:* ADR 0020 defines a built-in as compiled
into and named by core, while the `plugin/github-plugin` capability defined the bundle as
discovered-only with no privileged shortcut — one component under two incompatible meanings of
"built-in". The premise behind the bundle's promise, that core knows nothing of GitHub, is
already given up by D3 (`scm-status` reads GitHub Checks from core) and by
`add-sandbox-gha-executor` (the sandbox port is first-party). *Alternative rejected — a
ServiceLoader SPI for executors contributed by the bundle:* reverses ADR 0020 decision 1, makes
the `Executor` type a published Java surface under japicmp (evolution by signature, which the
contract exists to avoid), adds a third registry source with a precedence rule, and keeps two
equivalent contracts (Java and wire) in step by hand. *Alternative rejected — `github-actions` as
an external reference program:* the best trust posture (Tekton's custom tasks, Terraform's
single in-process provider), but the sandbox base image carries no JDK and a shell
reimplementation of job-log parsing is weaker than the client that exists; pickup condition: a
self-contained distribution of the program (native image or jlink) or a runtime guaranteed by
the box image contract. *Alternative zero rejected — no `github-actions`, `scm-status` only:*
job-log findings are not available from check-run output (proposal Q2); revisit when they are.

**D5 — Verdict derivation is one engine component.** (FR7, M2.) `VerdictDerivation(answer,
threshold) → Verdict` in `domain/engine`; built-ins emit SARIF only; the `shell` built-in
normalizes the legacy findings file to SARIF at the edge. *Rationale:* vendor exit codes are
policy-coupled (Semgrep, Trivy); one mapping owner. *Alternative rejected:* per-executor
pass/fail — the two-taxonomy failure again.

*D5 as amended (2026-11-07, escalation on task 2.2) — a pass keeps its below-threshold
findings.* The scenario "Warnings pass under the default threshold" promises the warnings
recorded as findings, and `Verdict.Pass` carries only `runUrl`: `VerdictDerivation` collects
every result and drops the list on a pass, `StateJsonMapper` writes `findings: []` for every
pass, and `JudgeVerdictExtractor` returns an empty `Pass` for `"passed": true` whatever the
judge's `findings` array holds. Premises verified: the findings-less pass comes from FR4 of
`add-stage-engine` (findings as feedback for the next attempt, which a pass never gets) and from
FR8 and D6 of `add-manual-run` ("the exit code is the primary verdict … file on exit 0 is ignored
with a warning"). D5 removes the second premise — the answer is the verdict's source, so results
below the threshold are known to the engine — and the first names only one consumer; the state
file, `status`, the briefing and the tracker report are the others, and a reviewer reads them.
The industry shape is the same: GitHub code scanning fails a check only at its configured level
and records every alert; SARIF's `level` is a severity, not an absence of a result; Trivy's
`--severity` gates while every finding is printed. *Decision:* `Verdict.Pass(runUrl, findings)`,
defensively copied, possibly empty; `VerdictDerivation` fills it with every result below the
threshold, in the order written; `JudgeVerdictExtractor` fills it with the parsed `findings` of a
passed vote, under the same strict schema as a failed one; `StateJsonMapper` writes and reads the
existing `findings` field for `"pass"` — the wire field already exists, so no format change, only
semantics — and so do the `status --json` and usage mappers. The feedback of a retry stays the
failed checks of prior attempts (FR4 of `add-stage-engine`): the engine filters by `Fail`, and a
spec pins that a pass with findings feeds nothing. The renderers of a pass (`BriefingSections`,
`StatusLineFormatter`) keep reporting "passed"; showing the findings there is display work for a
later change. *Alternative rejected — narrow the scenario (drop "recorded as findings" or defer it
to NG2):* a silent loss of a promised duty on every pass, the worst class of `spec-mismatch.md`;
NG2's `advisory` severity is a different question (which findings may fail a check), not whether
below-threshold findings survive.

**D6 — Credentialed verdict executors run in `FRESH_BOX` with a derived allowlist.** (NFR-S1.)
The loader rejects `same-box` on such checks; `SandboxCheckEnvironmentSource.freshBox` takes
the executor's declared hosts as the box's egress set. *Alternative rejected:* widening the
round box's allowlist — exposes the token and the SaaS hosts to the gnome.

**D7 — Crash consistency of the pending key.** Durable steps: (1) `start` submission (external
effect), (2) key commit + push, (3) terminal verdict in the attempt record (existing).
Windows: W1 after (1) before (2): the record is absent → resume re-drives `start` (NFR-R1;
the submission is keyed by the server, a duplicate is a second scan, observable in the
server, never a wrong verdict); W2 after (2) before (3): record present → resume polls,
never starts. Shape owner: the attempt record of the `task-branch-contract` capability
(`pendingPoll` field); recovery owner: `PendingPoll` on resume; idempotent (W2 twice = one
poll sequence; resolved record = no-op). Mutually-implied fields (key, check identity,
attempt) land in one commit. Ordering admits the sweeper: the record is a field of an
already-enumerated attempt. Readers read the tip (`EnvelopeMediumBoundarySpec` applies).
Kill-point specs: kill after (1), after (2), after (3), each resumed by a second instance.
*Amended 2026-11-02 (human decision; FR10, FR11, NFR-R4):* two more windows inside W2, both
resting on D11 and D12. W3: a `poll` returned `pending` with a new key, killed before the
key's commit (D12) — the record holds the previous key; resume polls it, and since the
contract keeps a superseded key pollable (D11) the executor answers for the same run, no
`start`. W4: a `poll` returned a terminal status, killed before (3) — the record still holds
the key; resume polls it again and the contract returns the ended run's terminal result again
(D11), so the verdict lands with no `start`. An executor that breaks D11 (refuses the key) is
`failed/infrastructure` naming the key: visible, never a silent re-submission. Kill-point specs:
also kill after a key-rotating poll and after a terminal poll, each resumed by a second instance.
For a `program` verdict executor the resume of W2–W4 reaches the program through D14.

**D14 — A recorded key is adopted by the resuming instance's program executor.** (FR3, FR11,
NFR-R1, NFR-R4; closes proposal Q3.) As built by `define-executor-contract`,
`ProgramExecutor.poll(ExecutorRequest request, PendingKey key)` claims the key from its
instance's in-memory `PendingRuns` (`PendingRuns.Entry(WorkingCopyView.@Nullable Taken
before)`, `Entry claim(PendingKey key)`) and refuses, spawning nothing, a key absent there; the
same entry holds the working-copy view taken before `start`, against which the poll that ends a
`verdict` run is judged (NFR-S4 of that change). On a second instance that entry does not exist.
Decision: the resume path of `PendingPoll` hands the key the pending record names to the
executor of the resumed check before its first poll, and the program executor *adopts* it — it
takes git's view of its own environment's working copy and enters the key into its `PendingRuns`
with that view as the entry's `before`. A poll of an adopted key then proceeds as for a key its
own `start` returned. Invariants: a key enters `PendingRuns` only from a `start` or `poll` result
of this instance or from the pending record through the resume path, never from the request on
the wire, so the refusal of an unknown key stays for every other key.

*The call path and the signatures it changes.* The engine reaches a verdict executor only
through `StageExecutor.execute(Request)` (G1: `EnginePorts` keeps `StageExecutor` and
`JudgeVoter`), so a poll is a `StageExecutor.Request` that carries a turn:
`@Nullable PendingTurn pending`, with `record PendingTurn(String key, Origin origin)` and
`enum Origin { ISSUED, RECORDED }` in `domain/engine/port`. No turn is a `start`; `ISSUED` marks
a key this run's own `start` or `poll` returned; `RECORDED` marks the key `PendingPoll` read from
the pending record on resume, set by that path only. The application-side wrapper that D18
builds around the resolved `Executor` dispatches the turn: none → `start(request)`; `ISSUED` →
`poll(request, key)`; `RECORDED` → `adopt(request, key)` when the executor implements the role
interface `PendingAdoption` (`app/port/check`: `void adopt(ExecutorRequest request, PendingKey
key)`), then `poll(request, key)`. `ProgramExecutor` implements `PendingAdoption`; the five
built-ins do not, since their key names a run the remote holds and every instance can poll it.
The `Executor` interface of `:executor-contract` is unchanged: adoption is a Java-side concern
of the in-process program adapter, never a contract operation a program sees — the reason D17
keeps Java-only members off the mirror interface.

*The premise, per medium.* The adopted view is a sound before-view only where nothing of the
first instance's program is left in the resuming working copy. Verified: a `fresh-box` check
materializes a new box from the attempt commit (`SandboxCheckEnvironmentSource.java:85-93`), so
the premise holds there. It does not hold in general on the host: `HostCheckEnvironmentSource`
runs the check in the task's own working copy (`HostCheckEnvironmentSource.java:28-34`, the
`DirectoryWorkspace` root), not in a checkout made for it. A resume on another clone gets a new
worktree from `git worktree add` at the branch (`TaskWorktreeManager.java:105`), clean; a resume
on the same clone reuses the worktree, whose leftovers `WorktreeSalvage` either commits as the
interrupted round's work (`WorktreeSalvage.java:13-25`) or, under `--discard-work`, resets
(`WorktreeSalvage.java:105-111`). A `same-box` check runs in the leased round box
(`SandboxCheckEnvironmentSource.java:69-70`); what a resuming instance's lease holds was not
verified. So adoption carries a guard rather than relying on the premise: **a key is adopted only
when the view taken at adoption shows no change against the working copy's `HEAD`; any change
refuses the poll as `failed/infrastructure` naming the key and the changed paths, with no
process spawned** — a cannot-verify the operator sees, never a verdict judged against a view that
already holds the first run's writes. The guard does not see writes a salvage commit already
moved into `HEAD`: that a salvage commits a killed check program's writes as round work is a
property of `WorktreeSalvage` that today's `command` checks share, recorded under Open Questions
as a finding outside this change.
W3 (superseded key) and W4 (ended run's key) are adopted the same way; the program's own
retention (D11) answers them. *Alternative rejected — persist the before-view in the pending
record:* puts a program-adapter detail (git status entries) into the engine's record, and a view
taken in the first environment would mismatch the second one's working copy, turning every
resume into a false cannot-verify. *Alternative rejected — accept any key on the wire:* removes
the unknown-key refusal for every poll, not only the resumed one. *Alternative rejected — an
`adopt` member on `Executor`:* a Java-only member no program can implement (D17). The `executor/wire-contract`
delta MODIFIES "Poll of a program is refused without its preconditions" and "A pending judge is
judged when it ends", scenario titles kept, each with a scenario for the adopted key, and ADDS
"A recorded key is adopted only over an unchanged working copy" for the guard.

**D8 — Sync surfaces.** Scout: `grep -rn "Kept in sync with"`, registry, and domain-term
search for "poll", "verdict", "findings file".
- *Pairs avoided by extraction (rule of two, `manual-sync-pairs.md`, 2026-11-06):* the GitHub
  HTTP core, which D4 would have left in the bundle while `github-actions` and `scm-status`
  needed it in core → one library, D15; the HTTP egress allowlist, which `http` holds in
  `:adapters` while the GitHub client needs it → one leaf, D16. Neither is a declared pair.
- *Touched pairs:* none of the registry pairs. `PinCheckedExternalCheckClient` → becomes a
  decorator over `Executor` for checks with pin paths (same single guard, no twin).
- *Dissolved parallel implementations:* three verdict-construction sites (`ShellCommandCheckRunner`,
  `FilesExistCheckRunner`, `ExternalPolling`) → one `VerdictDerivation` (D5); two findings
  readers already one (`ResultFileReader`); two poll-shaped loops (`ExternalPolling`, and
  the per-provider retry inside `GithubCheckExternalClient`) → one `PendingPoll` with the
  provider keeping only its HTTP retry (a transport concern, documented in its javadoc).
- *Host vs container:* `HostCheckEnvironmentSource` / `SandboxCheckEnvironmentSource` are
  not a declared pair and stay: they differ by medium, not by rule; both now hand the
  environment to the same `Executor` call.
- *Touched declared pair (D11):* `KitCase` (`:bootstrap`) ↔ the stand-in
  `steps/executor-run.sh` (registry "Declared pairs with no shared classpath"): the re-poll case
  is added at both ends in one task, the script's header and `BROKEN` switch list it, the registry
  row's case count goes from twelve to thirteen, and `KitCaseScriptIdentitySpec` keeps the two
  equal. Declared pair kept, not collapsed: still two ends with no classpath between them.
- *No new pair created.*

**D9 — Single-owner mechanisms.**

| Owner                                                                                    | Value (type)                                              | Consumers                                                                                                                                                         | Old way removed                                                                                                                                                                                                                                                                                                                 | Enforced by                                                                                                                                                          |
|------------------------------------------------------------------------------------------|-----------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `PendingPoll` (`domain/engine`)                                                          | `PendingPollRecord` (key, check identity, attempt, since) | `VerifyOrchestrator` (all verdict checks), `StageAttemptLoop` resume path, state mappers; the key-rotation commit (D12) is written by the owner, never by a check | `ExternalPolling` deleted; `GithubCheckExternalClient` run-listing loop reduced to one observation per `poll`                                                                                                                                                                                                                   | parameter type (`PendingKey`, no `String`); kill-point specs D7; grep gate `Thread.sleep\|sleeper.sleep` in `adapters/check` and `adapters/github/check` → zero hits |
| `VerdictDerivation` (`domain/engine`)                                                    | `Verdict` from `Answer.Verdict` + `Threshold`             | `VerifyOrchestrator`, `JudgeVoting` (judge votes already SARIF-shaped? no — exempt until `extract-agent-round-runner`, named)                                     | `new Verdict.Fail/Pass/CannotVerify(` in `ShellCommandCheckRunner`, `FilesExistCheckRunner`, `ExternalPolling`, `VerifyOrchestrator:153`, `HttpExternalCheckClient`, `GithubCheckExternalClient` deleted; exempt: `JudgeVerdictExtractor`, `JudgeRoundExecution`, `JudgeCriteriaPreflight`, `StateJsonMapper` (deserialization) | grep gate `new Verdict\.` → derivation + state mapper + judge exemptions only; data-driven threshold spec                                                            |
| The findings of a pass (D5 as amended)                                                   | `Verdict.Pass.findings` (below-threshold results)         | writers: `VerdictDerivation`, `JudgeVerdictExtractor`; readers: `StateJsonMapper`/`StateCheckDto`, `AttemptMapper`/`CheckDto` (`status --json`), `UsageReportJsonMapper`; `RoundExecution` feedback excluded by `Fail` filter | `CheckFindings.noteIgnoredOnPass` and the exit-0 drop in `ShellCommandCheckRunner` deleted with 2.2; `new Verdict.Pass()` no-arg kept only where no answer exists (`FilesExistCheckRunner` until routed; judge `CannotVerify` paths) | the record's canonical constructor (no findings-less form but the explicit empty list); state round-trip spec over a pass with findings; feedback spec: a pass with findings feeds nothing |
| `ExecutorRegistry` (from the contract; D18)                                              | `Executor` by `ExecutorName`                              | `VerifyOrchestrator` for every check (new), stage dispatch (existing, wrapping into `StageExecutor` at resolution)                                                 | `CheckClientDiscovery`, `CheckProviderSeam`, `ProviderDispatchingExternalCheckClient`, `CheckClientConfiguration`, `CheckEquipment` registry fields deleted; `META-INF/services/...CheckClientFactory` entries deleted                                                                                                          | `CheckClientFactory` type gone (compile); `ArchUnit`/grep: no `ServiceLoader.load(` outside tracker and sandbox-binding discovery                                    |
| `scm-status` built-in                                                                    | `ContextVerdicts` on `AttemptSha`                         | `VerifyOrchestrator` via registry                                                                                                                                 | none (new); identity spec: contexts read equal those the SCM holds for the attempt SHA (`ScmStatusShaPinningSpec`, WireMock)                                                                                                                                                                                                    | `AttemptSha` value type on the read; `expected` non-empty at load                                                                                                    |
| `:githubapi` (D15)                                                                       | the GitHub HTTP core (one client, credential, cache, rate-limit, retry) | the bundle's tracker; `github-actions` and `scm-status` in `adapters/scm`; `add-sandbox-gha-executor` later                                                 | the bundle's `adapter.github` package deleted; `GithubCheckExternalClient`'s own client construction routed through the library                                                                                                                                                                                                  | grep: `class GithubHttpClient` in one module (M6); the bundle's layering allowlist; `GithubPluginAbsenceSpec` narrowed to `adapter.tracker.github` references outside the bundle    |
| `:httpegress` (D16)                                                                      | `EgressAllowlist`, the guarded exchange                   | `http` built-in, `GithubHttpClient`                                                                                                                               | the `adapter.check.http` egress classes moved, not copied; `GithubHttpClient`'s direct `HttpClient` use replaced by the guarded exchange                                                                                                                                                                                       | architecture spec: `HttpClient.newBuilder(`/`newHttpClient(` only in the leaf (allowlist by file, asserted reached)                                                  |
| Pin set of a check (D17)                                                                 | law `pinPaths` ∪ `checkId` when `checkIdIsPinnedPath`     | the pin decorator of 5.1                                                                                                                                          | `ExternalCheckPinContributor`, `GithubCheckPinPaths` deleted                                                                                                                                                                                                                                                                     | the describe field is the only per-executor input; `PinDecoratorSpec` data-driven over the flag                                                                      |
| Credential delivery for verdict executors (`ChildEnvAllowlist` layer, from the contract) | `ExecutorCredentialNames`                                 | `SandboxCheckEnvironmentSource.freshBox`, `HostCheckEnvironmentSource`                                                                                            | `CheckProviderSeam.credentialEnvVars` union deleted; `CheckEquipment.credentialNames` deleted                                                                                                                                                                                                                                   | `ProgramCredentialDeliverySpec` extended with a verdict check: token in fresh box, absent in round box                                                               |

**D15 — The GitHub HTTP core is one library module, `:githubapi`.** (FR14.) The bundle's
`adapter.github` package — `GithubHttpClient`, `GithubCredential`,
`GithubConditionalRequestCache`, `GithubRateLimit`, `GithubRetryConfig`, the exceptions — moves
to a first-party library module that depends on `:domain`, `:httpegress`, resilience4j and
slf4j, and on nothing of `:executor-contract`, `:application` or `:adapters`. Consumers: the
bundle's tracker (its layering allowlist gains `:githubapi`), the `adapters/scm` built-ins, and
`add-sandbox-gha-executor` when it lands. It is not `gnomish-plugin-api`: the api stays free of
GitHub types (the `github-plugin` scenario "The plugin's private core is not in the public api"
holds as "not in the api"), and the library is not a compatibility surface for third parties —
its consumers are first-party, and japicmp does not baseline it. *Alternative rejected — keep
the core private to the bundle and give the built-in its own client:* a hand-synced pair of some
550 lines whose third consumer is already named. *Alternative rejected — publish the core
through `gnomish-plugin-api`:* grows the api by a vendor's HTTP client for no third-party need
and contradicts the api's thinness requirement.

**D16 — The HTTP egress allowlist is one leaf module, `:httpegress`.** (FR15.) `EgressAllowlist`,
`GuardedHttpCheckExchange`, `JdkHttpCheckExchange` and `EgressRefusedException` move from
`:adapters`'s `adapter.check.http` to a JDK-only leaf; the `http` built-in keeps using them,
and `GithubHttpClient` is built over the guarded exchange, so every in-process HTTP call of a
built-in verdict executor passes the allowlist of the hosts its describe declares. This closes a
false premise of `define-executor-contract` D1(b) and ADR 0020, which kept built-ins in process
for "in-process egress control" the GitHub client did not have. Enforcement: an architecture spec
asserts that no production class outside the leaf constructs a `java.net.http.HttpClient`
except the leaf's own exchange (allowlist by file, asserted reached).

**D17 — The pin contribution is a static describe flag.** (FR6, FR16.) `Describe` is one answer
per executor and cannot name a per-check path, while the former `pinContributor()` added the
check's `checkId`, which for `github-actions` is the workflow file. The contract gains a boolean
describe field, `checkIdIsPinnedPath`; the pin decorator of D8 unions the law's `pinPaths` with
the `checkId` when the flag is set. `github-actions` declares it, the other built-ins do not, a
program may. *Alternative rejected — law-declared pins only:* every existing pipeline loses the
workflow pin silently until its author edits the manifest. *Alternative rejected — a per-check
hook on the mirror interface:* a Java-only member no program can implement, the asymmetry
D1 of `define-executor-contract` forbids.

**D18 — The registry holds the contract's `Executor`; stage dispatch wraps.** (FR1, FR13.) As
built by `add-command-executor`, `ExecutorRegistry` holds `StageExecutor` (a round wrapper) and
takes the `command` factory as a typed parameter. A verdict executor is not a stage round, so
the registry's value becomes the contract's `Executor` for every name — law-declared programs,
`command`, and the five verdict built-ins, handed in by `ExecutorAdapterSelector` as typed
parameters of core-side modules (`adapters/scm`, `adapters/check`, `adapters/agent`) — and stage
dispatch wraps the resolved `Executor` into its `StageExecutor` at resolution. The refusal of a
law declaration under a built-in's name covers all six built-in names.

**D10 — Lock scope.** No lock added. `PendingPoll` sleeps on the injected `Sleeper` with
nothing held, as `ExternalPolling` does today.

**D11 — A pending key stays pollable until its run ends; a re-poll is idempotent.** (FR11, FR12,
NFR-R4; human decision 2026-11-02, closing Q2 of `extract-agent-round-runner`.) The wire
contract gains an ADDED requirement in `executor/wire-contract`: an executor declaring `poll`
answers a poll of every key it issued for a run — from `start` or from an earlier poll — until
the run ends, and a poll of an ended run's key returns its terminal status and answer again.
The conformance kit gains a thirteenth case, `pending-repoll` (start → pending → poll to a
terminal → poll the same key again → same result), gated by `poll` like the pending case. The
reference executor already answers every poll of its key with the run's answer, so it passes
unchanged; the misbehaving fake gains a `break-pending-repoll` preset. *Where it lives:* here,
because this change is the first that persists a key across a kill (W3, W4 of D7);
`define-executor-contract` is implemented and stays as built (bar the program-poll refusal
and the pending judge's before-view, which D14 modifies), and `extract-agent-round-runner`
reuses the requirement for its K3/K4 windows. ADDED rather than a MODIFIED "Closed status
taxonomy": the obligation is the poll's, not the status set's, and a MODIFIED here would overlap
`extract-agent-round-runner`'s MODIFIED of that requirement. *Rationale:* the window between
an executor's answer and the factory's commit of it cannot be closed by the factory alone; only
the executor's retention of the run closes it, and without it every kill in that window becomes
a second submission. *Alternative rejected —
leave it to each executor:* W3/W4 then resume with a key the executor may refuse, an
infrastructure failure and a re-drive on every such kill, which defeats G4. *Alternative
rejected — commit before acting on every poll answer:* the commit follows the answer, so the
window it leaves is the one this decision exists for.

**D12 — A changed key is persisted before the next poll, by the one owner.** (FR10, NFR-R4;
human decision 2026-11-02, closing Q3 of `extract-agent-round-runner`.) When a `poll` returns
`pending` with a key other than the recorded one, `PendingPoll` writes the new key into the
record in one commit with its register, pushed, before the next poll — the same write as the
first key's (D2), so no second persistence path exists. The rule is subject-neutral, so
`extract-agent-round-runner` adds only its `Round` subject. *Alternative rejected — keep polling
the first key:* an executor that rotates keys (a job requeued under a new id) would be polled by
a stale handle after every restart. *Alternative rejected — leave it to the transform change:*
a `verdict` poll has the same window, and two changes ADDing one requirement is a merge conflict.

**D13 — A model-less verdict built-in reports a known zero.** (NFR-C1.) Adopts design D8 of
`add-command-executor` (user decision 2026-11-04) for the five verdict built-ins: each reports
usage with provenance `REPORTED` and an empty per-model map, and its describe declares
`reportsUsage = true`. `UsageFold` marks a task's cost incomplete for any `ABSENT` entry, so
`ABSENT` from a check that mediates no model would mark every task incomplete. *Alternative
rejected:* `ABSENT` for built-ins (the earlier NFR-C1 wording) — every task would show "cost
incomplete" forever and mask a real agent round that lost its usage; D8 of that change already
rejected it for the `command` built-in on the same grounds.

## Risks / Trade-offs

- [Duplicate server-side submission in window W1] → bounded to one per kill; the record is
  the receipt (crash-consistency rule 7); the ledger logs the re-drive.
- [`github-actions` and `http` regress during the move] → their existing WireMock specs are
  kept and re-pointed at the built-ins (behavior-preserving extraction).
- [A GitHub token lives in the factory process for the built-ins] → accepted, the posture the
  tracker already has (`github-plugin` trust note) and `scm-status` needs regardless; the
  in-process allowlist of D16 bounds where the client may send it.
- [Core grows a vendor integration that is costly to extract later (Jenkins' detached plugins)]
  → accepted with the pickup condition of D4's external-program alternative recorded; the
  library of D15 keeps the integration in one place to move.
- [Removing the check SPI breaks an unknown third party] → none registered in
  `docs/community-plugins.md`; the api's major version is bumped per its semver requirement.
- [`expected` lists go stale as workflows are renamed] → located error at load is impossible
  (names live in CI); the timeout finding names the absent contexts (UX1).

## Migration Plan

1. After `define-executor-contract`, `add-command-executor`, `remove-interactive-console`.
2. Land built-ins and `PendingPoll` behind the four tokens; existing manifests unchanged.
3. Remove the check SPI and bump `gnomish-plugin-api`'s major version; rewrite the sample.
4. Rollback: additive registry entries; the SPI removal is the only irreversible step and is
   last.

## Open Questions

- Threshold vocabulary (proposal Q1) — settled in the spec as `error | warning | note`;
  numeric minimums deferred.
- Record-driven poll of a `program` executor (proposal Q3) — settled by D14.
- Salvage of a killed check program's writes (finding, outside this change; D14): on a host
  resume in the same clone, `WorktreeSalvage` commits whatever a check program killed mid-run
  left in the task worktree as the interrupted round's work (`WorktreeSalvage.java:13-25`) —
  true of today's `command` checks as of a `program` verdict executor, so this change neither
  causes nor widens it. Pickup condition: the change that next touches `WorktreeSalvage` or the
  host check environment (`HostCheckEnvironmentSource`) decides whether a kill inside verify
  discards rather than salvages; until then D14's guard keeps an adopted key from being judged
  over uncommitted leftovers.
