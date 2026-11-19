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
  the poll; the poll is paced by the larger of the check's `interval` and the executor's wait hint
  (no in-poll wait bound travels, NG13); a key a `poll`
  replaces is persisted before the next poll (human decision 2026-11-02).
- **MODIFIED** (human decisions 2026-11-12, blocker of task 4.2): the key lands as an entry of the
  open round's list in the task state, one per pending check, each with the round token, by a
  **pending commit** on the round's registered snapshot; a git-mode host round closes the gnome's
  work in a snapshot commit before verification, as a container round does, and one recogniser and
  one resume continue an interrupted verification on both media without re-running the round
  (design D21, D22; `git/git-task-persistence`, `lifecycle/task-branch-contract`).
- **ADDED** (wire contract, human decision 2026-11-02): an executor declaring `poll` keeps every
  key it issued pollable until its run ends, and a poll of an ended run's key returns its terminal
  result again; the conformance kit gains a re-poll case, the reference executor and the
  misbehaving fake follow it.
- **MODIFIED** (wire contract): "Poll of a program is refused without its preconditions" and "A
  pending judge is judged when it ends" — a key the task's pending record names is adopted by the
  resuming instance's program executor, so a persisted key resumes a program's poll, and a live
  key is polled from the run's table of the program's pending runs, which every binding of the
  program's entry shares (Q3, design D14).
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
- **MODIFIED** (human decisions 2026-11-08 and 2026-11-09, escalations of task 3.0a of #121):
  egress admission follows the **medium** a URL comes from. A host an operator file names is
  granted; a host the law tree names for an in-process call (a manifest target, an inline
  tracker `api-url`) is a request, admitted only by an operator grant or the adapter's vendor
  default; one entry grammar and one operator block `factory.egress` replace the two allowlist
  keys; a redirect target is judged against the same admitted set; the address class of every
  hop is judged at connect; the body bound, timeout and refusal key are the consumer's; a
  refusal is a configuration fault, never a retried outage (`verification/factory-egress-allowlist`,
  design D16 as amended). The `verification/verdict-executors` scenario "A built-in's HTTP call
  outside its declared hosts is refused" is restated against that admitted set.
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
- NG7: pinning the address the egress guard judged to the socket the JDK client opens (the
  name is resolved twice, by the guard and by the client; a rebinding in between is the known
  window). `java.net.http.HttpClient` exposes no resolver hook. Pickup condition: the change
  that replaces the JDK client in the egress leaf with one exposing the connect address, or that
  routes the factory's own calls through a proxy, closes the window or re-defers it. The window
  is named beside the decision in design D16 as the residual risk of admitting a host by name.
- NG8: pinning a port in an egress entry (`host:port`). The box guard admits by host and no
  consumer needs a port today; the grammar leaves the form unparsed. Pickup condition: a
  consumer whose connection must be bounded to one port of a host, or the first operator report
  of a shared host where only one port is the operator's.
- NG9: a daemon condition for an egress refusal of a **verdict built-in's** operator connection
  (`github-actions`, `scm-status`). The check's refusal stays `failed/infrastructure` naming the
  key, and the stage escalates "cannot verify" per task, as for any unavailable check; the tracker
  is the only consumer whose refusal opens `EGRESS` (FR18). Pickup condition: the change that lets
  a verdict executor's failure end a claimed run as a typed operator-precondition result, or the
  first operator report of several tasks escalated by one refused check connection.
- NG10: renaming the banned synonym `whitelist` in the heading of the stable
  `verification/factory-egress-allowlist` requirement "Interpolation into http requests is
  restricted to a whitelist" and its two scenario titles (re-deferred from NG8 of
  `fix-envelope-medium`, whose pickup condition this change meets by carrying a delta of the
  capability). A heading and scenario titles cannot be renamed through MODIFIED (`delta-specs.md`);
  the Purpose this change restates at archive drops the word (task 7.3). Pickup condition: a change
  that REMOVES that requirement, followed by the next change re-ADDing it under an allowlist
  heading, or any change that already REMOVES or re-ADDs it.

