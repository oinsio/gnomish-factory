# Tasks: add-factory-serve

TDD throughout (testing.md): each task starts from a failing Spock spec
referencing its FR. Design decisions referenced as D1–D10 (design.md).
Depends on `add-claim-heartbeat` being implemented — the heartbeat thread,
reaper, `listOpen`, and reconcile-on-resume are consumed as-is.

## 1. Returned fact on the port

- [ ] 1.1 `ReadyTask` gains the `returned` fact in the port value model, with
      doc-comment traceability; unit specs for the value object (FR7)
- [ ] 1.2 Contract property — returned fact: false for never-claimed tasks,
      true after park-and-return, true after stale-claim removal; `listOpen`
      size doubles as the open-front count (FR6, FR7, M1)
- [ ] 1.3 In-memory adapter derives `returned` from its recorded history;
      contract suite green on the reference (FR7)
- [ ] 1.4 GitHub adapter derives `returned` from thread markers (park report,
      holder-transition marker) with no new writes and ETag discipline
      intact; WireMock specs; contract suite green (FR7, NFR-P1)

## 2. Feed policy in core

- [ ] 2.1 Eligibility policy component: backoff filter (existing), returned
      tasks first and outside the WIP limit, fresh tasks only while
      open < W, head-zone pick (uniform among first K = 5 eligible)
      (FR6, FR9, D2, D4)
- [ ] 2.2 Open-front accounting from `listOpen` size with a per-claim
      re-check; overshoot-bounded spec (one task per racing instance)
      (FR6, D5, M4)
- [ ] 2.3 Bare `take` switches to the shared policy: returned priority, WIP
      no-op naming the limit, head-zone claim; existing backoff and
      race-fallthrough specs still green (FR6, FR9, D2)

## 3. Config

- [ ] 3.1 `tracker.wip-limit` (integer ≥ 1, default 10) parsing and
      validation with located, aggregated errors; spec pinning it loads from
      the factory's clone only (FR6, NFR-S3)
- [ ] 3.2 Factory-config instance knobs: slots N (default 2), idle-poll
      interval (default 30 s), SIGTERM grace (default 30 s), worktree age
      threshold (default 14 d) — parsing, defaults, CLI overrides
      (FR1, FR5, FR11, FR14, D3)

## 4. Scheduler and feed automaton

- [ ] 4.1 Slot ledger: `Semaphore(N)`, permit acquired before claim, released
      on terminal result with a feed wake-up; property spec — claim attempts
      in flight never exceed free slots, no task in two slots
      (FR1, D1, NFR-R1, M2)
- [ ] 4.2 Feed automaton: Filling (no pause), Idle-empty and Idle-blocked on
      the single jittered interval, Full polls nothing and wakes on
      slot-freed; state-transition specs with a controlled clock
      (FR5, FR9, D1, D4)
- [ ] 4.3 Slot body reuse: a slot runs the existing take cycle on a
      pre-claimed task; escalation, abort, and revocation behavior identical
      to single `take` (FR1, M2)
- [ ] 4.4 Per-clone git mutation lock: fetch/worktree-add/remove/push
      serialized per target clone; concurrent slot-lifecycle test over a
      local bare repo (NFR-R2, D8)

## 5. serve command and lifecycle

- [ ] 5.1 `gnomish serve` command surface: `--dir`, `--slots`, `--drain`;
      unconditionally non-interactive; startup label-provisioning smoke test
      → exit 1 with a clear error (FR2, FR4, FR12, D3, D7)
- [ ] 5.2 Serve runs the heartbeat thread across all slots; reaper keeps
      working in Full and Idle-blocked, and a reaped front lowers the
      open-front count (FR13)
- [ ] 5.3 WIP-limit observability: the Idle-blocked log line names the count
      of fronts awaiting decisions; feed-state transitions logged; MDC per
      task in slot logs (NFR-O1)
- [ ] 5.4 Drain mode: nothing-eligible → stop claiming, slots finish, exit 0
      with a closing report of worked tasks (FR10, NFR-O2, M3)
- [ ] 5.5 SIGTERM sequence: stop-claim flag, round-boundary latch per slot,
      release of claims stopped within grace, `ProcessHandle` tree kill on
      exit; integration spec with a fake long round (FR11, D9, M3)
- [ ] 5.6 Restart cleanliness spec: prior-instance claims are not adopted,
      go stale, and return via the reaper (FR12)
- [ ] 5.7 Outage tolerance: feed and heartbeat retry with backoff, slots
      keep working, daemon survives; integration spec over WireMock outage
      windows (NFR-R3)

## 6. Batch take

- [ ] 6.1 Varargs surface: `take <ref> <ref> ...` accepted, `--interactive`
      and `--base` rejected in batch; validation specs (FR2, FR3)
- [ ] 6.2 Batch disposition: per-ref matrix through the scheduler up to N,
      skips reported and the run continues; `Working` refs skipped unless
      whole-run `--takeover` (FR3, FR4, D6)
- [ ] 6.3 Summary and aggregate exit code: every ref's outcome listed; exit
      0 iff all zero, else smallest non-zero per-ref code; specs for both
      families (FR3, NFR-O2, D7)

## 7. Worktree janitor

- [ ] 7.1 Janitor component with the `dispose`-shaped seam: age-based
      disposal of ended tasks' environments, never a held task; startup +
      hourly tick; disposed worktree rematerializes on resume
      (FR14, D10)

## 8. Documentation

- [ ] 8.1 Operator guide — autonomous operation: serve/batch/drain reference
      with lifecycle behavior, feed states and the WIP-limit message,
      instance knobs vs protocol constants, the write-budget coupling
      (ΣN vs beat interval, the interval as the scaling knob), the WIP
      method boundary, cron path now `serve --drain` with the manual flip
      demoted to last resort (NFR-P2, UX1, UX2, UX4)
- [ ] 8.2 Operator guide — autonomy gate and CI hygiene: ready-label access
      = host code execution, no auto-`ready` from untrusted sources;
      `gnomish/*` workflows without privileged secrets, `GITHUB_TOKEN`
      read-only (NFR-S1, NFR-S2)

## 9. Final verification

- [ ] 9.1 Traceability sweep: every FR/NFR/UX of the proposal grep-resolves
      to at least one implementing entity or spec (traceability.md)
- [ ] 9.2 Full `./gradlew check` green; coverage and mutation gates hold for
      all new production code (M5)
