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

*D5, second amendment (2026-11-08, escalation on tasks 2.2a and 3.2) — the shell's exit-code
semantics leave `cli/manual-run`.* The main spec `openspec/specs/cli/manual-run/spec.md` still
binds the old behaviour in two requirements this change had no delta for: "Command check runner"
(exit 0 → `Pass`, 126/127 → `CannotVerify`, other non-zero → `Fail`) and "Findings-JSON wire
format" (the file replaces the synthetic finding only after a non-zero exit; scenario "A findings
file on a green check is ignored"). Premises verified: no delta of this change touched
`cli/manual-run`, and the only active delta there (`add-pipeline-routing`) ADDs one unrelated
requirement; FR5 ("its exit code is reported as status, never as a verdict") and the scenario
"Warnings pass under the default threshold" contradict both requirements directly; every scenario
of the two has a marker in `ShellCommandCheckRunnerSpec` or `CheckFindingsSpec`, so the deletion
of `noteIgnoredOnPass` (2.2a) and the `shell` executor (3.2) would have reddened stable scenarios
with no decision behind them — the narrowing `spec-mismatch.md` forbids. *Decision:* a
`cli/manual-run` delta REMOVEs the two requirements, and `verification/verdict-executors` ADDs
them back in the derived form — "The shell executor runs the command in the task environment"
and "The shell executor reads its findings path whatever the exit code" — with every kept duty
(`sh -c` through `exec()`, the bounded tail, the layered allowlist, 126/127 as infrastructure,
the scratch-area path and `GNOMISH_FINDINGS_FILE`, the synthetic result) and new scenario titles;
task 3.2 moves the eight markers. *Alternative rejected — MODIFY the two requirements keeping
every scenario title:* `delta-specs.md` makes a scenario title permanent under MODIFIED, and
"A findings file on a green check is ignored" would then name the opposite of its THEN for good.
*Alternative rejected — keep the exit-code semantics for this change (the escalation's option
B):* it withdraws the first amendment and leaves `shell` the one executor whose status is a
verdict, the per-executor pass/fail D5 rejected. "files_exist builtin runner" stays: its
verdict wording ("Fail with exactly two findings") holds through derivation.

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
- *Undeclared pair dissolved (2026-11-09):* the egress entry grammar, spelled once in
  `EgressEntries` (`sandbox/core`) for the box guard, the law loader and the describe preflight,
  and again in `EgressAllowlist` (`:httpegress`) for the http check — one rule in two modules with
  no marker. D16 moves `EgressEntries` into the leaf and deletes the second spelling; the
  `EgressGrammarOwnerSpec` gate keeps it single-owner.
- *No new pair created.*

**D9 — Single-owner mechanisms.**

