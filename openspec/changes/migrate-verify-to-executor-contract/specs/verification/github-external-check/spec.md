# verification/github-external-check — delta for migrate-verify-to-executor-contract

## MODIFIED Requirements

### Requirement: Polling is stateless and takeover-safe
A poll SHALL depend only on the check declaration, the attempt commit the engine delivers with
the verdict request and the pending key the engine persisted; no other poll state SHALL be
persisted, so any factory instance can resume polling after a crash or takeover and observe the
same runs. The executor SHALL read the attempt commit from the request the engine hands it,
never from a workspace type of its own.
**Implements**: NFR-R2 of add-external-check-github-actions; FR26 of add-sandbox-core; FR2, FR3, FR6 of migrate-verify-to-executor-contract

#### Scenario: Another instance resumes mid-poll
- **WHEN** the polling instance dies and another instance resumes the task
- **THEN** the new instance polls the same attempt commit with the persisted key and reaches the same verdict with no other state handed over

#### Scenario: The adapter consumes the engine workspace type
- **WHEN** the stage engine invokes the `github-actions` verdict executor with a request carrying the attempt commit of the round under verification
- **THEN** the executor reads the attempt commit from that request and polls runs of exactly that commit
