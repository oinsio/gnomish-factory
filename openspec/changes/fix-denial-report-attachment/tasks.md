# Tasks: fix-denial-report-attachment

## 1. Port — denials reachable through the contract (FR1, NFR-R1, D2)

- [ ] 1.1 Add `List<Finding> denialFindings()` to `TaskExecutionEnvironment`
      as a default method returning `List.of()`; javadoc names the best-effort
      contract (unreadable source → empty, never a failure) and traces FR1 /
      NFR-R1 of fix-denial-report-attachment.
- [ ] 1.2 Override in `SelfCheckedEnvironment` delegating to the guard; drop
      the public `guard()` accessor from the environment's surface (keep it
      package-internal only if construction/tests need it).
- [ ] 1.3 Extend the port-level contract spec suite: sandboxed adapter
      surfaces denials through the port type; host adapter returns empty;
      unreadable guard log degrades to empty without failing the round.

## 2. Guard — per-round delta cursor (D3)

- [ ] 2.1 Give the guard log read a daemon-side `--since` cursor: first read
      from container start, each read advances the cursor, so consecutive
      `denialFindings()` calls return disjoint per-round slices.
- [ ] 2.2 Spec the cursor: two reads around a new denial return it exactly
      once; a read with no new denials returns empty; a failed read neither
      advances the cursor nor fails (best-effort, NFR-R1).

## 3. Engine model — verdict-independent slot (FR2, D1, D4)

- [ ] 3.1 Add `List<Finding> denials` to `ExecutionResult` (both `Completed`
      and `DecisionNeeded`, interface accessor like `usage()`) and to
      `AttemptRecord` (defensively copied, possibly empty); update every
      constructor call site.
- [ ] 3.2 Thread denials in `RoundExecution` onto the `AttemptRecord` it
      builds — for verified rounds and `DecisionNeeded` rounds alike.
- [ ] 3.3 Spec FR2 isolation: an attempt with denials and all-pass checks
      records `PASSED`; denials never appear in `overallVerdict` input nor in
      the prior-failure feedback assembled for a retry.

## 4. Wiring — read at round close (FR3, D1)

- [ ] 4.1 In `ExecutorRoundExecution.run`, read
      `round.environment().denialFindings()` after `round.closeRound()` and
      carry the list on the returned `ExecutionResult`.
- [ ] 4.2 Spec the wiring with a fake environment: a round whose environment
      reports denials yields an `ExecutionResult` carrying them; a guard-less
      environment yields empty.

## 5. Report surfaces (FR4, NFR-O1, NFR-S1, UX1, UX2, D5)

- [ ] 5.1 `StateJsonMapper`: serialize per-attempt `denials` with the state
      finding DTO shape; absent field reads as empty (existing state files
      parse unchanged); update the `StateJsonMapperSpec` round-trip documents.
- [ ] 5.2 `StatusReportJsonMapper`: per-attempt `denials` array with the
      finding DTO shape; update `status-report-v1.reference.json`; keep the
      state↔live equivalence contract green with denials present.
- [ ] 5.3 Text renderer: list an attempt's denials alongside its findings;
      zero denials render nothing (UX2).
- [ ] 5.4 Spec M1 end to end at the mapper level: a denied round on a
      passing attempt shows `"result": "passed"` plus the denial finding
      (host/path/method, no body) in both documents.

## 6. Verification and closure

- [ ] 6.1 Full build green: `./gradlew check` including the PIT 100% gate;
      grep confirms a `src/main` consumer of `denialFindings()` and no public
      `guard()` accessor (M2, M3).
- [ ] 6.2 Verify FR/NFR/UX traceability coverage per `.claude/rules/
      traceability.md`; update the `sandbox-egress` deferral wording only via
      this change's delta spec (main spec updates happen at archive).
- [ ] 6.3 Recommend a Conventional Commits message referencing
      fix-denial-report-attachment.
