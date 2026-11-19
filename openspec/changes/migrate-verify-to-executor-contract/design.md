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
→ `poll(key)` at `interval` (no in-poll wait bound travels, NG13; amended 2026-11-13) → terminal or
timeout. Resume reads the record and polls; it never re-`start`s a persisted key.
*Rationale:* Argo's `Running + requeue`, Snakemake's `run_job/check_active_jobs`, our own
stateless-instance rule. *Alternative rejected:* executor-side waiting (`qualitygate.wait`)
— holds the box, loses the quality/infrastructure split, Airflow's slot lesson.
*D2 as amended (2026-11-10, D19):* the next `poll(key)` is made no sooner than
`max(interval, pollAfterMillis)` of the last pending answer, a hint never extends the timeout,
and the timeout is classified by the last pending answer's `pendingState` before the declared
timeout class applies (D19).
*D2 as amended (2026-11-12, D21, D22; human decisions on the blocker of task 4.2):* "persist into
the attempt's state (one commit with the attempt state, pushed)" reads "persist into the open
round's state by a **pending commit**" — a registered closing commit on the round's snapshot that
carries no attempt (D21); the snapshot is taken before verification on both media (D22).

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
*Amended 2026-11-16 (task 6.1, human decision):* a describe schema grades what it can state; what
it cannot — the `http` settings' cross-field rules (`HttpCheckParamsValidator`) and the
`factory.check.github-actions` / `factory.check.http` subsections (`GithubCheckSubsectionValidator`,
`HttpCheckSubsectionValidator`, called by `BuiltInCheckSubsections`) — stays with the built-in's own
validator, a core class that no plugin implements. So the api's validator interfaces leave
`gnomish-plugin-api` with nothing published in their place: the params interface becomes
`CheckSettingsValidator` in `:adapters` (`adapter.pipeline`; the `github-actions` validator, which
`:adapters:scm` cannot bind to it, is a plain class bound by `BuiltInCheckConfiguration`), the
subsection interface is dropped, and the `http` built-in's run context (`CheckRunContext`) becomes
`HttpRunContext` in `:adapters` (`adapter.check.http`). *Alternative rejected — delete the
validators with the interfaces:* their duties would have no carrier, which is a silent loss of a
check at load. *Alternative rejected — keep the interfaces in the api:* a published surface with no
third-party implementer, against FR14 and the one MINOR bump of D20.

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

*`files-exist` result cap (human decision 2026-11-10, task 3.1a):* the `files` setting has no
count limit; when more than `Answer.Verdict.MAX_RESULTS` paths are missing, the answer carries the
first `MAX_RESULTS - 1` as `error` results and one `error` result naming how many more are missing
(`FilesExistOutcome.reported`, the one rendering both the executor and the legacy port take), so
the check fails as a judgement, never `failed/infrastructure`.

