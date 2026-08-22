# Delta: git-task-persistence (fix-lifecycle-push)

## MODIFIED Requirements

### Requirement: Best-effort push
After every round commit the adapter SHALL push best-effort: durability is the recorded branch state; a failed push logs WARN and work continues. In sandboxed mode harvest SHALL precede every push, and a failed harvest skips the push with WARN. The live loop SHALL notice a moved branch tip — host: after tool events (a gnome commit); sandboxed: via the rate-limited environment poll of the Harvest protocol — and harvest and push best-effort mid-round. With no remote configured the run is purely local with no warnings. One exception: for a stage declaring external checks, delivery of the attempt commit is not best-effort — it is a verified precondition of the poll loop (see stage-engine), and an undeliverable commit classifies the check as an infrastructure failure instead of letting it expire as a poll timeout.

The same best-effort push SHALL follow every task lifecycle commit a mode records — task creation, an appended resume decision, every terminal outcome, the `Completed` cleanup commit, and, in host mode only, the tracker-write-confirmed commit (sandboxed mode has no `confirmTerminalWrite` and records none) — in both host mode and sandboxed mode, with one push per lifecycle operation (the `Completed` outcome and its cleanup commit share one push of the resulting tip). Exactly one code path SHALL own the push-after-lifecycle-commit rule: no caller-side lifecycle pushes remain. The push SHALL complete — succeed, fail with WARN, or no-op with no remote — before the lifecycle operation returns to its caller, so any signal the caller sends next (a tracker write) happens after the replication attempt, never before. A failed lifecycle push logs one WARN naming the task, branch, and lifecycle event.
<!-- implements FR11 of add-git-workflow -->
<!-- implements FR5, FR21 of add-sandbox-core -->
<!-- implements FR1, FR2, FR6, NFR-O1 of fix-lifecycle-push -->

#### Scenario: Push failure does not stop work
- **WHEN** origin is unreachable after a round commit
- **THEN** a WARN is logged and the next round starts normally

#### Scenario: Gnome commit triggers a push
- **WHEN** the gnome commits mid-round in host mode and the next tool event is observed
- **THEN** the adapter pushes the task branch best-effort

#### Scenario: Mid-round tip movement in a box is pushed
- **WHEN** the gnome commits mid-round inside the environment and the rate-limited poll observes the moved tip
- **THEN** the adapter harvests the branch and pushes it best-effort

#### Scenario: Completion reaches origin without human involvement
- **WHEN** a task ends `Completed` with an origin remote configured, in host or sandboxed mode
- **THEN** after the outcome and cleanup commits are created the branch is pushed, and the origin tip equals the local tip with no manual push

#### Scenario: Park commit is pushed before the tracker signal
- **WHEN** a host-mode task parks (Escalated or Paused) and its outcome commit with the pending marker is recorded
- **THEN** the push attempt for that commit completes before the terminal tracker write is issued

#### Scenario: Lifecycle push failure stays best-effort
- **WHEN** origin is unreachable while a lifecycle commit is recorded
- **THEN** one WARN names the task, branch, and lifecycle event, the lifecycle operation still succeeds, and the run continues

#### Scenario: Local-only runs stay silent
- **WHEN** a task runs its whole lifecycle in a clone with no origin remote
- **THEN** no push is attempted at any lifecycle commit and no warning is logged

## ADDED Requirements

### Requirement: Origin reconciliation at task touchpoints
At resume start and at a run's terminal boundary, the factory SHALL compare the local task-branch tip with the origin branch tip (one remote-refs read) and, when origin is missing the branch or holds a strict ancestor of the local tip, push best-effort — so a push missed by an earlier crash or outage is delivered by the next instance that touches the task, whichever machine it runs on. The local tip is supplied by the touchpoint from its own execution mode's reader; the reconciliation SHALL never block or fail the run: an unreachable origin or a failed catch-up push degrades to one WARN. A catch-up push that does run logs that origin was behind. With no origin remote the touchpoint check is a silent no-op. No periodic or background reconciliation is introduced.
<!-- implements FR3, NFR-R1, NFR-O1, NFR-C1 of fix-lifecycle-push -->

#### Scenario: A missed terminal push is healed on resume
- **WHEN** an instance crashed after committing a terminal outcome but before its push landed, and any instance later resumes the task
- **THEN** the resume's touchpoint check finds origin behind and pushes the branch best-effort before the run proceeds

#### Scenario: Reconciliation never blocks the run
- **WHEN** the touchpoint check cannot reach origin
- **THEN** one WARN is logged and the resume or terminal path continues unchanged

#### Scenario: Up-to-date origin costs one read
- **WHEN** a touchpoint check runs and origin already holds the local tip
- **THEN** no push is attempted and the check's only remote interaction was the single refs read

### Requirement: Delivery fence before a park's tracker write
In host mode, before the terminal tracker write of a park (Escalated or Paused), the factory SHALL verify the task branch tip is delivered to origin using the same delivery protocol external checks apply to attempt commits: a remote-tip ancestry check first, then a push with one bounded re-attempt on failure. With no origin remote the fence is a silent no-op. A fence that exhausts its re-attempts SHALL NOT block, fail, or delay the park beyond the bounded attempts: the tracker write proceeds, the park report visible to the human carries a one-line note that origin is behind the recorded park, and the pending-write marker stays governed by the existing confirm protocol. The fence applies only to marker-bearing parks — `Completed` and `Aborted` outcomes stay purely best-effort.
<!-- implements FR4, FR5, NFR-R2, NFR-O1, UX2 of fix-lifecycle-push -->

#### Scenario: Fence delivers before the park lands
- **WHEN** a host-mode park's first push attempt failed transiently and the fence's re-attempt succeeds
- **THEN** origin holds the park commit before the tracker's terminal write is issued and the park report carries no replication note

#### Scenario: Fence exhaustion surfaces instead of blocking
- **WHEN** every fence attempt fails against an unreachable origin
- **THEN** the park's tracker write still proceeds, the park report notes that origin is behind, and a WARN records the exhaustion

#### Scenario: No origin, no fence
- **WHEN** a host-mode task parks in a clone with no origin remote
- **THEN** the fence performs no remote interaction and the park proceeds exactly as before
