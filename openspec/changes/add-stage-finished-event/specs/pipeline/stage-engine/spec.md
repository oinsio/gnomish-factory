## MODIFIED Requirements

Layered on `Engine events` as modified by `tighten-spec-contract` (sequenced before this change):
that delta cut the body to the event list and moved the status-view sentence to its scenario;
this text adds the `StagePassed` variant and the scenario "A stage boundary is read from
StagePassed", and both must survive the later sync.

### Requirement: Engine events
The engine SHALL emit sealed events — RunStarted, AttemptStarted, ExecutionFinished, CheckStarted, CheckFinished, AttemptFinished (new state + trace), StagePassed (passed stage + advanced-to position), TaskFinished — each self-contained with the (taskId, stage, attempt) key shared with logs and telemetry, delivered synchronously; every run, including pre-flight escalations, emits at least RunStarted and TaskFinished; listener exceptions SHALL be logged and swallowed.
**Implements**: FR12, NFR-O2, UX2 of add-stage-engine; FR1 of add-stage-finished-event

#### Scenario: Status reconstructed from events alone
- **WHEN** a recording listener observes a run with a retry
- **THEN** position, attempt counters, check verdicts, and metrics rebuilt from events match the final state

#### Scenario: Broken listener does not break the run
- **WHEN** the listener throws on every event
- **THEN** the run completes normally and each failure is logged

#### Scenario: A stage boundary is read from StagePassed
- **WHEN** a listener needs to know that a stage passed and where the run advanced to
- **THEN** it reads both from the StagePassed event directly, with no inference from a change of position between events

## ADDED Requirements

### Requirement: StagePassed emission point and ordering
The engine SHALL emit StagePassed exactly once per stage whose verify passes in the running process, carrying the task id, the passed stage's name, and the advanced-to position. It SHALL be emitted only after the passing round's state — which already carries the advanced position — is durably persisted, and after that round's AttemptFinished, so the established persist → AttemptFinished ordering is preserved and StagePassed follows both.
**Implements**: FR1, FR2 of add-stage-finished-event

#### Scenario: Ordering on a passing round
- **WHEN** a stage's verify passes and the round is persisted
- **THEN** the recorded event order is AttemptFinished → StagePassed, and the persist completes before both

#### Scenario: Auto advance to the next stage emits the boundary
- **WHEN** a stage that is not the last one passes with auto advancement
- **THEN** StagePassed carries that stage's name and the next stage's position, and the run continues with the next stage

#### Scenario: Final stage completion emits the boundary
- **WHEN** the last stage of the pipeline passes with auto advancement
- **THEN** StagePassed carries that stage's name and the pipeline-end position, followed by TaskFinished with the Completed outcome

#### Scenario: Manual pause still marks the boundary
- **WHEN** a stage with manual advancement passes
- **THEN** StagePassed is emitted for it before the run pauses

### Requirement: StagePassed is not replayed for work done by an earlier run
The engine SHALL NOT emit StagePassed for a pass that was persisted by a previous run: a run resuming a state whose position already reflects an earlier pass, and a run starting at pipeline end, emit no StagePassed for that earlier work. A pass whose persist succeeded but whose process died before emission is therefore notified at most once — the notification is lost, never duplicated.
**Implements**: FR2, NFR-R2 of add-stage-finished-event

#### Scenario: Run starting at pipeline end
- **WHEN** a run starts from a state positioned at pipeline end
- **THEN** the run emits RunStarted and TaskFinished but no StagePassed

#### Scenario: Resume after a persisted pass
- **WHEN** a run resumes a state whose last persisted round passed a stage and advanced the position
- **THEN** no StagePassed is emitted for that already-persisted pass; only stages that pass during this run emit one