**D6 — Credentialed verdict executors run in `FRESH_BOX` with the law's granted egress.** (NFR-S1.)
The loader rejects `same-box` on such checks; `SandboxCheckEnvironmentSource.freshBox` takes
the law's `executors.<name>.egress` grant as the box's egress set beside `factory.egress.sandbox`,
which the describe preflight bounds (`ExecutorEgress`, `GrantAgreement`; D10 of
`define-executor-contract`): the box and its guard exist before `describe` can run in it, so a
host the describe declares and the law does not grant is never admitted (item 10 of the third
D16 review; the earlier wording "the executor's declared hosts" would have admitted, for a box
holding the token, hosts the law never granted). *Alternative rejected:* widening the
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
*Amended 2026-11-10 (D19):* the wait hint and the pending state of D19 are not part of the record
and add no durable step: a resume polls the recorded key at once, and a rate limit still in force
answers with a fresh hint — one request per resume. No window is added.
*Amended 2026-11-12 (D20; human decision, closes Q1 of `define-executor-contract`):* the record
is `PendingPollRecord(CheckRef check, int attempt, PendingKey key, Instant since)` in
`domain/engine`, the key typed by the `:pendingkey` leaf, never a `String`. Its one state-file
mapping is an optional `pendingPoll` object of the attempt (`StateAttemptDto`), `{"check":
{"index": <the check's zero-based position>, "ref": "<its label, as `StateCheckDto.ref`>"}, "key":
"<the key's value>", "since": "<ISO-8601 instant>"}` — the index is carried in full, unlike the
checks list's placeholder index (`StateJsonMapper.java:31-38`), because the resume (4.3) picks the
check by it; the attempt number is the attempt the object sits in, so it is not repeated. An
absent `pendingPoll` reads as "no record" (state files written before this change stay readable).
The cap on the key in the state file is `PendingKey.MAX_LENGTH` (4096 UTF-16 characters, no ISO
control character) by construction: the mapper builds the key through the leaf's constructor, so
there is no second constant, and a key it refuses makes the file a `MalformedStateFileException`.
The record carries no wait hint and no pending state (D19). No window is added.
*Amended 2026-11-12 again (D21, D22; human decisions on the blocker of task 4.2), superseding the
two amendments above wherever they differ:* the record is not a field of the attempt. It is an
entry of the open round's list on `TaskState`, `PendingPollRecord(CheckRef check, int attempt,
@Nullable RoundToken token, PendingKey key, Instant since)`, one entry per check of the round that
went `pending`, and its durable step (2) is a **pending commit** on the round's registered
snapshot (D21); step (3) is the round's closing commit, which carries no entry. "Shape owner: the
attempt record (`pendingPoll` field)" reads "the interrupted verification of `InProgress` — a
registered snapshot, or a registered pending commit above it — whose one recovery owner is the
interrupted-verification resume with `PendingPoll` (D21)". The window table is D21's, on both
media (D22); it adds W0 (snapshot registered, no `start` yet) and W1′ (pending commit landed
locally, its push failed).

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
of this run's executor or from the pending record through the resume path, never from the request on
the wire, so the refusal of an unknown key stays for every other key (as amended 2026-11-13 below:
the table is the run's, on the program's registry entry, not one executor instance's).

*The call path and the signatures it changes.* The engine reaches a verdict executor only
through `StageExecutor.execute(Request)` (G1: `EnginePorts` keeps `StageExecutor` and
`JudgeVoter`), so a poll is a `StageExecutor.Request` that carries a turn:
`@Nullable PendingTurn pending`, with `record PendingTurn(String key, Origin origin)` and
`enum Origin { ISSUED, RECORDED }` in `domain/engine/port`. No turn is a `start`; `ISSUED` marks
a key this run's own `start` or `poll` returned; `RECORDED` marks the key `PendingPoll` read from
the pending record on resume, set by that path only. The application-side wrapper that D18 (as
amended) builds at resolution around the executor bound to the check and its working copy
dispatches the turn: none → `start(request)`; `ISSUED` →
`poll(request, key)`; `RECORDED` → `adopt(request, key)` when the executor implements the role
interface `PendingAdoption` (`app/port/check`: `void adopt(ExecutorRequest request, PendingKey
key)`), then `poll(request, key)`. `ProgramExecutor` implements `PendingAdoption`; the five
built-ins do not, since their key names a run the remote holds and every instance can poll it.
The `Executor` interface of `:executor-contract` is unchanged: adoption is a Java-side concern
of the in-process program adapter, never a contract operation a program sees — the reason D17
keeps Java-only members off the mirror interface. Sequencing: the wrapper is built by task 3.6;
the request's `pending` turn and check subject, the none and `ISSUED` arms, and the wrapper's
mapping of a contract verdict answer to `ExecutionResult.VerdictReply` (D18, "The verdict answer
crosses the engine port") land in task 4.0b, before `PendingPoll` (4.2) is built on them; the
`RECORDED` arm and `PendingAdoption` land in 4.3a.
*Amended 2026-11-12 (D20):* every `PendingKey` above is the `:pendingkey` leaf's type
(`executorcontract.PendingKey`, `PendingKey.java:16-41`, is deleted by task 4.0c), and the turn
is `record PendingTurn(PendingKey key, Origin origin)` (today `PendingTurn(String key, Origin
origin)`, `PendingTurn.java:13`); the wrapper's arms hand `turn.key()` to `poll`/`adopt` as it is,
with no re-wrap (as built by 4.3a's first half: `CheckRound.java:98`, `:100` hand `turn.key()` on). `PendingAdoption`
names the leaf type in `:application` (`app/port/check`), and the dependency-analysis gate fails
a used-transitive dependency there (`build.gradle`, `issues { all { onAny { severity 'fail' } } }`,
the one exception being `:gnomish-plugin-api:sample`), so task 4.3a declares
`project(':pendingkey')` in `:application` and retires the reach entry task 4.0c gave it.
*Amended 2026-11-13 (task 4.3a; human decision after the scoped D14 review of the same day, which
found the first amendment of that day wrong; that amendment, "adopt on every poll", is replaced in
full):* **the table of pending runs belongs to the run, adoption to the resume.**
*Context, verified in code.* The check dispatch binds per request (`CheckRoundRoute.execute`,
`CheckRoundRoute.java:34-38`, binds `CheckDispatch.bind(check, workspace)` on every `execute`),
and `PendingPoll` goes through the port on `start` and on every poll (`PendingPoll.java:91`,
`:101-102`), so the bound executor that answered `start` is not the one a later poll reaches.
Today each `ProgramExecutor` holds its own table (`private final PendingRuns pending = new
PendingRuns()`, `ProgramExecutor.java:42`), so after 5.3 binds a `program` check the second
request of a live check finds no entry and is refused as an unknown key. `start` takes `before`
as the current view, dirty or not (`ProgramExecutor.java:96-97`); `RecordedKeyAdoption.entry`
refuses every entry but the header (`StatusEntries.changedAgainstHead`,
`StatusEntries.java:94-102`), so the `state.json` lag a pending commit leaves (D21, "The lag the
write leaves behind") would refuse an adoption made over it.
*Decision.*
- **One `PendingRuns` per run, on the `program` registry entry.** The table of issued keys, each
  with the `before` view its `start` took, is a long-lived collaborator of the program's
  `RegistryEntry` (D18 as amended: "its long-lived collaborators", built once per run at run
  assembly), shared by every binding of that entry, stage and check alike. Its entries are keyed
  by the subject and the key — for a check, the check's identity in the round (its position and
  label, `CheckRef`), so two checks of one program never collide on one key —
  and the map stays concurrent with the claim as its first phase (`lock-scope.md`, the three-phase
  shape, as `PendingRuns` is built today). The claim removes the entry for the poll's duration, so a
  second poll of a key while one runs is refused; `settled` puts it back under the key the program
  answered when that answer is `pending`, and under the polled key when the poll ended the run, with
  the same view "before" — so a re-poll of an ended run re-runs the program and is judged as the
  ending poll was (FR12; `executor/wire-contract`, "Re-poll of an ended run returns its result
  again"). The table holds at most one ended entry per check and key for the run, and goes with the
  run's registry entry. *Corrected 2026-11-13 (task 4.7):* the text said "an entry leaves the table
  at the poll that ends its run"; the conformance kit drives a `program` candidate through the
  factory's own `ProgramExecutor` (`ProgramCandidate`), so with that removal the factory refused the
  kit's re-poll as an unknown key before the program saw it, and a conforming program failed the
  `pending-repoll` case.
  Precedent: Kubernetes `ControllerExpectations` — one in-memory store per controller, shared by
  every reconcile, keyed by the object's identity, a missing entry falling to a safe path — with
  the durable record (the round's pending entries, D21) as the source of truth.
- **Where the subject comes from.** No request names a check's position today: `StageExecutor.Request`
  carries the check's declaration only (`VerifyCheck.@Nullable Executor check`), and
  `CheckRef.of(int index, VerifyCheck check)` (`CheckRef.java:57`) needs the index, which only the
  engine holds (`PendingPoll.answer(VerifyCheck.Executor check, CheckRef ref, PendingRound round)`,
  `PendingPoll.java:83`; the requests it sends, `round.request(check, null)` and
  `round.request(check, new PendingTurn(key, turn))`, `:93`, `:129`, drop it). So the engine's port
  carries it: `StageExecutor.Request` gains `@Nullable CheckRef checkRef`, set by `PendingPoll`
  through `PendingRound.request` on every request of a check round, and null exactly when `check`
  is null (the compact constructor refuses one without the other); the stage-round constructor
  stays as it is. `CheckRoundRoute.execute` hands `request.checkRef()` with the check to
  `CheckDispatch.bind`, and the entry's check binding receives it; a built-in's binding ignores it.
  A stage round has no subject in this change: a transform's `pending` is refused before any poll
  ("Pending from a role whose caller cannot poll"), so the stage binding scopes the table with no
  subject, and `extract-agent-round-runner` adds the `Round` subject (task 4.6 keeps the entry
  subject-neutral for it). A key by the check's value instead (`VerifyCheck.Executor`) is rejected:
  two checks declared alike, or one check in two stages over one entry, would share a key, and the
  scenario "A key issued for one check is unknown to another" could not tell them apart.
- **The live `ISSUED` turn goes straight to `poll`, with no adoption.** The key and its `before`
  are in the run's table because a binding of the same entry put them there at `start`; the
  answer that ends the run is judged against the view its `start` took, as the stable
  "A pending judge is judged when it ends" says. The wrapper reads `Origin` for exactly this
  (`CheckRound.java:97`): `ISSUED` → `poll`; `RECORDED` → `adopt`, then `poll`.
- **Adoption on `RECORDED` only, judged by identity.** A `RECORDED` turn exists only on the resume
  path (`PendingPoll.java:91`): a resume by another instance, or by this one after a restart — a
  new run, with an empty table. One rule decides, with two halves:
  - **`HEAD` is the commit the medium stands on by the factory's own act** — equality, never
    membership of a chain: for a fresh box, the attempt commit its binding materialised the box at
    (`fresh.materialize(branch, attemptWorkspace.attemptCommitSha())`,
    `SandboxCheckEnvironmentSource.java:93`), which the program check binding of task 5.3 passes as
    the expected `HEAD`; otherwise the round's last registered commit
    (`LastRegisteredCommit.lastRegistered()`), since the same clone's worktree and the same box
    follow the branch (their `HEAD` followed the pending commit). The expected `HEAD` is handed in
    by the binding, never read from the environment. This is the identity `FactoryMove.changed`
    already judges a run's move by (`HEAD` after the run equal to `registered`, `FactoryMove.java:56`).
    A `HEAD` on an earlier commit of the round's chain — a killed first run's `git reset --soft
    <snapshot>` or checkout of an intermediate pending commit — is refused: on the host it moved the
    branch itself, and admitting it would surface later as an unattributed compare-and-swap failure
    of the next pending or closing commit (`FactoryRegister.close(…, expectedTip)`, D21).
  - **The only other entry is the factory's `state.json` lag**: the working tree equal to the index,
    mode included, and the index object equal to the `state.json` of a commit of the round's
    registered chain — the first-parent chain from the last registered commit down to the round's
    snapshot, which includes a pending commit a resumed worktree was reset to (D21, "The lag the
    write leaves behind"). The chain serves this blob comparison only. It is read once, on the
    factory's side, by the one walker that exists: `PendingChain`, which `SnapshotTipCheck.inspect`
    already runs over the factory clone at the resume (D21; bare-object reads, no checkout, no hooks),
    never by a second walk in the environment where the killed run worked (crash-consistency item 14:
    recovery rebuilds context through the live path's parse; a walk there would also lean on the
    invocation policy's `core.useReplaceRefs=false`, `GitInvocation.java:72`, with grafts
    unverified). `PendingChain` yields the chain's commits beside the snapshot, `PendingVerification`
    carries them, the run's `CurrentRound` holds them from `restore(PendingVerification)` and appends each pending
    commit it registers, and the read-only role `LastRegisteredCommit` exposes them; the adoption
    reads each listed commit's `state.json` object through the `stateBlobAt` `WorkingCopyCheck` owns
    (an object read by commit id, no walk). A fresh box stands on the snapshot, the chain's bottom,
    so its view holds no lag at all.
  The judgement is made by the owner of the factory's move, `FactoryMove`, through a second query
  beside `changed`, never by a second copy of the lag rule. Any other change, a `HEAD` other than
  the expected one, a round with no registered commit, or a view that cannot be taken refuses the
  key's poll with `failed/infrastructure` naming the key and the paths (or `HEAD`), no process
  spawned. (Identity, not content, decides, crash-consistency item 13.) The baseline is a durable
  identity — the commit the factory put the medium on — taken from the record, as Bazel's REAPI
  names an action's inputs by `input_root_digest` and a git CI names a build by its commit, never a
  view re-taken per poll.
- **The in-place run** has no snapshot and so no `RECORDED` turn: nothing resumes it in another
  instance, and its per-run table covers every poll it makes. A dirty working copy there is the
  `start`'s `before`, as today.
*Signatures this changes* (`design-decisions.md`, "A decision added during implementation"),
today:
- the engine's port, `StageExecutor.Request` (`domain/.../engine/port/StageExecutor.java:76-83`):
  ```java
  record Request(
          TaskContext context,
          StageDefinition stage,
          Workspace workspace,
          int attempt,
          List<CheckResult> feedback,
          VerifyCheck.@Nullable Executor check,
          @Nullable PendingTurn pending)
  ```
  with the stage-round constructor `Request(TaskContext, StageDefinition, Workspace, int,
  List<CheckResult>)` (`:99-106`): it gains `@Nullable CheckRef checkRef` after `check`, set
  together with `check` (the compact constructor refuses one without the other); the stage-round
  constructor passes null for both and keeps its signature. A record's constructor is exempt from
  the parameter-count gate (`process-invariants.md`). Its one check-round construction,
  `PendingRound.request(VerifyCheck.Executor check, @Nullable PendingTurn turn)`
  (`PendingRound.java:125-126`), becomes `request(VerifyCheck.Executor check, CheckRef ref,
  @Nullable PendingTurn turn)`, called by `PendingPoll.answer` with the `ref` it already holds
  (`PendingPoll.java:83`, `:93`, `:129`);
- `StageExecutor bind(VerifyCheck.Executor check, Workspace workspace)` (`CheckDispatch.java:44`),
  `Executor executor(VerifyCheck.Executor judged, Workspace workspace)` (`RegistryEntry.java:94`) and
  `Executor bind(VerifyCheck.Executor check, Workspace workspace)` (`CheckBinding.java:25`): each
  gains `CheckRef ref` after the check, handed from `request.checkRef()` by
  `CheckRoundRoute.execute` (`CheckRoundRoute.java:34-38`); the five built-ins' bindings ignore it;
- `public ProgramExecutor(ExecutorDeclaration declaration, Describe describe, List<String>
  credentials, TaskExecutionEnvironment environment, ProgramRoundEquipment equipment)`
  (`ProgramExecutor.java:51-56`), with `new PendingRuns()` in a field initializer (`:42`): it
  gains a sixth parameter, `PendingScope scope`, and builds no table of its own. `PendingScope` is
  a new public record of `adapter.agent.program`, `PendingScope(PendingRuns runs, @Nullable CheckRef
  subject, @Nullable String expectedHead)`: the run's table, the subject the binding scopes it to
  (null for a stage round and for the kit's candidate) and the `HEAD` an adoption expects (the
  fresh-box binding's attempt commit; null means `LastRegisteredCommit.lastRegistered()` at the
  adoption). A record, so the eighth parameter the three values would otherwise cost never exists;
- `public ProgramExecutorFactory(PipelineLaw law, DescribedProgram program, ProgramRoundEquipment
  equipment)` (`ProgramExecutorFactory.java:29`), built once per declaration in
  `ExecutorRegistry.of` (`ExecutorRegistry.java:84`): it takes the entry's table, built there
  beside it (`new PendingRuns()`), and hands every `ProgramExecutor` it binds a scope with no
  subject over it; the program check binding of task 5.3 takes the same table and scopes it to the
  request's `CheckRef` and the attempt commit it materialised the box at; the conformance kit's
  candidate (`new ProgramExecutor(…)`, `ProgramCandidate.java:112`) builds one table per case, its
  run, with no subject;
- `final class PendingRuns` with `@Nullable Entry claim(PendingKey key)`, `void
  adopted(PendingKey key, Entry entry)`, `void settled(Entry entry, ExecutorResult result)`
  (`PendingRuns.java:32`, `:68`, `:79`, `:90`, all package-private): the class and its no-argument
  constructor become `public`, so `ExecutorRegistry` (`app`) and `ProgramCandidate`
  (`app.conformance`) in `:bootstrap` can build a table and hand it on; `claim`, `adopted`,
  `settled` and `Entry` stay package-private, so only `adapter.agent.program` reads or writes one.
  The map's key becomes the subject and the key, both taken from the `PendingScope`;
- `static PendingRuns.Entry entry(String executor, PendingKey key, WorkingCopyView view)`
  (`RecordedKeyAdoption.java:31`): it judges through `FactoryMove`'s adoption query against the
  expected `HEAD` and the registered chain, with the `stateBlobAt` read `WorkingCopyCheck` already
  owns (`WorkingCopyCheck.java:127`), not `StatusEntries.changedAgainstHead`;
- `static List<String> changed(Set<String> before, Set<String> after, Optional<String>
  registered, Function<String, Optional<String>> stateBlobAt)` (`FactoryMove.java:49-53`) stays;
  `FactoryMove` gains the adoption query over one view, the expected `HEAD` and the registered
  chain's commits;
- the read-only role `LastRegisteredCommit` (today the one method `Optional<String>
  lastRegistered()`, `LastRegisteredCommit.java`; `NONE = Optional::empty`) gains `List<String>
  registeredChain()`, the round's registered commits from the last registered one down to the
  snapshot (empty for `NONE` and for a round with nothing registered), which `CurrentRound`
  implements; `public record PendingVerification(String attemptCommit, String stage, int round,
  RoundToken token, Optional<RoundDecisionRequest> request, String registered)`
  (`PendingVerification.java:50-56`) gains the chain `PendingChain` walked, and
  `Optional<String> snapshotBelow(String branch, RegisteredCommit pending, AttemptKey key,
  RoundToken token)` (`PendingChain.java:58`) yields the commits it passed beside the snapshot
  (one walk, two answers).
*Alternative rejected — adopt on every poll* (the first amendment of 2026-11-13): it re-takes the
baseline on every poll, so `before` moves with the working copy, and it judges that baseline
"clean against `HEAD`": the lag of the factory's own pending commit, which lands before the first
poll (`PendingPoll.java:101-102`), refuses every live poll of a `program` verdict check on the
host and in `same-box`, and a dirty in-place working copy that `start` accepted refuses at the
first adoption. *Alternative rejected — hold the bound executor for the check in the router* (the
former D18 wording, "a bound executor lives for its round or check"): the port does not tell the
route when a check ends, so the hold is a cache keyed by the check's value with no lifetime
signal; the per-run table has a lifetime (the run) and an exit (the terminal poll) by
construction. *Cost:* none per poll beyond today's; on a fresh-box check a request materializes a
box (D18 as amended, a check's bound executor lives for one request), the cost task 5.3 accounts.

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
when the view taken at adoption has its `HEAD` on the commit the binding expects — the attempt
commit a fresh box was materialised at, otherwise the round's last registered commit — and no other
entry but the factory's own `state.json` lag, judged by identity through `FactoryMove` (as amended
2026-11-13 above);
any other change refuses the poll as `failed/infrastructure` naming the key and the changed paths,
with no process spawned** — a cannot-verify the operator sees, never a verdict judged against a view that
already holds the first run's writes. The guard does not see writes a salvage commit already
moved into `HEAD`: that a salvage commits a killed check program's writes as round work is a
property of `WorktreeSalvage` that today's `command` checks share, recorded under Open Questions
as a finding outside this change.
In a fresh box the guard does not see the writes of a `start` that returned `pending` either:
`ProgramExecutor` judges the working copy only on a terminal answer (`ProgramExecutor.java:173`),
and the box `start` ran in is disposed with its writes (D11 as amended 2026-11-14), so the terminal
poll's judgement covers that poll's own writes only. Nothing of a fresh box reaches the branch (it
is never harvested), so no write escapes; what is lost is the report of a read-only breach by a
pending `start`. It is accepted as a property of the box per request, not a defect: the
working-copy rule exists so that a verdict cannot alter what it judges or what the round keeps,
and a disposed box alters neither.
W3 (superseded key) and W4 (ended run's key) are adopted the same way; the program's own
retention (D11) answers them. *Alternative rejected — persist the before-view in the pending
record:* puts a program-adapter detail (git status entries) into the engine's record, and a view
taken in the first environment would mismatch the second one's working copy, turning every
resume into a false cannot-verify. *Alternative rejected — accept any key on the wire:* removes
the unknown-key refusal for every poll, not only the resumed one. *Alternative rejected — an
`adopt` member on `Executor`:* a Java-only member no program can implement (D17). The `executor/wire-contract`
delta MODIFIES "Poll of a program is refused without its preconditions" and "A pending judge is
judged when it ends", scenario titles kept, each with a scenario for the adopted key, and ADDS
"A recorded key is adopted only over an unchanged working copy" for the guard (as amended
2026-11-13: the run's table shared by the bindings of one entry, the key per check, and the guard's
`HEAD` equal to the commit the binding expects, with scenarios for a resume over the pending
commit's lag and for a `HEAD` on an earlier commit of the round's chain).
*D14 as amended 2026-11-12 (D21, D22):* on the resume path `PendingPoll` takes the record from the
open round's list the resumed state carries (D21), never from an attempt, and sets `RECORDED` for
each entry whose attempt and token name the resumed round. The per-medium premise is now met on
both media: a host resume in the same clone discards the verification residue above a registered
snapshot before it re-verifies (D22), so the adoption guard usually sees a working copy equal to
`HEAD`; the guard stays. *Corrected 2026-11-13:* "usually", because the discard is best effort
(`WorktreeSalvage.discard`, a failed step logs and leaves the leftovers,
`WorktreeSalvage.java:100-125`) and the container leaves its residue in the box (D22), so a resume
on the same clone or in the same box can find the pending commit's `state.json` lag; the guard's
identity rule accepts exactly that lag and nothing else.

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
- *Undeclared pair declared (item 10 of the second D16 review):* the box guard's mitmproxy addon
  (`EgressGuardConfig.java:60-66`, Python inside a Java constant) matches exact hosts and `*.`
  suffixes by hand — the `sandbox` subset of the grammar. It runs in the box, where no Java class
  can, so it cannot share `EgressEntries` (preference 1 unavailable, the barrier is a language
  and a process). It becomes a declared pair: both ends carry `Kept in sync with` (the Python
  side in its comment, the Java side naming `EgressGuardConfig`), the invariant is "the `sandbox`
  subset — exact host, IP literal, `*.suffix` never matching the apex — matches the same names",
  a row is added to the registry's no-shared-classpath table, and an identity spec drives both
  matchers over one table of names. It is the one exemption of `EgressGrammarOwnerSpec`.
- *Touched declared pair (D19, 2026-11-10):* `ExecutionResult` (`:domain`) ↔ the contract's
  `Status` + `FailureClass` (registry "Declared pairs with no shared classpath"): the engine's
  `PendingState` and the contract's `PendingState` join the pair — every contract token has one
  engine counterpart. Both ends gain the constant set in one task (4.0b), the mapping stays an
  exhaustive switch in the one contract → domain mapping, its spec iterates `values()` of both,
  and the registry row's invariant names `PendingState` (task 4.0b carries the row edit). Kept
  declared, not collapsed: `:domain` still cannot reach `:executor-contract`.
- *Undeclared fold dissolved (D19, 2026-11-10):* "is this response a rate limit" was judged in
  four places — `GithubRateLimit.isRateLimited` for a `403` (`GithubRateLimit.java:22-28`), and
  a `429` compared by hand in `ScmStatusReader.java:107` (constant `RATE_LIMIT`, `:34`),
  `GithubFreshBody.java:48` and the retry predicate `GithubRetryConfig.java:58` (constant
  `RATE_LIMIT_STATUS`, `:38`). D19 makes `GithubRateLimit` the one owner of the judgement and of
  the pause; the three consumers read its result.
- *Touched declared pair, mapping side (D19 review, 2026-11-12):* the contract → engine direction
  of the pair `ExecutionResult` ↔ `Status`/`FailureClass` has one implementation,
  `ProgramResultMapping` (`ProgramResultMapping.java:20-21`, "The one mapping of a contract result
  onto the engine's result shape"; reached by the check wrapper at `CheckRound.java:66`). D19's
  `PendingState`, `details` and verdict-answer branches are added there, never in a second switch
  beside it; what 4.0b extracts from `ShellResult.asDomain` is only the reading of one SARIF result
  (`SarifResult` → `VerdictAnswer.Result`), which `ProgramResultMapping` calls.
- *Untouched declared pair (D19 review, 2026-11-12):* `KitCase` ↔ `steps/executor-run.sh`. The
  bad-hint fake of D19 "Conformance" is its own preset section, not a `break-<case>` link, so the
  case names and the `BROKEN` switch (`executor-run.sh:45-53`) do not change and
  `KitCaseScriptIdentitySpec` is unaffected.
- *Pair avoided by extraction (D20, human decision 2026-11-12):* the pending key's rule (not
  blank, at most 4096 characters, no ISO control character), which `PendingPollRecord` in
  `:domain` needs typed while it lives in `executorcontract.PendingKey` (`PendingKey.java:16-41`),
  beyond the domain's reach. A domain twin declared as a pair was rejected: a JDK-only leaf both
  modules reach is possible, so the rule of two forbids the pair. → one leaf, `:pendingkey`; the
  contract's type is deleted. Not a declared pair, and no registry row.
- *Touched declared pair (D21, D22; human decisions 2026-11-12):* `GitAttemptPersistence` ↔
  `EnvironmentAttemptPersistence` (registry "Initial registry"). Both ends gain the second
  operation of the port, `persistPending` (D21), in one task (4.2a): the pending commit carries
  exactly `state.json`, sits on the round's last registered commit, lands with its register in one
  ref transaction and goes out in the closing push. The host writes it from objects in its clone's
  `.git` (no worktree, no index); the container writes it in the box through a private index, then
  harvest and admission (D21, "The write", as amended by the review of 2026-11-12). The host end
  also takes the base of its round boundary from the run's `CurrentRound` (D22) as the container
  end already does. *The new consumer's condition* (`manual-sync-pairs.md`, "A new consumer of a
  declared pair"): a pending commit is written while the pending check's run is still alive in the
  same working copy (`HostCheckEnvironmentSource.java:28-34`; a same-box check on the container),
  a writer that may stage, commit or hold `index.lock`. Neither end as first written held under it:
  `WorktreeClosingCommit` takes the branch tip as parent and the shared index as tree
  (`WorktreeClosingCommit.java:60-61`, `:72`, `:83`), and the in-box `git commit` commits whatever
  the box's index holds. Both ends now build the commit's tree from `registered`'s tree plus one
  blob and its parent from `registered`, so the run can reach neither. Preference 1 (collapse the
  pair onto `GitObjects`, the `cleanUp` precedent) was weighed and kept for the host only: the
  container's state commit is made in the box and its parent must be the box's `HEAD`
  (`HarvestedStateCommitCheck.verifyParent`, `:76-95`), so a factory-side container commit would
  leave the box behind the register. The
  registry row's invariant gains "and the pending commit: exactly `state.json`, on the round's last
  registered commit, its register in the same transaction" (task 4.2a carries the row edit; the
  rules directory is outside this change's artifacts). Kept declared, not collapsed: the two still
  write through different media.
- *Twin avoided by a shared step (D22):* the host snapshot is not a second copy of
  `EnvironmentRoundSnapshot`. Both ends live in `:adapters:git`, so no classpath barrier allows a
  declared pair (rule of two, preference 1): one snapshot step over a medium strategy — the
  `ResumeMechanics<B>` shape — owns the subject (`ServiceCommitMessages.snapshot`), the range
  judgement (`GnomeRangeInspection`, and on the host the uncommitted-envelope judgement before the
  restore, D22), the subject by the round's close outcome (`snapshot` or `unverified-snapshot`,
  D22), the empty-round commit, the register and the `CurrentRound.snapshotted` transition; the
  strategy only commits (in-box + harvest + admission, or the worktree's index through
  `WorktreeClosingCommit` — at the snapshot no check runs yet, so the index has one writer).
- *Twin avoided by a shared recogniser (D22):* the host's interrupted verification is recognised by
  the container's `SnapshotTipCheck` and resumed by the container's
  `ResumeVerificationStageExecutor`, both of which read only the register and a commit subject; no
  host copy of either is written. The rule "no salvage over an interrupted verification", today in
  `ContainerResumePreparation.prepare` only (`ContainerResumePreparation.java:44-56`), gets one
  owner that the container preparation, the two host resume paths
  (`GitResumeContinuation.java:75-80`, `TakeResumeRunner.java:105-110`) and the two revocation
  stops (`RevocationHandler.java:75`, `ContainerRunSupport.java:308-311`) call.
- *No other new pair created.*

**D9 — Single-owner mechanisms.**

| Owner                                                                                    | Value (type)                                              | Requires | Consumers                                                                                                                                                         | Old way removed                                                                                                                                                                                                                                                                                                                 | Enforced by                                                                                                                                                          |
|------------------------------------------------------------------------------------------|-----------------------------------------------------------|----------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `PendingPoll` (`domain/engine`)                                                          | `PendingPollRecord` (key as `PendingKey` of `:pendingkey`, check identity `CheckRef`, attempt, round token, since), one entry per pending check in `TaskState.pendingPolls` (D7 and D20 as amended 2026-11-12, D21) | the task branch (the list lands in a pending commit with its register, D21); the executor's `poll` | `VerifyOrchestrator` (all verdict checks), the resume path through `PendingVerification` and `ResumeVerificationStageExecutor` (D21), `AttemptPersistence.persistPending` and its implementations and decorators (`GitAttemptPersistence`, `EnvironmentAttemptPersistence`, `PushBestEffortAttemptPersistence`, `RevocationCheckingAttemptPersistence`, `InMemoryAttemptPersistence`), state mappers (`StateJsonMapper`, `StatePendingPollMapper`); the key-rotation commit (D12) is written by the owner, never by a check | `ExternalPolling` deleted; `GithubCheckExternalClient` run-listing loop reduced to one observation per `poll`                                                                                                                                                                                                                   | parameter type (`PendingKey` of the `:pendingkey` leaf, no `String`, on the record and on the port, D20); kill-point specs D7; grep gate `Thread.sleep\|sleeper.sleep` in `adapters/check` and `adapters/github/check` → zero hits |
| `TaskState.withPendingPolls` (`domain/engine`, D21) | `TaskState.pendingPolls` (`List<PendingPollRecord>`) | none (a value) | writer: `PendingPoll` only; every other writer of `TaskState` (`recordUnburnedRound`, `recordQualityFailure`, `recordPassAndAdvance`, `advanceTo`, `startOfStage`, the lifecycle rewrites of the resume, decision and approval writes) builds the state with an empty list; readers: `StateJsonMapper`, the resume path (D21) | the attempt's `pendingPoll` field and `AttemptRecordChecks.requirePendingPollOfRound` (`AttemptRecord.java:106-117`, `AttemptRecordChecks.java:75-81`) deleted, with `StateAttemptDto.pendingPoll` (`StateAttemptDto.java:78`) | the component set only through `withPendingPolls`; a data-driven spec over every `TaskState` writer asserting the empty list; the round's liveness judged by token in `PendingPoll` (D21), never by the list's presence |
| The round snapshot step (`adapters/git`, D22) | the round's snapshot commit (`String` commit id held in the run's one `CurrentRound` as `ClosedRound.attemptCommit`), its subject chosen by the round's close outcome (`VERIFIABLE` → `snapshot`, `UNVERIFIABLE` → `unverified-snapshot`) | a `.git` of the task's clone and the round's working copy (the box, or the host worktree) | the host round close (the git-mode host snapshot decorator over the run's cell, D22) and the container round close (`SandboxRoundEnvironmentSource`'s round, which today calls `EnvironmentRoundSnapshot.snapshot`, `EnvironmentRoundSnapshot.java:89`, from `closeRound()` — `ProgramStageExecutor.java:66`, `ExecutorRoundExecution.java:132` — which gains the close outcome); readers: `SnapshotTipCheck`, `GitAttemptPersistence`/`EnvironmentAttemptPersistence` (parent and range base through `CurrentRound`), `BestEffortPush`'s ancestry guard (host), `WorkingCopyCheck` (whether a move of `HEAD` during a `verdict`/`decision` run is the factory's: `branch.oid` equals the round's last registered commit, read through a read-only role interface of `app.port.git` that `CurrentRound` implements, carried by `ProgramRoundEquipment`; D21, "The lag the write leaves behind"), and `RecordedKeyAdoption` (the adoption's default expected `HEAD` and the round's `registeredChain()`, filled by `CurrentRound.restore` from the chain `PendingChain` walked, D14 as amended 2026-11-13) | a host round's gnome work landing only in the round commit (`GitAttemptPersistence.java:124-152`); `previousTip` as the host persistence's own memory (field `GitAttemptPersistence.java:88`, set `:109`, `:143`, read `:130`, handed to `BestEffortPush.pushBestEffort`, `:150-151`); the cell as a private field of `MidRoundPushRounds` (`:58`), replaced by one cell per run handed to the round source and to `TaskStoreGit.attemptPersistence(Path, String, CurrentRound)`; the unconditional `snapshot` subject of a container round close | one step over a medium strategy (no host copy of the subject, range judgement or register write); identity spec `HostSnapshotFlowSpec`: a host round's checks observe exactly the registered snapshot's tree and the round commit's parent is it |
| The interrupted-verification resume (`SnapshotTipCheck` → `ResumeVerificationStageExecutor`, D21, D22) | `PendingVerification` (snapshot, registered commit, stage, round, token, decision request); the round's pending entries ride the resumed `TaskState` read at the registered commit through `StateJsonMapper`, never `PendingVerification` | the clone's register and objects | both media's run assembly (`ExecutorAdapterSelector.stageRounds`, today sandbox only, `ExecutorAdapterSelector.java:98-100`; the host's through the per-run value of D22); the one "no salvage over an interrupted verification" rule, called by `ContainerResumePreparation.prepare`, `GitResumeContinuation.resumeFromRecordedPosition`, `TakeResumeRunner.resumeWithoutDecision`, `RevocationHandler.handle` (`RevocationHandler.java:75`) and `ContainerRunSupport.revocationSalvageAndPush` (`ContainerRunSupport.java:308-311`) | the host's unconditional salvage before a resume (`GitResumeContinuation.java:76-80`, `TakeResumeRunner.java:106-110`) and the unconditional salvage of both revocation stops, when the register is a snapshot or pending commit | the recogniser and the decorator are each one class used by both media; kill-point specs of D21 on both media; a revocation during a verification above a snapshot lands no salvage commit (4.2d) |
| `PendingKey` (`:pendingkey` leaf, D20; human decision 2026-11-12) | `PendingKey` (a non-blank value of at most `MAX_LENGTH` = 4096 UTF-16 characters with no ISO control character), and the judgement `unusable(String)` for a written string | none (pure; JDK only) | constructors and readers: `ResultReading` (`executor-contract`, the written `pendingKey`), `ExecutorContractJson` (the JSON binding, serializer only), the built-ins' result builders `HttpResults`, `GithubActionsResults`, `ScmStatusResults`, the six executors' `poll` (`ShellExecutor`, `FilesExistExecutor`, `HttpExecutor` in `:adapters`; `GithubActionsExecutor`, `ScmStatusExecutor` in `:adapters:scm`; `CommandStageExecutor` in `adapters/agent`), `ProgramExecutor`/`PendingRuns`/`ProgramSpawn`, `ConformanceKit` (`bootstrap`), `InProcessExecutorDouble` (`:test-fixtures` main), `StateJsonMapper` (the `pendingPoll` record, 4.1), `PendingPoll` (`domain/engine`, 4.2: builds `PendingTurn(…, ISSUED)` and `PendingPollRecord` from the result's key); carriers: `ExecutorResult.pendingKey`, `Executor.poll`, `ExecutionResult.Pending.key`, `PendingTurn.key`, `PendingPollRecord.key`, `PendingAdoption.adopt` (4.3a); pass-through: `ProgramResultMapping`, `CheckRound`; javadoc: `Status` (`{@link PendingKey}` of its own package, `Status.java:23`, no import) | `executorcontract.PendingKey` deleted (`PendingKey.java:16-41`), with its `@JsonValue`; the `String key` of `PendingTurn` (`PendingTurn.java:13`) and of `ExecutionResult.Pending` (`ExecutionResult.java:147-148`); the unwrap `Objects.requireNonNull(result.pendingKey()).value()` (`ProgramResultMapping.java:183-184`) and the re-wrap `new PendingKey(turn.key())` (`CheckRound.java:80`) | the parameter type on every carrier; `DomainLeafPuritySpec` (the leaf declares no internal module and no production artifact); grep gate: `class PendingKey\|record PendingKey` in `pendingkey/src/main` only, no `PendingKey(` built from `.key()` of a turn or result in production, and every `PendingKey` in `executor-contract/src/main` resolved through an import of `com.github.oinsio.gnomish.pendingkey.PendingKey` (the package-local `{@link}` survives a deletion the compiler sees elsewhere) |
| `ExecutorOutcomeClassifier` (`domain/engine`; the owner of design D10 of define-executor-contract, gate G4; its queries added by D22 as amended 2026-11-12, "Questions to the owner") | the attempt accounting of a result (`classify`), and three queries of the same table: `verifiable(ExecutionResult)` (the round completed and asked no question: `classify` answers `Proceed`), `pending(ExecutionResult)` → `Optional<ExecutionResult.Pending>` (the facts of a round that has not finished), `failure(ExecutionResult)` → `Optional<ExecutionResult.Failed>` (package-private) | none (pure) | `classify`: `RoundExecution`; `verifiable`: `SnapshottingStageExecutor` (`adapters/git`, the git-mode host snapshot trigger, D22); `pending`: `PendingPoll` (the `start` answer and every `poll` answer, 4.2) and `CheckRoundAnswers` (a pending answer has not ended); `failure`: `CheckRoundAnswers` (4.2). Not a consumer: `ProgramResultMapping.closeOf` decides the close from the contract result before the mapping (`ProgramCompletedAnswer.refusal`, the one judgement the mapping refuses a completed answer by), reading no engine status; `RoundOutcomeWords` (`application`) stays the gate's one `dto:` rendering exemption | the readers' own status reads: `instanceof ExecutionResult.Pending` in `PendingPoll` (two sites), the `switch` over the status in `CheckRoundAnswers.of`, `instanceof ExecutionResult.Completed` plus the decision-request test in `SnapshottingStageExecutor.execute`, and the round trip `toEngine(…).status() instanceof ExecutionResult.Completed` in `ProgramResultMapping.closeOf` | `OutcomeClassifierOwnerSpec` (`:bootstrap`, the "status read" rule: the classifier only, plus the `dto:` row of `RoundOutcomeWords`; no row added, the pickup-prefix rule unchanged); `ExecutorOutcomeClassifierSpec` pins each query against the table; `ProgramResultMappingSpec` pins `closeOf` equal to the mapping for every answer shape of both roles |
| `VerdictDerivation` (`domain/engine`)                                                    | `Verdict` from `VerdictAnswer` (on the port as `ExecutionResult.VerdictReply`, D18) + `Threshold` | none (pure) | `VerifyOrchestrator`, `JudgeVoting` (judge votes already SARIF-shaped? no — exempt until `extract-agent-round-runner`, named)                                     | `new Verdict.Fail/Pass/CannotVerify(` in `ShellCommandCheckRunner`, `FilesExistCheckRunner`, `ExternalPolling`, `VerifyOrchestrator:153`, `HttpExternalCheckClient`, `GithubCheckExternalClient` deleted; exempt: `JudgeVerdictExtractor`, `JudgeRoundExecution`, `JudgeCriteriaPreflight`, `JudgeCannotVerify` (the judge classes until `extract-agent-round-runner`, NG1), `StateJsonMapper` (deserialization) | grep gate `new Verdict\.` → derivation + state mapper + judge exemptions only; data-driven threshold spec                                                            |
| The findings of a pass (D5 as amended)                                                   | `Verdict.Pass.findings` (below-threshold results)         | none (a value) | writers: `VerdictDerivation`, `JudgeVerdictExtractor`; readers: `StateJsonMapper`/`StateCheckDto`, `AttemptMapper`/`CheckDto` (`status --json`), `UsageReportJsonMapper`; `RoundExecution` feedback excluded by `Fail` filter | `CheckFindings.noteIgnoredOnPass` and the exit-0 drop in `ShellCommandCheckRunner` deleted with 2.2; `new Verdict.Pass()` no-arg form gone from production (the final sweep of task 7.2 found no `new Verdict.Pass()` in any `src/main`; `files_exist` is routed through `FilesExistExecutor`, its runner's legacy `run` deleted) | the record's canonical constructor (no findings-less form but the explicit empty list); state round-trip spec over a pass with findings; feedback spec: a pass with findings feeds nothing |
| `ExecutorRegistry` (D18 as amended 2026-11-10) | one immutable `Map<ExecutorName, RegistryEntry>` (`Map.copyOf`, as today's `ExecutorRegistry.java:49`), a `RegistryEntry` being the name, its `Describe` and its long-lived collaborators, with two bindings: `executor(TaskExecutionEnvironment)` for a stage round (the existing `RoundExecutorFactory.executor(TaskExecutionEnvironment)`) and `executor(VerifyCheck.Executor, Workspace)` for a check round; never a ready `Executor`, nothing bound cached | at run assembly (after the law is bound and the describe preflight answered; in `take`/`serve` after the claim): the law, the describe answers, the admitted connections of the built-ins the law's checks name (a refusal is a located `ConfigError` on the describe refusal's path, D18); at resolution: the open round's environment or the check's working copy | `ExecutorDispatch.java` (stage rounds); the check dispatch of task 3.6, `CheckDispatch.java` (resolution and binding) and `CheckRound.java` (the D14 wrapper over the bound executor, the `StageExecutor` the port sees), reached from `ExecutorAdapterSelector.StageRounds.checks`; *added by task 3.6:* `BuiltInEntries.java` (the built-ins' entries: `command`, and each verdict built-in the law's checks name, the five `new <Built-in>Executor(` of `:adapters` in their bindings), `ScmBuiltIns.java` in `:adapters:scm` (the two SCM entries' bindings and the one `ScmConnectionReader` read of run assembly), `BuiltInConnections.java` (the admission before the describe branch, refusal as a located `ConfigError`, called by `RunDescribe.java`), the admitted connections carried on `DescribedLaw` as `AdmittedConnection`; `VerifyOrchestrator.java` and `PendingPoll` (4.2), which reach a check's executor only through the engine's `StageExecutor` port, the request naming the check as its subject (4.0b); `BuiltInCandidate.java` (the kit's in-process candidate binds through an entry, D18) | `CheckClientDiscovery`, `CheckProviderSeam`, `ProviderDispatchingExternalCheckClient`, `CheckClientConfiguration`, `CheckEquipment` registry fields deleted; `META-INF/services/...CheckClientFactory` entries deleted; the `CommandExecutorFactory command` parameter of `static ExecutorRegistry of(DescribedLaw, PipelineDefinition, PipelineLaw, RoundEnvironmentSource, ProgramRoundEquipment, CommandExecutorFactory)` (`ExecutorRegistry.java:65-71`) and the field `Map<ExecutorName, StageExecutor> byName` (`:46`) replaced; exemption until 5.2: the transitional `GithubCheckClientFactory`, which reads its connection through `ScmConnectionReader` | `CheckClientFactory` type gone (compile); `ArchUnit`/grep: no `ServiceLoader.load(` outside tracker and sandbox-binding discovery; `ExecutorRegistryOwnerSpec` (its form table, `ExecutorRegistryOwnerSpec.groovy:57-63`) extended by 3.6 with `new FilesExistExecutor(`, `new ShellExecutor(`, `new HttpExecutor(`, `new GithubActionsExecutor(`, `new ScmStatusExecutor(` — each spelled in production only in its entry's binding — and `ScmConnectionReader` `.read(` only in the registry assembly (plus the transitional provider until 5.2), asserted reached; immutability by the field's `Map.copyOf`; the six built-in names by their one `:domain` set (D18), `BuiltInExecutors` — `ExecutorRegistryOwnerSpec` pins the literals `"files-exist"`, `"shell"`, `"github-actions"`, `"scm-status"` to it; exempt by reason, `"http"` and `"command"` spelled as words of other vocabularies (a URL scheme in `EgressAdmission`, `EgressAllowlist`, `OriginUrlNormalizer`, the guard addon of `EgressGuardConfig`; a stage type or manifest token in `StageDefinitionMapper`, `StructuralValidation`, `VerifyCheckDtoDeserializer`, `BlankMechanismFields`); the five known-zero usages by `Usage.knownZero()` |
| `PendingRuns` (`adapters/agent`, `adapter.agent.program`; D14 as amended 2026-11-13) | the pending runs of one `program` executor in one run: per subject (a check's `CheckRef`, carried by `StageExecutor.Request.checkRef` from `PendingPoll` and handed to the binding in a `PendingScope`; none for a stage round) and `PendingKey`, the `before` view its `start` took (`PendingRuns.Entry`), or an adopted run's entry or refusal | none (in memory; the durable source of truth is the round's pending entries, `TaskState.pendingPolls`, D21) | one table per `program` registry entry per run, built where the entry is built (`ExecutorRegistry.of`, `ExecutorRegistry.java:84`) and shared by every binding of it: `ProgramExecutorFactory` (stage rounds) and the program check binding of task 5.3; the conformance kit's candidate builds one per case (`ProgramCandidate.java:112`); readers: `ProgramExecutor.poll` (`claim`); writers: `ProgramExecutor`'s `start`/`poll` (`settled`) and its adoption on a `RECORDED` turn (`adopted`), called only by `CheckRound` | the table built per executor instance (`new PendingRuns()`, `ProgramExecutor.java:42`); the adoption on every poll (the first amendment of 2026-11-13, never built); the adoption guard's "clean against `HEAD`" (`StatusEntries.changedAgainstHead` in `RecordedKeyAdoption.java:38`), replaced by `FactoryMove`'s adoption query against the expected `HEAD` (a fresh box's attempt commit, otherwise the last registered commit) and the registered chain `PendingChain` read at the resume | `PendingAdoptionOwnerSpec` (`:bootstrap`, ArchUnit), extended by 4.3a: `PendingAdoption.adopt` called only from `CheckRound`, and only on its `RECORDED` arm; `PendingRuns`' readers and writers confined to `adapter.agent.program` by visibility (the class and its no-argument constructor `public`, `claim`/`adopted`/`settled` and `Entry` package-private), so the spec does not re-check them; `new PendingRuns(` only in `ExecutorRegistry` and `ProgramCandidate` (ArchUnit); every target asserted reached; the scope by the constructor (`ProgramExecutor` takes a `PendingScope`, builds no table) |
| `PendingKeyPrefix` (`domain/engine`; task 4.5) | the shown form of a pending key: its first 16 code points, `...` when longer, as `UntrustedText` (`branchDocument` for a key read from the pending record, `subprocess` for one an executor issued) | the key | `PendingPoll.waitFor` → `EngineEvent.CheckPolled` / `EngineEvent.CheckTimedOut` → `LoggingEventListener` (the per-poll DEBUG line and the timeout line; ADR 0004 keeps the domain logger list closed), `PendingPoll.waitFor` (the resumed key, once per `RECORDED` turn) → `CheckOutcome.resumedKey` → `RoundOutcomeLedgerWriter` / `RoundOutcomeLineMapper` (the ledger's `resumedKey`) | none existed; exemption: the refusal texts of `ProgramExecutor.poll` and `RecordedKeyAdoption` keep `excerpt(256)`, since a cannot-verify reason must name the key, not a display prefix | `EngineEvent.CheckFinished` carries only the cut carrier, so `:application` holds no whole key to re-render; `PendingKeyPrefixSpec`, `PendingPollObservabilitySpec`, `LoggingEventListenerPollSpec`, `DomainLoggerAllowlistSpec` |
| `scm-status` built-in                                                                    | `ContextVerdicts` on `AttemptSha`                         | the network to the SCM API, through `:httpegress` | `VerifyOrchestrator` via registry                                                                                                                                 | none (new); identity spec: contexts read equal those the SCM holds for the attempt SHA (`ScmStatusShaPinningSpec`, WireMock)                                                                                                                                                                                                    | `AttemptSha` value type on the read; `expected` non-empty at load                                                                                                    |
| `:githubapi` (D15)                                                                       | the GitHub HTTP core (one client, credential, cache, rate-limit, retry) | an `AdmittedEndpoint` and the network through the guarded exchange; no `HttpClient` of its own | the bundle's tracker; `github-actions` and `scm-status` in `adapters/scm`; `add-sandbox-gha-executor` later                                                 | the bundle's `adapter.github` package deleted; `GithubCheckExternalClient`'s own client construction routed through the library                                                                                                                                                                                                  | grep: `class GithubHttpClient` in one module (M6); the bundle's layering allowlist; `GithubPluginAbsenceSpec` narrowed to `adapter.tracker.github` references outside the bundle    |
| `:httpegress` (D16 as amended 2026-11-09) | `EgressMedium`, `ConnectionEndpoint(URI, EgressMedium, List<String> egress)` (minted where the configuration is read), `AdmittedEndpoint` (minted only by `EgressAdmission.admit`: the URL and its admitted set), all three in `domain.egress`; `EgressRefusedException` (`RuntimeException`, the configuration class) and `ExchangeLimitException`; `EgressEntries` (the one grammar; the `sandbox` subset for the box), the operator block `factory.egress.{sandbox,in-process}` and a connection's `egress` | at load: nothing — no name resolved, IP literals and `localhost` classified statically; at connect: DNS and the network, on the client's own selector thread (no parked read) | **minting:** `ConnectionProfiles.endpoint` (the only `new ConnectionEndpoint(`), called by `TrackerConfigMapper` (`LAW` inline) and `ScmConnectionReader` in `adapters/scm` (`OPERATOR`, both built-ins); **load-time judges:** `TrackerConfigMapper` via `PipelineMapper.map`/`PipelineLoader` (tracker endpoint, carried on as `TrackerConfig.endpoint`), `ScmConnectionReader` at the executor registry assembly (built-ins' connections), `HttpCheckSubsectionValidator` and the manifest loader of the `http` check (law-medium target; a templated authority judged at connect), `ExecutorDeclarations` (`ExecutorDeclarations.java:142`, the law's `executors.<name>.egress`: `sandbox` subset enforced), `ProgramDescribe` (`ProgramDescribe.java:133`, a describe's `egress`: full grammar) and the describe preflight `GrantAgreement`, `ExecutorEgress` (the box's union), the `factory.egress` reader in `FactoryProperties` replacing `SandboxProperties.egressAllowlist` (`SandboxProperties.java:82`) (grammar, `sandbox` subset, removed keys); **classification:** `OperatorPreconditionRefusals` (refusal → `EGRESS`), `FeedOutageRetry` (rethrows a refusal), the `EGRESS` probe; *added by task 3.0c, missed by the row:* `FeedCycle` and `DaemonConditions.reportRefusal` (the feed's hand-off of a rethrown refusal to its condition), `ServePreconditions` through its `EgressProbe` role (bound to `EgressAllowlist.judge` at the composition root — the probe's one spelling), the endpoint registry `TrackerAdapterConfiguration.trackerEndpointRegistry` and the loader input `ConnectionAdmission` (the adapters' declarations, the profiles and the `in-process` scope travelling down `PipelineLoader` together), and, until task 3.3 moved it, the bundle's `GithubCheckClientFactory`, which minted its operator endpoint through `ConnectionProfiles.endpoint` and admitted it (a transitional row of `EgressMediumMintingSpec`, deleted by 3.3); *added by task 3.3:* the transitional check provider `GithubCheckClientFactory` in `adapters/scm` (until task 5.2 removes the check-provider SPI), which reads its connection through `ScmConnectionReader` and mints nothing itself, and the built-ins' describe `egress`, which joins the admitted set through `EgressAdmission.admit(…, declaredEgress)`; *added by task 3.4:* the manifest loader of the `http` check is `ManifestTargetSeam` (`adapter.pipeline`, called by `PipelineModelBuilder.mapAndValidate` with `ConnectionAdmission.operator()`) over `HttpTargetAdmission` (`adapter.check.http`), which judges a literal target through `EgressAdmission.request(URI, OperatorEgress)` — the law-medium request with no connection, mints nothing — and leaves a templated authority to connect; `HttpCheckSubsectionValidator` judges no host (the `http` subsection is empty by definition) and is a consumer of nothing here; the `http` built-in's one construction is `HttpExternalCheckClient.guarded(OperatorEgress, …)`, used by `HttpCheckClientFactory` and, from task 3.6, the registry assembly, and `EgressAllowlist.of` takes the typed `OperatorEgress`, no bare list; **connect-time consumers:** `GithubHttpClient` in `:githubapi` (the bundle's tracker, `github-actions`, `scm-status`), the `http` built-in's exchange, the webhook notifier of `add-stage-finished-event` (when it lands), `add-sandbox-gha-executor` through the library; **declared pair:** the box guard's addon matcher in `EgressGuardConfig` (`EgressGuardConfig.java:142-146` writes the list it matches; the `sandbox` subset) | `EgressAllowlist`'s own grammar deleted in favour of `EgressEntries`; `factory.sandbox.egress-allowlist` and `factory.check.http.allowlist` removed keys; `redirect-hosts` and *declared redirect hosts* withdrawn; `GithubHttpClient(String apiUrl, …)`, its direct `HttpClient` and `Redirect.NORMAL` replaced by the guarded exchange over an `AdmittedEndpoint`; the bare `String` `api-url` read in `GithubTrackerAdapterFactorySupport`; the blocking `readNBytes` body read; the hardcoded `factory.check.http.allowlist` in the refusal text replaced by the consumer's key | parameter type (`GithubHttpClient` takes `AdmittedEndpoint`, no `String` URL); `EgressMediumMintingSpec` (`new ConnectionEndpoint(` only in `ConnectionProfiles`, `EgressMedium.OPERATOR` only there and in `ScmConnectionReader`, `new AdmittedEndpoint(` only in `EgressAdmission`, asserted reached); `HttpClientConstructionBoundarySpec` (client construction in the leaf only, pending row deleted by 3.0c); `EgressGrammarOwnerSpec` (wildcard and class words spelled in `EgressEntries` only, one exemption: the declared pair); identity spec `EgressAdmissionByMediumSpec` on the real exchange over a local server; `GithubRefusalClassificationSpec`; `OperatorPreconditionOwnerSpec` extended to the `EGRESS` arm; the removed-key violations of the operator-configuration capability |
| Pin set of a check (D17)                                                                 | law `pinPaths` ∪ `checkId` when `checkIdIsPinnedPath`     | the law commit and the executor's describe | the pin decorator of 4.2f: `PinCheck.pinSet`/`PinCheck.guarding` (`:adapters`, `adapter.check`; built by `RunLaw.pinCheck` over the law commit) wrapping the bound executor in `PinCheckedExecutor`, called by `CheckDispatch.bind` with the registry entry's describe, for every check round (`CheckRoundRoute`) | `PinCheckedExternalCheckClient`, `GithubCheckPinPaths`, `RunLaw.pinGuarded`, `ProviderDispatchingExternalCheckClient.pinContributor` and the transitional provider's `pinContributor()` override deleted (4.2f); `ExternalCheckPinContributor` and `CheckClientFactory.pinContributor` read by no factory component, deleted with the api types by 6.1 | the describe field is the only per-executor input; `PinDecoratorSpec` data-driven over the flag; `ExecutorRegistryOwnerSpec` rows: `new PinCheck(` only in `RunLaw`, `.guarding(` only in `CheckDispatch`, `new PinCheckedExecutor(` only in `PinCheck`, `new CheckRoundRoute(`/`new CheckDispatch(` only in the selector, `new EnginePorts(` and `stageRounds.port()` only in `RunAssembler` |
| `GithubRateLimit` (`:githubapi`, D19) | the rate-limit judgement of one response and its pause (`Optional<RateLimit>`, `RateLimit(Duration pause)`): `retry-after` (seconds, or an HTTP-date against `Date`), else `x-ratelimit-reset − Date`, else 60 s; a `403` with GitHub's signal and a `429` alike | none (pure, over the response's status and headers) | `GithubRetryConfig` (retry only a pause of at most 8 s, no sooner than the pause), `GithubConditionalRequestCache.Fresh` (carries it), `ScmStatusReader` (→ `ScmStatusRead.RateLimited(Duration pause)` → `pollAfterMillis`), `GithubFreshBody` (classification unchanged, FR6) | the `429` comparisons at `ScmStatusReader.java:107` (constant `:34`), `GithubFreshBody.java:48` and `GithubRetryConfig.java:58` (constant `RATE_LIMIT_STATUS`, `:38`); `GithubRateLimit.isRateLimited`; the boolean `Fresh.rateLimited`. A pause is never negative (a reset at or before `Date` is a zero pause) | parameter type (`Fresh` carries `@Nullable RateLimit`, no boolean); `GithubRateLimitOwnerSpec` in `:bootstrap` (added by 4.0a, on the model of `BaseHeadDefaultBoundarySpec`): a scan of `githubapi/src/main` and `adapters/scm/src/main` with comments stripped, in which the literal `429` and the header names `retry-after`, `x-ratelimit-reset`, `x-ratelimit-remaining` appear only in `GithubRateLimit.java`, asserting the scan reached that file |
| The contract → domain mapping of a result: `ProgramResultMapping` (`adapters/agent`, `ProgramResultMapping.java:20-21`; D18, D19 as reviewed 2026-11-12) — one owner for both roles; 4.0b extracts from `ShellResult.asDomain` only the reading of one SARIF result (`SarifResult` → `VerdictAnswer.Result`), which the mapping's verdict branch calls | `ExecutionResult`: `VerdictReply` (SARIF results → `VerdictAnswer.Result`, `SarifResult.details` → `Finding.details`; a transform round still refuses it, `ProgramResultMapping.java:89-98`), `Failed(failureClass, reason, findings, details)` (a `failed/quality` result's `details` → the details of its one finding, `Finding(reason, null, details)`; every class carries `details` on the port, and `VerifyOrchestrator` (4.2f) puts a `failed/infrastructure` result's `details` into `CannotVerify.details`), `Pending(key, pollAfter, state, reason, details)` | none (pure) | the check wrapper of D14/D18 (`CheckRound`, every check round, built-in and program) and `ProgramStageExecutor` (transform rounds); readers of the values it fills: `PendingPoll`, `VerdictDerivation`, `VerifyOrchestrator`, `StateFindingMapper`, `AttemptMapper`, `UsageReportJsonMapper`, `ProgramRequests.feedback`, `TerminalOutcomeRender` | `ShellResult.asDomain` (legacy port path; its SARIF-result half moves under the mapping), the details folds `ShellResult.folded`/`asSarif`, `GithubActionsResults.error`, `HttpResults`, `ShellExecutor.java:113-116` (timeout, `failed/quality` with the output tail) and `:119` (unanswerable, `failed/infrastructure`). The SARIF-result reading itself is `SarifResultReading` in `:application` (`app.port.check`), not in `adapters/agent`: `:adapters` (`ShellResult.asDomain`, its first consumer) cannot reach `:adapters:agent`, and both reach `:application` (task 4.0b). Exempt, not mappings (task 4.0b sweep): the three `ExecutionResult.Failed` constructions outside it — `RoundFailures.thrown`, `AgentRoundFailure.of` and `ProgramStageExecutor`'s failure before its result was read — each build a factory `failed/infrastructure` where no contract result exists, with no details | parameter type (the wrapper returns only what the mapping builds); `ProgramResultMappingSpec` iterating `values()` of `Status`, `FailureClass` and `PendingState` (the declared pair's identity); grep gate in 4.0a: `folded(` and a `"\n" + details` concatenation in `adapters/src/main` and `adapters/scm/src/main` → zero hits; grep in 4.0b: `new ExecutionResult.Pending(` and `new VerdictReply(` in production only in `ProgramResultMapping` (today `ProgramResultMapping.java:69` is the one `Pending` construction); identity spec: a shell check's output tail written as `properties.details` arrives as `Finding.details` in the state file (`ShellDetailsFlowSpec`, real environment, written by 4.2f) |
| Credential delivery for verdict executors (`ChildEnvAllowlist` layer, from the contract) | `ExecutorCredentialNames`                                 | a fresh box (`program`) or the factory process (built-in) | `SandboxCheckEnvironmentSource.freshBox`, `HostCheckEnvironmentSource`                                                                                            | `CheckProviderSeam.credentialEnvVars` union deleted; `CheckEquipment.credentialNames` deleted                                                                                                                                                                                                                                   | `ProgramCredentialDeliverySpec` extended with a verdict check: token in fresh box, absent in round box                                                               |
| The built-ins' credential names in the run's scrub and never-allowlist sets (NFR-S1; named by task 5.2, 2026-11-14): `BuiltInCheckSubsections` (`:bootstrap`) — the token name of each SCM built-in the operator connected (its describe's credential, or the name its subsection or profile renames it to, through `ScmBuiltIns.credential`), per law the token name of each SCM built-in a check names, and the name each `http` check gives in its own `auth` setting (`HttpExecutor.credentials`, which its describe cannot declare); a `program` executor's names are not here, they are its own grant | `Set<String>` of credential names, unioned with the tracker's names by the caller | the operator's `factory.check.*` subsections and connection profiles; for the per-law half, the `PipelineDefinition` | `CheckEquipment.childEnv` / `installationChildEnv` (host, describe), `DoctorProjectReadings.declaredSecrets` (`doctor`), `ContainerRunSupportFactory.create` (container) — each unions the owner's names with the tracker's | `CheckProviderSeam.checkCredentialEnvVars` deleted with the seam; `CheckEquipment.credentialNames` deleted by 5.3, the union with the tracker's names done by each caller | `ExecutorRegistryOwnerSpec`: `HttpExecutor.credentials(` called in production only by `BuiltInCheckSubsections` (added by 5.3); `BuiltInCheckSubsectionsSpec`, `ManualRunAssemblyCheckCredentialSpec` |

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
and admission by the medium a URL comes from.** (FR15, FR17, FR18, NFR-R5, UX4, NG7, NG9; first amended
2026-11-08 by the human decision on the escalation of task 3.0a of #121; amended again
2026-11-09 after the decision review of that amendment under `.claude/rules/spec-mismatch.md`
and a best-practice survey — RFC 9110 §15.4, the JDK's `RedirectFilter`, Go `net/http`, OkHttp,
curl, GitLab's outbound-request settings, Gitea's `ALLOWED_HOST_LIST` consolidation, OpenProject's
single SSRF allowlist, Cilium `toFQDNs`, Deno `--allow-net`.) `EgressAllowlist`,
`GuardedHttpCheckExchange`, `JdkHttpCheckExchange` and `EgressRefusedException` move from
`:adapters`'s `adapter.check.http` to a leaf module (task 3.0, done, JDK-only then; it takes `:domain`
as its one internal edge with this decision, below). The 2026-11-08 wording —
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
- **Law medium** — the `.gnomish/` tree at the law commit: a stage manifest's http target and an
  inline `tracker.github.api-url`. A host it names for an **in-process** call is a
  **request**, admitted only when the operator medium grants it — by an egress-block entry, by a
  `connection:` profile that names the host, or by the vendor default the adapter declares
  (`api.github.com` for the GitHub tracker and built-ins: a GitHub token is for GitHub).
  A requested host nobody grants is a located load error at the line of the request, naming
  the ways to grant it — for a manifest target the `in-process` entry alone, since a profile
  grants only its own connection's calls ("A manifest target is never admitted by a connection";
  task 3.4). A manifest target whose authority carries a `${…}` reference
  (`HttpCheckVariables.erase` grades it as `x` at load, `HttpCheckVariables.java:90-92`) is not
  judged at load: its grant is judged at connect, on the substituted URL, against the
  `in-process` scope, a miss being the same configuration refusal (item 11 of the third D16
  review; *alternative rejected — refuse a reference in the authority at load:* it refuses a
  manifest shape that loads today, against UX2, while the guard judges every hop at connect
  anyway). Existing projects on `https://api.github.com` load unchanged;
  a GHES or Gitea project adds one line to its operator file.
- **A box's law egress is not an in-process request.** `executors.<name>.egress` stays what D10
  of `define-executor-contract` made it (human decision 2026-11-09 on item 1 of the second D16
  review): the law's **grant** to the box guard of a `program` executor's own box, bounded by the
  describe (the preflight refuses a grant the describe does not carry), admitted beside
  `factory.egress.sandbox` (`ExecutorEgress.java:10-18`; main spec `pipeline-config`, "Named
  executor declarations"). It reaches no in-process call and no operator credential; it is parsed
  by the one grammar below and limited to the `sandbox` subset. *Alternative rejected — make it a
  request the operator must grant:* it breaks every shipped program declaration at load with no
  security gain this change needs (the box carries only the executor's own declared credentials,
  D6), and it would MODIFY an implemented capability of another change.

*The medium travels as a value, minted where it is known.* The medium of a URL is known in one
place only: where the configuration is read. `TrackerConfigMapper` merges a `connection:` profile
into the tracker subsection on purpose, so "the adapter never learns that a profile was
involved" (`TrackerConfigMapper.java:48-51,73`), and the adapter builds its client from a bare
`String` (`GithubTrackerAdapterFactorySupport.java:66-67`). So the decision names three things
(item 2 of the second D16 review, human decision 2026-11-09):

- **The values** are pure records in `:domain` (package `domain.egress`), so every module that
  names them already reaches them: `EgressMedium { OPERATOR, LAW }`; `ConnectionEndpoint(URI url,
  EgressMedium medium, List<String> egress)` (the connection's own operator grants — a profile's
  `egress` — which must reach the judge with the URL; task 3.0c); `AdmittedEndpoint` — the endpoint after admission, carrying its URL and
  its admitted set (connection host, connection `egress`, describe `egress`, `in-process`); and
  the refusal type of the guard (below). `:httpegress` takes `:domain` as its one internal edge
  (it stops being dependency-free; every consumer of the leaf — `:githubapi`, `:sandbox:core`,
  `:adapters` — already reaches `:domain`, so no module gains a reach), and `gnomish-plugin-api`
  takes no edge to the leaf. *Refinement of 2026-11-09 (item 1 of the third D16 review) over the
  first wording, "the api takes `:httpegress` as an `api` dependency":* the carrier the review
  found missing is `TrackerConfig`, which lives in `:domain`; putting the values beside it keeps
  the published api's edges as they are. *Alternative rejected — `:domain` takes `:httpegress`:*
  the domain would reach the exchange's `HttpClient` code, and every module of the build would
  list the leaf as a transitive reach.
- **Who mints the medium:** `ConnectionProfiles.endpoint(Map<String, Object> subsection, String
  key, EgressMedium inlineMedium)`, a new method beside `resolve` (`ConnectionProfiles.java:86-98`
  returns a merged map and knows no key): a value taken from the referenced profile is
  `OPERATOR`, an inline value takes the caller's `inlineMedium` — `LAW` for the tracker
  subsection, `OPERATOR` for a `factory.check.<name>` subsection. Its two callers are the
  tracker mapper and **one** connection reader of the built-ins in `adapters/scm`,
  `ScmConnectionReader`, which `github-actions` and `scm-status` both use (item 2 of the third
  review). `new ConnectionEndpoint(` appears in `ConnectionProfiles` only, and the literal
  `EgressMedium.OPERATOR` in `ConnectionProfiles` and `ScmConnectionReader` only.
- **How an adapter names its endpoint:** a new `TrackerAdapterFactory` member,
  `endpoint()`, returning `Optional<EndpointDeclaration>` with `EndpointDeclaration(String key,
  String vendorDefaultHost)` (GitHub: `api-url`, `api.github.com`; the in-memory tracker: empty,
  it makes no HTTP call). The built-ins declare the same through their describe. This is
  `gnomish-plugin-api` surface, covered by the api's one MINOR bump (0.14.0 → 0.15.0; migration step 3).
- **Who judges at load, and how the result travels:** core, over the typed value —
  `EgressAdmission.admit(ConnectionEndpoint, EndpointDeclaration, OperatorEgress)` in
  `:httpegress`. For the tracker, `PipelineLoader` hands `PipelineMapper.map` and
  `TrackerConfigMapper.map(TrackerDto, ConnectionProfiles, List<ConfigError>)`
  (`TrackerConfigMapper.java:53-54`) two more inputs, keyed by tracker type as the tracker
  validators already are: the adapters' `EndpointDeclaration`s and the operator's
  `OperatorEgress`. The mapper admits the endpoint and stores it as a new component of
  `TrackerConfig`, `Optional<AdmittedEndpoint> endpoint` (empty when the adapter declares none) —
  the one carrier, so every factory member that reads the config (`create`, `probeRead`,
  `refuseForeignRef`, `GithubTrackerAdapterFactory.java:177,212`) has it with no signature of its
  own changed. For the built-ins, `ScmConnectionReader` admits at run assembly — once per run,
  after the describe preflight, in `take`/`serve` after the claim (D18 as amended, task 3.6) —
  the connections of the built-ins the law's checks name; a refusal is a located `ConfigError`
  naming the check, the executor and the key, which parks the task as a describe refusal does
  (`take`/`serve`) or is the located error exit (`run`), never a daemon condition (D18, "Where a
  refusal at run assembly goes"); a built-in the law never names is not built. A refusal
  at load is the located load error above. `GithubHttpClient` takes the `AdmittedEndpoint` and
  no `String` URL, so no consumer can reach the core with an unjudged URL.

*One grammar, one owner.* `EgressEntries` (`sandbox/core`, `isEntry(String)`,
`normalized(String)`) is already the one grammar for the box guard, the law's
`executors.<name>.egress` and the describe preflight; `EgressAllowlist` in the leaf carries a
second copy of the same grammar (`EgressAllowlist.java:115-121`, `*.` wildcard and exact host) —
an undeclared pair (D8). `EgressEntries` moves into `:httpegress` (`:sandbox:core`
takes the leaf as an `api` dependency; both already reach `:domain`) and every list below is parsed by it. The grammar, stated
once: an exact host name; `*.suffix`, matching any depth of subdomain and **never the apex**
(Cilium and Gitea semantics, the behaviour both copies already have); an IP literal; a CIDR
block; and the address-class words `loopback` and `private` (loopback; RFC 1918 and ULA). A
class word grants every host resolving only into that class and waives its block **for the
admitted set it is listed in, and no other**: `private` in a connection's `egress` waives the
block for that connection's calls only, while `private` in `in-process` waives it for every
in-process call, manifest targets included (item 7 of the second D16 review; the guard already
builds one admitted set per consumer). The cloud-metadata addresses are liftable by nothing
(GitLab's rule). `host:port` is not in the grammar (NG8: the box guard admits by host; pickup
when a consumer needs a port).

*The `sandbox` scope takes the subset its guard can apply.* The box guard's mitmproxy addon
matches names only — an exact host or a `*.` suffix (`EgressGuardConfig.java:60-66`, Python
inside the Java constant); it resolves nothing and knows no CIDR. So `factory.egress.sandbox`
and the law's `executors.<name>.egress` accept the subset exact host, IP literal and `*.suffix`;
a CIDR block or a class word there is a located load error naming the scope (item 10 of the
second D16 review). This narrows nothing that works today, since the current grammar has no
CIDR. The addon's matcher is a second implementation of the subset's matching across a
classpath barrier (Python in the box, Java in the factory): a **declared pair** with
`EgressEntries` (`manual-sync-pairs.md`, both ends marked, a row in the no-shared-classpath
table), and the one exemption of `EgressGrammarOwnerSpec`, with that reason.
*Alternative rejected — extend the addon to CIDR and class words:* the addon would have to
resolve names inside the box guard, a second resolver beside the factory's, for a need no
operator has raised; pickup condition: the first sandbox consumer that must reach a host by
address class.

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
the file, the line and the new key — a new removed-key table in `ConfigViolations`, keyed by
the old key and naming its replacement scope (no such table exists today: the earlier removed key
`factory.agent-cli-env-passthrough` is reported as an unknown key with no replacement, and keeps
that message);
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
host, and the **static class** of a host — the class of an IP literal, and loopback for the
name `localhost` and names under `.localhost` (RFC 6761 §6.3 reserves them for loopback); no
other name is classified at load. So a class word grants at load only a host whose class is
static: `http://localhost:8080` granted by `loopback` is admitted at load. A law-medium host
whose only possible grant is a class word and whose class is not static (`gitea.internal`
against `private`) is admitted at load **conditionally**: the grant is judged at connect, on the
resolved addresses, and a miss there is the same configuration refusal naming the same key, not
an outage (item 6 of the second D16 review, human decision 2026-11-09; the cost, named in UX4:
this one class of configuration error surfaces at the first call rather than at load). A literal
blocked address in a connection is a located load error with no lookup, unless its class word
is in the connection's admitted set; a cloud-metadata literal is an error always.
*The scheme rule:* an operator-medium connection may be `http` or `https` (the operator chose
the transport for a host they run); a law-medium connection is `https` only, unless its host is
statically loopback and granted by `loopback` — a committer cannot downgrade the operator's
token to cleartext on a granted host, and the specs' `http://localhost` fixtures load; a manifest
target is `https` only (main spec, "Plain http is refused"). *Alternative rejected — a class
word never grants at load:* it moves every fixture's admission to connect for no gain, since
`localhost` and literals need no lookup to classify. *Alternative rejected — resolve at load
when the only grant is a class word:* it reintroduces the network into load, which this
decision removes for ADR 0019, decision 5. Resolution happens at connect: the guard resolves the
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
timeout (`GithubHttpClient.java:73`, connect only). So the response carries the headers (the
`http` built-in's findings keep rendering status and body only); the body bound, the request
timeout and the key a refusal names are parameters of the consumer's exchange, and a consumer may
read a body as a bounded tail. The numbers, so that no implementer invents them: the `http`
built-in keeps 1 MiB and 30 s; the GitHub core declares **16 MiB** (a thread of a hundred
65 k-character comments in UTF-8 fits with room) and **60 s** per request, and reads a job log
as a tail bounded by its own 16 MiB. The tracker declares no `egress` of its own: GitHub answers
a transferred repository with a same-host `301`, and nothing else redirects it.

*The timeout bounds the whole exchange, body included* (item 8 of the second D16 review, human
decision 2026-11-09). The JDK's request timeout ends the wait for the response headers only:
the first D16 review measured a body read through `BodyHandlers.ofString` still blocked at 8 s
against a 2 s timeout on JDK 25.0.3 (macOS), and `JdkHttpCheckExchange.send` reads the body with
`readNBytes` after `send` returns, on the calling thread. So the leaf sends with `sendAsync` and
a body subscriber of its own that counts the bytes it is handed and cancels its subscription
past the consumer's bound (or keeps the last bytes, for a tail read); the returned future is
bounded by the consumer's timeout measured from the send, and on expiry the leaf calls `cancel(true)` on
the future `sendAsync` returned and its subscriber cancels its subscription — `orTimeout` alone
only completes the caller's future, while the JDK forwards nothing but `cancel` to the exchange
(`java/net/http/HttpClient.java:681-688`, `jdk/internal/net/http/common/MinimalFuture.java:104-109`
on JDK 25.0.3; item 5 of the third D16 review), so a timed-out call would leak its connection. The body arrives on the client's own selector thread, so no thread of the factory
parks in a read (`lock-scope.md`, "The read side is no exception"), and the timeout bounds
headers and body together. The task that builds it measures the stalled-body case on macOS and
on Linux (`scripts/linux-specs.sh`), asserts that the server side sees the connection closed
within the timeout plus one bound, and records the JDK build and OS of each
(`design-decisions.md`, "A platform fact is measured on every platform CI runs").
*Alternative rejected — keep the blocking read and name the window:* a platform that sends
headers and stalls holds a serve slot or a tracker call with no bound on the shipped JDK, the
silent stall the bound exists to prevent.

*A hop keeps the request's meaning, and credentials stay on their host.* The rule by status
is RFC 9110 §15.4.2–15.4.4 as the JDK implements it — `jdk/internal/net/http/RedirectFilter.java:89-96`
on JDK 25.0.3+9 (identical on 21.0.10): `case 301, 302 -> orig.equals("POST") ? "GET" : orig;
case 303 -> "GET"; case 307, 308 -> orig;`. So a `301` or `302` hop turns a `POST` into a `GET`
with no body and no `Content-*` header and keeps **every other method** — a tracker `DELETE`
(`GithubClaimLease`, `GithubLabelOps`, `GithubStaleClaimRemoval`) or `PATCH` (`GithubHeartbeat`,
`GithubCommentUpsert`) stays what it was, and a `HEAD` stays bodiless; a `303` hop is a `GET` with
no body; a `307` or `308` hop keeps method and body (item 5 of the second D16 review: the earlier
wording, "301/302/303 → GET", contradicted its own cited precedent and would have re-sent a
release or a heartbeat as a `GET` and read the `2xx` as success — a silent loss of a duty;
OkHttp's `redirectsToGet`, which converts every method but `PROPFIND`, is the outlier and is not
followed); a hop from `https` to `http` is refused (the JDK's `Redirect.NORMAL`,
Go, requests); hops are bounded by `MAX_REDIRECTS = 3`, and a hop onto a URL already visited in
the chain is a loop and is refused. A cross-host hop drops the **credential deny-set** — the
JDK's own five names `Authorization`, `Cookie`, `Host`, `Origin`, `Referer`, plus
`Proxy-Authorization` and `WWW-Authenticate` — and keeps every other header (`Accept`,
`User-Agent`, `X-GitHub-Api-Version`): the first amendment's "no header at all" was stricter than
any client and would break a target that negotiates on `Accept`. Same host keeps everything;
"subdomain keeps credentials" (Go's rule) is rejected explicitly — `*.blob.core.windows.net` is
not GitHub. Measured 2026-11-08 on JDK 25.0.3+9 and 27.0.1: `Redirect.NORMAL` already drops the
five across hosts; the leaf keeps the property where a spec can assert it.

*A refusal is a configuration fault, not an outage — and only a grant miss is a refusal.*
Premises as verified 2026-11-09: `EgressRefusedException extends IOException`
(`EgressRefusedException.java:19`); `GithubHttpClient.doSend` wraps every `IOException`
(`GithubHttpClient.java:148`) and `GithubRetryConfig` retries up to `MAX_ATTEMPTS`
(`GithubRetryConfig.java:54-57`), so a refusal would leave as `GithubHttpException`, the outage
path; the leaf's `EgressRefusal.Reason` has six values, and three of them are not about a grant —
an unknown host is `UNRESOLVABLE` (`EgressAllowlist.java:98-100`), a fourth hop is
`REDIRECT_LIMIT` (`GuardedHttpCheckExchange.java:85`), an oversized body `RESPONSE_SIZE`
(`JdkHttpCheckExchange.java:58`) (item 4 of the third D16 review). So the leaf's outcomes are
sorted into three classes, each with its own type:

- **Refusal** — `SCHEME`, `ADDRESS_CLASS`, `NOT_ALLOWLISTED`: what the operator's grants decide.
  It leaves as `EgressRefusedException`, now a `RuntimeException` in `domain.egress` (no longer an
  `IOException`, so no transport retry can wrap it), carrying the reason and the consumer's
  configuration key. Not retried, never an outage.
- **Transport** — `UNRESOLVABLE` leaves the reasons: an unknown host is thrown as the JDK's
  `UnknownHostException`, an `IOException` like a reset or a timeout, and travels each
  consumer's transport path (the tracker's `GithubTransportException extends
  TrackerUnavailableException`, retried; a check's `failed/infrastructure`). The `http`
  built-in's classification of an unknown host moves from the egress refusal to the transport
  failure with it.
- **Exchange limit** — `REDIRECT_LIMIT`, `RESPONSE_SIZE` (the loop refusal included): the
  platform's answer exceeded the consumer's bounds. It leaves as `ExchangeLimitException`, a
  `RuntimeException` of the leaf naming the limit, never "grant the host": a check is
  `failed/infrastructure` naming the limit; a tracker call fails as an unclassified failure (the
  crash arm, with its abort accounting, which is right for a platform anomaly), not retried by
  the GitHub core.

*Where a refusal goes, per layer* (FR18; item 3 of the third D16 review, human decision 2026-11-09,
option A after `/architect` Mode C). The truth of an operator's grant is the same for every task
of the daemon, so by ADR 0019, decision 5, a refusal of a **tracker** call is an operator
precondition of the daemon's level: one new `DaemonConditionKind`, `EGRESS`, one new arm of the
one classifier `OperatorPreconditionRefusals` (`EgressRefusedException` → `EGRESS`), and the
machinery that already serves the four preconditions:

| Layer | Today (verified) | Decided |
|-------|------------------|---------|
| `serve` startup | — | the load-time admission is the startup assessment (ADR 0019 d.1): the daemon exits as a startup failure printing the refusal and its key |
| `serve` feed poll and claim (`FeedCycle.java:99,133`) | `FeedOutageRetry.run` retries every `RuntimeException` (`FeedOutageRetry.java:76-91`) | `FeedOutageRetry` rethrows a typed refusal instead of retrying it; the feed reports it to `DaemonConditions` as `EGRESS`, and a held feed reads nothing from the tracker (ADR 0019 d.3) |
| claimed run, `take` and `serve` (finish, park, comments, labels) | `ClaimedRunFailureDispatch.java:99` → `crashAbort.onCrash`, `INSTANCE_CRASH`, fuse charged | classified by `OperatorPreconditionRefusals.kindOf` → `OperatorPreconditionUnmet(EGRESS)`: claim released plain (best effort — the release may be refused too, logged), no abort marker, backoff or fuse charge; `take`/`run` exit 8; under `serve` the condition opens |
| `take` before its claim | — | the command ends on the same typed result, exit 8, no claim made |
| heartbeat worker (`HeartbeatBeater.java:68`), reaper (`Reaper.java:109`) | log and continue (beat unconfirmed, tick skipped) | unchanged: the designed degradation; the condition opened by the feed or a slot holds new claims, and an unconfirmed claim expires through the lease path |
| a verdict built-in's call (`http`, `github-actions`, `scm-status`) | a refusal is `CannotVerify` (`HttpExternalCheckClient.java:103-115`) | unchanged in kind: `failed/infrastructure` naming the consumer's key, no retry; no condition (NG9) |

The `EGRESS` probe repeats the connect-time judgement of the tracker's `AdmittedEndpoint` —
resolve its host, judge every address against its admitted set — and sends no request (ADR 0019
d.3: a condition's probe is its own check). A refusal met on a **redirect hop** is judged on a
host the probe does not visit, so the probe passes and the condition closes; the next claim meets
the refusal again — at most one claim per free slot per episode, the cost ADR 0019 accepts
("never costs more than one claim per free slot per episode"). *Alternatives rejected —* the
refusal as an outage (`TrackerUnavailableException`): it is retried, holds a slot up to ten
minutes per terminal write and never tells the operator the key; *alternative zero* — no
classification: the feed retries the same refusal forever and every claimed run is an
`INSTANCE_CRASH` charging the abort fuse for an operator's configuration, visible but wrong; the
refusal at the task's level (park the task as an invalid law): rejected by ADR 0019 d.5, every
task is parked one by one for one daemon-level truth.

*What a refused terminal write leaves, and who recovers it* (`crash-consistency.md` item 2): the
terminal outcome is already durable on the task branch, so the frozen state is the existing shape
"terminal outcome recorded, tracker write pending"; its owner is unchanged — the reconcile every
claim of a task with an existing branch begins with (main spec `tracker/tracker-take`) posts the
write from the branch once the condition has closed. No new shape, no new owner.

*Test posture.* Two shapes of spec reach the GitHub client on `http://localhost` or
`http://127.0.0.1` (grep on the task branch, 2026-11-09): 37 test files construct
`new GithubHttpClient(` directly against WireMock, and the take, serve and Gitea end-to-end
fixtures reach it through an inline `api-url` in a fixture's `config.yaml`; only one test file
writes a `project.yaml` beside an `api-url` today. Both shapes go through the admission owner,
never around it:

- a **direct-construction spec** builds its `AdmittedEndpoint` through `EgressAdmission.admit`
  over an operator egress with `loopback` in `in-process` — one helper in `:githubapi`'s test
  fixtures, `GithubTestEndpoints.loopback(URI)`, so the 37 files change one call each. That is
  configuration handed to the owner, not a switch: no constructor overload and no default
  admits a URL unjudged;
- a **configuration fixture** gains an operator file (`project.yaml`) granting `loopback` under
  `factory.egress.in-process`, written by the shared fixture that already writes the law's
  `config.yaml`.

`TakeExitCodeE2eSpec` keeps its expectation (the unreachable tracker fails at the first call,
not at load). A manifest target on `http` stays refused (main spec, "Plain http is refused").

*Current signatures the decision changes:* `HttpCheckExchange.Response(int status, String body,
@Nullable String location)`; `GuardedHttpCheckExchange(HttpCheckExchange delegate, EgressAllowlist
allowlist)`; `EgressAllowlist(List<String> entries, HostResolver resolver)` and
`EgressAllowlist.from(Map<String, Object> subsection)`; `JdkHttpCheckExchange()` with the
constants `REQUEST_TIMEOUT` and `MAX_BODY_BYTES`; `GithubHttpClient(String apiUrl, String token)`
and the package-private `GithubHttpClient(String apiUrl, String token, RetryConfig retryConfig)`
(`GithubHttpClient.java:63,68`), its `public HttpResponse<String> send(HttpRequest.Builder
requestBuilder)` (`:126`); `static boolean GithubRateLimit.isRateLimited(HttpResponse<?> response)`
(`GithubRateLimit.java:22`); the `retryOnResult(response -> response.statusCode() >= 500 …)`
predicate over `HttpResponse<String>` (`GithubRetryConfig.java:57`); `public ConditionalResult
GithubConditionalRequestCache.get(HttpRequest.Builder requestBuilder, String cacheKey)`
(`GithubConditionalRequestCache.java:69`); `TrackerAdapterFactory`'s members `type`, `create`,
`expandRef`, `refuseForeignRef`, `probeRead`, `subsectionValidator`, `credentialEnvVars`,
`configuredDesignatorKinds` (no endpoint member); `TrackerConfigMapper`'s
`profiles.resolve(PipelineMapper.castSubsection(...))` (`TrackerConfigMapper.java:73`). *After
this decision* the client's constructors are `GithubHttpClient(AdmittedEndpoint endpoint, String
token)` and the package-private `GithubHttpClient(AdmittedEndpoint endpoint, String token,
RetryConfig retryConfig)`; `send` keeps its signature and returns the guarded exchange's
response adapted to `HttpResponse<String>`, so `isRateLimited`, the retry predicate and the cache
keep theirs; the retry still retries only `IOException`, so `EgressRefusedException` and
`ExchangeLimitException`, both `RuntimeException`s, pass through it unwrapped;
`ConnectionProfiles.resolve(Map<String, Object>)` returning the merged map
(`ConnectionProfiles.java:86-98`); `TrackerConfig(String type, int abortThreshold, Duration
heartbeatInterval, int heartbeatTtlMultiplier, int wipLimit, Map<String, Object> subsection)`
(`TrackerConfig.java:52-58`), which gains the `Optional<AdmittedEndpoint> endpoint` component;
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
matching and the class words are spelled in `EgressEntries` only — `BlockedAddressClass`
(`BlockedAddressClass.java:39`, which labels a refusal `"loopback"`) takes its labels from
`EgressEntries` rather than spelling them (one exemption,
`EgressGuardConfig.java`, the declared pair's other end); `EgressMediumMintingSpec` in
`:bootstrap` asserts that `new ConnectionEndpoint(` appears in production code only in
`ConnectionProfiles`, `EgressMedium.OPERATOR` only there and in `ScmConnectionReader`, and
`new AdmittedEndpoint(` only in `EgressAdmission` (allowlist by file, asserted reached), and `GithubHttpClient`'s parameter type does the rest (no `String` URL); the identity spec
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

**D18 — One registry for stage and check rounds; entries bound at resolution (as amended
2026-11-10).** (FR1, FR13.) *Original text, superseded by the amendment below wherever the two
differ (the registry's value, and "at startup"):* As
built by `add-command-executor`, `ExecutorRegistry` holds `StageExecutor` (a round wrapper) and
takes the `command` factory as a typed parameter. A verdict executor is not a stage round, so
the registry's value becomes the contract's `Executor` for every name — law-declared programs,
`command`, and the five verdict built-ins, handed in by `ExecutorAdapterSelector` as typed
parameters of core-side modules (`:adapters:scm`, `:adapters` (`adapter.check`), `:adapters:agent`) — and stage
dispatch wraps the resolved `Executor` into its `StageExecutor` at resolution. The refusal of a
law declaration under a built-in's name covers all six built-in names.

*D18 as amended (2026-11-10, blocker of task 3.6; `/architect` Mode C) — the registry holds an
entry bound at resolution, never a ready `Executor`.* The premise "built at startup from the law
and the built-ins" is refuted by the code of tasks 3.1–3.5 and of `add-command-executor`: every
built-in takes the check's declaration and the domain `Workspace` in its constructor
(`FilesExistExecutor.java:58`, `ShellExecutor.java:66`, `HttpExecutor.java:56`,
`GithubActionsExecutor.java:76`, `ScmStatusExecutor.java:66`), the two SCM built-ins read the
attempt commit from that handle (`AttemptCommitWorkspace`, `GithubActionsExecutor.java:104`,
`ScmStatusExecutor.java:94`), the wire `Workspace` is a path only and `ExecutorRequest` names no
attempt commit, and `command` and the law's programs bind to a `TaskExecutionEnvironment` that
exists only once a round is open (`CommandExecutorFactory.java:78`,
`ProgramExecutorFactory.java:52`). `ExecutorRegistry`'s own javadoc (`ExecutorRegistry.java:37-40`,
D8 of `define-executor-contract` as built, D6 of `add-command-executor`) records the same refusal,
which the first text of D18 overrode without naming it. The literature and comparable systems
agree: a registry holds a constructor (Kubernetes scheduler `PluginFactory`, Jenkins
`Descriptor` → `Step` → `StepExecution`), a per-call context travels as an argument, and runtime
data never enters a constructor when the graph is composed (van Deursen, "runtime data should flow through the
method calls of already-constructed object graphs"); the one legitimate factory argument is the
value unavailable when the graph is composed (here: at run assembly).

*Decision.* What D18 keeps: one registry for stage and check rounds, `command` no longer a typed
parameter of the dispatch, the built-ins handed in by `ExecutorAdapterSelector`, the refusal over
six names. What changes is the value: an **entry** per name, built at **run assembly** from what
run assembly has — once per run, after the law is bound and described, on the path
`RunAssembler.assemble(ManualRunAssembly, RunOrder, TaskContext, TaskState, AttemptPersistence,
List<String>, DescribedLaw)` → `ExecutorAdapterSelector.stageRounds(ManualRunAssembly,
ChildEnvAllowlist, PipelineLaw, DescribedLaw, PipelineDefinition)` → `ExecutorRegistry.of(…)`
(`RunAssembler.java:96`, `ExecutorAdapterSelector.java:88-89`; "the registry of one run",
`ExecutorRegistry.java:53`), which in `take` and `serve` runs after the claim
(`TakeEngineExecution.java:137-146`, `TakeContainerEngineExecution.java:132-139`) — the executor's name and `describe`, and its long-lived collaborators (the git runner, the
`http` client, the SCM reader over its admitted connection) — with the executor bound at
resolution, by the one value that does not exist earlier: `executor(TaskExecutionEnvironment)`
for a stage round (the existing `RoundExecutorFactory`, D2 of `add-command-executor`, kept as
is) and `executor(VerifyCheck.Executor, Workspace)` for a check round. The wrapper the D14
paragraph names ("the application-side wrapper that D18 builds") is built at resolution over the
bound executor and is the `StageExecutor` the engine's port sees; a program entry binds for a
check through the fresh box of task 5.3. Nothing bound is cached in the registry: the registry
stays immutable after construction (`process-invariants.md`), a stage round's bound executor lives
for its round and a check's for one request (amended 2026-11-13, see D14: nothing bound outlives
its request; what a program's runs need across requests — the table of its pending runs, each key
with the `before` view its `start` took — is a long-lived collaborator of the program's entry,
one per run and shared by every binding of it, and across instances the key and the round's
record are the handle, never a held instance), and resolving with no open round is impossible by construction, since the binding
takes the environment or the working copy as its argument. The domain sees none of this: it
reaches a check's executor only through `StageExecutor.execute(Request)` (the D14 paragraph;
`domain/build.gradle` reaches `:operatorevent` and `:untrustedtext` only, so neither
`VerifyOrchestrator` nor `PendingPoll` can name the contract or the registry), and the request
of a check round names the check as its subject in role `verdict`, as a stage round names its
stage. Construction follows the law (the Kubernetes rule "instantiate only the plugins the
profile enables"): the entry of a built-in the law's checks never name is not built, so an
operator without `factory.check.github-actions` or `factory.check.scm-status` runs every task
whose law names neither (`ScmConnectionReader.read` throws without `api-url`,
`ScmConnectionReader.java:62-63`).

*Where a refusal at run assembly goes* (human decision 2026-11-10, item 1 of the scoped D18
review). Which built-ins a run needs is decided by the law, so by ADR 0019, decision 5 (the level
follows the owner of the truth), a connection the law's check needs and the operator did not
supply, or the egress guard refused, is the **task's** precondition, never the daemon's: the
refusal is a located `ConfigError(String file, String where, String message)` naming the check
(its manifest and `verify[<i>]`), the executor and the operator key (`factory.check.<name>.<key>`),
and it takes the describe refusal's path, `LawDescription.Refused(List<ConfigError> errors)`. So
in `take` and `serve` the task is parked exactly as a describe refusal parks it
(`TaskTierLaw.parkUndescribed(LawBinding, List<ConfigError>, TakeOrder, TaskState)`, called at
`TakeEngineExecution.java:140` and `TakeContainerEngineExecution.java:136`): `park(INFRA)` with
the located report, no attempt burned, no abort accounting, no `DaemonCondition` (none is
added); in `run` it is the located error exit of `ManualRunDescribe.described`
(`PipelineLoadFailedException`, exit as an invalid law). The refusal therefore never leaves
`assemble` as an exception: an `EgressRefusedException` escaping there would reach
`ClaimedRunFailureDispatch.onFailure` (`ClaimedRunFailureDispatch.java:89`), where
`OperatorPreconditionRefusals.kindOf` opens the daemon-level `EGRESS` condition, and an
`IllegalArgumentException` would reach `crashAbort.onCrash` (`:98`), the crash arm with its fuse —
both the wrong level. The connections are read and admitted before the describe branch decides
(where exactly is the implementer's: inside `RunDescribe.describe`, the one owner of a run's
described law, or one step beside it), and the admitted connections travel to the registry
assembly with the described law. This one located error is also the error the
`verification/verdict-executors` scenario "Unconnected credentialed built-in is a located error"
promises: in a run the law is loaded and described per task, so "loading reports a located
error naming the check and the executor" is this refusal; no second, earlier validator owns the
same case.
*A `program` check outside a fresh box* (review r2 of the D14 amendment, 2026-11-13, item 4;
human decision the same day). The loader accepts `executor: <program>` on a check, and a check
without credentials verifies `same-box` by default (`sandbox/execution-environment`, "Defaults
SHALL be … same-box verification"); host mode has no fresh box. Such a check has no binding in
this change (NG14), and resolving it would reach `RegistryEntry.executor`'s
`IllegalStateException("executor '…' runs no check round in this run")` (`RegistryEntry.java:94-97`),
which `ExecutorRegistry.resolve` documents as a wiring defect and the round records as an internal
failure that tells the operator nothing. So the same path refuses it: a law whose check names a
`program` executor that would run outside a fresh box (host mode, or the `same-box` placement) is a
located `ConfigError` naming the check (its manifest and `verify[<i>]`) and the executor, taking
`LawDescription.Refused` exactly as the unconnected built-in above — parked in `take`/`serve`, the
located exit in `run`. Task 5.3 builds it with the program check binding, which is the owner that
knows which placements bind; the `IllegalStateException` stays for a wiring defect no law can reach.
The first change that binds a `program` check outside the fresh box (NG14's pickup) lifts the
refusal for its medium. *Alternative rejected — leave the crash until the pickup:* a law that
loads cleanly and fails every verification with an internal error, with nothing naming the
`verify-in: fresh-box` and container mode the check needs.

*The verdict answer crosses the engine port* (item 5 of the scoped D18 review). Every check round
reaches its executor through `StageExecutor.execute(Request)`, whose request today is
`record Request(TaskContext context, StageDefinition stage, Workspace workspace, int attempt,
List<CheckResult> feedback)` (`StageExecutor.java:62-63`) and whose result's answer is
`sealed interface Answer permits DecisionRequest` (`ExecutionResult.java:145`); a pending key is
already expressible (`record Pending(String key) implements Status`, `:132`). The result side
therefore gains a verdict variant: `ExecutionResult.Answer` permits a new
`record VerdictReply(VerdictAnswer answer) implements Answer` beside `DecisionRequest`;
`VerifyOrchestrator` hands it to `VerdictDerivation.derive(VerdictAnswer answer, Threshold
threshold)` (`VerdictDerivation.java:41`); the check wrapper maps a contract result to it through
`ProgramResultMapping`, the one contract → domain mapping (D9 row; its reading of one SARIF result
is extracted from `ShellResult.asDomain`, see 4.2f), and a
stage round never takes it: `ProgramResultMapping` keeps refusing an answer of any other kind
for a transform round (`ProgramResultMapping.java:89-98`), and the stage engine reads only a
`DecisionRequest`. The request side gains the check as subject in role `verdict` and the
`@Nullable PendingTurn pending` of D14. The declared pair `ExecutionResult` ↔ the contract's
`Status`/`FailureClass` covers statuses and classes only and is not touched. Task 4.0b carries
the port change, before `PendingPoll` (4.2) is built on it.
*Amended 2026-11-10 (D19):* the pending status widens on the port, so that `PendingPoll` reads
facts and never a text: `record Pending(String key)` (`ExecutionResult.java:132`) becomes
`record Pending(String key, @Nullable Duration pollAfter, PendingState state, @Nullable
UntrustedText reason, @Nullable UntrustedText details)`, with `enum PendingState { RUNNING,
UNREPORTED, UNOBSERVED }` in `domain/engine` (an absent wire field maps to `RUNNING`). The
declared pair above therefore gains `PendingState` (D8). The one contract → domain mapping (the
D9 row), `ProgramResultMapping.toEngine(String executor, AttemptKey key, ExecutorResult result,
List<Denial> environmentDenials)` (`ProgramResultMapping.java:55`), which today keeps only the key
(`:66-73`), fills it — once, for a check round and a transform round alike — so
`extract-agent-round-runner` finds the facts on the port when it adds its `Round` subject.
*Amended 2026-11-12 (D20):* the key on the port is typed: `ExecutionResult.Pending(PendingKey key,
@Nullable Duration pollAfter, PendingState state, @Nullable UntrustedText reason, @Nullable
UntrustedText details)` (today `String key`, `ExecutionResult.java:147-148`) and `PendingTurn
(PendingKey key, Origin origin)`, `PendingKey` being the `:pendingkey` leaf's type; the mapping
passes the contract result's key through (today it unwraps it, `ProgramResultMapping.java:183-184`).
The declared pair `ExecutionResult` ↔ `Status`/`FailureClass`/`PendingState` is not touched: the
key is one type on both sides, not a pair.
*Amended 2026-11-12 (D19 review):* a failed status carries its details on the port too:
`record Failed(FailureClass failureClass, UntrustedText reason, List<Finding> findings)`
(`ExecutionResult.java:107`) becomes `record Failed(FailureClass failureClass, UntrustedText
reason, List<Finding> findings, @Nullable UntrustedText details)`; `ProgramResultMapping` fills
it from the result's `details` and, for `failed/quality`, also writes it as the details of the
one finding it builds (`ProgramResultMapping.java:113-114`, today `new Finding(reason.raw(), null,
null)`), so a timed-out command's output tail stays in its finding; `VerifyOrchestrator` (4.2f)
carries a `failed/infrastructure` result's `details` into `CannotVerify.details`. The record's
one deconstruction pattern, `ExecutorOutcomeClassifier.java:61`, and its constructions (named in
task 4.0b) change with it.

*The six built-in names have one owner* (item 9 of the scoped D18 review). Today the names are
spelled apart: `files-exist`, `shell`, `github-actions`, `scm-status` as constants of
`VerifyCheck.Executor` (`VerifyCheck.java:70-79`), `http` as `HttpExecutor.NAME`
(`HttpExecutor.java:44`), `command` as `CommandExecutorFactory.NAME`
(`CommandExecutorFactory.java:33`); the registry refuses only `command`
(`ExecutorRegistry.java:75-78`, an `IllegalStateException` per run with no location), and the
load-time "unknown provider" and "defaulted github" errors are judged by
`ExternalCheckSeamValidator` keyed by the check SPI (`ExternalCheckSeamValidator.java:44-46`),
which 5.2 and 6.1 remove. Decision: one set of the six names in `:domain` (`domain.pipeline`),
the one spelling of the set, read by the load validation of the law
(`ExecutorDeclarations`, with the check mapping for the unknown-executor case: a law declaration under any of the six is a located error; a check naming
an executor outside the six ∪ the law's declarations is a located error listing exactly that
union, so the `scm-status`/`github-actions` hint survives whether or not the law names them) and
by the registry's refusal, kept as a safety net behind the load error. *Alternative rejected —
the registry's names as the list:* by this decision the registry holds no entry for a built-in
the law never names, so the hint would vanish exactly when the author needs it.

*The conformance kit drives a built-in through its entry* (items 3 and 4 of the scoped D18
review). A built-in reads the domain `Workspace` it was bound to and never the request's path
(`FilesExistExecutor.java:86`, `HttpExecutor.java:89`, `GithubActionsExecutor.java:104`,
`ScmStatusExecutor.java:94`), so `BuiltInCandidate`'s "one executor instance serves every case"
(`BuiltInCandidate.java:24-25`) judges a working copy the kit does not name. The in-process
candidate takes the entry's describe and a per-case binding — a function from the case's working
copy to the bound executor (for an SCM built-in, an attempt-commit workspace) — rather than a
ready `Executor`, since `command` binds through a `TaskExecutionEnvironment` and the SCM
built-ins through an attempt-commit workspace. And it carries the settings of its ordinary cases:
the kit's ordinary cases send `settingsTree(Map.of())` (`ConformanceKit.java:123-125`) and the
settings-schema case judges only the pending settings (`:75`), while `files-exist` requires
`files` (`FilesExistSettings.java:37`) and `http` requires `url` (`HttpSettings.java:48`); so an
in-process candidate supplies the settings its ordinary cases send, the settings-schema case
judges those same settings, and a program keeps `{}` with the CLI unchanged (the ADDED
`executor/conformance-kit` requirement "An in-process candidate carries the settings of its
ordinary cases").

*Alternative rejected —
D18 literal, the per-check data on the wire (the attempt commit as a request field):* the wire
is the contract shared with external programs, and a field only in-process implementers can
honour is a leaky contract that every reader will come to depend on; an external verdict program
stands in its working copy at the attempt commit and reads `HEAD` itself, and the check's
declaration (`checkId`, `threshold`, `command`) is configuration known at run assembly, not runtime
data, so it belongs in the built-in's construction, not in every request. What makes `HEAD`
the commit is the fresh box alone, so the field is reopened by the change that binds a `program`
check outside it (proposal NG14), and an echo of the judged commit in the result is deferred as
proposal NG15 (`/architect` Mode C, 2026-11-14: the CI systems that pass both a value and a
checkout record their defects where the two disagree, which one source cannot). *Alternative rejected —
one shared instance reading the "current check" from a mutable cell:* an ambient context, a
silently wrong result under two concurrent checks, and a breach of immutability after
construction. ADR 0020 records the principle in the amendment task 6.4 carries.

*D18 as amended 2026-11-12 (sequencing; human decision on blocker B of task 4.2):* a check round
reaches its executor only through `CheckDispatch`, never through the stage chain. Today the stage
chain ignores the request's check: `ExecutorDispatch.execute` (`ExecutorDispatch.java:57-66`)
switches on the stage's executor type, so a check request would re-run the stage's agent, and
`ResumeVerificationStageExecutor.execute` (`ResumeVerificationStageExecutor.java:64`) matches a
pending verification by stage and round alone. `StageRounds.checks` is built and never read
(`ExecutorAdapterSelector.java:101`, `:113`). The route — `request.check() != null` →
`CheckDispatch.bind(check, workspace).execute(request)` — sits at the run's assembly, outside
both `ResumeVerificationStageExecutor` and `ExecutorDispatch`, so the engine still sees one
`StageExecutor` (G1). It lands in task 4.2f (formerly 5.1), together with the pin decorator over `Executor` (D17)
and the switch of `VerifyOrchestrator` from `ExternalPolling` to `PendingPoll`. Task 4.2 builds
`PendingPoll` in `domain/engine` on fake ports and changes no production wiring, and the kill-point
tasks that need the real route (4.3, 4.3a, 4.6) run after it. Order (as amended by the D21/D22
review of 2026-11-12: the host snapshot and the run's round cell come before the pending commit
that stands on them): 4.1r → 4.2 → 4.2c → 4.2a → 4.2b → 4.2d → 4.2e (formerly 5.1a) → 4.2f
(formerly 5.1) → 4.3 → 4.3a → 4.6 → 4.4; 4.5 and 4.7 may run anywhere in that order.
*Alternative rejected — B1, route `check != null` to `CheckDispatch` in task 4.2:* every tree
between 4.2 and 4.2f would run a `github-actions` check with no pin of its workflow file. That is a
security regression with a green build, and it would sit under the kill-point specs of 4.3, 4.3a
and 4.6. The route would also have to be placed outside `ResumeVerificationStageExecutor`, which
is 4.2f's work anyway. *Alternative rejected — B2, an adapter over the legacy
`ExternalCheckClient` with a synthetic key:* a key no executor issued, which defeats the adoption
rule of D14.

**D19 — A pending result carries typed engine facts; its texts stay texts; details travel apart.**
(FR2, FR4, FR5, FR6, FR9, NFR-R2, UX1; human decision 2026-11-10, option A — a `pending` result
carries typed fields rather than the engine parsing its free-text `reason` — with the four
follow-up choices of the same day's decision review: a result-level `details` and SARIF
`properties.details`; the `:githubapi` retry fix; `unobserved` resolving as cannot-verify;
the `github-actions` "no run" case deferred, NG11.)

*What the engine decides on.* Of a `pending` answer the engine makes two decisions, and only
these: when to poll the key again, and how to classify a timeout. Each gets one typed field; the
names of what is awaited are shown, never decided on, so they stay in `reason`.

- `pollAfterMillis` — an optional integer from 0 to 86 400 000, present only with `pending`
  (on `start` or on `poll`). `PendingPoll` polls the key no sooner than `max(interval,
  pollAfterMillis)` after reading the answer; the hint is a lower bound, never a faster poll, and
  never extends the check's timeout: a hint that reaches past the deadline ends the wait at the
  deadline with no further poll, and the timeout is resolved from that answer. Absent, the
  interval rules, as before. The hint is relative, never an instant: the executor's clock is not
  the factory's (a box, a remote, a second instance on resume), and a relative value is what HTTP
  `Retry-After`, gRPC `RetryInfo.retry_delay` and Kubernetes `RequeueAfter` carry. An executor
  that reads an absolute reset converts it by the server's `Date`. It is not persisted (D7 as
  amended).
- `pendingState` — an optional token of the closed set `running` (every awaited subject seen,
  one still running), `unreported` (an awaited subject never appeared on the polled commit —
  FR9's report on another SHA included), `unobserved` (the source could not be read this time — a
  rate limit). At the timeout `PendingPoll` reads the **last** pending answer: `unreported` or
  `unobserved` resolve as cannot-verify whatever the declared timeout class, since no verdict was
  obtained (the infrastructure failure of `stage-description.md`, which burns no attempt);
  `running` or no field keeps the declared class (`ExternalPolling.java:132-145`, today's only
  rule). Unknown-token arm: read as an absent field. `unobserved` covering the rate limit is the
  human's choice of 2026-11-10: today a rate limit at the deadline burns an attempt as quality.
- The texts: the timeout's finding (quality class) or cannot-verify carries as its details the
  `reason` of the last pending answer whose state is not `unobserved`; when the last answer was
  `unobserved`, that answer's `reason` follows it (amended 2026-11-12, D19 review: an `unobserved`
  reason names the source that could not be read — `scm-status`'s rate-limit sentence,
  `ScmStatusResults.java:64-69` — not what is awaited, and the rule "a hint past the deadline ends
  the wait" makes a rate limit the last answer whenever the reset lies beyond the timeout). When
  no answer of the wait was other than `unobserved`, the details are the last `reason` alone.
  `PendingPoll` keeps the two texts in memory for the wait; neither is persisted (D7 as amended).
  `scm-status` names in its `unreported`/`running` `reason` every expected context still absent
  or running (it already does, `ScmStatusResults.java:44-56`), which is how UX1 is met.
- Subject: the wait hint is a rule of the pending answer, whatever its subject. The timeout
  classification by `pendingState` is defined here for the check subject only (a check's declared
  timeout class); for a pending `transform` round it is `extract-agent-round-runner`'s to decide,
  when that change adds the `Round` subject to `PendingPoll` (pickup condition: that addition).
  `ProgramResultMapping` fills both fields for either role, so the facts are on the port then.
- Shape errors fail closed, as a key does (`ResultReading.java:47-53`): either field on a status
  other than `pending`, a `pollAfterMillis` outside its range or not an integer, reads as
  `failed/infrastructure` naming the field.

*Details travel apart from the message* (human decision 2026-11-10). The contract already carries
`details` on a request's feedback finding (`executorcontract/Finding.java:13-14`) and on a
denial, but not on a SARIF result (`SarifResult(@Nullable UntrustedText ruleId, SarifLevel level,
UntrustedText message, @Nullable UntrustedText uri, @Nullable Integer startLine)`,
`SarifResult.java:13-18`; `SarifReader.java:48-62` reads nothing else) nor on the result itself.
So `ShellResult.asSarif()` (`ShellResult.java:119-123`, through `folded(UntrustedText head,
@Nullable UntrustedText details)`, `:86-91`), `GithubActionsResults.error` (`:62-71`),
`HttpResults` (`:59,74`) and `ShellExecutor` (`:113-116`, the timeout's `failed/quality` with
the output tail, and `:119`, the unanswerable `failed/infrastructure`) fold details into the
message or the reason, and the scenario "A red command without a findings file carries its output tail" fails on
the contract path. Decision: a SARIF result carries its details as `properties.details` — SARIF's
own extension point, a property bag on every object — read when it is a string and ignored, never
malformed, when it is another type (a third-party tool's property of the same name is display
text at worst); `SarifResult` gains `@Nullable UntrustedText details`. A result carries an
optional `details` beside `reason` (the denial's precedent). Like `reason`, it is allowed on any
status (the schema and the constructor refuse it on none) and read only for a `failed` or
`pending` result; on `completed` it is ignored, since a verdict's details travel on its SARIF
results (clarified 2026-11-12, D19 review). The one contract → domain mapping (D9 row,
`ProgramResultMapping`) puts them into `Finding.details`, `ExecutionResult.Failed.details` (the
one finding's details for `failed/quality`; `CannotVerify.details` for `failed/infrastructure`,
by `VerifyOrchestrator`) and the `Pending` details, which the state file, `status --json`, the usage report, the
terminal render and the next attempt's feedback already read; no executor folds any more.

*The producer and the GitHub core* (human decision 2026-11-10). `GithubRateLimit` becomes the
one owner of "is this response a rate limit, and for how long" (D9 row): today `static boolean
GithubRateLimit.isRateLimited(HttpResponse<?> response)` (`GithubRateLimit.java:22`) judges a
`403` only and extracts no value, `GithubConditionalRequestCache.Fresh(int statusCode, String
body, @Nullable String eTag, boolean rateLimited)` (`GithubConditionalRequestCache.java:125-126`)
carries a boolean, and a `429` is compared by hand at `ScmStatusReader.java:107`,
`GithubFreshBody.java:48` and in the retry predicate `GithubRetryConfig.java:58`
(`RATE_LIMIT_STATUS`, `:38`). After: `static Optional<RateLimit> GithubRateLimit.of(HttpResponse<?>
response)`, `RateLimit(Duration pause)`, the pause read from `retry-after` (seconds, or an
HTTP-date against `Date`), else `x-ratelimit-reset − Date`, else 60 s (GitHub's stated minimum
when neither header is present), for a `403` with the signal and a `429` alike; `Fresh(int
statusCode, String body, @Nullable String eTag, @Nullable RateLimit rateLimit)`;
`ScmStatusRead.RateLimited(int statusCode)` (`ScmStatusRead.java:22`) becomes
`RateLimited(int statusCode, Duration pause)`; `ScmStatusResults.rateLimited(AttemptSha sha, int
statusCode)` (`ScmStatusResults.java:64`) sets `pendingState: unobserved` and `pollAfterMillis`
from the pause, clamped to at most 86 400 000 (a reset further away is waited for in steps; the
pause is never negative, since `GithubRateLimit` reads a reset at or before `Date` as a zero
pause); `ScmStatusResults.of(ContextVerdicts contexts, List<String> expected, AttemptSha
sha)` (`:41`) sets `unreported` while an expected context is absent and `running` otherwise.
The retry: `GithubRetryConfig.build()` today retries a rate-limited response three more times at
0.5, 1 and 2 s (`GithubRetryConfig.java:34-37,55-60`), against GitHub's rule not to repeat a
request before `retry-after` or the reset and its warning that an integration that keeps going
may be banned. After: a rate-limited response whose pause exceeds the retry's own ceiling (8 s,
`MAX_INTERVAL`) is not retried and is returned at once, so its consumer acts on the pause
(`scm-status` → the hint; the tracker → its existing classification of the status, which it
already reached after the retries, now with three requests fewer); a shorter pause is retried no
sooner than the pause. `github-actions` and `http` set neither pending field: FR6 promises their
behaviour unchanged, and the `github-actions` "no workflow run on this SHA" case — the same
"never appeared" class, today `PollStatus.Running` (`GithubWorkflowRunVerdict.java:47`) — is
deferred by NG11, with its rate limit (`failed/infrastructure` at once, `GithubFreshBody.java:48`).

*Current signatures the decision changes* (beside those quoted above): `ExecutorResult(Status
status, @Nullable FailureClass failureClass, @Nullable PendingKey pendingKey, @Nullable
UntrustedText reason, @Nullable JsonNode answer, Usage usage, List<Denial> denials)`
(`ExecutorResult.java:35-42`), which gains `@Nullable Long pollAfterMillis`, `@Nullable
PendingState pendingState` (a `WireToken` enum of `:executor-contract` with `fromWire`) and
`@Nullable UntrustedText details`, the constructor refusing either pending field off `pending`
and a `pollAfterMillis` outside 0–86 400 000 (amended 2026-11-12: the in-process built-ins'
results are never serialized, so the constructor is where the range binds them, the same rule
`ResultReading` applies on the wire);
`record Pending(String key)` (`ExecutionResult.java:132`), widened by D18 as amended, its key
typed by D20 (`PendingKey` of the `:pendingkey` leaf; the contract's `pendingKey` component above
takes the same leaf type);
`record Failed(FailureClass failureClass, UntrustedText reason, List<Finding> findings)`
(`ExecutionResult.java:107`), gaining `@Nullable UntrustedText details` (D18 as amended
2026-11-12);
`ExternalPolling.poll(VerifyCheck.Executor check, Workspace workspace)` (`ExternalPolling.java:90`)
and its `timeoutAnswer(VerifyCheck.Executor check)` (`:132`), whose rules `PendingPoll` (4.2)
takes over with the two above added.

*Conformance.* No kit case is added. Every result a `program` candidate writes is judged against
the result schema (`WireObservation.java:104-116`, the documents read through the environment's
`readFile`), so the schema change reaches every case of a program. An in-process built-in's
result is not serialized and not schema-checked (`BuiltInCandidate.java:127-130` reports no
violations; only its describe is judged, `:72-78`), so for a built-in the `ExecutorResult`
constructor's refusal of a misplaced or out-of-range field is the check, and `scm-status` cannot
build an out-of-range hint (`ScmStatusResults.rateLimited` clamps the pause into
0–86 400 000; `GithubRateLimit` never yields a negative pause). The bad-hint fake is its own
committed preset, `[pending-bad-hint]` in `tables/executor.params` — the reference executor whose
`start` answers the data section `executor-result#pending-bad-hint` (`pending` with a key and
`pollAfterMillis: -1`) — not a `break-<case>` link: `steps/executor-run.sh:45-53` accepts only the
kit's case names and exits 97 on any other, and those names do not change (D8). The kit's
`pending-poll` case run against it fails naming `pollAfterMillis`; `StandInRowReachSpec` covers
the new section.

*Alternative zero rejected — no typed field, the engine keeps the fixed interval and the declared
timeout class:* "Rate limit backs off" promises that the engine respects a wait hint, and the
pending-poll requirement promises an absent expected context resolves as infrastructure; without
the fields a stale `expected` entry or GitHub's throttling burns every attempt as a quality
failure with feedback the gnome cannot act on, and a primary limit (reset up to an hour away) is
polled every interval with up to four requests per read. *Alternative rejected — the engine parses
`reason`:* the executor's words are an `UntrustedText`, shown and never parsed (`logging.md`);
a wording change would change a verdict. *Alternative rejected — an absolute `pollNotBefore`:*
couples the contract to the executor's clock, and persisting it would need a commit per poll,
against D12's one commit per key change. *Alternative rejected — a typed list of awaited names
(`waitingOn`):* no engine decision reads a name; pickup condition: the first engine or dashboard
decision that reads the state of one awaited subject. *Alternative rejected — the executor
decides the timeout class:* it does not know the deadline, which is the check's (D2), and
`scm-status` holds no state between polls. *Alternative rejected — details in `message.markdown`:*
in SARIF that is another rendering of the same message, not a further detail. *Alternative
rejected — keep the folds (alternative zero for details):* the promised details scenario fails,
and every reader of `Finding.details` gets `null` while the message grows a log tail.

**D20 — The pending key is one JDK-only leaf, `:pendingkey`, typed on every layer.** (FR2, FR3,
FR10, NFR-R1; human decision 2026-11-12, option B of the blocker of task 4.1; closes Q1 of
`define-executor-contract` together with D7 as amended.) *Context.* D9's `PendingPoll` row
promises "parameter type (`PendingKey`, no `String`)", and the owner and its record live in
`domain/engine`. The one key type, `record PendingKey(@JsonValue String value)`
(`executor-contract/.../executorcontract/PendingKey.java:16-41`: not blank, at most `MAX_LENGTH =
4096` UTF-16 characters, no ISO control character; the judgement `static @Nullable String
unusable(String value)` shared with `ResultReading.java:67`), lives in `:executor-contract`, which
carries Jackson as `api` (`executor-contract/build.gradle`), while `:domain` may reach JDK-only
leafs only (`domain/build.gradle:3-10`, `DomainLeafPuritySpec`). No domain twin exists, and the
domain carries the key as a bare `String` in two places this change built: `record
PendingTurn(String key, Origin origin)` (`domain/.../engine/port/PendingTurn.java:13`) and
`ExecutionResult.Pending(String key, …)` (`ExecutionResult.java:147-148`). The key is unwrapped
on the way in (`ProgramResultMapping.java:183-184`, `Objects.requireNonNull(result.pendingKey())
.value()`) and re-wrapped on the way out (`CheckRound.java:80`, `new PendingKey(turn.key())`), so
a domain `String` the rule refuses would surface as an `IllegalArgumentException` at the poll.
*Decision.* A new dependency-free leaf, `:pendingkey` (directory `pendingkey/`, package
`com.github.oinsio.gnomish.pendingkey`, named like `:untrustedtext` and `:operatorevent`), is the
one owner of the key's type and rule: `public record PendingKey(String value)` with `public static
final int MAX_LENGTH = 4096`, the compact constructor refusing a blank or unusable value, and
`public static @Nullable String unusable(String value)` (the judgement a reader applies to a
written string before it constructs a key). No Jackson annotation: the leaf declares no
production artifact (JSpecify is compile-only through `java-conventions`, as for
`:untrustedtext`), so `DomainLeafPuritySpec` admits it. `executorcontract.PendingKey` is
**deleted**, not re-exported: `ExecutorResult.pendingKey`, `Executor.poll(ExecutorRequest,
PendingKey)`, `ResultReading` and every built-in name the leaf type, and the contract binds it in
its one JSON binding — `ExecutorContractJson` registers a serializer writing the value as a bare
JSON string, beside the `UntrustedText` pair already registered there (`ExecutorContractJson.java`,
the `untrusted-text` module). No deserializer: no contract type binds `PendingKey` on the read
side — a result is read through `@JsonDeserialize(converter = ResultReading.class)`
(`ExecutorResult.java:46`) from `WrittenResult`, whose `pendingKey` is a `String`
(`WrittenResult.java:21`), and `ResultReading` alone judges it and constructs the key, so a second
refusal path for the same wire field (a failed read beside `failed/infrastructure`) is not built. The domain takes it as `api` (it sits on `PendingTurn`, `ExecutionResult.Pending` and
`PendingPollRecord`); `:executor-contract` takes it as `api`. The domain fields become `record
PendingTurn(PendingKey key, Origin origin)` and `ExecutionResult.Pending(PendingKey key, @Nullable
Duration pollAfter, PendingState state, @Nullable UntrustedText reason, @Nullable UntrustedText
details)`; `ProgramResultMapping` passes the key through and `CheckRound` hands `turn.key()` to
`poll` unchanged. *What stays unchanged.* The wire: `pendingKey` stays a JSON string of 1 to 4096
characters with no control character, the result schema is not touched, and `ResultReading`'s
`failed/infrastructure` for an unusable written key keeps its words ("status pending with an
unusable pendingKey: …"). The plugin API's surface by intent: `:executor-contract` is not on
`:gnomish-plugin-api`'s graph and has no japicmp baseline; `:domain` is, so retyping two
components of re-exposed domain records is a binary break of the domain jar and the leaf jar
joins the published jar graph — both absorbed into this change's one MINOR bump (`0.14.0` on
`main` → `0.15.0`, D16, still unreleased), with `compat-baseline/` regenerated over the five-jar
set (`gnomish-plugin-api`, `domain`, `untrustedtext`, `operatorevent`, `pendingkey`) and the
version comment of `gnomish-plugin-api/build.gradle` naming D20 beside D16. *The cap and the
oversized-key scenario.* The record's key is the leaf type, so the state file's cap **is**
`PendingKey.MAX_LENGTH` by construction — no second constant; a state file whose key the leaf
refuses is a `MalformedStateFileException` (a registered commit cannot hold one, since its writer
held a `PendingKey`). An executor's key over the cap never reaches the record: `ResultReading`
reads it as `failed/infrastructure` naming the cap, and a built-in cannot construct one, so the
check resolves as cannot-verify with no poll ("Oversized key is infrastructure"). *Rationale:*
rule of two, preference 1 (`manual-sync-pairs.md`): a module both consumers can reach exists as
soon as the rule moves into a JDK-only leaf, which is the shape `:untrustedtext` already has for
both `:domain` and `:executor-contract`; and D9's enforcement becomes the parameter type on every
layer, which removes the escape hatch (`implementation.md` item 3). *Alternative zero — no typed
key in the domain:* the record and the two port fields keep `String`; the rule is applied at two
edges (the state mapper and `CheckRound.java:80`) and any domain code can build a record from an
unchecked string, the refusal surfacing as an unclassified exception at the poll. Rejected as
option C: D9's "no `String`" is unmet and the escape hatch stays. *Alternative rejected — a domain
twin with a copy of the rule, declared as a pair with the contract's type (option A):* the pair's
barrier is not real — a JDK-only leaf both modules reach is possible, so the rule of two forbids
the declared pair, and two copies of a length and character rule drift into a key one side
accepts and the other refuses (an exception at `CheckRound`). *Alternative rejected — move the
type into `:untrustedtext`:* the text leaf owns terminal safety of untrusted text; an opaque
continuation key is not text the factory shows, and the leaf's empty allowlist is argued for that
one responsibility (`settings.gradle`). *Alternative rejected — keep `executorcontract.PendingKey`
as a subclass or wrapper re-exporting the leaf type:* a record cannot be extended, and a wrapper
is a second key type with a conversion at every boundary, which is the re-wrap this decision
removes.
*D20 as amended 2026-11-12 (D21):* the record is `PendingPollRecord(CheckRef check, int attempt,
@Nullable RoundToken token, PendingKey key, Instant since)`. Today it is `PendingPollRecord(CheckRef
check, int attempt, PendingKey key, Instant since)` (`PendingPollRecord.java:26`). The token is
null only in the in-place run, which has no branch. The record is an entry of
`TaskState.pendingPolls`, not a field of the attempt. The state-file mapping moves from the
attempt's `pendingPoll` object to a root array `pendingPolls` of `StateJsonDto`, whose elements
carry `attempt` and `token` explicitly. The key's typing, the cap and the malformed-file rule are
unchanged.

**D21 — The pending record is a fact of the open round, landed by a pending commit on the round's
snapshot.** (FR2, FR3, FR10, FR20, NFR-R1, NFR-R4.) *Human decisions 2026-11-12 on the blocker
of task 4.2:*
1. The record leaves the attempt for a list of the open round's entries on `TaskState`, one per
   check, each with the round token (4.1 is reworked).
2. The host persists the key too and gets a verify-only resume (D22). The recommendation to keep
   the host's key in memory was rejected.
3. The production cut-over happens in 4.2f, formerly 5.1 (D18 as amended).

*Context — premises verified in code.*
- The attempt record is built only after verification
  (`RoundExecution.java:157-178`; `RecordedRound.record` passes `null` for the pending poll,
  `RecordedRound.java:44-45`), and `AttemptRecord.Result` has no "awaiting a verdict" value
  (`AttemptRecord.java:130-136`).
- The persistence port is called once per finished round: `void persist(String taskId, TaskState
  state, ToolTrace trace)` (`AttemptPersistence.java:39`). It renders the subject from `static
  AttemptRecord roundOf(TaskState state)`, which is `attempts().getLast()`
  (`AttemptPersistence.java:52-54`), and it throws on round 0 when no attempt was appended.
- The container refuses a second state commit per round: the parent must be the snapshot
  (`HarvestedStateCommitCheck.verify(String taskId, String tip, String snapshot, String tracePath,
  byte[] stateBytes, byte[] traceBytes)`, `HarvestedStateCommitCheck.java:67`, `verifyParent`
  `:76-95`).
- A record placed in `attempts` would be read as an outcome by `StopEscalation.recorded`
  (`StopEscalation.java:28-31`, read at `Engine.java:113`), and it would shift the round number,
  which is `state.attempts().size()` (`RoundExecution.java:99`).
- The container already has a registered mid-round commit. The admitted snapshot is a closing
  commit (`EnvironmentRoundSnapshot`). Its resume reads the registered commit's subject
  (`SnapshotTipCheck.inspect`, `SnapshotTipCheck.java:88-139`) and skips the executor
  (`ResumeVerificationStageExecutor`). A commit above the snapshot that does not carry the snapshot
  subject would make that recogniser return empty (`:117-119`): the resume would salvage and re-run
  the agent, which is worse than no record.

*Decision.*
- **The record.** `TaskState` gains `List<PendingPollRecord> pendingPolls`. Today the type is
  `public record TaskState(Position position, int attemptsUsed, List<AttemptRecord> attempts,
  UsageSnapshot totals, ExecutorVersions executorVersions)` (`TaskState.java:41-46`).
  - The list is set only through `TaskState withPendingPolls(List<PendingPollRecord>)`, called only
    by `PendingPoll`; every other writer builds the state with an empty list.
  - `AttemptRecord.pendingPoll` and `AttemptRecordChecks.requirePendingPollOfRound` are deleted
    (`AttemptRecord.java:106-117`, `AttemptRecordChecks.java:75-81`).
  - An entry stays in the list after its check has ended, until the round closes. A resume
    re-polls it, and D11 answers with the ended run's result; it does not `start` again. So two
    pending checks of one round both survive a kill.
- **The write.** The port gains `void persistPending(String taskId, TaskState state)`.
  - Its contract: `state`'s attempts, position and counter equal the last persisted state's; the
    position is `Position.AtStage`; the list is not empty; no trace is written. The subject
    `gnomish: pending <stage>#<round> <token>` is rendered by `ServiceCommitMessages.pending(String
    stage, PendingPollRecord last)`: the stage from `state.position()` (`AtStage.name()`, unchanged
    by the contract), the round and token from the list's last entry. `PendingPollRecord` carries
    no stage (`PendingPollRecord.java:26`; `CheckRef(int index, UntrustedText label)`,
    `CheckRef.java:28`), so `state.position()` is the one source of the stage on both ends.
  - The pending commit carries exactly `state.json` and sits on the round's last registered commit:
    the snapshot when it is the round's first pending commit, otherwise the round's last pending
    commit — a second check's first `pending` stacks on the first check's commit as a key rotation
    (D12) does. It lands with its register in one ref transaction, followed by the closing push.
  - **A round with no registered snapshot writes no pending commit.** When the run's cell holds no
    snapshot (a host snapshot declined on a boundary verdict, D22), `persistPending` refuses before
    any write. A container snapshot the admission refused is recorded in the cell but not
    registered (`EnvironmentRoundSnapshot.java:107-112`), so its ref transaction's compare-and-swap
    refuses instead. Either way the outcome is the one below.
  - **What a failed `persistPending` comes to.** It moves nothing and throws. `PendingPoll` runs
    inside `VerifyOrchestrator.runCheck`'s catch (`VerifyOrchestrator.java:151-161`), so the check
    whose key the write carried is cannot-verify and no `poll` is made: persist-before-poll holds.
    The round then closes through `persist`, which judges what is there. A refused container
    pending commit leaves the in-box commit as the box's `HEAD`, so the state commit's parent is
    not the registered commit, and `HarvestedStateCommitCheck` aborts the task as a boundary
    violation. A failed host compare-and-swap means a check committed in the worktree; the round
    close's range inspection judges that commit from `ClosedRound.registered`. A revocation found
    by `RevocationCheckingAttemptPersistence` on this write is recorded (`revocation()`). Every
    later write through the decorator then throws before its delegate, so a check's cannot-verify
    never lands a round commit after a revocation.
  - **Host: built from objects, no worktree, no index (review of 2026-11-12).** The pending commit
    is written as objects in the host clone's `.git` through `GitObjects`, the way
    `GitObjectsTerminalCommits.cleanUp` writes the host cleanup ("needs no worktree, takes no index
    lock", `GitObjectsTerminalCommits.java:22-28`). The steps: `state.json` becomes a blob; the tree
    is `registered`'s tree with that one blob replaced; `commit-tree -p <registered>`; then
    `FactoryRegister.close(Path repo, String taskId, String commit, String expectedTip)`
    (`FactoryRegister.java:64`) with `expectedTip` = `registered`, a compare-and-swap of the branch
    and the register.
    A pending check's run is still alive in that worktree when the write happens
    (`HostCheckEnvironmentSource.java:28-34`; a `pending` run has not ended). So no step before the
    decision takes a lock without a compare-and-swap (`lock-scope.md`, "Writers from different
    processes"), and nothing the run staged or committed can enter the commit's tree or parent.
    *Rejected:* `WorktreeClosingCommit.commit` with only `state.json` staged, as first written. It
    takes the branch tip as parent (`WorktreeClosingCommit.java:60-61`, `:83`) and the shared index
    as tree (`:72`), and `index.lock` is its first lock, with no compare-and-swap behind it.
  - **Container: in the box, through a private index.** The in-box commit is built with
    `GIT_INDEX_FILE` naming a temporary index read from `HEAD`. Only `state.json` is added there.
    Then `commit-tree -p <registered>` runs, and `update-ref HEAD <commit> <registered>`. Then
    comes the harvest, a new `AdmissionRule.pending` (the parent is `registered`, exactly
    `state.json` changed), and the read-back of `HarvestedStateCommitCheck`. A same-box check that
    staged a path cannot put it into the commit. The box's `HEAD` moves to the pending commit, so
    the in-box state commit that closes the round has it as its parent, and
    `HarvestedStateCommitCheck.verifyParent` (`:76-95`) accepts it.
    *Rejected:* the container commit built factory-side from objects, as on the host. The box's
    `HEAD` would stay on the snapshot, so the in-box state commit's parent would be the snapshot,
    not the registered pending commit, and `verifyParent` refuses that.
  - **The lag the write leaves behind, and its two readers.** On both media the working copy's
    `HEAD` follows the branch to the pending commit (the host worktree's `HEAD` is the branch's
    symbolic ref). The index and the working tree keep the previous `state.json`. Two readers see
    that lag (three since D14 as amended 2026-11-13: the adoption guard, at the end of the first
    item, shares the first reader's owner), and each recognises it by identity, never by the path alone:
    - *The verdict run's working-copy check* (NFR-S4 of `define-executor-contract`; `WorkingCopyCheck`,
      whose view keeps the `branch.oid` header, `StatusEntries.java:11-16`). Without a rule, every
      `program` verdict check that answered `pending` would be refused at its end, naming `HEAD`.
      The rule, by identity (crash-consistency item 13; review r2 of 2026-11-12): when the view's
      `branch.oid` moved during the run, the move is the factory's only when the `branch.oid`
      after the run equals the round's last registered commit, the commit the run's `CurrentRound`
      holds as `ClosedRound.registered` (moved by `pendingRegistered`, D21 "The parent of the
      round's close") at the moment the after-view is judged. A round with no registered snapshot
      has nothing to match, so the expected value is the `branch.oid` before the run, and any move
      is refused, as today. A `.gnomish-task/state.json` entry counts as lag only when its index
      object equals `<before>:.gnomish-task/state.json` and its working-tree column is unchanged.
      Every other difference still refuses the answer, an edit of `state.json` included. Under
      this rule every commit of the run is refused, an empty one and an edit-and-revert pair
      included: a commit of the run either lands before the factory's pending commit, which then
      fails its compare-and-swap on `registered` and leaves `HEAD` on the run's commit, or lands
      above it, so `HEAD` is not the registered commit in either case. On the container the
      harvested pending commit keeps the in-box commit's id, so the in-box `HEAD` and the register
      name the same commit.
      *How the check reads the cell.* The check reads the commit through a read-only role
      interface of `app.port.git` that `CurrentRound` implements (one method returning the round's
      last registered commit, empty when the round has none). It reaches the check through the
      run's bound program entry (D18 as amended: built once per run at run assembly): the
      `ProgramRoundEquipment` record built in `ExecutorAdapterSelector.stageRounds`
      (`ExecutorAdapterSelector.java:93`) carries the run's cell under that role — the host's per-run
      value (D22) or `SandboxRunPieces.rounds()` — and `ProgramExecutor` hands it to
      `WorkingCopyCheck` (`new WorkingCopyCheck(...)`, `ProgramExecutor.java:136`; today
      `WorkingCopyCheck(TaskExecutionEnvironment environment, Duration bound, InstantSource
      clock)`, `WorkingCopyCheck.java:76`). The in-place run and the conformance kit's candidate
      (`ProgramCandidate.java:116`) pass the role's local default, which holds no commit.
      *Rejected — the move judged by content* (the first text of this paragraph): the move counted
      as the factory's when `git diff --quiet <before> HEAD -- . ':(exclude).gnomish-task/state.json'`
      held. That is authorship by content, which item 13 forbids ("a subject or an envelope at the
      tip proves nothing; only the registered commit names the factory's record"). An empty commit
      of the run, an edit-and-revert pair and a commit of `state.json` alone would have passed, and
      the premise that the round close's range inspection reports them is false:
      `GnomeRangeInspection` refuses only a merge, a `gnomish: ` subject and a path under the state
      directory (`GnomeRangeInspection.java:13-19`; `AdmissionRule.hostRound()` permits none), and
      the host round commit sits on the branch tip (`WorktreeClosingCommit.java:60-61`), so the
      first two would enter the task branch with the verdict accepted. It also cost a subprocess in
      the environment (an exec in the box) where the identity rule is one comparison.
      *Corrected 2026-11-13 (D14 review):* the D14 adoption guard does not find the lag gone in
      every case. Only a `RECORDED` key is adopted (D14 as amended 2026-11-13; a live `ISSUED` key
      is in the run's table and is not adopted), but the host discard is best effort
      (`WorktreeSalvage.discard`, `WorktreeSalvage.java:100-125`) and a same-box resume keeps the
      box's residue (D22). So the guard is a reader of the lag too, by the same identity: its
      `HEAD` must equal the commit the binding expects (a fresh box's attempt commit, otherwise the
      round's last registered commit), the lag's blob must be the `state.json` of a commit of the
      round's registered chain (read by `PendingChain` at the resume, D14), and it asks
      `FactoryMove`'s adoption query (D14) whether the one entry it sees is this lag, rather than
      holding a third copy of the rule.
    - *The host round close* (`GitAttemptPersistence`'s working-tree boundary,
      `EnvelopeRestore.uncommittedEdits()`, which compares the index and the working tree with
      `HEAD`). The lag is recognised when both the index's and the working tree's
      `.gnomish-task/state.json` equal the `state.json` of one of the round's own registered
      commits, read on the first-parent chain from `ClosedRound.registered` down to
      `ClosedRound.attemptCommit`. That chain includes a pending commit a resumed worktree was reset
      to. Then
      `persist` restores that one path from the register before it judges. Any other content of
      the path is judged as today. Nothing else writes in the worktree at that point, because
      every check has ended.
  - The decorators `PushBestEffortAttemptPersistence` (`:41`) and
    `RevocationCheckingAttemptPersistence` (`:150`) forward it as they forward `persist`.
    `InMemoryAttemptPersistence` (`:30`) keeps the state in memory: the in-place run has no branch
    and no resume.
- **The parent of the round's close.** The state commit, and the host round commit, sit on the
  round's last registered commit, held in `CurrentRound`. Today `public record ClosedRound(RoundToken
  token, String attemptCommit)` (`ClosedRound.java:17`) becomes `ClosedRound(RoundToken token,
  String attemptCommit, String registered)`. `registered` equals `attemptCommit` after
  `snapshotted(String)` (`CurrentRound.java:60`) and moves with each pending commit through a new
  `void pendingRegistered(String commit)`. `HarvestedStateCommitCheck.verify`'s `snapshot` parameter
  becomes the expected parent. The field and the host persistence's switch to it come with the host snapshot
  (task 4.2c), which runs first; the pending commit (task 4.2a) adds only the writer that moves it.
- **The resume.**
  - `SnapshotTipCheck.inspect` (`:88`) also recognises a registered `gnomish: pending` commit. It
    follows first parents over `pending` subjects of the same stage, round and token to the snapshot
    of that identity, and returns `PendingVerification` with both commits. Today `public record
    PendingVerification(String attemptCommit, String stage, int round, RoundToken token,
    Optional<RoundDecisionRequest> request)` (`PendingVerification.java:41-42`) gains `String
    registered`. `CurrentRound.restore` (`:70`) restores both.
  - `ResumeVerificationStageExecutor` stays the one owner that skips the executor.
  - The entries reach `PendingPoll` with the resumed `TaskState` read at the registered commit
    through `StateJsonMapper`, the parse the live path's write uses.
- **Liveness by identity (crash-consistency item 13).**
  - `PendingPoll` turns an entry into a `RECORDED` turn only when its `attempt` is the round's
    number and its `token` equals the token of the round being verified.
  - On a resume that token is the one restored from the snapshot's subject, a different and earlier
    commit, so this is not a self re-read. On a fresh round the token is newly minted, so a
    carried-over entry never matches.
  - The token reaches `PendingPoll` from `RoundExecution`, which takes it once before verification
    (`identity(...)`, `RoundExecution.java:132`, `:145-148`). `VerificationResult
    verify(List<VerifyCheck> checks, TaskContext context, Workspace workspace, AttemptKey key)`
    (`VerifyOrchestrator.java:93`) gains `TaskState state, @Nullable RoundToken token` (six
    parameters); `RoundIdentity` (`RoundIdentity.java:33`, `takeToken()` only) is unchanged.
  - The emptied list in every other writer is hygiene, never the judge.
- **One path, live and resumed (item 14).** The live path continues from the entry it wrote, the
  resume from the entry it read. Both go through one `PendingPoll` method, so there is no second
  continuation path.

*Kill windows (both media; durable steps: (0) snapshot registered, (1) `start` returns `pending`,
(2) pending commit landed and pushed, (2′) a rotated key's pending commit, (3) a terminal `poll`,
(4) the round's closing commit with no entry):*

The snapshot is not pushed, neither the container's (`EnvironmentRoundSnapshot.java:27-29`) nor
the host's (D22). So in W0 and W1 the register a pickup reads depends on the clone. The clone
that wrote the snapshot reads the snapshot. Any other clone, after its fetch, reads origin's
register, which is the previous round's closing commit. The exception is a container snapshot
that the attempt delivery pushed: delivery runs before `start` for every check the engine polls
(`PendingPoll.answer` over `VerifyCheck.Executor.polled()` — every `external` check and every
executor other than `files_exist` and `shell`, `http` included), not only for a SHA-scoped one,
so a round with such a check has its snapshot on origin from the delivery on. The column says which clone each row
describes (crash-consistency items 2 and 15: the shape depends on the reader, so the table names
the reader).

| Window | Register shows | Shape | Recovery owner | Action |
|---|---|---|---|---|
| W0: after (0), before (1) | this clone: snapshot | `InProgress`, interrupted verification | `SnapshotTipCheck` → `ResumeVerificationStageExecutor` | re-verify, no executor, no attempt burned; `start` as on a first run |
| W0, another clone | the previous closing commit (container: the snapshot when attempt delivery pushed it — then as on this clone) | `InProgress`, during the agent | the ordinary resume | salvage and re-run of the stage executor, no attempt burned |
| W1: after (1), before (2) at origin | this clone: snapshot | same as W0 | same as W0 | re-verify; `start` again (NFR-R1: a duplicate is visible, never a wrong verdict) |
| W1, another clone | as W0 on another clone | same | same | salvage and re-run of the stage executor, no attempt burned; the round's checks `start` again |
| W1′: (2) landed locally, push failed | this clone: local pending commit ahead of origin | local-ahead (task-branch-contract "Local-ahead is kept, local-behind fast-forwards") | reconciliation, then as W2 | as W2 on this clone; on another clone as W0/W1 there |
| W2: after (2) | pending commit | `InProgress`, interrupted verification with entries | same + `PendingPoll` | poll every live entry (`RECORDED`, D14), no `start`; the deadline counts from `since` |
| W3: a new key returned, before (2′) | previous pending commit | same | same | poll the previous key (D11) |
| W4: after (3), before (4) | pending commit | same | same | poll again; D11 returns the ended run's result; no `start` |
| after (4) | the round's closing commit, empty list | the shape the close produces | its owner | none; a second pass is a no-op |

- *Item 4:* key, check, attempt, token and `since` land in one commit.
- *Item 5:* the entries are removed only by (4), after the verdict.
- *Item 6:* no shape is added and the sweeper's set is unchanged.
- *Item 12:* a pending commit carries the snapshot's registered position, counter, attempts and
  stop byte for byte. A pickup of it may poll, and nothing more: no executor run, no advance, no
  close.
- Checks that ran before the pending one in the chain run again on W0–W4. They judge the same
  snapshot, a bounded cost.
- W2–W4 read the same on every clone: a pending commit is pushed with its register.

*Two promises restated by this decision (review of 2026-11-12; the premise of each is the entry
rule above, "An entry stays in the list after its check has ended, until the round closes"):*
- The parent of a pending commit is the round's last registered commit. For a second check of the
  round that answers `pending`, that is the first check's pending commit, not the snapshot. The
  `git/git-task-persistence` scenario "A pending commit sits on the round's last registered commit"
  is therefore the round's *first* pending commit. A scenario of its own states the second one.
  The `verification/pending-poll` requirement names "the round's last registered commit".
- A pickup re-polls an ended entry until the round's closing commit lands (W4). So "Second recovery
  is a no-op" holds once that commit has landed, not once a poll resolved: its WHEN is "a branch
  whose round's closing commit already landed". The earlier WHEN, "whose poll already resolved",
  contradicted the scenario "Every pending check of the round is continued" and row W4 of this
  table for the same state.

*Rationale:*
- The open round already has a registered, owned intermediate shape on the container. Extending it
  adds no shape, no recovery owner and no reader of `attempts`.
- The recogniser and the decorator are each one class for both media (D22).

*Alternative rejected — the record on the attempt (D7 and D20 as amended earlier on 2026-11-12;
task 4.1 as built):* the attempt is written only after verification and is read as an outcome by
at least seven readers: the round number, `StopEscalation.recorded`, `attemptsUsed`,
`startOfStage`, `RoundRecords.priorFailures`, the usage fold, and `AttemptPersistence.roundOf`.
*Alternative rejected — O1, an intermediate attempt with a new `Result`:* each of those readers
would learn to skip it (scatter), it violates item 12, and the shifted round number breaks the
token-to-snapshot binding. *Alternative rejected — a record on `TaskState` with no commit kind of
its own:* half a decision, because the container refuses a second state commit per round.
*Alternative zero — the key in memory only:* for the five built-ins `start` is a read, but every
kill restarts the timeout, and a program verdict executor re-submits once per kill. FR3, NFR-R1
and NFR-R4 would be left with no owner, and `extract-agent-round-runner` (its FR10–FR12) needs
this write anyway. *Alternative rejected by the human (2026-11-12) — container only, the host's key
in memory:* the host gets the same write and a verify-only resume (D22).

*Cross-change sequencing (`extract-agent-round-runner`, not edited here):* its D11 rests on two
premises refuted above: "the pending sub-state is a field of an InProgress attempt", and "each
[persistence end] writes the pending round commit through its existing sequence … with no new
step". When that change is applied it takes `persistPending`, the entry list with a `Round`
subject, and the recogniser. `ResumeVerificationStageExecutor` must not answer `completed` for an
entry whose subject is a round rather than a check: that round has not finished. This change
keeps the record and the recogniser subject-neutral for it. Two more points will meet that change.
First, it widens the host snapshot trigger (D22, `completed` only) to a `pending` transform result.
On the container that result now closes under the unverified-snapshot subject (D22, "The
container's round close names its outcome"), so the change must also give the recogniser a
pending-round subject. Second, it either keeps the live path in memory, as D21 does, or amends D21:
its own D11 re-reads the record from the commit on the live path.

**D22 — A git-mode host round snapshots the gnome's work before verification; one recogniser and
one resume serve both media.** (FR19, FR3, NFR-R1; human decision 2026-11-12: a snapshot in
every host round that reaches verification (eager, not lazy), one protocol with the container;
human decision 2026-11-12 on the D21/D22 review: the container's round close names its outcome, so
a round its executor did not complete is never resumed as an interrupted verification.)

*Context — premises verified in code.*
- A host round closes in one commit of the whole worktree (`GitAttemptPersistence.persist`,
  `GitAttemptPersistence.java:124-152`; main spec `git/git-task-persistence` "Round commit of the
  whole working tree", scenario "Host round atomicity"). Until then the gnome's work is commits
  above the register plus uncommitted files on disk.
- Host checks run in that worktree (`HostCheckEnvironmentSource.java:28-34`).
- The host resume always salvages and re-runs the round with its agent:
  `GitResumeContinuation.resumeFromRecordedPosition(RunOrder order, TaskState finalState)`
  (`GitResumeContinuation.java:75-80`) and `TakeResumeRunner.resumeWithoutDecision(TakeOrder order,
  ResumeBootstrap bootstrap, TaskState finalState)` (`TakeResumeRunner.java:105-110`).
- The interrupted-verification resume is wired for the sandbox only (`ExecutorAdapterSelector.java:98-100`).
- A host key recorded today would have no reader. Polled after a re-run of the agent, it would
  judge a working copy that no longer exists.

*Decision.*
- **The host snapshot.** After the host stage executor returns `completed`, before the engine
  verifies, the round's gnome work is closed by a snapshot commit: subject `gnomish: snapshot
  <stage>#<round> <token>` (`ServiceCommitMessages.snapshot`), the gnome range judged by
  `GnomeRangeInspection`, the owned paths restored from the register (`EnvelopeRestore`), `git add
  -A`, a closing commit with its register in one transaction, and `CurrentRound.snapshotted`.
  - **The boundary verdict comes first, and a verdict other than clean declines the snapshot
    (review of 2026-11-12).** The step judges the gnome range from the tip the round opened on
    (`GnomeRangeInspection`). It then judges the working tree's uncommitted envelope edits
    (`EnvelopeRestore.uncommittedEdits()`), *before* `EnvelopeRestore.restore()`. Today's round close
    keeps the same order (`GitAttemptPersistence.verifyBoundary`, `GitAttemptPersistence.java:158-171`).
    The restore overwrites `.gnomish-task/` from the register (`EnvelopeRestore.java:67-75`), so a
    judgement after it would see a clean tree.
    On `Violated` or `CannotVerify` the step restores nothing, commits nothing and throws nothing.
    The cell stays without `snapshotted`, and the round's `persist`, based on the round's token,
    judges the same tree itself. It restores, reports the violation, and the task ends `Aborted`, or
    it reports cannot-verify as a persist failure, also `Aborted`. This is how the container treats a
    refused snapshot ("a refused snapshot moves nothing; the persist judges the same commit again and
    throws it", `EnvironmentRoundSnapshot.java:29-33`).
    A throw from inside the stage chain would reach `RoundExecution.execute`'s catch
    (`RoundExecution.java:103-107`) and become an infrastructure failure of the executor, not
    `Aborted`. The stable scenario "An uncommitted envelope edit does not ride the host round
    commit" is therefore still met by `persist`. A round whose snapshot was declined is verified on
    the worktree and writes no pending commit (D21).
  - No push: the next pending commit or the round commit carries it, as in the container.
  - An empty round still gets a commit, like the container's `--allow-empty`.
    `WorktreeClosingCommit.commit(Path worktree, String taskId, String message)`
    (`WorktreeClosingCommit.java:59`), which refuses "nothing to commit", gains an allow-empty form
    for this one caller.
  - The trigger is the completed result. A round that ended `failed`, or that asked a decision, gets
    no snapshot: its record commit stays the round's one commit, as today. Otherwise a resume would
    re-verify a failed round, or verify a round whose question the host's per-round decision file
    never put into the tree (the host decision transport keeps the request outside the worktree).
  - The trigger lives in the git-mode host assembly beside `MidRoundPushRounds` (`public
    MidRoundPushRounds(RoundEnvironmentSource host, GitRunner runner, InstantSource clock)`,
    `MidRoundPushRounds.java:77`). The in-place run has neither the decoration nor a snapshot.
  - **The run's round cell has one owner, the git-mode host run preparation (review of
    2026-11-12).** Today the host cell is a private field of `MidRoundPushRounds` (`:58`). That
    class is built by a stateless operator (`ManualRunConfiguration.java:180`) inside
    `ExecutorAdapterSelector.stageRounds` (`:84`). The persistence is built elsewhere and earlier,
    through `AttemptPersistence attemptPersistence(Path worktree, String taskId)`
    (`TaskStoreGit.java:43`). No value reaches both, so the cell moves out:
    - Each of the three git-mode host run preparations constructs one `CurrentRound` per run:
      `GitModeRunner.java:125`, `TakeEngineExecution.java:130` and `GitResumeContinuation.java:150`.
      On a resume the preparation also holds the `@Nullable PendingVerification` found by the
      no-salvage owner below. Both travel as one per-run value of `app.port.git`, beside
      `CurrentRound`.
    - The pending verification reaches the preparation as an explicit argument from the path
      that called the owner, never by a second read of the register inside the preparation
      (crash-consistency item 14; review r2 of 2026-11-12). This is the container's shape:
      `TakeContainerResumeRunner.java:93` passes `pending`, `:112` and
      `TakeContainerFreshClaim.java:122` pass `null`. On the host:
      - `TakeResult run(TakeOrder order, ResumeBootstrap bootstrap, TaskContext context, TaskState
        state)` (`TakeEngineExecution.java:122`) gains `@Nullable PendingVerification pending`.
        `TakeFreshClaim.java:137` passes `null`, `TakeResumeRunner.resumeWithoutDecision`
        (`TakeResumeRunner.java:105-119`) the owner's answer, `TakeResumeRunner.resumeDecided`
        (`:146`) `null`.
      - `private void runToTerminalBoundary(RunOrder order, TaskContext context, TaskState state)`
        (`GitResumeContinuation.java:147`) gains it too. `resumeFromRecordedPosition` (`:75-82`)
        passes the owner's answer, `resumeEscalated` (`:112`) and `resumePaused` (`:127`) pass
        `null`: a resume after a decision or an approval starts from a closed round.
      - `GitModeRunner.java:125`, the fresh run, holds `null`.
    - `TaskStoreGit.attemptPersistence` becomes `attemptPersistence(Path worktree, String taskId,
      CurrentRound rounds)`.
    - `TaskGit`'s `midRoundPush` component (`UnaryOperator<RoundEnvironmentSource>`, `TaskGit.java:49`)
      becomes a role interface of `app.port.git` that decorates a host round source over a given
      cell. A seam a spec fakes is a role interface (`process-invariants.md`, "Three clauses",
      clause 3). `MidRoundPushRounds` then takes the cell as a constructor argument instead of
      creating it.
    - `ManualRunAssembly.withHostGitPush(UnaryOperator<RoundEnvironmentSource> decoration)`
      (`ManualRunAssembly.java:195`) takes the per-run value too. `stageRounds` reads it for the
      snapshot decorator and for `ResumeVerificationStageExecutor`, as it reads
      `SandboxRunPieces.rounds()` and `pendingVerification()` for the container
      (`ExecutorAdapterSelector.java:100`).
    The container's per-run owner, `ContainerRunSupport` (`ContainerRunSupport.java:72`), is
    unchanged. The cell is constructed once per run, never attached after construction
    (`process-invariants.md`, "Immutable after construction"). One cell per run is what keeps
    the snapshot, the persistence and the resume on one round identity (crash-consistency item 14).
    *Rejected:* a second cell in the persistence, which would give one round two identities, and
    an attach-setter on `GitAttemptPersistence`, which "Immutable after construction" forbids.
- **The step.** One snapshot step with a medium strategy (D8). The container keeps
  `EnvironmentRoundSnapshot`'s trigger at the round close (`public EnvironmentRoundSnapshot(
  TaskExecutionEnvironment environment, GitRunner runner, Path cloneDir, CurrentRound rounds)`,
  `:68-69`; `public String snapshot(String taskId, String stage, int round)`, `:89`). The step
  takes the round's close outcome (below). The host strategy is invoked only after a `completed`
  result, so it always closes as verifiable.
- **The container's round close names its outcome (human decision 2026-11-12, review of D21/D22).**
  - *The defect.* `ProgramStageExecutor` closes the round, which takes the container snapshot,
    before it maps the program's result (`round.closeRound()` at `ProgramStageExecutor.java:66`,
    `ProgramResultMapping.toEngine` at `:70`). A round whose program answered `failed` is therefore
    registered under the snapshot subject. A kill before its state commit (a `quality` or `limit`
    failure), or before the park commit of its escalation (a `failed/infrastructure` result, which
    the engine shapes as `RoundOutcome.Unrecorded`, `RoundExecution.java:137`), resumes it through
    `ResumeVerificationStageExecutor` as `completed` (`ResumeVerificationStageExecutor.java:81-83`).
    The round is then verified, and on a pass the stage advances past a round its program called a
    failure. This is a silently wrong outcome (crash-consistency item 12: the snapshot implied an
    authorisation that only a `completed` result grants).
  - *The decision.* The port's `void closeRound()` (`RoundEnvironmentSource.java:72`) takes a
    two-valued close outcome, `VERIFIABLE` or `UNVERIFIABLE`.
    - An agent round closes `VERIFIABLE`. It reaches its close only after the agent exited
      (`ExecutorRoundExecution.java:132`), and its result is `completed` or a decision request
      that the snapshot carries (D17).
    - `ProgramStageExecutor` closes `VERIFIABLE` exactly when `ProgramResultMapping`, the one
      mapping, maps the contract result to a `completed` engine result. The judgement is the
      mapping's own, made before the close; the denials read after the close still join the
      mapped result.
    - The host round source ignores the argument: the D22 host trigger already acts on `completed`
      only.
    - The shared snapshot step names an `UNVERIFIABLE` close
      `gnomish: unverified-snapshot <stage>#<round> <token>`, rendered by
      `ServiceCommitMessages.unverifiedSnapshot` and admitted by `AdmissionRule.snapshot` with that
      subject. The subject does not start with `ServiceCommitMessages.SNAPSHOT_PREFIX`
      (`ServiceCommitMessages.java:34`), so `SnapshotTipCheck` returns empty for it
      (`SnapshotTipCheck.java:117-119`). It still falls in the `gnomish: ` service namespace, so a
      gnome commit carrying it is a violation (`GnomeRangeInspection.SERVICE_SUBJECT`, `:37`). A
      registered pending commit is never above it, because only a `completed` round is verified.
  - *Kill windows.*
    - K1 lies after the unverifiable close is registered and before the round's state commit (a
      `quality` or `limit` failure).
    - K2 lies after the unverifiable close and before the escalation's park commit (`Unrecorded`).
    - In both, the register shows the unverified snapshot. The envelope is the previous round's,
      so the shape is `InProgress`, "during the agent", and no shape is added.
    - The recovery owner is the ordinary resume: salvage, then a re-run of the round's stage
      executor at the same round number. No attempt is burned, because the lost failure was never
      recorded; the re-run earns its outcome again, which is a bounded cost.
    - A second pass is a no-op once the re-run's own close has landed.
    - Before this decision, both windows verified the round. The `ProgramStageExecutor` order
      itself stays: the close still comes before the mapped result is returned.
  - *Rejected:* taking the snapshot after the mapping, in place of the close. The state commit's
    parent check (`HarvestedStateCommitCheck.verifyParent`) needs the snapshot whatever the
    outcome, and the denials are read after the close. *Rejected:* deferring to a later change (the
    former Open Question). Task 4.2c rebuilds the container snapshot into the shared step, so the
    pickup condition was met by this change itself.
- **The host round close.** `public GitAttemptPersistence(GitRunner runner, Path worktreeRoot,
  String taskId, ClaimEpochSource epochs)` (`GitAttemptPersistence.java:99`) gains `CurrentRound
  rounds`, the run's cell (above).
  - The base of its round boundary is the round's last registered commit (`ClosedRound.registered`)
    when the round was snapshotted, and the tip the round opened on otherwise. It no longer comes
    from the adapter's own `previousTip` (field `:88`, set `:109` and `:143`, read `:130`), which
    would read the snapshot's `gnomish:` subject as a gnome violation. The field has a second
    consumer: the ancestry guard of `BestEffortPush.pushBestEffort(…, String previousTip)`
    (`BestEffortPush.java:93-125`, `:116`). That guard takes the same base, `ClosedRound.registered`
    or the opened-on tip.
  - The lag of the round's pending commits in the worktree's `state.json` is recognised and restored
    before the working-tree judgement (D21, "The lag the write leaves behind").
  - The round commit carries `state.json`, the trace and whatever verification left in the worktree,
    exactly as today's single commit does.
- **The resume.**
  - `SnapshotTipCheck` (`public SnapshotTipCheck(GitRunner runner, Path cloneDir)`, `:74`) runs over
    the host clone too.
  - `ResumeVerificationStageExecutor` (`public ResumeVerificationStageExecutor(StageExecutor delegate,
    CurrentRound rounds, @Nullable PendingVerification pending)`, `:56`) wraps the host stage chain
    with the host's cell. `static StageRounds stageRounds(ManualRunAssembly assembly,
    ChildEnvAllowlist childEnv, PipelineLaw law, DescribedLaw described, PipelineDefinition
    definition, CheckRunContext runContext)` (`ExecutorAdapterSelector.java:70-76`) takes the
    pending verification for both media.
  - A registered snapshot carrying the round's decision request is re-raised, not re-verified
    (`ResumeVerificationStageExecutor.java:81-83`; stable `executor/agent-executor` "Pending decision
    survives factory death"). Only the container produces one, because the host takes no snapshot
    of a decision round. FR19 and the `lifecycle/task-branch-contract` requirement say so.
  - The rule "a registered snapshot or pending commit is not salvaged over" moves out of `static
    @Nullable PendingVerification prepare(SandboxRunSupport support, boolean discardWork, Position
    position, String taskId)` (`ContainerResumePreparation.java:44-56`) into one owner. The two host
    resume paths call it too.
  - **The revocation stop calls the same owner (review of 2026-11-12).** A revocation met during a
    verification also salvages today: the host through `RevocationHandler.handle` →
    `worktreeSalvage.salvage(taskId)` (`RevocationHandler.java:75`, wired at
    `TakeEngineExecution.java:153`), the container through
    `ContainerRunSupport.revocationSalvageAndPush` (`ContainerRunSupport.java:308-311`).
    Above a registered snapshot or pending commit, that salvage would commit the killed checks'
    residue. The register would leave the snapshot, `SnapshotTipCheck` would return empty, and the
    next pickup would re-run the agent and `start` again over a persisted key. The FR19/FR20 duty
    would be lost silently.
    Both paths therefore ask the one owner. Over such a commit the host discards the residue and
    the container skips its salvage. Both still push, which carries the registered commits to
    origin. The stop note names the discard instead of a salvage commit.
  - On the host, the residue above such a commit is discarded (`WorktreeSalvage.discard`,
    `WorktreeSalvage.java:105-111`), with or without `--discard-work`: the gnome's work is in the
    snapshot, and the residue belongs to the killed verification.
  - The container leaves its residue in the box, as today: its state commit stages exactly two files.
- **Host windows.** Those of D21, plus "during the agent", whose register is the previous closing
  commit: salvage and re-run, as today.
- **SHA-scoped built-ins on the host** stay unsupported (NG12). The host now has an attempt commit
  before verification, but `RecordedAttemptCommitWorkspace` and `RemoteAttemptDelivery` are not
  wired for it (`RunAssembler.java:108`, `AttemptDelivery.assumedDelivered()`).

*Rationale:*
- One protocol on both media: the container's shape, recogniser and resume owner carry the host
  with no twin.
- Every host verification killed after its snapshot now resumes without re-running the agent or
  burning an attempt, not only a pending poll.
- The cost is one closing commit per completed host round: about 8–12 git processes, no push.

*Alternative rejected — a lazy snapshot, only when a check first answers `pending`:* host rounds
without a pending check would stay one commit. But it needs a host-only capture of the tree at
verification start, or the resume judges the residue of the checks before it (a silently wrong
verdict). It also gives the host two behaviours on a kill. The human chose the eager form on
2026-11-12. *Alternative rejected — a host copy of `SnapshotTipCheck` and
`ResumeVerificationStageExecutor`:* both read only the register and a subject; a twin in the same
module is the declared pair the rule of two forbids. *Alternative rejected — a snapshot at every
host round close (the container's trigger):* see the trigger above.

*Not narrowed:* the MODIFIED "Round commit of the whole working tree" restates its three scenarios.
"Host round atomicity" gains the new promise for a completed round: a snapshot commit with the
gnome's changes, then one round commit with `state.json` and the trace, and no state without the
snapshot below it. A round with no snapshot keeps the one-commit promise. The atomicity promise
each round had is kept as the named shape "interrupted verification" with its one owner.

*Amended 2026-11-12 (task 4.2c) — questions to the owner; human decision 2026-11-12.* Task 4.2 and
task 4.2c added four readers of an `ExecutionResult` status variant outside its one owner,
`ExecutorOutcomeClassifier` (design D10 of define-executor-contract, gate G4,
`OutcomeClassifierOwnerSpec`): `PendingPoll` and `CheckRoundAnswers` (`domain/engine`),
`SnapshottingStageExecutor` (`adapters/git`) and `ProgramResultMapping.closeOf` (`adapters/agent`).
Decision: the readers ask the owner. The classifier becomes public and gains queries named in the
table's own terms — `verifiable(result)` (the `Proceed` row: completed, no question), `pending(result)`
(the pending facts, as an `Optional`), and the package-private `failure(result)` — each the table
read once. `PendingPoll`, `CheckRoundAnswers` and `SnapshottingStageExecutor` call them (D9,
`ExecutorOutcomeClassifier` row). `ProgramResultMapping.closeOf(Role, ExecutorResult)` reads no
engine status at all: it decides from the contract result before the mapping — `completed`, and an
answer the role takes, judged by `ProgramCompletedAnswer.refusal`, the one judgement the mapping
refuses a completed answer by — so the close and the mapped status cannot disagree.
`:domain` is published by `:gnomish-plugin-api`; the japicmp baseline of the unreleased 0.15.0
takes the new public type. *Rejected:* a new exemption category in the gate (rows for
`SnapshottingStageExecutor` and `ProgramResultMapping` under a reason that names no pickup). The
gate's pickup-prefix rule exists so the list cannot grow without a named follow-up; a reader that
can ask the owner has no follow-up to name, and a second reading of the status is the split
failure-class table G4 prevents.

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
*D11 as amended 2026-11-14 (`/architect` Mode C before task 5.3):* where the run is retained. A
fresh-box `program` check gets a box per request (D18 as amended 2026-11-13: nothing bound outlives
its request): `SandboxCheckEnvironmentSource.freshBox` materializes it at the attempt commit
(`SandboxCheckEnvironmentSource.java:93`; the seed clone's `git reset --hard` under `set -e`,
`DockerSeedCloneCommand.java:88`) and disposes it on close (`:111-113`). The attempt commit is the
same on every request of the check, a resumed one included: `RecordedAttemptCommitWorkspace`
reads `rounds.closed().attemptCommit()` (`RecordedAttemptCommitWorkspace.java:42-43`), which a
pending commit does not move (it moves `registered`, `ClosedRound.java:27`) and a resume restores
from `PendingVerification.attemptCommit`. So the before-view `start` took and the view at the
terminal poll are views of two clean copies of one commit, and the adoption guard's expected
`HEAD` holds. The retention this decision requires therefore lives outside the box — in the
service the program drives, or in the key — which is no new demand: after a kill the adoption
guard (D14) accepts a key only over a clean copy, so a run kept in a box could not survive a resume
in any case, and the box per request makes the live path the resume path (the one-path choice of
D14 as amended 2026-11-13). The obligation is enforced, not stated only: the conformance kit's
pending-then-poll and re-poll cases poll from a working copy other than `start`'s (ADDED
`executor/conformance-kit` "Pending cases poll from a new working copy"; the poll copy's content
follows the case's role — for `verdict` and `decision` the content before `start`, as the fresh
box gives, for `transform` the files of `start`'s copy without its `.git`, as the pending round
commit gives (FR10 of `extract-agent-round-runner`); a candidate with several live roles is run in
the first, a known limit chosen by the human after the scoped D11 review of 2026-11-14 over running
the pending cases once per role; the obligation itself is stated in `executor/wire-contract` "A
pending key stays pollable until its run ends"), and
`verification/verdict-executors` ADDS "Each request of a fresh-box program check gets its own box
at the attempt commit". *Cost:* per request an image inspection, a git probe, a network and a
volume, a seed clone, a container start, the self-check and the teardown, beside one process spawn
— seconds against a fraction of one, at the engine's poll cadence (D19): bounded extra work, which
task 5.3 measures. *Alternative rejected — one box per check and attempt commit, reused across its
requests* (`LiveBox`, as `FreshJudgeEnvironments` does for judge votes): it cuts a poll to one
process, but without the kit's cold-copy rule a program keeping its run in the box passes every
live run and fails only after a kill, and the port names no end of a check to dispose the box by,
where a judge box ends with its stage. With the rule in the kit, reuse is a cache (Concourse reuses
its `check` containers on the same terms), deferred as proposal NG16 with the measurement as its
pickup. *Alternative rejected — a poll that needs no box:* the box is the program's egress and
credential cage; a poll outside it would run the program on the host unguarded.

**D12 — A changed key is persisted before the next poll, by the one owner.** (FR10, NFR-R4;
human decision 2026-11-02, closing Q3 of `extract-agent-round-runner`.) When a `poll` returns
`pending` with a key other than the recorded one, `PendingPoll` writes the new key into the
record in one commit with its register, pushed, before the next poll — the same write as the
first key's (D2), so no second persistence path exists. The rule is subject-neutral, so
`extract-agent-round-runner` adds only its `Round` subject. *D12 as amended 2026-11-12 (D21):*
"one commit with its register" is the next pending commit, written by `persistPending` on the
previous pending commit, with the check's entry carrying the new key; the other entries of the
list are carried unchanged. *Alternative rejected — keep polling
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
- [A host round now lands two factory commits (snapshot, round commit), and a pending check adds
  one per key (D21, D22)] → accepted (human decision 2026-11-12): about 8–12 git processes per
  completed host round, no push of its own; the container already pays the same shape.
- [Checks before the pending one re-run on a resume (W0–W4)] → bounded: they judge the same
  registered snapshot; only the pending checks are continued from their entries.
- [A failed pending-commit push leaves another instance in W1 (W1′)] → one duplicate `start`,
  visible in the ledger, as ADR 0003 ("durability is the push") accepts for every closing write.
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
- [A GitHub request has no timeout today and a hung read holds a slot] → closed by the move,
  body included: the guarded exchange sends asynchronously with a bounded body subscriber and
  bounds the whole exchange by the consumer's timeout (D16 as amended 2026-11-09, item 8 of the
  second review); the JDK's request timeout alone ends only the wait for headers, so the
  stalled-body case has its own scenario and is measured on macOS and Linux by the task.
- [A redirect turns a tracker `DELETE` or `PATCH` into a `GET` and the `2xx` reads as success]
  → the hop rule is the JDK's `RedirectFilter` rule: only a `POST` changes method on `301`/`302`
  (D16, item 5 of the second review); a spec asserts a `DELETE` answered by a `301` stays a
  `DELETE`.
- [An egress refusal is retried and reported as an outage] → the GitHub core classifies the
  refusal apart from transport (D16 as amended, NFR-R5); a law-medium host without a grant and
  a literal blocked address are judged at load with no lookup, while an unresolvable host is an
  infrastructure failure at the call, never a configuration refusal.
- [Core grows a vendor integration that is costly to extract later (Jenkins' detached plugins)]
  → accepted with the pickup condition of D4's external-program alternative recorded; the
  library of D15 keeps the integration in one place to move.
- [Removing the check SPI breaks an unknown third party] → none registered in
  `docs/community-plugins.md`; the api's version takes the one MINOR bump its pre-1.0 semver
  requirement prescribes for a break (0.14.0 → 0.15.0).
- [`expected` lists go stale as workflows are renamed] → located error at load is impossible
  (names live in CI); the timeout finding names the absent contexts (UX1); a stale entry is
  `unreported` at the timeout and resolves as cannot-verify, never burning an attempt (D19).
- [A rate-limited GitHub read is retried into a ban] → the core retries only a pause of at most
  8 s and returns a longer one at once; `scm-status` turns it into a wait hint (D19).

## Migration Plan

1. After `define-executor-contract`, `add-command-executor`, `remove-interactive-console`.
2. Land built-ins and `PendingPoll` behind the four tokens; existing manifests unchanged.
3. Remove the check SPI under `gnomish-plugin-api`'s one MINOR bump (0.15.0); rewrite the sample.
4. Rollback: additive registry entries; the SPI removal is the only irreversible step and is
   last.

## Open Questions

- Threshold vocabulary (proposal Q1) — settled in the spec as `error | warning | note`;
  numeric minimums deferred.
- Record-driven poll of a `program` executor (proposal Q3) — settled by D14.
- Where a contract result's details travel (task 4.2f) — settled by D19: SARIF
  `properties.details` and a result-level `details`.
- Salvage of a killed check program's writes — settled by D22 (2026-11-12): verification now
  runs only above a registered snapshot on both media, and a resume that finds one discards the
  host worktree's residue instead of salvaging it (the container's state commit never stages it).
  `WorktreeSalvage` still commits the leftovers of a round killed before its snapshot, which are
  the gnome's work, as before.
- A program round killed between its round close and its state commit, on the container medium,
  was resumed as `completed` and verified. Settled by D22 (human decision 2026-11-12, "The
  container's round close names its outcome"): a round whose executor did not complete closes
  under the unverified-snapshot subject, which the recogniser does not take, and task 4.2c
  carries the fix.
