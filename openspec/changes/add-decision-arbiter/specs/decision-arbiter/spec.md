# decision-arbiter

## ADDED Requirements

### Requirement: Arbiter configuration is per stage and optional
A stage manifest MAY declare a `decisions:` section naming the arbiter
model, its settings, a `rulesFile`, and `maxDecisions`; a stage without the
section SHALL keep the pre-arbiter behavior (decision rounds park for a
human) exactly.
<!-- implements FR1 of add-decision-arbiter -->

#### Scenario: No arbiter configured
- **WHEN** a valid decision request ends a round on a stage with no
  `decisions:` section
- **THEN** the task parks for a human exactly as before this change
- **AND** the escalation report states that no arbiter was configured

### Requirement: Decision rules are law
The decision-rules file SHALL be read once at invocation start from the
factory-owned clone into the frozen pipeline law; content changes in the
gnome's working copy SHALL have no effect on any consult of the running
invocation, and a resume SHALL re-freeze the rules from the clone.
<!-- implements FR2 of add-decision-arbiter -->

#### Scenario: Gnome edits to the rules have no effect
- **WHEN** the gnome rewrites the decision-rules file in its working copy
  mid-task and a consult follows
- **THEN** the arbiter judges under the invocation-frozen rules content

#### Scenario: Human-fixed rules apply on resume
- **WHEN** a human edits the rules file in the factory clone and resumes a
  parked task
- **THEN** subsequent consults use the updated rules

### Requirement: The verdict is a closed selector schema
The arbiter verdict SHALL be either `decided` naming exactly one optionId
from the request's enumerated options plus a rationale and an optional
notify flag, or `cannotDecide` with a reason. Output that fails this
schema — including free-form instructions or an optionId outside the
request's options — SHALL classify as cannot-decide.
<!-- implements FR4 of add-decision-arbiter -->

#### Scenario: Verdict selects an enumerated option
- **WHEN** the arbiter returns `decided` with an optionId from the request
- **THEN** the engine records the decision and the stage continues

#### Scenario: Out-of-vocabulary verdict fails closed
- **WHEN** the arbiter output names an option not in the request, or is
  unparseable prose
- **THEN** the consult result is cannot-decide and the task parks for a
  human with the raw output attached to the report

#### Scenario: Planted instruction cannot escape the option list
- **WHEN** a working-copy file read by the arbiter contains an injected
  instruction demanding an action outside the enumerated options
- **THEN** no verdict other than one of the enumerated options or
  cannot-decide can result
<!-- implements M3 of add-decision-arbiter -->

### Requirement: The arbiter reads, never writes
An arbiter consult SHALL execute with the read-only tool allowlist that a
manifest can only narrow, and SHALL read a fresh environment materialized
from the attempt's harvested commit — never the gnome's live environment
and never with any write-capable tool.
<!-- implements FR7, NFR-S2 of add-decision-arbiter -->

#### Scenario: Live box is not exposed
- **WHEN** a consult runs for a sandboxed round
- **THEN** the arbiter's environment is materialized from the attempt
  commit, and disposing it leaves the gnome's environment untouched

### Requirement: Consult budget is an engine wall
The engine SHALL consult the arbiter at most once per decision request and
at most `maxDecisions` times per stage; a request arriving with the cap
spent SHALL park for a human with the cap exhaustion named in the report.
<!-- implements FR6, NFR-C1 of add-decision-arbiter -->

#### Scenario: Cap exhaustion parks
- **WHEN** a valid decision request follows `maxDecisions` recorded
  consults in the same stage
- **THEN** no consult happens and the task parks with reason "decision cap
  exhausted" and the consult history attached

### Requirement: Advisory notify informs without blocking
A `decided` verdict carrying the notify flag SHALL post one attributed
tracker comment (decision, author, scope) through the marked-comment
primitive while the stage continues; the comment text SHALL be treated as
display data by every reader. A human veto SHALL be the existing
park-and-supersede flow, requiring no new tracker state.
<!-- implements FR8, NFR-S2 of add-decision-arbiter -->

#### Scenario: Notify comment posted, work continues
- **WHEN** the arbiter decides with notify
- **THEN** the next round starts without a park and the tracker carries
  the attributed advisory comment

### Requirement: Consult failures are infrastructure failures
A consult that cannot produce a verdict for infrastructure reasons
(timeout, transport error, process failure) SHALL be retried per the
factory's retry policy without burning a stage attempt; persistent failure
SHALL fall back to the human park with a "cannot consult" report.
<!-- implements NFR-R2 of add-decision-arbiter -->

#### Scenario: Persistent consult failure falls back to human
- **WHEN** every consult retry fails on transport errors
- **THEN** the task parks for a human and the report names the failed
  consult, not a quality failure

### Requirement: Verdicts are durable before they act
The consult SHALL follow intent → effect → receipt: the decided verdict is
committed to the task branch before any round consumes it; recovery of a
kill between consult and commit re-consults, and recovery with a committed
verdict SHALL be a no-op.
<!-- implements NFR-R1 of add-decision-arbiter -->

#### Scenario: Kill between verdict and commit
- **WHEN** the process dies after the arbiter answered but before the
  decision commit
- **THEN** the next pickup re-runs the consult and continues

#### Scenario: Kill after commit
- **WHEN** the process dies after the decision commit
- **THEN** the next pickup finds the committed decision, consults nothing,
  and starts the next round

### Requirement: Every consult is observable and priced
Each consult SHALL leave a structured log line (task, stage, verdict kind,
author) and record arbiter token usage on the round beside executor and
judge usage; unreported usage SHALL stay empty, never fabricated.
<!-- implements NFR-O1 of add-decision-arbiter -->

#### Scenario: Usage reaches the usage report
- **WHEN** a consult reports token usage
- **THEN** the round's record carries it and the usage command renders it
