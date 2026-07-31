# github-tracker — delta

## ADDED Requirements

### Requirement: Beat is an in-place edit of the claim comment
The adapter SHALL implement `heartbeat` as an edit (PATCH) of the existing
claim comment: the comment id — the lease anchor decided by earliest-id — never
changes; the body keeps the structural claim marker and gains/refreshes the
human-readable progress line. One beat SHALL cost exactly one write. The claim
version reported by `listOpen` SHALL be the pair (comment id, `updated_at`)
of the claim comment. A 404 on the edit — the comment no longer exists —
SHALL map to the port's "claim gone" signal, as SHALL a 404 on the comment
listing that resolves the claim (the issue itself is gone — the strongest form
of a lost claim); network errors and any non-404 non-2xx map to infrastructure
failure.
<!-- implements FR1, FR5, FR8 of add-claim-heartbeat -->

#### Scenario: Anchor survives the beat
- **WHEN** the holder beats a claim comment repeatedly
- **THEN** the comment id stays constant while `updated_at` advances, and the
  rendered comment shows the current stage, attempt, and alive-at time

#### Scenario: Deleted comment means claim gone
- **WHEN** the beat PATCH returns 404 because a reaper deleted the comment
- **THEN** the adapter reports the claim as lost, not as an infrastructure
  failure

#### Scenario: Gone issue means claim gone
- **WHEN** the comment listing that resolves the claim returns 404 because the
  issue itself is gone
- **THEN** the adapter reports the claim as lost, not as an infrastructure
  failure, and attempts no PATCH

### Requirement: Open-task listing via state labels with conditional requests
`listOpen` SHALL query open issues carrying the working or needs-human labels
(List Issues API, PR entries excluded), using conditional requests
(`If-None-Match`/ETag) so an unchanged poll costs no rate limit. For each
`Working` task the adapter SHALL resolve the claim comment and report holder
and version; a `Working`-labeled task whose live claim comment is missing but
whose thread still carries a prior claim marker SHALL be reported with that
last-known holder and an absent (null) version — core policy decides what that
means. A `Working`-labeled issue with no claim footprint at all (e.g. a human
mislabel outside the factory's coordination) has no holder to name, and because
the `Working` state requires a holder it SHALL be omitted from the listing
rather than reported with an invented holder.
<!-- implements FR4, FR5 of add-claim-heartbeat -->
<!-- implements NFR-P1 of add-claim-heartbeat -->

#### Scenario: Listing spans both open states
- **WHEN** the repo holds a working issue, a needs-human issue, a ready issue,
  and an open PR labeled working
- **THEN** `listOpen` returns exactly the two open tasks, the working one with
  holder and (comment id, updated_at) version

#### Scenario: Missing live claim keeps the last-known holder
- **WHEN** a working issue's live claim comment is gone but the thread still
  carries a prior claim marker
- **THEN** `listOpen` reports it as `Working` with that last-known holder and an
  absent (null) version

#### Scenario: Working label with no claim footprint is omitted
- **WHEN** an issue wears the working label but its thread carries no claim
  marker at all (a human mislabel outside the factory's coordination)
- **THEN** `listOpen` omits it rather than inventing a holder for the `Working`
  state

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
finds the comment already gone and the label already flipped, both harmless. A
404 on the pre-action re-read (the issue itself is gone) SHALL be the same
safe no-op reporting an absent version, never an infrastructure failure.
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

#### Scenario: Gone issue is a converging no-op
- **WHEN** the pre-action re-read returns 404 because the issue itself is gone
- **THEN** the adapter changes nothing and reports an absent version, never an
  infrastructure failure
