# Tasks: add-tracker-port

TDD throughout (testing.md): each task starts from a failing Spock spec referencing
its FR. Design decisions referenced as D1–D15 (design.md).

## 0. Artifact reconciliation (before implementation)

- [x] 0.1 Settle the EscalationReport-kind → ParkReason mapping and define
      "infrastructure abort" precisely — DECIDED: `AttemptsExhausted`,
      `DecisionNeeded` → `ESCALATION`; `CannotVerify`, `CannotExecute`,
      `PipelineMismatch` → `INFRA`; abort path = engine `Aborted` OR uncaught
      run exception. Recorded in design D3, tracker-port outcome-mapping
      scenario, tracker-take abort requirement (FR2, FR14)
- [x] 0.2 Settle how a parked task returns to work — DECIDED: only a human
      returns `AwaitingHuman → Ready` (label flip); the factory claims only
      `Ready` tasks; the take decision-wait dialog is removed — an escalation
      always parks and exits, resume happens at claim from the branch outcome
      (deviation from explore Р5 noted: no direct take of parked tasks).
      Recorded in proposal (FR9, FR12, FR13, UX3, U2, NFR-P1), design
      (D3, D5, D12), tracker-port and tracker-take specs (FR2, FR9)
- [x] 0.3 Reconcile design D5/D6 property names with the implemented
      `FactoryProperties` — DECIDED: keep the `factory.*` prefix; rename
      `factory.instance-id` → `factory.instance-name` with default
      `gnomish-factory` (neutral — no hostname leak into public issues;
      the name names the factory, not a gnome); backoff keys
      `factory.tracker.abort-backoff-base`/`-cap`. Recorded in design D5/D6
      (FR9, FR17)
- [x] 0.4 Define the `take` exit-code table — DECIDED (full table, empty queue
      = 0): 0 delivered/empty queue, 1 failure outside a claimed run, 2 usage,
      3 pipeline load, 10 parked-escalation, 11 parked-checkpoint, 12 abort
      below fuse, 13 parked-infra, 14 revoked, 15 refused/skipped. Recorded in
      design D16 and the tracker-take spec (FR9, FR10, FR15)
- [x] 0.5 Decide how to spec NFR-S1 credential scrubbing — DECIDED: MODIFIED
      delta added to the agent-executor spec (launcher always excludes tracker
      credential variables); the scrub list comes from the adapter's
      declaration on its registration seam (D17), mandatory for adapter
      authors (NFR-S1)

## 1. Tracker port and value model

- [ ] 1.1 Value model in `app/port/tracker`: `TaskRef`, `TaskSnapshot`,
      `AbortFacts`, `TrackerTaskState` (Ready/Working/AwaitingHuman/Finished/Gone),
      `ParkReason`, `ClaimResult`, `Decision`, `ReadyTask`, `TrackerTask` — with
      unit specs for invariants (FR1, FR2, D1)
- [ ] 1.2 `Tracker` port interface with the ten v1 operations and doc comments
      carrying traceability links (FR1, D1)
- [ ] 1.3 `InstanceId` composite generation: name from `factory.instance-name`
      (default `gnomish-factory`) + per-process base36 suffix; spec for
      uniqueness across same-name processes (FR9, D6)
- [ ] 1.4 ArchUnit boundary rule: no core class references
      `adapter/tracker/**` types — the take runner compiles against the port
      alone (FR1)

## 2. Contract suite and in-memory reference adapter

- [ ] 2.1 Contract property — `listReady` filtering: states, non-task
      artifacts, readiness criterion, no backoff filtering, queue order +
      abort facts (FR4)
- [ ] 2.2 Contract property — claim atomicity: concurrent race yields exactly
      one winner; claim is issued only against `Ready` tasks (per 0.2)
      (FR4, NFR-R1)
- [ ] 2.3 Contract property — marker round-trip: abort facts readable
      cross-instance; `collectDecisions` empty after an ack until a new human
      reply (FR4)
- [ ] 2.4 Contract property — `fetchTask` facts: snapshot fields,
      `Working(holder)`, `AwaitingHuman(reason)`, abort facts; `Gone` for
      closed/missing without exception (FR1, FR2)
- [ ] 2.5 In-memory adapter core: task store and the ten port operations,
      no config subsection (FR3)
- [ ] 2.6 In-memory test harness: human operations (reply, return, close) and
      deterministic race interleaving hooks (FR3)
