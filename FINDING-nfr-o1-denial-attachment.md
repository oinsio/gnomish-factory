# Finding: NFR-O1 / UX3 guard denials never reach the task report

**Status:** confirmed defect in `add-sandbox-core` (change not archived). Fix deferred to a
separate OpenSpec change — see `openspec/changes/add-sandbox-core/proposal.md` Q6 and the
NOTE under task 6.2.

**Scope of this change:** none. Read-back plumbing is untouched; only the deferral is recorded.

## Summary

Guard egress denials are computed, parsed, and unit-tested, but **no production code attaches
them to the task report**. The requirement *"Denials are findings, not silence"* (NFR-O1) and
its scenario *"Denied exfiltration attempt reaches the report"* are therefore unmet — a blocked
exfiltration attempt is invisible to a reviewer (breaks scenario U3 / UX3).

This is **not a forgotten one-line call**. Two structural gaps make the intended wiring
impossible without a model change.

## Evidence

| Fact | Location |
|------|----------|
| `denialFindings()` produces `List<Finding>` from the guard log | `adapter/environment/EgressGuard.java:128` |
| `GuardDenialLog` parses marked JSONL into findings | `adapter/environment/GuardDenialLog.java` |
| Denial read-back accessor exists only on the concrete type | `adapter/environment/SelfCheckedEnvironment.java:78` |
| Port declares **no** denial accessor (8 methods, none egress) | `adapter/environment/TaskExecutionEnvironment.java` |
| Round boundary holds the **port** type, never downcasts | `adapter/git/SandboxRoundEnvironmentSource.java:68` |
| Check boundary holds the **port** type, never downcasts | `adapter/check/SandboxCheckEnvironmentSource.java:62,83` |
| Overall stage verdict = **last** check's verdict | `domain/engine/RoundExecution.java` (`overallVerdict`) |
| Findings reach report only via `Verdict.Fail` in `checkResults` | `domain/engine/AttemptRecord.java`, `StateJsonMapper`, `StatusReportJsonMapper` |
| Zero `src/main` consumers of `denialFindings()` / `guard()` | whole-`src` grep |

Build note: the project compiles green today — `@Override` on a record's canonical accessor is
legal Java, so the unreachable `guard()` accessor is not a compile error, just dead code.

## Root cause

1. **Port has no denial accessor.** `SelfCheckedEnvironment.guard()` is reachable only by
   downcasting to the concrete type. The round/check sources hold `TaskExecutionEnvironment`,
   so a consumer literally cannot reach denials through the contract.
2. **Report model has no verdict-independent findings slot.** Findings enter `state.json` /
   `status.json` exclusively through a check `Verdict.Fail(List<Finding>)` inside
   `AttemptRecord.checkResults`, and the stage's overall verdict is the *last* check's verdict.
   Consequently:
   - Folding denials into a `Verdict.Fail` **changes the stage outcome** (PASS → QUALITY_FAILURE)
     — but NFR-O1 is observability, not a gate.
   - `Verdict.Pass` carries no findings, so on a passing attempt there is nowhere to put a denial.

The task chain dropped the ball: **6.2** delegated attachment to *"task 8.2's funnel"*, but
**8.2** routed only judge / external / command findings. The denial channel was never built.

This is the exact twin of the already-resolved NFR-O2 gap (proposal Q4): *"a Pass verdict has
no field to carry the run URL"*, which the project fixed with a domain-model change
(`PollStatus.Pass` gained a field, task **8.4a**). NFR-O1 needs the analogous treatment.

## Recommendations for the follow-up change

Add a **verdict-independent** denial-findings slot to the report path so denials are visible
without touching pass/fail:

1. **Port** — add `List<Finding> denialFindings()` (host-agnostic) to
   `TaskExecutionEnvironment`; `SelfCheckedEnvironment` delegates to `guard.denialFindings()`,
   host/raw environments return `List.of()`. Drop the dead concrete-only `guard()` accessor (or
   keep it internal). This closes gap #1 and lets consumers reach denials through the contract.
2. **Model** — add a `List<Finding>` field (e.g. `denials`) to `AttemptRecord`, independent of
   `checkResults`, so it never participates in `overallVerdict` or `priorFailures`. This closes
   gap #2.
3. **Wiring** — read `environment.denialFindings()` at round close
   (`SandboxRoundEnvironmentSource.closeRound`, near `EnvironmentRoundSnapshot`) and thread the
   list onto the round's `AttemptRecord`. Best-effort semantics of `denialFindings()` are
   preserved (unreadable log → empty, never a failure).
4. **Mappers** — surface the new field additively in `StateJsonMapper` (`state.json`) and
   `StatusReportJsonMapper` (`status.json`); add `StateFindingDto` / `FindingDto`-shaped
   entries; update reference JSON.
5. **Tracker reach — open decision.** Findings reach the tracker only via `EscalationReport`
   rendering (`EscalationResumeDialog.renderEscalation`, currently only `CannotVerify.details`
   is fenced through `TrackerFence`). Decide in the new change whether denials must also reach
   the tracker park report on a non-escalating task, or `status.json` + `state.json` suffice for
   U3/UX3 (the reviewer reads `status.json`).
6. **Tests & traceability** — a spec asserting a denied round produces a denial finding in the
   report on a **passing** attempt (the scenario that today's model cannot represent); trace to
   NFR-O1 / UX3; keep the PIT gate green.

Keep it one initiative (report-model + wiring for egress denials). Related hardening items
(`add-sandbox-hardening` NFR-O1: L7 violations "recorded like denials", tool-stripping findings,
cost ledger) can reuse the same slot but are separate scope.