| Owner                                                                                    | Value (type)                                              | Consumers                                                                                                                                                         | Old way removed                                                                                                                                                                                                                                                                                                                 | Enforced by                                                                                                                                                          |
|------------------------------------------------------------------------------------------|-----------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `PendingPoll` (`domain/engine`)                                                          | `PendingPollRecord` (key, check identity, attempt, since) | `VerifyOrchestrator` (all verdict checks), `StageAttemptLoop` resume path, state mappers; the key-rotation commit (D12) is written by the owner, never by a check | `ExternalPolling` deleted; `GithubCheckExternalClient` run-listing loop reduced to one observation per `poll`                                                                                                                                                                                                                   | parameter type (`PendingKey`, no `String`); kill-point specs D7; grep gate `Thread.sleep\|sleeper.sleep` in `adapters/check` and `adapters/github/check` → zero hits |
| `VerdictDerivation` (`domain/engine`)                                                    | `Verdict` from `Answer.Verdict` + `Threshold`             | `VerifyOrchestrator`, `JudgeVoting` (judge votes already SARIF-shaped? no — exempt until `extract-agent-round-runner`, named)                                     | `new Verdict.Fail/Pass/CannotVerify(` in `ShellCommandCheckRunner`, `FilesExistCheckRunner`, `ExternalPolling`, `VerifyOrchestrator:153`, `HttpExternalCheckClient`, `GithubCheckExternalClient` deleted; exempt: `JudgeVerdictExtractor`, `JudgeRoundExecution`, `JudgeCriteriaPreflight`, `JudgeCannotVerify` (the judge classes until `extract-agent-round-runner`, NG1), `StateJsonMapper` (deserialization) | grep gate `new Verdict\.` → derivation + state mapper + judge exemptions only; data-driven threshold spec                                                            |
| The findings of a pass (D5 as amended)                                                   | `Verdict.Pass.findings` (below-threshold results)         | writers: `VerdictDerivation`, `JudgeVerdictExtractor`; readers: `StateJsonMapper`/`StateCheckDto`, `AttemptMapper`/`CheckDto` (`status --json`), `UsageReportJsonMapper`; `RoundExecution` feedback excluded by `Fail` filter | `CheckFindings.noteIgnoredOnPass` and the exit-0 drop in `ShellCommandCheckRunner` deleted with 2.2; `new Verdict.Pass()` no-arg kept only where no answer exists (`FilesExistCheckRunner` until routed; judge `CannotVerify` paths) | the record's canonical constructor (no findings-less form but the explicit empty list); state round-trip spec over a pass with findings; feedback spec: a pass with findings feeds nothing |
| `ExecutorRegistry` (from the contract; D18)                                              | `Executor` by `ExecutorName`                              | `VerifyOrchestrator` for every check (new), stage dispatch (existing, wrapping into `StageExecutor` at resolution)                                                 | `CheckClientDiscovery`, `CheckProviderSeam`, `ProviderDispatchingExternalCheckClient`, `CheckClientConfiguration`, `CheckEquipment` registry fields deleted; `META-INF/services/...CheckClientFactory` entries deleted                                                                                                          | `CheckClientFactory` type gone (compile); `ArchUnit`/grep: no `ServiceLoader.load(` outside tracker and sandbox-binding discovery                                    |
| `scm-status` built-in                                                                    | `ContextVerdicts` on `AttemptSha`                         | `VerifyOrchestrator` via registry                                                                                                                                 | none (new); identity spec: contexts read equal those the SCM holds for the attempt SHA (`ScmStatusShaPinningSpec`, WireMock)                                                                                                                                                                                                    | `AttemptSha` value type on the read; `expected` non-empty at load                                                                                                    |
| `:githubapi` (D15)                                                                       | the GitHub HTTP core (one client, credential, cache, rate-limit, retry) | the bundle's tracker; `github-actions` and `scm-status` in `adapters/scm`; `add-sandbox-gha-executor` later                                                 | the bundle's `adapter.github` package deleted; `GithubCheckExternalClient`'s own client construction routed through the library                                                                                                                                                                                                  | grep: `class GithubHttpClient` in one module (M6); the bundle's layering allowlist; `GithubPluginAbsenceSpec` narrowed to `adapter.tracker.github` references outside the bundle    |
| `:httpegress` (D16 as amended 2026-11-09)                                                | egress admission: the one entry grammar (`EgressEntries`), the operator egress block (`factory.egress.sandbox`, `factory.egress.in-process`) and the per-connection `egress` list, the grant judgement of a law-medium host at load, the address-class judgement of every hop at connect, the guarded exchange with per-consumer bound, timeout and refusal key | the box guard and the law loader (`ExecutorEgress`, the describe preflight) through `EgressEntries`; the `http` built-in (manifest target); `GithubHttpClient` in `:githubapi` for the bundle's tracker, `github-actions` and `scm-status` (operator connection or granted law host); the webhook notifier of `add-stage-finished-event` (operator connection, when it lands); `add-sandbox-gha-executor` through the library | `EgressAllowlist`'s own grammar deleted in favour of `EgressEntries`; `factory.sandbox.egress-allowlist` and `factory.check.http.allowlist` removed keys; `redirect-hosts` and *declared redirect hosts* withdrawn; `GithubHttpClient`'s direct `HttpClient` and `Redirect.NORMAL` replaced by the guarded exchange; the hardcoded `factory.check.http.allowlist` in the refusal text replaced by the consumer's key | `HttpClientConstructionBoundarySpec` (client construction in the leaf only, pending row deleted by 3.0b); `EgressGrammarOwnerSpec` (wildcard and class words spelled in `EgressEntries` only); identity spec `EgressAdmissionByMediumSpec` on the real exchange over a local server; `GithubRefusalClassificationSpec`; the removed-key violations of the operator-configuration capability for the two old keys |
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