- NG11: the `github-actions` "no workflow run on this SHA" case as an `unreported` pending state,
  and its rate limit as a wait hint. Today a missing run is a running poll that times out by the
  declared class, and a rate limit is `failed/infrastructure` at once; FR6 keeps both unchanged,
  and D19 gives the two fields to `scm-status` only (human decision 2026-11-10). Pickup
  condition: the change that decides Q2 (folding `github-actions` into `scm-status`), or the
  first change to `github-actions`' timeout or rate-limit handling.
- NG12: SHA-scoped verdict built-ins (`scm-status`, `github-actions`) in host mode. The host
  gains an attempt commit before verification (D22), but its checks keep the plain worktree and
  `AttemptDelivery.assumedDelivered()`, so these built-ins stay infrastructure failures on the host
  as today (human decision 2026-11-12). Pickup condition: the first pipeline that needs an
  SHA-scoped check in host mode.
- NG13: an in-poll wait bound on the wire (the executor blocking inside one `poll` up to a bound
  the factory names). The promise "the check's `interval` is passed as the executor's in-poll wait
  bound" is withdrawn (human decision 2026-11-13, `/architect` Mode C of task 4.3a): the request's
  `Bounds` has no field for it, no executor waits inside a poll, and passing the interval as a wait
  would double the cadence (the executor waits N, the engine waits N again). Pacing stays with the
  engine, `max(interval, pollAfter)` clamped to the deadline (D19). Pickup condition: the first
  executor whose backend supports a blocking wait (a `WaitOperation`-like call), or a check whose
  per-poll cost (a process spawn, a rate limit) the `pollAfter` hint cannot bring down; the field
  then follows `google.longrunning` `WaitOperation.timeout`: optional, distinct from the run's
  deadline, clamped to the remaining check deadline, absent meaning no wait. The withdrawal reaches
  the round poll of `extract-agent-round-runner` too, whose design repeats the withdrawn phrase
  ("passing it as the in-poll wait bound", its D11); that change's owner corrects it at its next
  update, since one change does not edit another's artifacts.
