## ADDED Requirements

### Requirement: Claim-return physics
`returnToReady` on GitHub SHALL be a second entry into the same
claim-retirement routine `removeStaleClaim` runs, differing only in its
marker kind and its comparison key. It SHALL re-read the claim comment fresh
(never through the conditional-request cache) and compare its holder and
epoch with the caller's claim identity; then post the "claim returned"
boundary marker; then delete the caller's own claim comment; then
flip the working label back to ready with point calls — in that order.
**Implements**: FR1, FR2, FR4, NFR-R2, NFR-O2 of add-claim-return
**Notes**: The order makes every kill window freeze a shape the sweep owns.

#### Scenario: Return leaves an audit trail
- **WHEN** a slot returns its task on daemon shutdown
- **THEN** the thread shows the "returned to ready by <instance>: daemon
  shutting down" marker, the slot's claim comment is deleted, and the issue
  wears the ready label

#### Scenario: Foreign claim is never touched
- **WHEN** a former holder returns a task whose claim comment now names
  another instance or a newer epoch
- **THEN** no comment is posted or deleted, no label moves, and the result
  reports the live claim

#### Scenario: An absent claim or a missing issue is a no-op
- **WHEN** a holder returns a task whose claim comment is absent, or whose issue answers 404
- **THEN** nothing is written and the result reports the current facts

#### Scenario: The return marker is a claim boundary
- **WHEN** the "claim returned" marker is posted
- **THEN** it names the returning holder, the returned claim's identity and the reason, its
  human-readable line reads "returned to ready by <instance>: <reason>", and it anchors later
  claim verify-reads exactly like the stale-claim-removed, release, park, abort and finish
  markers

#### Scenario: Kill after the marker converges through the sweep
- **WHEN** the connection fails after the return marker is posted but before
  the claim comment is deleted
- **THEN** the frozen state classifies `IndexLagging` and the next reaper pass
  completes the flip to ready without a second marker

#### Scenario: Kill after the deletion converges through the sweep
- **WHEN** the connection fails after the claim comment is deleted but before the working label
  is flipped
- **THEN** the frozen state classifies `IndexLagging`, the marker being newer than the labels it
  implies, and the next reaper pass completes the flip to ready

#### Scenario: Racing writers converge
- **WHEN** a return and another retirement of the same claim run concurrently
- **THEN** both converge on the claim comment deletion's 404 and the label flip's idempotence,
  leaving one ready-labelled issue
