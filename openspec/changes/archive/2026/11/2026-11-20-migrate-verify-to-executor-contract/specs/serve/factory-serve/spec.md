# serve/factory-serve — delta for migrate-verify-to-executor-contract

## ADDED Requirements

### Requirement: A tracker egress refusal is an operator precondition of the daemon
A refusal of the egress guard met by a tracker call SHALL be the typed operator precondition
`EGRESS`. Met by the feed's poll or claim, it SHALL not be retried and SHALL open the `EGRESS`
condition; met by a claimed run, it SHALL end the run on the typed precondition result, release
the claim with no abort charge and open the same condition. The condition's probe SHALL repeat the
connect-time judgement of the tracker's endpoint without sending a request.
**Implements**: FR18, NFR-R5 of migrate-verify-to-executor-contract
**Notes**: ADR 0019, decision 5: the operator's grants are the same for every task of the daemon, so their truth is the daemon's; the heartbeat and the reaper keep their designed degradation (design D16 as amended 2026-11-09).

#### Scenario: The feed stops on a refusal instead of retrying it
- **WHEN** the tracker's endpoint host starts resolving into an address class no admitted set grants, and the feed polls
- **THEN** the poll is not retried, the `EGRESS` condition opens naming the tracker's configuration key, and the feed reads nothing from the tracker until a probe passes

#### Scenario: A refusal mid-run is a precondition result, not a crash
- **WHEN** a slot's terminal write to the tracker is refused by the egress guard
- **THEN** the slot ends with the typed precondition result, no uncaught-crash event is logged, the claim is released, the task's abort facts read zero, and the `EGRESS` condition opens

#### Scenario: The probe closes the condition when the grant holds again
- **WHEN** the `EGRESS` condition is open and the endpoint's host resolves again into an admitted address
- **THEN** the next probe passes without sending a request, the condition closes, and the feed claims again
