# github-tracker — delta

## ADDED Requirements

### Requirement: Beat is an in-place edit of the claim comment
The adapter SHALL implement `heartbeat` as an edit (PATCH) of the existing
claim comment: the comment id — the lease anchor decided by earliest-id — never
changes; the body keeps the structural claim marker and gains/refreshes the
human-readable progress line. One beat SHALL cost exactly one write. The claim
version reported by `listOpen` SHALL be the pair (comment id, `updated_at`)
of the claim comment. A 404 on the edit — the comment no longer exists —
SHALL map to the port's "claim gone" signal; network errors and 5xx map to
infrastructure failure.
<!-- implements FR1, FR5, FR8 of add-claim-heartbeat -->

#### Scenario: Anchor survives the beat
- **WHEN** the holder beats a claim comment repeatedly
- **THEN** the comment id stays constant while `updated_at` advances, and the
  rendered comment shows the current stage, attempt, and alive-at time

#### Scenario: Deleted comment means claim gone
- **WHEN** the beat PATCH returns 404 because a reaper deleted the comment
- **THEN** the adapter reports the claim as lost, not as an infrastructure
  failure

### Requirement: Open-task listing via state labels with conditional requests
`listOpen` SHALL query open issues carrying the working or needs-human labels
(List Issues API, PR entries excluded), using conditional requests
(`If-None-Match`/ETag) so an unchanged poll costs no rate limit. For each
`Working` task the adapter SHALL resolve the claim comment and report holder
and version; a `Working`-labeled task whose claim comment is missing SHALL be
reported with an absent claim — core policy decides what that means.
<!-- implements FR4, FR5 of add-claim-heartbeat -->
<!-- implements NFR-P1 of add-claim-heartbeat -->

#### Scenario: Listing spans both open states
- **WHEN** the repo holds a working issue, a needs-human issue, a ready issue,
  and an open PR labeled working
- **THEN** `listOpen` returns exactly the two open tasks, the working one with
  holder and (comment id, updated_at) version

#### Scenario: Unchanged listing is free
- **WHEN** `listOpen` polls twice with nothing changed between
- **THEN** the second request carries `If-None-Match` and handles `304` as
  "no change" without consuming rate limit

### Requirement: Stale-claim removal physics
`removeStaleClaim` SHALL: post the structural "stale claim removed" boundary
marker naming the dead holder and the removed claim's identity (the marker is
a claim boundary — it anchors subsequent claim verify-reads exactly like
release/park/abort/finish markers); delete the dead claim comment (all
instances operate under one token, so deletion is physically possible); and
flip the working label back to ready with point calls. Before acting it SHALL
re-check the claim comment's (id, `updated_at`) against the caller's observed
version and no-op on mismatch. Racing removals converge: a second remover
finds the comment already gone and the label already flipped, both harmless.
<!-- implements FR4, FR5 of add-claim-heartbeat -->
<!-- implements NFR-R2, NFR-O1 of add-claim-heartbeat -->

#### Scenario: Removal leaves an audit trail
- **WHEN** a reaper removes a stale claim
- **THEN** the thread shows the boundary marker naming the dead holder, the
  claim comment is deleted, and the issue wears the ready label

#### Scenario: Marker anchors the next lease round
- **WHEN** two instances race to claim the task after a removal
- **THEN** the claim verify-read considers only claim comments posted after
  the removal marker, and earliest comment id wins as usual

#### Scenario: Beaten claim is not removed
- **WHEN** the holder's beat lands between observation and removal
- **THEN** the version re-check fails and the adapter changes nothing
