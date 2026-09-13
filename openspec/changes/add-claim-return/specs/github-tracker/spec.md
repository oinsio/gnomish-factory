## ADDED Requirements

### Requirement: Claim-return physics
`returnToReady` on GitHub SHALL be a second entry into the same
claim-retirement routine `removeStaleClaim` runs, differing only in its
marker kind and its comparison key. It SHALL: re-read the claim comment
fresh (never through the conditional-request cache) and compare its holder
and epoch against the caller's own claim identity — any difference, or an
absent comment, or a 404 on the issue, is a safe no-op reporting the current
facts; then post the structural "claim returned" boundary marker naming the
returning holder, the returned claim's identity, and the reason (the marker
is a claim boundary — it anchors subsequent claim verify-reads exactly like
the stale-claim-removed, release, park, abort, and finish markers); delete
the caller's own claim comment; and flip the working label back to ready
with point calls. The marker's human-readable line SHALL read "returned to
ready by <instance>: <reason>". The three writes SHALL run in that order so
every kill window freezes a shape the sweep owns: a marker with the claim
comment still present and the working label on is `IndexLagging`; a deleted
comment with the working label on is `IndexLagging` as well (the marker is
newer than the labels it implies); racing writers converge on the deletion's
404 and the label flip's idempotence.
<!-- implements FR1, FR2, FR4 of add-claim-return -->
<!-- implements NFR-R2, NFR-O2 of add-claim-return -->

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

#### Scenario: Kill after the marker converges through the sweep
- **WHEN** the connection fails after the return marker is posted but before
  the claim comment is deleted
- **THEN** the frozen state classifies `IndexLagging` and the next reaper pass
  completes the flip to ready without a second marker