**D16 — HTTP egress is one leaf module, `:httpegress`, with one grammar, one operator block,
and admission by the medium a URL comes from.** (FR15, FR17, NFR-R5, UX4, NG7; first amended
2026-11-08 by the human decision on the escalation of task 3.0a of #121; amended again
2026-11-09 after the decision review of that amendment under `.claude/rules/spec-mismatch.md`
and a best-practice survey — RFC 9110 §15.4, the JDK's `RedirectFilter`, Go `net/http`, OkHttp,
curl, GitLab's outbound-request settings, Gitea's `ALLOWED_HOST_LIST` consolidation, OpenProject's
single SSRF allowlist, Cilium `toFQDNs`, Deno `--allow-net`.) `EgressAllowlist`,
`GuardedHttpCheckExchange`, `JdkHttpCheckExchange` and `EgressRefusedException` move from
`:adapters`'s `adapter.check.http` to a JDK-only leaf (task 3.0, done). The 2026-11-08 wording —
"an operator connection is admitted by its own configuration, since the operator who typed the URL
consented" — rested on a premise one grep refutes, and its delta kept a rule the wording
contradicted. Both are repaired here; the decision is restated in full.

*Premise repaired: the author of a URL is its medium, and there are two media, not two roles.*
`tracker.github.api-url` is read from `.gnomish/config.yaml` of the **target repository**
(`docs/guides/operator-guide.md`, "Three places carry configuration"; `GithubTrackerAdapterFactorySupport.java:67`
reads the subsection the pipeline loader parsed from the law), the same tree the stage manifest
lives in, written by whoever commits to the repository; only the `factory.*` files of ADR 0011
(`~/.gnomish/factory.yaml`, `~/.gnomish/projects/<name>/project.yaml`) are the operator's. So the
2026-11-08 rule admitted, by its own configuration, a URL a committer wrote — and the tracker's
token is the operator's. The project already has the mechanism that separates the two media:
the **vendor connection profile** (`factory.connections.<name>`, design D8 of
`add-plugin-architecture`), which the tracker subsection references as `connection: <name>`
(main spec `tracker/github-tracker`, "Subsection resolves its connection from a named profile").
The decision therefore reads:

- **Operator medium** — a `factory.*` file: a `factory.check.<name>` subsection
  (`github-actions`, `scm-status`, inline or by `connection:`), a `factory.connections.<name>`
  profile, the operator's egress block (below). A host it names is **granted**.
- **Law medium** — the `.gnomish/` tree at the law commit: a stage manifest's http target, an
  inline `tracker.github.api-url`, an `executors.<name>.egress` entry. A host it names is a
  **request**, admitted only when the operator medium grants it — by an egress-block entry, by a
  `connection:` profile that names the host, or by the vendor default the adapter's describe
  declares (`api.github.com` for the GitHub tracker and built-ins: a GitHub token is for GitHub).
  A requested host nobody grants is a located load error at the line of the request, naming
  the two ways to grant it. Existing projects on `https://api.github.com` load unchanged;
  a GHES or Gitea project adds one line to its operator file.
- The `program` executors keep D6 here and D10 of `define-executor-contract` (the law grants
  `egress`, the describe declares, the preflight refuses a grant the describe does not carry).

