# sandbox-lifecycle — delta for harden-logging-observability

## MODIFIED Requirements

### Requirement: Fail-closed verdicts
"No verdict" SHALL be distinct from "no claims": a tracker or runtime error during evaluation SHALL skip the affected decisions — removing nothing, emitting skipped-no-verdict — and SHALL never degrade to an empty live set. A destructive action the runtime refused SHALL likewise emit skipped-no-verdict with the failure as its reason, never the action it did not complete. An object listing that could not be obtained affects every decision at once and SHALL abort the whole pass: no verdicts, and — since a tick that reached no object is not a tick that found no work — no completed tick either. A sweep skip never blocks startup or a slot and is retried on the next scheduled pass.

The skipped-no-verdict guarantee SHALL extend to per-object read failures: an
object whose inspection fails or whose inspected shape cannot be interpreted
SHALL emit skipped-no-verdict naming the read failure — an enumerated object
never leaves a pass without a verdict event. Verdict sinks that log SHALL
grade the line's level by category: steady-state categories (checked-alive,
kept-under-threshold) below the default level, action categories at INFO, and
skipped-no-verdict at WARN, so a degraded sweep is distinguishable from a
healthy one on the operator plane.
<!-- implements NFR-R1, NFR-R3 of add-serve-sandbox-lifecycle -->
<!-- implements FR5, FR12 of harden-logging-observability -->

#### Scenario: Tracker outage removes nothing
- **WHEN** the claims listing fails during a sweep tick
- **THEN** no tracked object is stopped or disposed, the tick reports skipped-no-verdict, and the daemon's slots continue unaffected

#### Scenario: A refused removal is not reported as a removal
- **WHEN** the runtime rejects the stop or removal of an object the matrix decided to clean up
- **THEN** the object's verdict is skipped-no-verdict naming the failed action, and no ledger line or count claims it was stopped or disposed

#### Scenario: An unreachable runtime completes no tick
- **WHEN** the object listing cannot be obtained at all
- **THEN** the pass is abandoned with no verdicts and publishes no tick, so the stall is visible to the tick-overdue alert instead of reading as a healthy zero-work tick

#### Scenario: Empty claim list is a real verdict
- **WHEN** the tracker answers successfully with zero fresh claims
- **THEN** tracked objects are evaluated as unowned per the decision matrix

#### Scenario: Unreadable object still gets a verdict
- **WHEN** an enumerated object's inspection fails or returns an
  uninterpretable shape during a pass
- **THEN** the object emits skipped-no-verdict naming the read failure instead
  of silently dropping out of the pass

#### Scenario: Quiet tick, loud degradation
- **WHEN** a sweep tick evaluates objects that are all alive and under
  threshold
- **THEN** the logging sink emits nothing at INFO or above for them, while any
  skipped-no-verdict in the same tick logs at WARN
