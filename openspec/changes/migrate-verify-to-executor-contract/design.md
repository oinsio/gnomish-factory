# Design: migrate-verify-to-executor-contract

## Context

See proposal.md — Why. Constraints:

- After `define-executor-contract`: `ExecutorRegistry` (name → `Executor`), `ExecutorResult`
  (status + answer), `ExecutorOutcomeClassifier`, `ResultFileReader`, describe-validated
  settings. Its D10 lists check-side verdict construction (`ShellCommandCheckRunner`,
  `FilesExistCheckRunner`, `ExternalPolling`, `VerifyOrchestrator:149`) as exemptions that
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
rate limit. Its token resolves by the git-remote connection profile, not the tracker's.
*Rationale:* "named verdicts on a SHA" is the common contract of every SCM, covering any CI
without per-CI code; expected contexts are the only cure for "pending forever". *Alternative
rejected:* folding it into the GitHub tracker adapter — ties CI reading to the issue tracker,
wrong under Jira + GitHub.

**D4 — `github-actions` and `http` move as built-ins with unchanged behavior.** (FR6.) Their
provider classes become `Executor` implementations in `adapters/github` and `adapters/check`;
subsection and params validators become describe schemas; pin contribution becomes a
describe field. *Alternative rejected:* dropping `github-actions` for `scm-status` now —
job-log findings are not available from check-run output (proposal Q2).

**D5 — Verdict derivation is one engine component.** (FR7, M2.) `VerdictDerivation(answer,
threshold) → Verdict` in `domain/engine`; built-ins emit SARIF only; the `shell` built-in
normalizes the legacy findings file to SARIF at the edge. *Rationale:* vendor exit codes are
policy-coupled (Semgrep, Trivy); one mapping owner. *Alternative rejected:* per-executor
pass/fail — the two-taxonomy failure again.

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

**D8 — Sync surfaces.** Scout: `grep -rn "Kept in sync with"`, registry, and domain-term
search for "poll", "verdict", "findings file".
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
| `VerdictDerivation` (`domain/engine`)                                                    | `Verdict` from `Answer.Verdict` + `Threshold`             | `VerifyOrchestrator`, `JudgeVoting` (judge votes already SARIF-shaped? no — exempt until `extract-agent-round-runner`, named)                                     | `new Verdict.Fail/Pass/CannotVerify(` in `ShellCommandCheckRunner`, `FilesExistCheckRunner`, `ExternalPolling`, `VerifyOrchestrator:149`, `HttpExternalCheckClient`, `GithubCheckExternalClient` deleted; exempt: `JudgeVerdictExtractor`, `JudgeRoundExecution`, `JudgeCriteriaPreflight`, `StateJsonMapper` (deserialization) | grep gate `new Verdict\.` → derivation + state mapper + judge exemptions only; data-driven threshold spec                                                            |
| `ExecutorRegistry` (from the contract)                                                   | `Executor` by `ExecutorName`                              | `VerifyOrchestrator` for every check (new), stage dispatch (existing)                                                                                             | `CheckClientDiscovery`, `CheckProviderSeam`, `ProviderDispatchingExternalCheckClient`, `CheckClientConfiguration`, `CheckEquipment` registry fields deleted; `META-INF/services/...CheckClientFactory` entries deleted                                                                                                          | `CheckClientFactory` type gone (compile); `ArchUnit`/grep: no `ServiceLoader.load(` outside tracker and sandbox-binding discovery                                    |
| `scm-status` built-in                                                                    | `ContextVerdicts` on `AttemptSha`                         | `VerifyOrchestrator` via registry                                                                                                                                 | none (new); identity spec: contexts read equal those the SCM holds for the attempt SHA (`ScmStatusShaPinningSpec`, WireMock)                                                                                                                                                                                                    | `AttemptSha` value type on the read; `expected` non-empty at load                                                                                                    |
| Credential delivery for verdict executors (`ChildEnvAllowlist` layer, from the contract) | `ExecutorCredentialNames`                                 | `SandboxCheckEnvironmentSource.freshBox`, `HostCheckEnvironmentSource`                                                                                            | `CheckProviderSeam.credentialEnvVars` union deleted; `CheckEquipment.credentialNames` deleted                                                                                                                                                                                                                                   | `ProgramCredentialDeliverySpec` extended with a verdict check: token in fresh box, absent in round box                                                               |

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
`define-executor-contract` is implemented and stays as built, and `extract-agent-round-runner`
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

## Risks / Trade-offs

- [Duplicate server-side submission in window W1] → bounded to one per kill; the record is
  the receipt (crash-consistency rule 7); the ledger logs the re-drive.
- [`github-actions` and `http` regress during the move] → their existing WireMock specs are
  kept and re-pointed at the built-ins (behavior-preserving extraction).
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