*One grammar, one owner.* `EgressEntries` (`sandbox/core`, `isEntry(String)`,
`normalized(String)`) is already the one grammar for the box guard, the law's
`executors.<name>.egress` and the describe preflight; `EgressAllowlist` in the leaf carries a
second copy of the same grammar (`EgressAllowlist.java:115-121`, `*.` wildcard and exact host) —
an undeclared pair (D8). `EgressEntries` moves into `:httpegress` (JDK-only, so `:sandbox:core`
takes the leaf as an `api` dependency) and every list below is parsed by it. The grammar, stated
once: an exact host name; `*.suffix`, matching any depth of subdomain and **never the apex**
(Cilium and Gitea semantics, the behaviour both copies already have); an IP literal; a CIDR
block; and the address-class words `loopback` and `private` (loopback; RFC 1918 and ULA). A
class word grants every host resolving only into that class and waives its block. The
cloud-metadata addresses are liftable by nothing (GitLab's rule). `host:port` is not in the
grammar (NG9: the box guard admits by host; pickup when a consumer needs a port).

*One operator block with scopes, not three keys.* Today the hosts the factory may reach are
spelled in `factory.sandbox.egress-allowlist` (the box; sandbox-boundary level),
`factory.check.http.allowlist` (manifest http targets; the one content of the `http` subsection,
`HttpCheckSubsectionValidator`) and — in the first amendment — a per-connection `redirect-hosts`
list, three lists with no stated reason to differ, the shape Gitea had across `[webhook]`,
`[migrations]` and `[security]` and is consolidating. One block replaces them:

```yaml
factory:
  egress:
    sandbox:        # sandbox-boundary level (project file only): what a box may reach
      - api.anthropic.com
    in-process:     # project level: what the factory process may reach beyond its grants
      - sonar.example.com
      - loopback    # a class word: the specs' WireMock and Gitea fixtures grant it here
```

A `factory.check.<name>` subsection and a `factory.connections.<name>` profile MAY carry an
`egress` list in the same grammar: the hosts that connection may be redirected to or may call
beside its base URL (a GHES operator names its own log store there). The two old keys are
**removed keys** under the operator-configuration capability's rule: a startup violation names
the file, the line and the new key (the precedent is `factory.agent-cli-env-passthrough`);
no alias survives, so the three lists cannot drift. The `http` subsection becomes empty by
definition. `gnomish project show` prints the block like any other key.

*A redirect target is an egress target; there is no "redirect host".* No surveyed system keeps
a second list for redirect targets: a hop is judged like a first request, against the same
admitted set. The admitted set of an in-process call is the union of its connection's host, its
connection's `egress`, the built-in's describe `egress` (the `github-actions` describe declares
`api.github.com`, `results-receiver.actions.githubusercontent.com` and
`*.blob.core.windows.net`, the hosts GitHub's own runner documentation lists for job logs and
artifacts) and the `in-process` scope. The term *declared redirect hosts* and the key
`redirect-hosts` of the first amendment are withdrawn.

*Admission by medium at load, address class at connect.* Load judges what needs no network:
the scheme and host of every URL, the grammar of every entry, the grant of every law-medium
host, and the class of an IP literal (a literal loopback or metadata address in a connection is
a located load error with no lookup). Resolution happens at connect: the guard resolves the
host, judges every address against the blocked classes, and refuses before any socket; an
unresolvable host is an infrastructure failure — the tracker's transport path, a check's
`failed/infrastructure` — never a configuration refusal, so a DNS outage at startup is an
outage and not a typo (Terraform `validate` reads no remote; Envoy never resolves synchronously
and treats an empty resolution as an empty cluster; nginx's startup death on an upstream name is
the counter-example operators route around). This is also what ADR 0019, decision 5, asks: the
truth of an operator connection is the daemon's, not one task's law, so it is never a located
law error that parks a task. *Residual risk, named here and not only in NG7:* the guard resolves
and the JDK client resolves again, and a rebinding between the two reaches an address the guard
never saw; `java.net.http.HttpClient` exposes no resolver hook and setting `Host` is restricted.
GitLab closes it by connecting to the judged address; the factory cannot until NG7's pickup.

*Per consumer, not per leaf.* Premises as verified 2026-11-08: the response is
`Response(int status, String body, @Nullable String location)` (`HttpCheckExchange.java:40`),
while the GitHub core reads `ETag` (`GithubConditionalRequestCache.java:81`),
`x-ratelimit-remaining` / `retry-after` (`GithubRateLimit.java:26-27`) and
`X-Accepted-GitHub-Permissions` (`GithubLabelProvisioner.java:53,92`); the exchange refuses a
body over 1 MiB (`JdkHttpCheckExchange.java:44`) while the core reads comment threads and issue
listings at `per_page=100` (`GithubCommentThread.java:32`, `GithubFeedQuery.java:79`) and a whole
job log for a 4000-character tail (`GithubWorkflowJobsFetcher.java:77-86`); the refusal text
hardcodes `factory.check.http.allowlist` (`EgressAllowlist.java:87`); a GitHub request carries no
timeout (`GithubHttpClient.java:72`, connect only). So the response carries the headers (the
`http` built-in's findings keep rendering status and body only); the body bound, the request
timeout and the key a refusal names are parameters of the consumer's exchange, and a consumer may
read a body as a bounded tail. The numbers, so that no implementer invents them: the `http`
built-in keeps 1 MiB and 30 s; the GitHub core declares **16 MiB** (a thread of a hundred
65 k-character comments in UTF-8 fits with room) and **60 s** per request, and reads a job log
as a tail bounded by its own 16 MiB. The tracker declares no `egress` of its own: GitHub answers
a transferred repository with a same-host `301`, and nothing else redirects it.

*A hop keeps the request's meaning, and credentials stay on their host.* The rule by status
follows RFC 9110 §15.4 as every surveyed client implements it: a `301`, `302` or `303` hop is a
`GET` with no body and no `Content-*` header (OkHttp's `redirectsToGet`); a `307` or `308` hop
keeps method and body; a hop from `https` to `http` is refused (the JDK's `Redirect.NORMAL`,
Go, requests); hops are bounded by `MAX_REDIRECTS = 3`, and a hop onto a URL already visited in
the chain is a loop and is refused. A cross-host hop drops the **credential deny-set** — the
JDK's own five names `Authorization`, `Cookie`, `Host`, `Origin`, `Referer`, plus
`Proxy-Authorization` and `WWW-Authenticate` — and keeps every other header (`Accept`,
`User-Agent`, `X-GitHub-Api-Version`): the first amendment's "no header at all" was stricter than
any client and would break a target that negotiates on `Accept`. Same host keeps everything;
"subdomain keeps credentials" (Go's rule) is rejected explicitly — `*.blob.core.windows.net` is
not GitHub. Measured 2026-11-08 on JDK 25.0.3+9 and 27.0.1: `Redirect.NORMAL` already drops the
five across hosts; the leaf keeps the property where a spec can assert it.

*A refusal is a configuration fault, not an outage.* `EgressRefusedException extends IOException`
(`EgressRefusedException.java:19`); `GithubHttpClient.doSend` wraps every `IOException`
(`GithubHttpClient.java:142`), `GithubRetryConfig` retries four times (`GithubRetryConfig.java:62`)
and the exhausted budget leaves as `GithubHttpException`, the outage path. The core classifies a
refusal apart from transport: no retry, no outage signal, the error names the consumer's key
(NFR-R5, UX4). An unresolvable host is transport, as above.

*Test posture.* About 75 specs drive the GitHub client against WireMock or a Gitea container on
`http://localhost` or `http://127.0.0.1`, most through an inline `api-url` in a fixture's
`config.yaml`. Under this decision the fixture's **operator** file grants `loopback` in the
`in-process` scope — operator configuration a fixture already writes (`project.yaml`), not a
switch in production code; `TakeExitCodeE2eSpec` keeps its expectation (the unreachable tracker
fails at the first call, not at load). A manifest target on `http` stays refused (main spec,
"Plain http is refused").

*Current signatures the decision changes:* `HttpCheckExchange.Response(int status, String body,
@Nullable String location)`; `GuardedHttpCheckExchange(HttpCheckExchange delegate, EgressAllowlist
allowlist)`; `EgressAllowlist(List<String> entries, HostResolver resolver)` and
`EgressAllowlist.from(Map<String, Object> subsection)`; `JdkHttpCheckExchange()` with the
constants `REQUEST_TIMEOUT` and `MAX_BODY_BYTES`; `GithubHttpClient(String apiUrl, String token)`
and the package-private `GithubHttpClient(String apiUrl, String token, RetryConfig retryConfig)`;
`EgressRefusedException(EgressRefusal refusal) extends IOException`; `EgressEntries.isEntry(String)`
and `EgressEntries.normalized(String)` in `sandbox/core`; `ExecutorEgress.union(PipelineDefinition)`;
`HttpCheckSubsectionValidator` grading the `allowlist` key; `FactoryProperties.check`
(`Map<String, Map<String, Object>>`, project level).

*Alternative zero — no guard on the GitHub core (the escalation's option B):* FR15's duty for
the credentialed GitHub calls is left with nobody, the construction gate keeps a permanent
pending row, and the webhook of `add-stage-finished-event` would be a second copy of the rules.
Rejected. *Alternative rejected — admission by author (the 2026-11-08 wording):* the premise is
false for the inline tracker key, so a committer's URL would be "the operator's consent"; and it
exempted a typed host from the address-class rule its own delta applied to every hop. *Alternative
rejected — one allowlist and a test switch (option A as first worded on 2026-11-08):* it was
rejected then for needing a switch in production code, which was wrong — a `loopback` entry in
the fixture's operator file is configuration; it is still rejected for making the operator list
`api.github.com` beside every tracker subsection and for giving GHES no rule. *Alternative
rejected — a separate `redirect-hosts` list (the first amendment):* a second list for the same
question, with no precedent in any surveyed system. *Alternative rejected — keep three operator
keys and unify only the grammar:* Gitea's path, now being undone; the grammar is unified here
anyway, and the consolidation costs the removed-key rule the configuration capability already
has. *Alternative rejected — aliases for the old keys:* the project's convention is a removed
key that names its replacement, and an alias is a second spelling that drifts.

Enforcement: the architecture spec `HttpClientConstructionBoundarySpec` asserts that no
production class outside the leaf constructs a `java.net.http.HttpClient` (allowlist by file,
asserted reached; its pending row for `GithubHttpClient` is retargeted to `:githubapi` by task
3.0a and deleted by 3.0c); an `EgressGrammarOwnerSpec` in `:bootstrap` asserts that `*.`-prefix
matching and the class words are spelled in `EgressEntries` only; the identity spec
`EgressAdmissionByMediumSpec` drives the real exchange over a local server (a law-medium host
granted by an operator entry admitted, the same host with no grant a located load error, an
`in-process` `loopback` entry admitting `127.0.0.1`, a cross-host hop carrying `Accept` and no
`Authorization`); `GithubRefusalClassificationSpec` pins "a refusal makes no retry and no outage
signal, an unresolvable host does".


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
  in-process guard of D16 bounds where the client may send it, and a cross-host redirect hop
  drops the credential deny-set, so the token never reaches a log store.
- [A committer points an inline `tracker.github.api-url` at a host of their own and the factory
  sends the operator's token there] → a law-medium host needs an operator grant (D16 as amended
  2026-11-09): the vendor default is admitted, any other host is a located load error until the
  operator grants it in an egress entry or a `connection:` profile.
- [The operator block's removed keys stop every existing installation at startup] → the
  violation names the new key and the line (the removed-key rule of the operator-configuration
  capability); the operator guides carry the two-line migration; no silent alias.
- [A name rebinds between the guard's resolution and the JDK client's connect] → open window,
  named in D16 and deferred in NG7 with its pickup condition; the address-class judgement on
  every hop still refuses what the guard can see.
- [The exchange's body bound refuses a long comment thread or a big job log] → the bound is the
  consumer's (D16 as amended): the GitHub core declares its own, and reads a job log as a
  bounded tail; a bound still exists, so no consumer reads an unbounded body.
- [A GitHub request has no timeout today and a hung read holds a slot] → closed by the move:
  every request through the guarded exchange carries the consumer's timeout (D16 as amended).
- [An egress refusal is retried and reported as an outage] → the GitHub core classifies the
  refusal apart from transport (D16 as amended, NFR-R5); a law-medium host without a grant and
  a literal blocked address are judged at load with no lookup, while an unresolvable host is an
  infrastructure failure at the call, never a configuration refusal.
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
