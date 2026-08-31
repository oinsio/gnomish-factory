# Tasks: cap-abort-cause-length

## 1. Truncation primitive

- [ ] 1.1 TDD (red first): write `AbortCauseBudgetSpec` pinning the contract — a cause at
      or under the budget passes byte-for-byte with no marker (FR1); an over-budget cause
      comes back at most the budget in length, keeps the head and the tail, and carries
      the `[N characters omitted]` marker with the exact omitted count (FR1, NFR-O1); the
      boundary cases (exactly at budget, budget+1) are data-driven rows; a realistic
      rendered exception chain keeps its top-level message in the head and its deepest
      `Caused by:` in the tail. Verify: spec fails against a stub, then passes.
- [ ] 1.2 Implement `AbortCauseBudget` in `application/.../app/take/` per design D2–D4:
      the 28,000-char constant with its derivation comment (Jira 32,767 minus framing
      headroom), static `cap(String)`, head ≈ two thirds / tail ≈ one third, cut on a
      near line boundary when one exists. Traceability: `Implements FR1, NFR-O1 of
      cap-abort-cause-length`. Verify: 1.1 spec green.

## 2. Choke-point wiring

- [ ] 2.1 TDD (red first): extend `AbortHandlerSpec` — an over-budget cause reaches
      `recordAbort`'s `AbortRecord.cause` and the fuse-trip `park(INFRA)` report already
      capped (marker present, length within budget), while the ERROR log event carries the
      full uncapped text (NFR-O1); a within-budget cause reaches both writes unchanged
      (FR1). Drive through a stub `Tracker` and a Logback `ListAppender`. Verify: red.
- [ ] 2.2 Wire `AbortCauseBudget.cap` into `AbortHandler.handle` after the ERROR log and
      before `AbortReportBuilder.build` / `AbortRecord` (design D1); update `AbortHandler`
      and `AbortReportBuilder` javadoc to name the budget guarantee. Traceability comment:
      `Implements FR1, NFR-R1 of cap-abort-cause-length`. Verify: 2.1 spec green,
      existing `AbortHandlerSpec` scenarios stay green.
- [ ] 2.3 Fuse-accounting bound (NFR-R1): add a spec asserting the complete fuse-trip
      report built from a maximal capped cause — framing included — stays under Jira's
      32,767-character comment limit, pinning the headroom invariant of design D2 so a
      framing change that outgrows it fails a test, not production. Verify: spec green.

## 3. Gates and record

- [ ] 3.1 Run `./gradlew :application:check` (Spotless, Error Prone/NullAway, Spock,
      JaCoCo, PIT — mutation gate must stay at 100% for the new class). Verify: BUILD
      SUCCESSFUL with no new PIT survivors.
- [ ] 3.2 Cross-check traceability per `.claude/rules/traceability.md`: every FR/NFR of
      this change greps to at least one implementing entity and one spec. Verify:
      `grep -rn "cap-abort-cause-length" application/src` lists code and specs for FR1,
      NFR-R1, NFR-O1.
