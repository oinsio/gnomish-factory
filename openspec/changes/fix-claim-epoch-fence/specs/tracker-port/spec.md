## MODIFIED Requirements

### Requirement: Claim issues a monotonic claim token
<!-- implements FR13 of harden-task-branch-contract; FR7 of fix-claim-epoch-fence -->
A successful `claim` SHALL return an opaque claim token that is strictly
increasing per task across successive (re)claims, and the same token SHALL be
readable in the claim facts of `listOpen`, `listReady`, and `heartbeat`
observations. Each adapter chooses its own monotonic source (the GitHub
adapter uses the tracker-assigned claim comment id); core compares tokens only
for order — it never interprets their structure. This token is the claim epoch
of the `task-branch-contract` capability: holders stamp it into commits and
tracker writes as provenance of the tenure that wrote them, and a fenced
tracker operation compares it as the holder's identity. No reader classifies
an older-token artifact as stale; the branch's fence is the fast-forward-only
push and the tracker's fence is the round-boundary revocation check.

#### Scenario: Reclaim returns a greater token
- **WHEN** a task is claimed, reaped, and claimed again by any instance
- **THEN** the second claim's token compares strictly greater than the first's

#### Scenario: Token is observable by other instances
- **WHEN** instance A holds a claim and instance B reads the task's claim
  facts from a listing
- **THEN** B obtains the same token A was issued, as an opaque ordered value
