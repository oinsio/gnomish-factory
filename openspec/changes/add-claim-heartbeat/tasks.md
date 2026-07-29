# Tasks: add-claim-heartbeat

TDD throughout (testing.md): each task starts from a failing Spock spec
referencing its FR. Design decisions referenced as D1–D12 (design.md).
Depends on `add-tracker-port` implementation being in place.

## 1. Port and value model extension

- [ ] 1.1 Value model in `app/port/tracker`: `ClaimVersion` (marker identity +
      last-update fact), `OpenTask` (state, holder, version), heartbeat result
      (beaten | claim gone), stale-claim-removal result (removed |
      mismatch with current facts) — with unit specs for invariants (FR5, D5)
- [ ] 1.2 `Tracker` port interface gains `listOpen`, `heartbeat`,
      `removeStaleClaim` with doc comments carrying traceability links
      (FR5, D5)

## 2. Contract suite extension and in-memory reference

- [ ] 2.1 Contract property — `listOpen` filtering: only `Working`/
      `AwaitingHuman`, never `Ready`/`Finished`/`Gone`/non-tasks; `Working`
      entries carry holder and version; `listReady` unchanged (FR5)
- [ ] 2.2 Contract property — heartbeat: a beat changes the version another
      instance reads; marker identity stable; beat after removal reports
      claim gone, distinguishable from infrastructure failure (FR1, FR5, FR8)
- [ ] 2.3 Contract property — `removeStaleClaim`: round-trip (task `Ready`,
      claim gone, transition marker recoverable), version-mismatch no-op,
      concurrent-removal convergence via interleaving hooks (FR4, FR5, NFR-R2)
- [ ] 2.4 In-memory adapter: implement the three operations + harness hooks
      for removal races and beat/removal interleavings (FR5)
- [ ] 2.5 Extended contract suite green on the in-memory reference (M1)

## 3. GitHub adapter

- [ ] 3.1 `heartbeat` as claim-comment PATCH: body refresh (structural marker +
      progress line), id stable, one write; 404 → claim gone, 5xx/network →
      infrastructure (WireMock specs) (FR1, FR8, D1)
- [ ] 3.2 `listOpen` via working/needs-human labels with ETag conditional
      requests, PR exclusion; claim-comment resolution to (comment id,
      updated_at); missing claim comment → absent claim (FR5, NFR-P1)
- [ ] 3.3 `removeStaleClaim` physics: pre-action version re-check,
      `stale-claim-removed` boundary marker (existing hidden-JSON convention),
      dead-comment deletion, point label flip; racing removal converges
      (FR4, D5, D12)
- [ ] 3.4 Claim verify-read anchors on the removal marker as a boundary; spec
      for the post-removal lease round (FR4, D5)
- [ ] 3.5 Extended contract suite green on the GitHub adapter over WireMock
      (M1, M2)

## 4. Core lease policy

- [ ] 4.1 Staleness memory: per-claim observation store (version + first-seen
      monotonic instant), TTL judgment; controlled-clock specs including the
      fresh-observer grace period and the beaten-claim-never-stale property
      (FR2, D2, NFR-R1)
- [ ] 4.2 Instance heartbeat thread: beats every held claim on the interval,
      payload assembled from `EngineEventListener` events (stage, attempt,
      alive-at); starts with the first claim, stops when none held (FR1, D3)
- [ ] 4.3 Reaper duty on the heartbeat tick: `listOpen` → feed staleness
      memory → `removeStaleClaim` for stale `Working` claims; never claims
      for itself (FR4, D4)
- [ ] 4.4 Beat-failure taxonomy: 5xx/network → WARN and continue; claim gone →
      flag for the next round boundary, reaction identical to revocation
      (stop, salvage push, no tracker writes) (FR8, D7)
- [ ] 4.5 Outage behavior spec: no observations during an outage → no TTL
      progress; recovery restarts windows (FR9, D2)

## 5. Config keys

- [ ] 5.1 `tracker.heartbeat-interval` (duration, default 5 min) and
      `tracker.heartbeat-ttl-multiplier` (integer ≥ 3, default 3) parsing and
      validation with located, aggregated errors (FR3)
- [ ] 5.2 Spec pinning that protocol constants load from the factory's clone
      only — a worktree config edit never changes the running instance's
      beat/TTL (FR3, NFR-S1)

## 6. take integration

- [ ] 6.1 Heartbeat-thread lifecycle in the take run: start at first claim,
      stop at terminal result or claim loss; integration spec that the claim
      comment is beaten during a long fake round (FR1)
- [ ] 6.2 Disposition matrix update: `Working` → takeover path — facts shown
      (holder, last-beat age), TTY confirmation or `--takeover` flag,
      refusal naming the holder otherwise; confirmed path =
      `removeStaleClaim` + ordinary claim + branch resume (FR6, D9)
- [ ] 6.3 Zombie pre-write checks: conditional "claim still ours" before
      park/finish/release; verify no force-push exists on any task-branch
      code path (FR7, D6)
- [ ] 6.4 Reconcile-on-resume at the head of the claim path: branch terminal
      outcome without tracker counterpart → deferred finish/park, zero engine
      rounds, exit by the reconciled outcome (FR10, D10, M4)
- [ ] 6.5 Terminal-write retry with backoff bounded (~10 min), ERROR naming
      the unreconciled state on give-up; abort path stays best-effort
      (FR10, D10)
- [ ] 6.6 Integration test — death and recovery: instance A's claim goes
      stale under a controlled clock, instance B's run reaps it, a later run
      resumes from the branch (M2)
- [ ] 6.7 Integration test — zombie fence over a local bare repo: two holders
      push the same task branch, exactly one lands, the loser takes the
      normal abort path (M3)

## 7. Command-check credential scrub

- [ ] 7.1 Feed the adapter-declared credential list into the command-check
      process construction; spec: `GNOMISH_GITHUB_TOKEN` absent from a check's
      environment, env unchanged when no tracker is configured (FR11, D11)

## 8. Documentation

- [ ] 8.1 Operator guide: rewrite the stuck-`Working` section (automatic
      reaping in long-lived runs, confirmed `--takeover`, cron-only manual
      escape hatch until `serve`), document heartbeat/TTL keys and the shared
      write-budget coupling (FR3, FR6, NFR-P1, UX3)
- [ ] 8.2 Adapter author guide: the three new operations, claim-version
      obligations, removal-marker boundary semantics, the extended contract
      suite as law (FR5)

## 9. Final verification

- [ ] 9.1 Traceability sweep: every FR/NFR/UX of the proposal grep-resolves to
      at least one implementing entity or spec (traceability.md)
- [ ] 9.2 Full `./gradlew check` green; coverage and mutation gates hold for
      all new production code (M5)
