# Proposal: migrate-verify-to-executor-contract

## Why

After `define-executor-contract` the engine speaks one executor contract for stages, but its
three non-LLM verify checks still speak three ports (`BuiltinCheckRunner`,
`CommandCheckRunner`, `ExternalCheckClient`) with their own verdict construction, and external
verifiers still attach as in-process Java plugins through `CheckClientFactory` — the one
place where the mixed plugin model is violated. Every SonarQube-like verifier (run in the
box, then poll a server) is impossible to express, the GitHub poller is bundled with the
tracker although its real sibling is the git remote, and absence of a CI status is
indistinguishable from "not started yet". This change makes verification the second consumer
of the contract: checks are executors in role `verdict`, with `pending + key` polling owned
by the engine and persisted on the branch.

## What Changes

- **ADDED**: verify checks are executors in role `verdict`; a check names its executor like a
  stage does. `builtin`, `command` and `external` are kept as manifest sugar mapping to
  built-in executor names, so existing pipelines load unchanged.
- **ADDED**: built-in verdict executors `files-exist`, `shell` (the former command check,
  findings file accepted as legacy format and stored as SARIF), `github-actions` and `http`
  (the former check providers, now built-ins over the contract with `poll`), and the new
  **`scm-status`** reader of named verdicts on the attempt SHA (GitHub Checks + Statuses,
  Gitea statuses) with an expected-contexts list.
- **ADDED**: engine-owned `pending + key` polling for any verdict executor declaring `poll`:
  the key is persisted in the task-branch state before the first poll; any instance resumes
  the poll; the check's `interval` is passed as the executor's in-poll wait bound; a key a `poll`
  replaces is persisted before the next poll (human decision 2026-11-02).
- **ADDED** (wire contract, human decision 2026-11-02): an executor declaring `poll` keeps every
  key it issued pollable until its run ends, and a poll of an ended run's key returns its terminal
  result again; the conformance kit gains a re-poll case, the reference executor and the
  misbehaving fake follow it.
- **MODIFIED** (wire contract): "Poll of a program is refused without its preconditions" and "A
  pending judge is judged when it ends" — a key the task's pending record names is adopted by the
  resuming instance's program executor, so a persisted key resumes a program's poll (Q3, design
  D14).
- **ADDED**: verdict derivation from SARIF level against the check's threshold in one engine
  component; the exemptions of `define-executor-contract` D10 for check-side verdict
  construction expire here.
- **REMOVED / BREAKING**: `CheckClientFactory`, `CheckParamsValidator`,
  `CheckSubsectionValidator`, `ExternalCheckPinContributor` leave `gnomish-plugin-api`; the
  sample plugin becomes a sample external verdict program. No third-party implementation
  exists.
- **MODIFIED**: `ExternalCheckClient`, `CommandCheckRunner`, `BuiltinCheckRunner` ports are
  removed from `EnginePorts`; `judge` keeps `JudgeVoter` until `extract-agent-round-runner`.
- **MODIFIED**: a verdict source scoped by branch (code scanning, Sonar) must pin the attempt
  SHA; `absent after timeout` of an expected context is an infrastructure failure.
