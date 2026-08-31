# manual-run — delta for harden-logging-observability

## MODIFIED Requirements

### Requirement: Instance-local logging
Logging SHALL go to a single rolling file under `~/.gnomish/logs/` (daily/size roll, bounded history and total size) with `taskId`, `stage`, `attempt` MDC on every line — `taskId` set by the runner, `stage`/`attempt` maintained by an event-listener adapter on the engine thread. The console appender SHALL pass WARN and above only, with ERROR duplicated to stderr; engine events SHALL be logged as structured INFO lines. Logs SHALL never be committed to git.

A manual run SHALL end with the canonical per-task summary line — outcome,
stage, attempts used, wall time, token usage by model — assembled from the
engine's event stream and rendered by the same renderer the other modes use,
for every terminal outcome including aborts. Manual mode is the debugging
mode: the summary and the engine-event INFO lines together SHALL let the
operator see where a run stalled without raising verbosity.
<!-- implements NFR-O1, NFR-O2, NFR-S2 of add-manual-run -->
<!-- implements FR3 of harden-logging-observability -->

#### Scenario: Quiet dialog
- **WHEN** a stage executes and verifies successfully
- **THEN** stdout contains only dialog output while the event INFO lines appear in the log file with full MDC

#### Scenario: Manual run ends with the summary
- **WHEN** a manual run reaches any terminal outcome
- **THEN** the log's last line for that task is the canonical summary carrying
  outcome, stage, attempts, wall time, and token usage