- [ ] 2.7 Wire in-memory adapter into the contract suite; all properties green
      (FR3, FR4, M1, M2)

## 3. pipeline-config tracker section

- [ ] 3.1 Core keys parsing: optional `tracker` section, `type`,
      `abort-threshold` default 3; absent section leaves loading unchanged —
      specs for both (FR17)
- [ ] 3.2 Seam validation with located, aggregated errors: unknown `type`, `type`
      without subsection, mismatched subsection; adapter-validator delegation
      hook (FR17, D5)

## 4. GitHub adapter

- [ ] 4.1 Build dependencies: WireMock and Resilience4j (neither is present
      yet), lockfile update, dependency-analysis and Spotless gates green
      (NFR-R2, NFR-P1)
- [ ] 4.2 `tracker.github` subsection validator: mandatory `api-url`, `repo`,
      `labels` as `{name, color}` with hex validation; errors aggregate with core
      errors (FR17, NFR-S1 token-from-env only)
- [ ] 4.3 Canonical id: build/parse `github:owner/repo#42`, host inclusion by
      normalized `api-url` comparison (D7); round-trip spec; sanitize reuse
      unchanged (FR16)
- [ ] 4.4 HTTP client core: `java.net.http` + Resilience4j retry, auth header
      from `GNOMISH_GITHUB_TOKEN`; WireMock specs for retry and auth
      (NFR-R2, NFR-S1)
- [ ] 4.5 Conditional requests: ETag cache, `If-None-Match`, 304 handled as
      "no change" without consuming rate limit; WireMock specs (NFR-P1)
- [ ] 4.6 Label operations: point add/remove state transitions, exclusivity;
      WireMock specs for lost-update avoidance (FR5)
- [ ] 4.7 Label provisioning: idempotent create with color+description, no
      recolor of existing, startup failure on unwritable repo names the repo
      (FR5, NFR-R4)
- [ ] 4.8 Structural markers: hidden HTML-comment JSON + human line for
      claim/abort/ack/note/report; parse-back spec (FR7, D9, NFR-O1)
- [ ] 4.9 Feed: List Issues query (`state=open`, ready label, asc), PR filtering,
      abort-fact enrichment; WireMock specs (FR8)
- [ ] 4.10 `fetchTask`: snapshot + state from labels/closure (`state_reason` into
      revocation context) + holder + abort facts; `Gone` for closed/missing
      (FR2, FR5)
- [ ] 4.11 Lease claim happy paths: label → claim comment → boundary-anchored
      verify-read → earliest-id winner; WireMock interleaving spec for the
      race (FR6, NFR-R1, D13)
- [ ] 4.12 Lease claim failure paths: loser deletes own marker and reports
      `Held(winner)`; unverifiable claim backs out as infrastructure after
      retries; WireMock specs (FR6, NFR-R1, D13)
- [ ] 4.13 Decisions and ack: `collectDecisions` after last ack (`since` +
      boundary anchor), `acknowledgeDecision` (FR12)
- [ ] 4.14 State writes: `park`/`finish`/`release`/`recordAbort`/`postNote`
      comment+label operations (FR14, FR18)
- [ ] 4.15 Foreign-repo check with rename-redirect tolerance (WARN on redirect
      match, refusal otherwise); WireMock spec (FR16, D8)
- [ ] 4.16 Run the shared contract suite against the WireMock-backed GitHub
      adapter; zero exemptions (FR4, M1, M2)

## 5. Take runner and CLI

- [ ] 5.1 `TakeResult` model and outcome mapping: Completed→`finish`,
      Paused→`park(CHECKPOINT)`, Escalated→`park(ESCALATION)` for
      AttemptsExhausted/DecisionNeeded and `park(INFRA)` for CannotVerify/
      CannotExecute/PipelineMismatch (the latter reachable on cross-instance
      resume after a pipeline edit); infra parks leave the abort counter
      untouched (FR18, D2, D3)
- [ ] 5.2 Snapshot at first claim into `TaskContext`/`task.json`; resume does not
      re-read; status title from snapshot (FR11)
- [ ] 5.3 Abort path per 0.1 (engine `Aborted` and uncaught run exception):
      ERROR log, best-effort `recordAbort`, K fuse decides `park(INFRA)` with
      abort history; counter reset on first persisted round
      (FR14, NFR-R2, NFR-C1)
