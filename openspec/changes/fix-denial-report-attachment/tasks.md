# Tasks: fix-denial-report-attachment

## 1. Port — denials reachable through the contract (FR1, NFR-R1, D2)

- [x] 1.1 Add `List<Finding> denialFindings()` to `TaskExecutionEnvironment`
      as a default method returning `List.of()`; javadoc names the best-effort
      contract (unreadable source → empty, never a failure) and traces FR1 /
      NFR-R1 of fix-denial-report-attachment.
- [x] 1.2 Override in `SelfCheckedEnvironment` delegating to the guard; drop
      the public `guard()` accessor from the environment's surface (keep it
      package-internal only if construction/tests need it).
- [x] 1.3 Extend the port-level contract spec suite: sandboxed adapter
      surfaces denials through the port type; host adapter returns empty;
      unreadable guard log degrades to empty without failing the round.

## 2. Guard — per-round delta cursor (D3)

- [x] 2.1 Give the guard log read a daemon-side `--since` cursor: first read
      from container start, each read advances the cursor, so consecutive
      `denialFindings()` calls return disjoint per-round slices.
- [x] 2.2 Spec the cursor: two reads around a new denial return it exactly
      once; a read with no new denials returns empty; a failed read neither
      advances the cursor nor fails (best-effort, NFR-R1).

- [x] 2.3 Unplanned but required by D3: 2.1's cursor was lease-scoped and in
      memory while the guard container it indexes survives a crash and a kept
      environment, so the first read after every resume re-read the container's
      whole surviving tail (up to the 1000-line cap) and attached rounds that
      already committed their own denials to the resumed round — the report
      then misstated when those denials happened. The cursor is now durable
      (FR5, D3a): `state.json` carries it, `EnvironmentAttemptPersistence`
      writes it with the same state commit that carries the round's denials,
      and `ContainerTerminalDrive` offers it back through `SandboxRunSupport`
      before the first environment materializes. It travels as a `(source,
      position)` pair (`DenialCursor`, `sandbox/core`) and is applied only when
      `source` equals the live guard container's runtime id, so a resume on
      another machine — or onto a recreated container — reads its own log from
      the start rather than filtering it by a foreign daemon's clock.
      `EgressGuardSpec` pins the resumed-lease delta, the rejected foreign
      cursor, and the unreadable-identity case; `EnvironmentRoundProtocolSpec`
      pins the committed cursor; `ContainerRunSupportSpec` pins the restore.

## 3. Engine model — verdict-independent slot (FR2, D1, D4)

- [x] 3.1 Add `List<Finding> denials` to `ExecutionResult` (both `Completed`
      and `DecisionNeeded`, interface accessor like `usage()`) and to
      `AttemptRecord` (defensively copied, possibly empty); update every
      constructor call site.
- [x] 3.2 Thread denials in `RoundExecution` onto the `AttemptRecord` it
      builds — for verified rounds and `DecisionNeeded` rounds alike.
- [x] 3.2b Unplanned but required by 3.2: the "`DecisionNeeded` rounds alike"
      half was pinned only at the adapter (`ExecutorRoundDenialSpec`); every
      engine-level scenario passed `[]`. `DenialIsolationSpec` now drives a
      decision round with a denial and asserts the record is
      `DECISION_NEEDED`, keeps the denial, and still runs no check. The
      behavior was already correct — this closes the coverage hole, not a bug.
- [x] 3.3 Spec FR2 isolation: an attempt with denials and all-pass checks
      records `PASSED`; denials never appear in `overallVerdict` input nor in
      the prior-failure feedback assembled for a retry.

## 4. Wiring — read at round close (FR3, D1)

- [x] 4.1 In `ExecutorRoundExecution.run`, read
      `round.environment().denialFindings()` after `round.closeRound()` and
      carry the list on the returned `ExecutionResult`.
- [x] 4.2 Spec the wiring with a fake environment: a round whose environment
      reports denials yields an `ExecutionResult` carrying them; a guard-less
      environment yields empty.
