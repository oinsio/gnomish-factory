# factory-serve — delta for add-base-ref-resolution

## ADDED Requirements

### Requirement: Serve survives base-refresh outages quietly
A base-resolution infrastructure failure in a serve slot SHALL NOT kill the
slot or the daemon: the claim is released per tracker-take, the slot returns
to the feed, and running slots continue. The first failure per remote target
SHALL log WARN with an operator-event code; repeats SHALL be suppressed to
DEBUG with a periodic roll-up, so a dead remote does not flood the log while
serve keeps cycling. No automatic tracker escalation is posted on these
failures; sustained inability to start work surfaces through the existing
abort-threshold accounting and operator observation, not through a new
mechanism.
<!-- implements FR9, NFR-O1 of add-base-ref-resolution -->

#### Scenario: One WARN, then suppression
- **WHEN** the remote is unreachable for an hour while serve keeps
  attempting claims
- **THEN** the log carries one WARN with the event code, subsequent failures
  are DEBUG with a roll-up, the daemon is still running, and every attempted
  task is back in Ready

#### Scenario: Recovery needs no operator action
- **WHEN** the remote returns after an outage
- **THEN** the next feed cycle claims a task, resolves and refreshes its
  base normally, and no residue of the outage (claims, statuses, comments)
  needs manual cleanup