- [ ] 5.4 Backoff policy in core: exponential base/cap from factory config,
      applied over adapter abort facts to hide feed entries (FR10, D10, NFR-C1)
- [ ] 5.5 Revocation: `AttemptPersistence` decorator round-boundary check +
      control exception; salvage commit, best-effort push, stop note, release
      (FR15, D2)
- [ ] 5.6 Resume wiring over the existing git protocol: branch locate/narrow
      fetch, worktree materialization, divergence rules, salvage or
      `--discard-work`, outcome reset and attempt-counter reset on resume,
      consumed decision appended via `TaskRepository` (FR9, FR12, D3)
- [ ] 5.7 Decision collection at resume claim and ack-before-acting;
      `DecisionNeeded` with no pending reply parks again restating the
      question (FR12, FR13)
- [ ] 5.8 Escalation exit: park with report + return-path message ("reply and
      move the task back to ready"), identical with and without a TTY — no
      in-run wait (FR13, D12, UX3)
- [ ] 5.9 Explicit-mode disposition matrix incl. mandate overrides, refusal of
      parked tasks naming the return path, and refusal messages naming
      holder/state (FR9, UX2)
- [ ] 5.10 Bare auto mode: eligible head claim, race-loss fall-through, one task,
      clean empty-queue exit (FR10)
- [ ] 5.11 Final report rendered from `StatusReport` (stages/attempts/usage/
      branch/wall time) for `finish` (FR18, D11)
- [ ] 5.12 Exit-code mapping (D16): `TakeResult` → process exit code
      (0/1/2/3/10/11/12/13/14/15), specs for every family (FR9, FR10, FR15)
- [ ] 5.13 `TakeCommand` CLI surface: subcommand registration/dispatch, argument
      parser, flag matrix and validation (no `--mode`, no ad-hoc group, no
      `--from-stage`, bare form rejects `--base`) (FR9, D4)
- [ ] 5.14 Short-ref expansion (`42`, `#42` via the binding) and `run`-matrix
      regression: spec asserts existing `run` validation specs unchanged
      (FR9, D4)
- [ ] 5.15 Spring wiring: adapter selection by `tracker.type`, factory config
      properties per 0.3: rename `factory.instance-id` → `factory.instance-name`
      (default `gnomish-factory`, existing specs updated), add
      `factory.tracker.abort-backoff-base`/`-cap` (FR17, D5)
- [ ] 5.16 MDC canonical task id on all take lifecycle logs, joining the
      existing stage/attempt keys (NFR-O1)
- [ ] 5.17 Credentials isolation (D17): adapter registration seam declares
      credential env variable names; `AgentProcessLauncher` scrubs the
      declared names from the child environment (today it inherits the full
      parent env); spec asserts no tracker variables reach the gnome during a
      take run (NFR-S1)

## 6. Integration and quality gates

- [ ] 6.1 Lifecycle integration spec A: ready → claim → work → delivered with
      final report, against in-memory tracker + local bare git; assert the
      issue thread alone tells the story (claim, reports, ack, final) (M3, UX4)
- [ ] 6.2 Lifecycle integration spec B: escalate → human reply + return to
      ready → cross-instance resume → delivered; second instance shares
      nothing local with the first (M3, NFR-R3)
- [ ] 6.3 Revocation integration spec: close-under-work → salvage commit, push,
      stop note, release; branch and worktree kept (FR15)
- [ ] 6.4 Abort integration spec: abort → backoff-hidden from bare feed →
      fuse trip at K parks with full abort history (FR14, FR10)
- [ ] 6.5 Coverage + PIT over new production code to targets; justify any
      boundary exemptions (M4)

## 7. Documentation

- [ ] 7.1 Operator guide `docs/operator-guide.md` per its spec requirement
      (config layers, labels, decisions/ack, snapshot, escape hatch, fork
      warning, CLI reference incl. the D16 exit-code table) (FR19)
- [ ] 7.2 Projects v2 section + reference bridge workflow
      `docs/examples/board-bridge.yml` (cron `gh` script: column → ready label)
      (FR19, D14)
- [ ] 7.3 Adapter author guide `docs/adapter-author-guide.md` per its spec
      requirement (model, port semantics, contract suite as law, Redmine sketch,
      config subsection ownership, known limitations) (FR19)
- [ ] 7.4 README/CLAUDE.md touch-up: `gnomish take` in the CLI surface, tracker
      port in the architecture summary (FR19)