- **MODIFIED** (human decision 2026-11-06, escalation of task #121): `github-actions` is a
  core-side built-in, not a provider of the github vendor bundle; the bundle keeps the tracker
  only, and the `plugin/github-plugin` and `plugin/plugin-discovery` promises are restated for
  the tracker port. The GitHub HTTP core the tracker and the built-in share leaves the bundle
  for a first-party library module, and the HTTP egress allowlist leaves `:adapters` for a leaf
  both HTTP built-ins and that library use (rule of two, `manual-sync-pairs.md`).
- **ADDED** (wire contract): a describe field by which an executor declares that a check's
  `checkId` names a repository file to pin, so the pin set stays computable from a static
  describe and the law.

## Goals

- G1: one dispatch, one result shape and one classifier for stages and checks; `EnginePorts`
  shrinks from five executor-ish ports to two (`StageExecutor`, `JudgeVoter`).
- G2: a SonarQube-class verifier (run in box, poll server) is expressible as one external
  program with `poll`, without any engine change.
- G3: any CI that publishes statuses to the SCM is covered by the `scm-status` built-in with no
  per-CI code.
- G4: a factory restart during a long poll resumes the poll from the persisted key on any
  instance, without re-submitting.

## Non-Goals

- NG1: migrating `judge` — it moves with the agent dialect in `extract-agent-round-runner`.
- NG2: a `severity: blocking | advisory` attribute on checks — `harden-verification-integrity`.
- NG3: sealing verification assets against the gnome-editable tree — same follow-up.
- NG4: webhooks or a receiver companion; `poll` stays the only observation path.
- NG5: `pending` for role `transform` (long agent rounds across restarts) — the engine wiring
  lands here for `verdict`; the transform role is taken up by `extract-agent-round-runner`
  (FR10–FR12, design D11), which reuses this change's `PendingPoll` and `PendingPollRecord`
  (adding the record subject `Round` beside the check), this change's key rotation (FR10) and
  wire obligation (FR11), and relies on task 4.3a for a `program` poll resumable by another
  instance (human decisions 2026-11-02).
- NG6: changing the manifest grammar beyond the executor-name form; the four type tokens stay.

## Users & Scenarios

- U1 — Pipeline author with GitHub Actions: declares `scm-status` with the expected check
  names; a workflow that never reports fails the check as infrastructure after the timeout
  instead of hanging forever.
- U2 — Pipeline author with SonarQube: ships a `sonarqube` verdict program in the image; its
  `start` runs the scanner and returns `pending` with the analysis id; the engine polls.
- U3 — Operator: a factory restart mid-poll shows the resumed poll in the ledger with the
  same key; no duplicate scan appears on the server.
- U4 — Plugin author: the sample verdict program and the conformance kit replace compiling a
  Java factory against `gnomish-plugin-api`.

## Requirements

### Functional

- FR1: A verify check SHALL resolve to a named executor in role `verdict`; the manifest tokens
  `builtin`, `command`, `external` map to built-in names (`files-exist`, `shell`, the
  provider name) and an explicit `executor:` form is accepted beside them.
- FR2: The engine SHALL run every verdict check through the contract: `start`, then on
  `pending` persist the key with the check identity and attempt in the task-branch state,
  then `poll(key)` at the check's `interval` until a verdict or the `timeout`, classifying
  the timeout by the check's declared timeout class.
- FR3: Resume SHALL continue a persisted poll with its key and SHALL NOT call `start` again;
  a persisted key whose executor no longer declares `poll` is an infrastructure failure.
- FR4: The `scm-status` built-in SHALL read named verdicts on the attempt SHA from the
  configured SCM (GitHub Checks and Commit Statuses, Gitea statuses), require an
  `expected` contexts list, return `pending` while any expected context is absent or
  running, and treat duplicate same-named contexts as `latest per context`.
- FR5: The `shell` built-in SHALL run the declared command through the environment seam with
  the existing bounds and accept both SARIF and the legacy `{"findings":[...]}` file,
  normalizing to SARIF; its exit code is reported as status, never as a verdict.
- FR6: The `github-actions` and `http` built-ins SHALL preserve their current observable
  behavior (workflow-run lookup by commit with job-log findings; status-code probe with
  conditions) as contract executors with `poll`, including the pin of the workflow file.
- FR7: Verdict pass/fail SHALL be derived in one engine component from SARIF levels against
  the check's threshold (default `error`); executors SHALL construct no verdict; a pass SHALL
  carry the results below the threshold as its findings, recorded like a failure's.
- FR8: `CheckClientFactory` and its companion types SHALL be removed from
  `gnomish-plugin-api`; the api's sample module SHALL ship a verdict program that passes the
  conformance kit instead of a Java factory.
- FR9: Verdict sources scoped by branch SHALL verify the reported `commit_sha` equals the
  attempt SHA; a mismatch is `pending` until the timeout, then infrastructure.
- FR10: A `poll` that returns `pending` with a key different from the recorded one SHALL have
  the new key persisted in the pending record, in one commit with its register, pushed, before
  the next `poll`, for every subject the pending-poll owner serves (human decision 2026-11-02;
  `extract-agent-round-runner` reuses it for a `transform` round, its FR12).
- FR11: The wire contract SHALL require of an executor declaring `poll` that every key it issued
  for a run (from `start` or from a `poll`) stays pollable until the run ends, and that a `poll`
  of an ended run's key returns that run's terminal status and answer again, never a new run
  (human decision 2026-11-02).
- FR12: The conformance kit SHALL carry a re-poll case pinning FR11 for any candidate declaring
  `poll`; the reference executor SHALL pass it and the misbehaving fake SHALL have a preset that
  breaks it.
- FR13: The `github-actions` built-in SHALL be compiled into the factory outside the github
  vendor bundle and registered by core like every built-in; the vendor bundle SHALL carry the
  github tracker only, and removing it SHALL disable the github tracker with no core source
  change while `github-actions` and `scm-status` stay available.
- FR14: The GitHub HTTP core (client, credential header, conditional-request cache, rate-limit
  classification, retry, exceptions) SHALL exist once, in a first-party library module that is
  not `gnomish-plugin-api`, consumed by the vendor bundle's tracker and by the core-side
  built-ins; no second GitHub HTTP client SHALL exist in the build.
- FR15: The HTTP egress allowlist and the guarded exchange SHALL exist once, in a leaf module,
  and every in-process HTTP call of a built-in verdict executor — `http`, `github-actions`,
  `scm-status` — SHALL go through it.
- FR16: An executor's describe MAY declare that a check's `checkId` names a repository file to
  pin; the pin-check guard SHALL union that file with the law-declared pin paths. The
  `github-actions` built-in SHALL declare it, so the workflow file stays pinned with no manifest
  edit.

### Non-Functional Reliability

- NFR-R1: Persisting the pending key is one commit with the attempt's state and lands before
  the first poll; a kill after `start` but before that commit re-drives `start` on resume
  (a verdict `start` is a submission keyed by the server, so a duplicate is observable, not
  harmful), and recovery of a persisted key never re-submits.
- NFR-R2: Polling backs off on SCM rate-limit responses and prefers the combined-status call.
- NFR-R3: Every wire enum introduced (`timeoutClass`, verdict threshold) has a round-trip spec.
- NFR-R4: A kill after a `poll` returned a new key and before it was persisted, and a kill after
  a terminal `poll` and before the verdict was recorded, each resume by polling the persisted key
  with no second `start` (FR10, FR11; design D7 windows W3, W4); kill-point specs cover both,
  each resumed by a second instance.

### Non-Functional Observability

- NFR-O1: Each poll logs executor, key, elapsed and remaining time under the task's MDC; the
  ledger line of a check carries `executor=<name> status=<status>` like a stage round.

### Non-Functional Security

- NFR-S1: A `program` verdict executor declaring credentials SHALL run in `FRESH_BOX` with its
  own egress allowlist derived from its describe; a `SAME_BOX` declaration on such a check is a
  located load error. A built-in verdict executor declaring credentials runs in the factory
  process under the in-process egress allowlist of its declared hosts (FR15); its token enters
  no box, round or fresh.
- NFR-S2: `scm-status` and `github-actions` resolve their token through the secrets port by the
  credential name of the connection the operator configures for the executor
  (`factory.check.<name>`, inline or by a `factory.connections` profile), never through the
  tracker's subsection.

### Non-Functional Cost

- NFR-C1: A verdict executor reports usage with provenance; a built-in that mediates no model
  reports `REPORTED` with an empty per-model map and declares `reportsUsage = true` in its
  describe (design D8 of `add-command-executor`, adopted as D13), so the task's cost stays
  complete and the ledger renders no per-model zero.

## Operator Experience Criteria

- UX1: An `scm-status` check that times out names every expected context still absent.
- UX2: Existing pipelines with `builtin`/`command`/`external` checks load with no manifest edit.
- UX3: The ledger shows a resumed poll as `resumed key=<prefix>` on the instance that took it.

## Success Metrics

- M1: `EnginePorts` holds exactly two executor-facing ports after the change.
- M2: Zero `new Verdict.` constructions outside the one engine derivation component (grep).
- M3: The SonarQube-shaped reference verdict program (fake server) passes end to end with a
  kill between `start` and the first poll, resumed by a second instance.
- M4: `gnomish-plugin-api` contains no check SPI type (grep).
- M5: `adapters/github` holds no check or verdict code and no HTTP core; with its service
  registrations hidden the factory starts, the github tracker is absent, `github-actions` and
  `scm-status` resolve (the narrowed `GithubPluginAbsenceSpec`).
- M6: exactly one GitHub HTTP client class and one egress allowlist class exist in the build
  (grep).

## Open Questions

- Q1: Threshold vocabulary per check (`error` | `warning` | `note`) or a numeric minimum count.
- Q2: Whether `github-actions` should be folded into `scm-status` once job-log findings can be
  fetched from the check-run output.
- Q3: How task 4.3a's record-driven poll of a `program` executor reaches the program for a key
  this instance never issued, a superseded key and an ended run's key (FR3, FR11) — settled by
  design D14: the resuming instance's program executor adopts the recorded key, only over an
  unchanged working copy; the
  `executor/wire-contract` delta MODIFIES "Poll of a program is refused without its
  preconditions" and "A pending judge is judged when it ends", scenario titles kept.

## Capabilities

### New Capabilities
- `verification/verdict-executors`: built-in verdict executors, their describe, the SCM-status
  reader semantics, legacy findings normalization.
- `verification/pending-poll`: engine-owned pending/poll lifecycle, persistence of the key,
  resume semantics, timeout classification.

### Modified Capabilities
- `stage-engine`: the external poll loop generalizes to any verdict executor; verdict derived
  from SARIF; check ports removed.
- `verification/check-provider-model`: provider SPI replaced by executor names; params
  validated by describe schema.
- `plugin/plugin-api-contract`: check SPI removed; sample proves the verdict-program path.
- `executor/wire-contract` (added by `define-executor-contract`): ADDED "A pending key stays
  pollable until its run ends" and "A recorded key is adopted only over an unchanged working
  copy"; MODIFIED "Poll of a program is refused without its
  preconditions" and "A pending judge is judged when it ends" (D14).
- `executor/conformance-kit` (added by `define-executor-contract`): ADDED "Re-poll case pins the
  idempotent poll".
- `pipeline-config`: check tokens map to executor names; `expected` contexts and threshold;
  MODIFIED "Stage model mirrors the stage contract and all verify-check types" (executor check +
  `judge`).
- `verification/http-check-provider`: MODIFIED — `http` is a built-in verdict executor resolved
  from the executor registry.
- `verification/github-external-check`: MODIFIED "Polling is stateless and takeover-safe" — the
  persisted pending key is the one poll state; the attempt commit comes with the request.
- `verification/verification-hardening`: MODIFIED "Pin-check guards external checks" — the guard
  is a pin decorator over the check's verdict executor, before its first `start`; the executor's
  contribution is the `checkId` when its describe declares it (FR16).
- `verification/github-external-check`: MODIFIED "The adapter contributes its workflow file to
  the pin set" (through the describe flag) and "Adapter is constructed from factory
  configuration" (a core built-in connected by `factory.check.github-actions`).
- `plugin/github-plugin`: MODIFIED, ADDED — the bundle is the tracker; the shared HTTP core is a
  first-party library, not the api and not private to the bundle; core's built-ins do not depend
  on the bundle (FR13, FR14).
- `plugin/plugin-discovery`: MODIFIED, ADDED — ServiceLoader discovery and the four-point
  criterion are stated for the tracker port; the check port is outside the criterion (FR13).
- `plugin/plugin-api-contract`: MODIFIED "The first-party vendor bundle satisfies the same
  constraint" — the bundle may depend on the GitHub library beside the api (FR14).
- `pipeline-config`: MODIFIED "Local sanity validation of mechanism and check configs" — the
  unknown-provider and defaulted-provider scenarios are stated against the executor registry.
- `executor/wire-contract`: ADDED — the describe field of FR16.
- `cli/manual-run`: REMOVED "Command check runner" and "Findings-JSON wire format" — the shell
  check's exit code is status, never a verdict (FR5, design D5 as amended 2026-11-08); their kept
  duties are ADDED to `verification/verdict-executors` in the derived form ("The shell executor
  runs the command in the task environment", "The shell executor reads its findings path
  whatever the exit code"), and task 3.2 moves the eight scenario markers.

## Impact

- `domain/engine`: `VerifyOrchestrator`, `ExternalPolling`, `EnginePorts`; new `VerdictDerivation`,
  `PendingPollRecord` in state. `adapters/check` becomes the `http` built-in; `adapters/github/check`
  moves out of the bundle into the new core-side module `adapters/scm` with `scm-status`.
- New modules (design D15, D16): the leaf `:httpegress` (from `:adapters`'s `adapter.check.http`
  egress classes) and the library `:githubapi` (from the bundle's `adapter.github` package); the
  bundle's layering allowlist gains `:githubapi`; `add-sandbox-gha-executor` is the library's
  third consumer and adds no client of its own.
- `gnomish-plugin-api`: four types removed, sample rewritten. `bootstrap`: `CheckClientDiscovery`,
  `CheckProviderSeam`, `ProviderDispatchingExternalCheckClient` deleted; `GithubPluginAbsenceSpec`
  narrowed to the bundle's packages.
- ADR 0020 amended: "built-in executor" defined as compiled into and named by core, the
  distribution-is-not-mechanism rule stated for tracker plugins only, and the "in-process egress
  control" premise of its rejected alternative corrected (the GitHub client had no guard before
  FR15).
- Sequencing: after `define-executor-contract` and `add-command-executor` (shared command core);
  before `extract-agent-round-runner`; the `executor/wire-contract` and `executor/conformance-kit`
  deltas ADD requirements to capabilities `define-executor-contract` adds, so that change archives
  first; the `executor/wire-contract` delta also MODIFIES two of its requirements ("Poll of a
  program is refused without its preconditions", "A pending judge is judged when it ends"), none
  of which `extract-agent-round-runner` modifies, so its MODIFIED "Closed status taxonomy" still
  layers on `define-executor-contract`'s text alone; `bootstrap` `KitCase` and the stand-in
  `executor-run.sh` (a declared pair) gain the re-poll case at both ends; `remove-interactive-console` removes the interactive
  check client independently (its delta on the pin-check requirement precedes ours).
- Sequenced after `tighten-spec-contract`: its deltas carry the `**Implements**:` lines and the
  500-character requirement bodies that change introduces; it has no MODIFIED block to layer, and
  the markers and exemption lines of the scenarios its REMOVED requirements take with them are
  removed in this change (tasks.md, section 6).
