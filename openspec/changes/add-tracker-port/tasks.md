# Tasks: add-tracker-port

TDD throughout (testing.md): each task starts from a failing Spock spec referencing
its FR. Design decisions referenced as D1–D15 (design.md).

## 1. Tracker port and value model

- [ ] 1.1 Value model in `app/port/tracker`: `TaskRef`, `TaskSnapshot`,
      `AbortFacts`, `TrackerTaskState` (Ready/Working/AwaitingHuman/Finished/Gone),
      `ParkReason`, `ClaimResult`, `Decision`, `ReadyTask`, `TrackerTask` — with
      unit specs for invariants (FR1, FR2, D1)
- [ ] 1.2 `Tracker` port interface with the ten v1 operations and doc comments
      carrying traceability links (FR1, D1)
- [ ] 1.3 `InstanceId` composite generation: configured/hostname name + per-process
      base36 suffix; spec for uniqueness across same-name processes (FR9, D6)

## 2. Contract suite and in-memory reference adapter

- [ ] 2.1 Abstract Spock contract base class: `listReady` filtering property
      (states, non-task artifacts, readiness criterion, no backoff filtering,
      queue order + abort facts) (FR4)
- [ ] 2.2 Contract properties: claim atomicity race (exactly one winner) and
      marker round-trip (abort facts cross-instance; decisions empty after ack)
      (FR4, NFR-R1)
- [ ] 2.3 In-memory adapter: task store, human test operations (reply, return,
      close), deterministic race interleaving hooks (FR3)
- [ ] 2.4 Wire in-memory adapter into the contract suite; all properties green
      (FR3, FR4, M1, M2)

## 3. pipeline-config tracker section

- [ ] 3.1 Core keys parsing: optional `tracker` section, `type`,
      `abort-threshold` default 3; absent section leaves loading unchanged —
      specs for both (FR17)
- [ ] 3.2 Seam validation with located, aggregated errors: unknown `type`, `type`
      without subsection, mismatched subsection; adapter-validator delegation
      hook (FR17, D5)

## 4. GitHub adapter

- [ ] 4.1 `tracker.github` subsection validator: mandatory `api-url`, `repo`,
      `labels` as `{name, color}` with hex validation; errors aggregate with core
      errors (FR17, NFR-S1 token-from-env only)
- [ ] 4.2 Canonical id: build/parse `github:owner/repo#42`, host inclusion by
      normalized `api-url` comparison (D7); round-trip spec; sanitize reuse
      unchanged (FR16)
- [ ] 4.3 HTTP client core: `java.net.http` + Resilience4j retry, auth header
      from `GNOMISH_GITHUB_TOKEN`, ETag/`If-None-Match` conditional GET support;
      WireMock specs incl. 304 handling (NFR-P1, NFR-R2)
- [ ] 4.4 Label operations: point add/remove state transitions, exclusivity;
      WireMock specs for lost-update avoidance (FR5)
- [ ] 4.5 Label provisioning: idempotent create with color+description, no
      recolor of existing, startup failure on unwritable repo names the repo
      (FR5, NFR-R4)
- [ ] 4.6 Structural markers: hidden HTML-comment JSON + human line for
      claim/abort/ack/note/report; parse-back spec (FR7, D9, NFR-O1)
- [ ] 4.7 Feed: List Issues query (`state=open`, ready label, asc), PR filtering,
      abort-fact enrichment; WireMock specs (FR8)
- [ ] 4.8 `fetchTask`: snapshot + state from labels/closure (`state_reason` into
      revocation context) + holder + abort facts; `Gone` for closed/missing
      (FR2, FR5)
- [ ] 4.9 Lease claim: label → claim comment → boundary-anchored verify-read →
      earliest-id winner; loser annuls; unverifiable claim backs out as infra;
      WireMock interleaving specs (FR6, NFR-R1, D13)
- [ ] 4.10 Decisions and ack: `collectDecisions` after last ack (`since` +
      boundary anchor), `acknowledgeDecision`, `park`/`finish`/`release`/
      `recordAbort`/`postNote` comment+label writes (FR12, FR14, FR18)