- NG14: a `program` verdict check bound on the host or in the round's box (`same-box`). This change
  binds a `program` check only through the fresh box (task 5.3; design D18, "a program entry binds
  for a check through the fresh box"), and no task binds one in the task's worktree or the leased
  round box, so the second-instance kill-point row of a `program` check (W2–W4) runs on the
  container medium only (task 5.3, over 4.3b's matrix), and the adoption over the pending commit's
  `state.json` lag (design D14 as amended 2026-11-13) is pinned at the adapter
  (`ProgramPollSpec`), not on a host flow. Pickup condition: the first change that gives a
  `program` registry entry a check binding outside the fresh box; it brings the host or same-box
  half of that kill-point row, including a resume on the same clone over the lag. Until then the
  operator sees a refusal, not a crash: a law whose check names a `program` executor that would run
  outside a fresh box (host mode, or the `same-box` default) is refused at run assembly as a
  located `ConfigError` naming the check and the executor, parked in `take`/`serve` and a located
  exit in `run` (task 5.3; `verification/verdict-executors` "A program verdict check outside a
  fresh box is a located error"). The same pickup reopens the attempt commit on the wire: a
  verdict program reads the commit under verification as `HEAD` of its working copy, which only a
  fresh box guarantees (materialized at the attempt commit, `git reset --hard` under `set -e`), and
  design D18 rejects an attempt-commit request field while that holds; on the host or in the round
  box `HEAD` is the registered or a pending commit, so the change that binds a program check there
  decides whether the request names the commit (`/architect` Mode C, 2026-11-14).
- NG15: an echo of the judged commit in a verdict result, compared by the factory with the commit
  it materialized (Concourse's `in` emitting the fetched version, Tekton `git-clone`'s `commit`
  result). Reading `HEAD` keeps one source of the commit, so the drift those echoes guard against
  (an explicit value and a checkout that disagree) cannot arise here; what an echo would still catch
  is a program that judges something other than its working copy. Pickup condition: the first
  verdict program that judges the commit through an outside service rather than its working copy,
  or a reported case of a verdict judged against another commit; the echo is then an optional
  result field, compared and refused as `failed/infrastructure` on a mismatch.
- NG16: reusing a fresh box across the requests of one pending check, a warm pool of boxes, or a
  clone cache. Each `start` and each `poll` of a fresh-box `program` check materializes its own box
  and disposes it (design D11 as amended 2026-11-14); the cost per request — image inspection, git
  probe, network and volume, seed clone, container start, self-check, teardown — is paid beside one
  process spawn, at the engine's poll cadence (D19). Pickup condition: the measurement of task 5.3
  showing the box's cost comparable to the check's poll interval. Reuse then follows Concourse's
  rule for its `check` containers: correctness holds from a cold box, which the conformance kit
  already enforces (the pending cases poll from a new working copy), and the reused box is only a
  cache with a lifetime bound and the adoption guard's cleanliness check on every request.

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
  then `poll(key)` no sooner than the larger of the check's `interval` and the last pending
  answer's wait hint until a verdict or the `timeout`, classifying the timeout by the check's
  declared timeout class unless the last pending answer's state says no verdict could be
  obtained (design D19).
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
- FR15: The HTTP egress guard — the allowlist and the guarded exchange — SHALL exist once, in a
  leaf module, and every in-process HTTP call of the factory with a credential attached — the
  `http`, `github-actions` and `scm-status` built-ins and the GitHub tracker — SHALL go through
  it, with the address-class and redirect rules applied to every hop whoever authored the URL.
- FR17 (human decisions 2026-11-08 and 2026-11-09 on the escalation of task 3.0a of #121): a
  host is admitted by the medium its URL comes from. A host an operator file names — a
  `factory.check.<name>` subsection, a `factory.connections.<name>` profile, the operator egress
  block — is granted; a host the law tree names for an in-process call — a stage manifest's http
  target, an inline `tracker.github.api-url` — is a request, admitted only by an operator grant or
  by the vendor default the adapter declares, and a request nobody grants SHALL be a located load
  error naming the ways to grant it. A law's `executors.<name>.egress` stays the law's grant to its
  own box, bounded by the describe (D10 of `define-executor-contract`). The medium SHALL travel as
  a typed value from where the configuration is read to the client, which SHALL take no bare URL.
  One entry grammar SHALL serve every egress list (the `sandbox` scope and box egress accept its
  name-only subset); one operator block, `factory.egress` with the scopes `sandbox` and
  `in-process`, SHALL replace `factory.sandbox.egress-allowlist` and `factory.check.http.allowlist`
  as removed keys; a connection MAY carry its own `egress` list. The address-class judgement SHALL
  run at connect on the resolved addresses of every hop whoever authored the URL, waived only by an
  operator entry naming the class or the literal address, and only for the admitted set the entry
  is listed in, never for a cloud-metadata address; load SHALL resolve no name, classifying only
  IP literals and `localhost` statically. A law-medium connection SHALL be `https` unless its host
  is statically loopback and granted by `loopback`. A redirect target SHALL be judged as an egress
  target against the same admitted set, with no separate redirect-host list; a `301`/`302` hop
  SHALL change only a `POST` into a `GET`. The response bound, the request timeout — bounding
  headers and body together — and the configuration key a refusal names SHALL be the calling
  consumer's.
- FR16: An executor's describe MAY declare that a check's `checkId` names a repository file to
  pin; the pin-check guard SHALL union that file with the law-declared pin paths. The
  `github-actions` built-in SHALL declare it, so the workflow file stays pinned with no manifest
  edit.
- FR19 (human decision 2026-11-12): a git-mode host round whose stage executor completed SHALL
  close the gnome's work in a registered snapshot commit before verification, as a container round
  does, and a pickup that finds a registered snapshot or pending commit SHALL re-verify that round
  on either medium without running its stage executor, without burning an attempt and without
  salvaging over it — unless the snapshot carries the round's decision request, which is re-raised
  as today. A revocation stop SHALL not salvage over such a commit either. A sandboxed round whose
  stage executor did not complete SHALL close under a subject no pickup takes for an interrupted
  verification (human decision 2026-11-12, design D22).
- FR20 (human decision 2026-11-12): the pending record SHALL be a fact of the open round, not of
  an attempt: one entry per check of the round that went `pending`, each naming the round's token,
  kept until the round's closing commit; a pickup SHALL continue only entries whose attempt and
  token name the round it resumes.
- FR18 (human decision 2026-11-09, third review of D16): a refusal of the egress guard met by a
  tracker call SHALL be an operator precondition of the daemon (ADR 0019): at `serve` startup it
  SHALL refuse the daemon; met by the `serve` feed it SHALL open a daemon condition and SHALL not be
  retried; met by a claimed run it SHALL end the run on the typed operator-precondition result,
  release the claim with no abort charge, and under `serve` open the same condition, whose probe
  repeats the endpoint's connect-time judgement. An unresolvable host and an exchange limit SHALL
  not be refusals.

### Non-Functional Reliability

- NFR-R1: Persisting the pending key is one pending commit with the open round's state (design
  D21) and lands before the first poll; a kill after `start` but before that commit re-drives `start` on resume
  (a verdict `start` is a submission keyed by the server, so a duplicate is observable, not
  harmful), and recovery of a persisted key never re-submits.
- NFR-R2: Polling backs off on SCM rate-limit responses and prefers the combined-status call:
  the engine polls no sooner than the wait the source named, and the GitHub core re-sends no
  rate-limited request before its pause (human decision 2026-11-10, design D19).
- NFR-R3: Every wire enum introduced (`timeoutClass`, verdict threshold) has a round-trip spec.
- NFR-R4: A kill after a `poll` returned a new key and before it was persisted, and a kill after
  a terminal `poll` and before the verdict was recorded, each resume by polling the persisted key
  with no second `start` (FR10, FR11; design D7 windows W3, W4); kill-point specs cover both,
  each resumed by a second instance.
- NFR-R5: A refusal of the egress guard SHALL be retried by no consumer — the `serve` feed's
  outage retry included — and SHALL not travel as the tracker's outage class
  (`TrackerUnavailableException`): it is a configuration fault, reported once with the key it
  judged, and it charges no abort fuse (FR18); an unresolvable host stays on the consumer's
  transport path, retried as an outage.

### Non-Functional Observability

- NFR-O1: Each poll logs executor, key, elapsed and remaining time under the task's MDC; the
  ledger line of a check carries `executor=<name> status=<status>` like a stage round.

### Non-Functional Security

- NFR-S1: A `program` verdict executor declaring credentials SHALL run in `FRESH_BOX` with its
  own egress set, the law's `executors.<name>.egress` grant bounded by its describe; a `SAME_BOX` declaration on such a check is a
  located load error. A built-in verdict executor declaring credentials runs in the factory
  process under the in-process egress guard, admitted by its operator connection, the
  connection's `egress` list and its describe's `egress` (FR15, FR17); its token enters no box,
  round or fresh, and a cross-host redirect hop drops the credential deny-set
  (`Authorization`, `Cookie`, `Host`, `Origin`, `Referer`, `Proxy-Authorization`,
  `WWW-Authenticate`) while every other header travels.
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
- UX4: An egress refusal names the configuration key of the consumer whose URL it judged
  (`tracker.github.api-url`, `factory.check.github-actions`, `factory.egress.in-process`),
  never another consumer's key. A law-medium host whose only grant is a class word and whose
  name is not statically classified is judged at its first call, so that one refusal surfaces
  at the call rather than at load (design D16).

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
  fetched from the check-run output (its decision is a pickup condition of NG11).
- Q3: How task 4.3a's record-driven poll of a `program` executor reaches the program for a key
  this instance never issued, a superseded key and an ended run's key (FR3, FR11) — settled by
  design D14: the resuming instance's program executor adopts the recorded key, only over a
  working copy whose `HEAD` is the commit the factory put it on (a fresh box's attempt commit,
  otherwise the round's last registered commit) and whose only other difference is the factory's
  own `state.json` lag (a `state.json` of the round's registered chain), judged by identity; a live key is found in the run's table of pending runs, shared by
  every binding of the program's entry, and is not adopted (D14 as amended 2026-11-13); the
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
  copy" (`HEAD` equal to the commit the factory put the working copy on, the factory's lag
  ignored); MODIFIED "Poll of a program is refused without its
  preconditions" and "A pending judge is judged when it ends" (D14).
- `executor/conformance-kit` (added by `define-executor-contract`): ADDED "Re-poll case pins the
  idempotent poll", "An in-process candidate carries the settings of its ordinary cases" (the
  built-ins are judged by the kit, FR1, FR13) and "Pending cases poll from a new working copy"
  (the poll copy's content follows the case's role; FR12, FR11).
- `pipeline-config`: check tokens map to executor names; `expected` contexts and threshold;
  MODIFIED "Stage model mirrors the stage contract and all verify-check types" (executor check +
  `judge`).
- `verification/http-check-provider`: MODIFIED — `http` is a built-in verdict executor resolved
  from the executor registry.
- `verification/factory-egress-allowlist`: MODIFIED "The http check is governed by an
  operator-owned egress allowlist" (the list is the `in-process` scope of the operator egress
  block); ADDED "Egress admission follows the medium of the URL", "One grammar for every egress
  entry", "The guarded exchange serves every factory HTTP consumer", "A redirect target is an
  egress target", "A redirect hop keeps the request's meaning", "A cross-host hop carries no
  credential", "An egress refusal is a configuration fault", "Load classifies a host only
  statically", "A law-medium connection is https unless loopback"; MODIFIED "The http check
  enforces scheme, address-class, and resource limits" (no entry lifts the cloud-metadata block)
  (FR15, FR17, NFR-R5, UX4). `add-stage-finished-event` ADDs a distinct
  requirement to the same capability; no MODIFIED header overlaps.
- `operator/operator-configuration`: MODIFIED "Every key declares where it may be set" (the
  boundary key is `factory.egress.sandbox`); ADDED "The operator egress block replaces the two
  allowlist keys" (FR17).
- `sandbox/sandbox-egress`: MODIFIED "Default-deny allowlist owned by the operator" (the list
  is `factory.egress.sandbox`, parsed by the name-only subset of the one grammar) (FR17).
- `operator/vendor-connection-profile`: ADDED "A profile may grant egress hosts" (FR17).
- `verification/github-external-check`: MODIFIED "Polling is stateless and takeover-safe" — the
  persisted pending key is the one poll state; the built-in reads the attempt commit from the
  engine's attempt-commit workspace handle the check is bound with, never from a type of its own
  (design D18; a verdict program reads it as `HEAD` of the fresh box materialized at it).
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
- `build-system/module-layering`: MODIFIED "Vertical adapter modules per technology" — the
  GitHub HTTP core is the first-party library `:githubapi`, declared by the bundle and
  `adapters/scm` only (D15); MODIFIED "Enforced acyclic dependency direction" — `:sandbox:core`
  gains the `:httpegress` edge and the leaf's own edge is `:domain` alone (D16); the dependency-free leaf `:pendingkey`, the one owner of the pending key's type and rule, joins the JDK-only leafs `:domain` reaches and is declared by `:executor-contract` (D20); the `:application` edge scenario names the
  `:executor-contract` and `:httpegress` edges it already has and the `:pendingkey` edge its check
  port takes in task 4.3a (D14, D16, D20).
- `plugin/plugin-api-contract`: MODIFIED "Thin plugin-api contract module" (already in this
  change) — the tracker SPI factory also bears `endpoint()` (D16); MODIFIED "The published jar
  graph is the compatibility baseline" — the `pendingkey` leaf the domain reaches joins the
  published jar set, so the baseline covers five jars (D20).
- `git/git-task-persistence`: MODIFIED "Round commit of the whole working tree" (a completed host
  round closes as snapshot plus round commit) and "Sandboxed round protocol: snapshot and state
  commits" (pending commits; the state commit sits on the round's last registered commit); ADDED
  "Pending commits of an open round", "No salvage over an interrupted verification" and "An
  unverifiable round close is not an interrupted verification" (FR19, FR20, D21, D22).
- `sandbox/execution-environment`: MODIFIED "Host adapter mechanics" — its scenario "Host adapter
  keeps worktree mechanics" states that a completed git-mode host round closes with its snapshot
  commit and one round commit above it; a round with no snapshot keeps the single round commit
  (FR19, D22).
- `lifecycle/task-branch-contract`: ADDED "An interrupted verification is InProgress with one
  recovery owner" (FR19, FR20, D21).
- `serve/factory-serve`: ADDED "A tracker egress refusal is an operator precondition of the
  daemon" (FR18, NFR-R5).
- `pipeline-config`: MODIFIED "Local sanity validation of mechanism and check configs" — the
  unknown-provider and defaulted-provider scenarios are stated against the executor registry.
- `executor/wire-contract`: ADDED — the describe field of FR16; ADDED "A pending result may
  carry a wait hint", "A pending state classifies the timeout" and "A result carries details apart
  from its message" (design D19); ADDED "The factory's own pending commit is not a change of the
  run" (FR20, D21).
- `verification/verdict-executors`: ADDED "The SCM status reader names what it awaits" (D19).
- `plugin/github-plugin`: ADDED "The GitHub core waits out a rate limit it was told about"
  (NFR-R2, D19).
- `cli/manual-run`: REMOVED "Command check runner" and "Findings-JSON wire format" — the shell
  check's exit code is status, never a verdict (FR5, design D5 as amended 2026-11-08); their kept
  duties are ADDED to `verification/verdict-executors` in the derived form ("The shell executor
  runs the command in the task environment", "The shell executor reads its findings path
  whatever the exit code"), and task 3.2 moves the eight scenario markers.

## Impact

- `domain/engine`: `VerifyOrchestrator`, `ExternalPolling`, `EnginePorts`; new `VerdictDerivation`,
  `PendingPollRecord` as entries of `TaskState.pendingPolls` (design D21), `AttemptPersistence`
  gains `persistPending`, its key typed by the new leaf `:pendingkey` (design D20, human
  decision 2026-11-12), as are `ExecutionResult.Pending.key` and `PendingTurn.key`. `adapters/check` becomes the `http` built-in; `adapters/github/check`
  moves out of the bundle into the new core-side module `adapters/scm` with `scm-status`.
- New modules (design D15, D16): the leaf `:httpegress` (from `:adapters`'s `adapter.check.http`
  egress classes) and the library `:githubapi` (from the bundle's `adapter.github` package); the
  bundle's layering allowlist gains `:githubapi`; `add-sandbox-gha-executor` is the library's
  third consumer and adds no client of its own. The leaf exposes the operator-connection
  admission (design D16 as amended), which the webhook notifier of `add-stage-finished-event`
  takes in place of the "notification-side guard equivalent to the http check's" its D7 planned;
  this one adds no second guard. Before either change archives, `add-stage-finished-event`
  restates its ADDED `verification/factory-egress-allowlist` requirement — today "only `https`"
  and a class waived only for "that literal address" — to the scheme rule and the class words of
  D16, and its D7 key `factory.check.http.allowlist`, which this change removes; this change does
  not edit that one.
- New leaf (design D20): `:pendingkey`, the pending key's type and rule moved out of
  `:executor-contract` (`executorcontract.PendingKey` deleted; the contract binds the leaf type in
  `ExecutorContractJson`; the wire is unchanged). `:domain` and `:executor-contract` declare it;
  every module reaching either lists it in its layering allowlist. The retyped domain records are
  a binary break of the domain jar under the api's japicmp gate, absorbed into the one MINOR
  bump (0.14.0 → 0.15.0), and the leaf jar joins the compatibility baseline.
- `gnomish-plugin-api`: four types removed, sample rewritten; `TrackerAdapterFactory` gains
  `endpoint()`, and `TrackerConfig` (`:domain`) carries the admitted tracker endpoint (D16); both
  under the one MINOR bump (0.14.0 → 0.15.0). The api takes no edge to `:httpegress`; the leaf takes `:domain`. `bootstrap`: `CheckClientDiscovery`,
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
- `adapters/git` and the resume paths (design D21, D22, human decisions 2026-11-12): the pending
  commit at both persistence ends (a declared pair), the host snapshot step beside the container's,
  `SnapshotTipCheck` and `ResumeVerificationStageExecutor` serving the host, one owner of "no
  salvage over an interrupted verification" for the container and the two host resume paths. The
  retyped `TaskState` and `PendingPollRecord` ride the one MINOR bump (0.15.0).
  `extract-agent-round-runner` (its D11) takes `persistPending` and the entry list with a `Round`
  subject when it is applied; two of its premises are refuted (D21, cross-change note).
- Sequenced after `tighten-spec-contract`: its deltas carry the `**Implements**:` lines and the
  500-character requirement bodies that change introduces; it has no MODIFIED block to layer, and
  the markers and exemption lines of the scenarios its REMOVED requirements take with them are
  removed in this change (tasks.md, section 6).
