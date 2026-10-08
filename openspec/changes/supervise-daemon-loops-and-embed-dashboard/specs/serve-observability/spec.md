# Spec Delta

## ADDED Requirements

### Requirement: Snapshot writer survives the death of its thread
The snapshot writer SHALL be respawned when its thread dies, so that the
snapshot keeps being written while the daemon runs. A single death SHALL be
followed by a write within two snapshot intervals, inside the dashboard's
staleness window, so the dashboard does not report the daemon as dead. Every respawn SHALL be logged at ERROR.
<!-- implements FR6, NFR-O1 of supervise-daemon-loops-and-embed-dashboard -->

#### Scenario: Writer death is not a dead daemon
- **WHEN** the snapshot writer's thread dies once while the daemon serves
- **THEN** a new snapshot is written within two snapshot intervals and the
  dashboard shows no dead-daemon alarm

#### Scenario: Interrupted writer does not spin
- **WHEN** the writer's thread is interrupted without a stop
- **THEN** the next snapshot write happens on the normal timer or trigger, not
  in a tight loop

### Requirement: The final stopped snapshot is the last write across respawns
After the shutdown's final `stopped` write, no further snapshot write SHALL
occur, even if the writer's thread died and a respawn was pending when the
stop began.
<!-- implements FR7 of supervise-daemon-loops-and-embed-dashboard -->

#### Scenario: Death racing the final write
- **WHEN** the writer's thread dies and the stop begins during the respawn
  backoff
- **THEN** the snapshot file's last content is the final `stopped` record