- [x] 4.3 Unplanned but required by D3: a round that dies before its close
      never reached 4.1's read, so its denials stayed unread and the guard
      cursor unadvanced — and an in-process escalation resume reuses the same
      environment, which reported the hung round's denials as the next
      attempt's. `ExecutorRoundExecution` now drains them on the failure path
      (read + WARN, attached to nothing — no `AttemptRecord` exists for a
      `CannotExecute` round), recorded as D1a; `FailedRoundDenialSpec` pins
      the drain, the next round's isolation, and the best-effort read.

- [x] 4.4 Unplanned but required by 4.1: the success-path read was the one
      denial read with no best-effort guard on it, so an environment that
      threw (rather than degrading to empty as the port promises) discarded a
      round that had already finished — snapshot committed, usage and trace in
      hand — into `CannotExecute`. `ExecutorRoundExecution.denialsOf` now
      catches and reports none, matching 4.3's stance on the failure side;
      `ExecutorRoundDenialSpec` pins it with a throwing denial source.

## 5. Report surfaces (FR4, NFR-O1, NFR-S1, UX1, UX2, D5)

- [x] 5.1 `StateJsonMapper`: serialize per-attempt `denials` with the state
      finding DTO shape; absent field reads as empty (existing state files
      parse unchanged); update the `StateJsonMapperSpec` round-trip documents.
- [x] 5.2 `StatusReportJsonMapper`: per-attempt `denials` array with the
      finding DTO shape; update `status-report-v1.reference.json`; keep the
      state↔live equivalence contract green with denials present.
- [x] 5.3 Text renderer: list an attempt's denials alongside its findings;
      zero denials render nothing (UX2).
- [x] 5.4 Spec M1 end to end at the mapper level: a denied round on a
      passing attempt shows `"result": "passed"` plus the denial finding
      (host/path/method, no body) in both documents.

- [x] 5.5 Unplanned but required by 5.3: the text renderer is the first console
      sink to print finding text, and a denial's host/path are gnome-chosen, so
      `StatusLineFormatter.findingLine` routes both through the findings funnel
      (`FindingsSanitizer.strip`, FR15 of add-sandbox-core) and collapses the
      `\n`/`\t` strip deliberately keeps, so one denial stays one line and a
      crafted path can neither rewrite the operator's terminal nor forge report
      rows. Findings as data stay verbatim in `state.json` / `status.json`.

- [x] 5.6 Unplanned but required by NFR-S1: `flow.request.path` includes the
      query, so a denied `GET /upload?token=…` wrote up to 300 characters of
      gnome-chosen payload into the finding's location — and a denial finding
      is committed to the task branch in `state.json`. `GuardDenialLog` now
      cuts the path at the first `?` before capping it; the delta spec and the
      NFR-S1 line say so, and the glossary's **Denial** entry defines the field
      set as query-free.
- [x] 5.7 Unplanned but required by the no-jargon invariant: `denial` became a
      domain field of two published contracts, so `docs/glossary.md` gains a
      **Denial** entry under Sandbox; the **Findings** entry, which defined
      findings as check results only, is corrected — a denial is a finding no
      check produced.

## 6. Verification and closure

- [x] 6.1 Full build green: `./gradlew check` including the PIT 100% gate;
      grep confirms a `src/main` consumer of `denialFindings()` and no public
      `guard()` accessor (M2, M3).
- [x] 6.1b Unplanned but required by 6.1: `AttemptRecord` and both
      `ExecutionResult` variants are `:domain` types re-exposed by
      `gnomish-plugin-api`, so the new component removed their old canonical
      constructors and armed `japicmpApiGate`. Accepted the break the documented
      way — api version 0.1.0 → 0.2.0 (pre-1.0 breaking = MINOR) plus a
      regenerated `compat-baseline/`, both in this change's diff.
- [x] 6.2 Verify FR/NFR/UX traceability coverage per `.claude/rules/
      traceability.md`; update the `sandbox-egress` deferral wording only via
      this change's delta spec (main spec updates happen at archive).
- [x] 6.3 Recommend a Conventional Commits message referencing
      fix-denial-report-attachment.
