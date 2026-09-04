# Delta: tracker-take

## MODIFIED Requirements

### Requirement: Abort protocol with a K fuse
An infrastructure abort is either an engine `Aborted` outcome (durable persist
failed) or an uncaught exception of the take run itself. On an infrastructure
abort the factory SHALL log ERROR, then best-effort: post the
structural abort comment, release the claim, and return the task to `Ready` via
`recordAbort` — a dead tracker never blocks the abort itself. The
consecutive-abort count SHALL be the crash-cause category of the unified
recovery accounting ("Recovery attempts share one budgeted accounting with the
crash fuse") — one counter model and one quarantine outcome, replacing the
previous standalone fuse counter while remaining shared across instances via
tracker facts. When that accounting reaches the configured threshold, the
factory SHALL instead park the task as `AwaitingHuman(infra)` with a report
carrying the categorized failure history (crash and recovery causes distinct).

Any abort-cause text handed to a tracker write — the structural abort marker's
cause and the fuse-trip report — SHALL first be capped to the abort-cause
budget, a fixed limit sized so the resulting comment body fits every supported
tracker (the smallest known comment limit is Jira Cloud's 32,767 characters)
with headroom for the report's own framing. Truncation SHALL keep the head and
the tail of the text and join them with an explicit marker naming the number of
characters omitted — never a silent cut, and never a cut that drops the end of
the text, where a rendered exception chain carries its root cause. Text within
the budget SHALL pass through byte-for-byte unchanged. The ERROR log and the
task branch's own state records SHALL keep the full, uncapped text: the bound
is the tracker's, not the diagnostic record's.
<!-- implements FR14 of add-tracker-port -->
<!-- implements NFR-R2 of add-tracker-port -->
<!-- implements NFR-C1 of add-tracker-port -->
<!-- implements FR14, NFR-O2 of harden-task-branch-contract -->
<!-- implements FR1 of cap-abort-cause-length -->
<!-- implements NFR-R1 of cap-abort-cause-length -->
<!-- implements NFR-O1 of cap-abort-cause-length -->

#### Scenario: Abort below the fuse
- **WHEN** a run aborts with the shared accounting below its threshold
- **THEN** the task returns to `Ready` with a structural abort comment and the
  slot-free process exits

#### Scenario: Fuse trips at K
- **WHEN** a run aborts and the shared accounting reaches its threshold
- **THEN** the task is parked as `AwaitingHuman(infra)` with a report carrying
  the categorized history (crash vs recovery causes, counts, last cause and
  time) pointing to the structural records as the full cross-instance history

#### Scenario: Runner crash is an abort
- **WHEN** the take run dies with an uncaught exception mid-run
- **THEN** the best-effort abort protocol runs: structural abort comment, claim
  released, task back to `Ready` (or parked at the shared threshold)

#### Scenario: Oversized cause is capped before the tracker write
- **WHEN** a run aborts with a cause longer than the abort-cause budget (e.g. a
  deep rendered exception chain)
- **THEN** the structural abort marker and, at the fuse, the park report carry
  the cause truncated to the budget — head kept, tail kept, an explicit
  omitted-characters marker between them — while the ERROR log carries the full
  text

#### Scenario: Cause within the budget is untouched
- **WHEN** a run aborts with a cause at or under the abort-cause budget
- **THEN** the tracker writes carry the cause byte-for-byte, with no marker

#### Scenario: Capped cause never breaks the abort accounting
- **WHEN** a run aborts with an arbitrarily large cause
- **THEN** the comment body the tracker write produces is within every supported
  tracker's limit, so the abort marker lands and the consecutive-abort count
  stays honest