- [ ] 4.11 Foreign-repo check with rename-redirect tolerance (WARN on redirect
      match, refusal otherwise); WireMock spec (FR16, D8)
- [ ] 4.12 Run the shared contract suite against the WireMock-backed GitHub
      adapter; zero exemptions (FR4, M1, M2)

## 5. Take runner and CLI

- [ ] 5.1 `TakeResult` model and outcome mapping: Completed→`finish`,
      Escalated→`park(ESCALATION)`, Paused→`park(CHECKPOINT)` (FR18, D2, D3)
- [ ] 5.2 Snapshot at first claim into `TaskContext`/`task.json`; resume does not
      re-read; status title from snapshot (FR11)
- [ ] 5.3 Abort path: ERROR log, best-effort `recordAbort`, K fuse decides
      `park(INFRA)` with abort history; counter reset on first persisted round
      (FR14, NFR-R2, NFR-C1)
- [ ] 5.4 Backoff policy in core: exponential base/cap from factory config,
      applied over adapter abort facts to hide feed entries (FR10, D10, NFR-C1)
- [ ] 5.5 Revocation: `AttemptPersistence` decorator round-boundary check +
      control exception; salvage commit, best-effort push, stop note, release
      (FR15, D2)
- [ ] 5.6 Decision collection and ack-before-acting for both sources (FR12)
- [ ] 5.7 Decision wait race: two virtual threads, first-non-empty wins, poll
      cancellation, tracker-win console notice; headless path leaves
      `AwaitingHuman` with "reply and re-run" report (FR13, D12, UX3)
- [ ] 5.8 Explicit-mode disposition matrix incl. mandate overrides and refusal
      messages naming holder/state (FR9, UX2)
- [ ] 5.9 Bare auto mode: eligible head claim, race-loss fall-through, one task,
      clean empty-queue exit (FR10)
- [ ] 5.10 Final report rendered from `StatusReport` (stages/attempts/usage/
      branch/wall time) for `finish` (FR18, D11)
- [ ] 5.11 `TakeCommand` CLI: forms, flag matrix and validation (no `--mode`, no
      ad-hoc group, no `--from-stage`, bare form rejects `--base`), short-ref
      expansion; `run` matrix untouched — spec asserts existing validation specs
      unchanged (FR9, D4)
- [ ] 5.12 Spring wiring: adapter selection by `tracker.type`, factory config
      properties (`instance-name`, poll interval, backoff base/cap), MDC task id
      on all lifecycle logs (FR17, D5, NFR-O1)
- [ ] 5.13 Credentials isolation spec: gnome process env contains no tracker
      variables during a take run (NFR-S1)

## 6. Integration and quality gates

- [ ] 6.1 Lifecycle integration spec A: ready → claim → work → delivered with
      final report, against in-memory tracker + local bare git (M3)
- [ ] 6.2 Lifecycle integration spec B: escalate → human decision → cross-instance
      resume → delivered; second instance shares nothing local with the first
      (M3, NFR-R3)
- [ ] 6.3 Revocation and abort integration specs: close-under-work salvage path;
      abort → backoff-hidden → fuse trip at K (FR14, FR15)
- [ ] 6.4 Coverage + PIT over new production code to targets; justify any
      boundary exemptions (M4)

## 7. Documentation

- [ ] 7.1 Operator guide `docs/operator-guide.md` per its spec requirement
      (config layers, labels, decisions/ack, snapshot, escape hatch, fork
      warning, CLI reference) (FR19)
- [ ] 7.2 Projects v2 section + reference bridge workflow
      `docs/examples/board-bridge.yml` (cron `gh` script: column → ready label)
      (FR19, D14)
- [ ] 7.3 Adapter author guide `docs/adapter-author-guide.md` per its spec
      requirement (model, port semantics, contract suite as law, Redmine sketch,
      config subsection ownership, known limitations) (FR19)
- [ ] 7.4 README/CLAUDE.md touch-up: `gnomish take` in the CLI surface, tracker
      port in the architecture summary (FR19)
