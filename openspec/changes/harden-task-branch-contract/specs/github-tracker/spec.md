# github-tracker — delta for harden-task-branch-contract

## MODIFIED Requirements

### Requirement: Lease-pattern claim decided by earliest comment id
Claim SHALL be a lease ordered by the sweep-universe rule: set the working
label first — the write admitting the task into the sweep universe — then post
the structural claim comment, then re-read claim comments posted since the
newest boundary marker and treat the earliest comment id (GitHub's server-side
total order) as the winner. The loser SHALL delete its own claim comment,
leave labels as they stand, and report `Held(winner)`. If the verify-read
persistently fails after retries, the adapter SHALL best-effort delete its own
marker and fail the claim as infrastructure, never proceeding unverified. A kill between the
claim writes freezes a swept shape — `ClaimPending` before the comment, a
dead-holder `Claimed` after — never a state outside the sweep universe or
authoritative in the race: the race-winning comment is the last claim write.
<!-- implements FR6 of add-tracker-port -->
<!-- implements NFR-R1 of add-tracker-port -->
<!-- implements FR12 of harden-task-branch-contract -->

#### Scenario: Concurrent claim race
- **WHEN** two instances post claim comments and both re-read (scripted
  WireMock interleaving)
- **THEN** the earlier comment id proceeds; the other reports `Held` naming
  the winner and deletes its marker

#### Scenario: Unverifiable claim backs out
- **WHEN** the verify-read keeps failing after the claim comment posted
- **THEN** the claim fails as infrastructure; the instance does not start work

#### Scenario: Kill between label transition and claim comment is swept
- **WHEN** the instance dies between the working-label transition and its
  claim comment
- **THEN** the frozen state — working label, no claim footprint — is swept as
  `ClaimPending`, returns to `Ready` after grace, and wins no race meanwhile

### Requirement: Structural comments carry coordination facts
Claim, abort, ack, note, park, finish, progress, and stale-claim-removal
comments SHALL carry a machine-recognizable structural marker plus
human-readable text (recommended shape: leading hidden HTML comment with
one-line JSON — kind, instance, time, format version). The marker SHALL
additionally carry a content identity — the task and the write's intent, never
the bot account — that keys the upsert primitive; markers and epoch stamps
SHALL carry only task identity and counters — no filesystem paths, no
hostnames, no credential material. Park and finish
SHALL be distinct marker kinds — the park marker additionally carries the
park reason as its payload; no marker kind is
shared between lifecycle events, and the retired dual-use `report` kind SHALL
NOT be recognized. The round-trip law of the port contract SHALL hold over
these markers: abort facts readable back by any instance; decisions collected
only after the last ack; the abort count reset by the latest progress marker.
<!-- implements FR7 of add-tracker-port -->
<!-- implements NFR-O1 of add-tracker-port -->
<!-- implements FR4 of fix-abort-progress-reset -->
<!-- implements FR1, FR2 of enforce-finish-terminality -->
<!-- implements FR11, NFR-S1 of harden-task-branch-contract -->

#### Scenario: Markers are invisible to humans, visible to machines
- **WHEN** the adapter posts a claim comment
- **THEN** the rendered GitHub comment shows only the human-readable line, and a
  fresh adapter instance parses holder, time, and kind from the comment body

#### Scenario: Progress marker is a recognized kind
- **WHEN** the adapter posts a progress comment and a fresh adapter instance
  parses the issue's comments
- **THEN** the progress marker is recognized as the `progress` kind with its
  instance and time, alongside the existing
  claim/abort/ack/note/park/finish/stale_claim_removed kinds

#### Scenario: Content identity names the task and intent, not the account
- **WHEN** a marker of any kind is parsed back
- **THEN** its content identity yields the task and the intent that wrote it,
  is independent of the bot account that posted it, and contains no paths,
  hostnames, or credential material

#### Scenario: Park and finish are structurally distinct
- **WHEN** one issue is parked with an escalation report and another is
  finished with a final summary, and a fresh instance parses both threads
- **THEN** the park marker parses as kind `park` carrying its reason, the
  finish marker parses as kind `finish`, and neither is mistakable for the
  other by any field-presence inference

## REMOVED Requirements

### Requirement: Open-task listing via state labels with conditional requests
**Reason**: The listing embedded adapter-side judgment — omitting a
working-labeled issue with no claim footprint as a human mislabel — hiding
the claim sequence's own kill window from every sweep (the ghost-claim
defect).
**Migration**: Replaced by "Open-task listing reports facts via state labels"
below: same query and rate-limit economy, facts only; judgment moves to core.

## ADDED Requirements

### Requirement: Open-task listing reports facts via state labels
`listOpen` SHALL query open issues carrying the working or needs-human labels
(List Issues API, PR entries excluded), using conditional requests
(`If-None-Match`/ETag; `304` is "no change") so an unchanged poll costs no
rate limit. For each task the adapter SHALL report facts only —
the state labels present, the claim facts (live claim with holder and
version, dead footprint with last-known holder, or none), and the latest
boundary-marker kind — and SHALL NOT omit, reinterpret, or judge any
combination; the core classification decides. The claim version remains the
(comment id, `updated_at`) pair of the live claim.
<!-- implements FR4, FR5 of add-claim-heartbeat -->
<!-- implements NFR-P1 of add-claim-heartbeat -->
<!-- implements FR19 of harden-task-branch-contract -->

#### Scenario: Working label with no claim footprint is reported, not omitted
- **WHEN** an issue wears the working label but its thread carries no claim
  marker at all
- **THEN** the listing reports the issue with its label facts and an absent
  claim footprint — no adapter-side rule drops it as a human mislabel

#### Scenario: Missing live claim keeps the last-known holder
- **WHEN** a working issue's live claim comment is gone but the thread still
  carries a prior claim marker
- **THEN** the listing reports the dead footprint with that last-known holder
  and an absent (null) version — core policy decides what it means


### Requirement: Feed entries carry claim facts
Each `listReady` entry SHALL carry the same claim facts as the open listing,
resolved from the per-issue comments fetch the feed enrichment already
performs (no additional API calls), so the sweep enumerates a ready-labeled
issue still carrying a claim footprint instead of losing races to a ghost.
<!-- implements FR19 of harden-task-branch-contract -->

#### Scenario: Ready issue with a live claim is visible to the sweep
- **WHEN** a delayed claim comment landed on a ready-labeled issue
- **THEN** the feed entry reports the live claim fact with no extra API read,
  and the issue routes to repair, never treated as cleanly claimable


### Requirement: Factory comments are written find-then-upsert
Every factory-authored comment SHALL be written through one shared
find-then-upsert behavior: locate an existing comment by its hidden
content-identity marker and update it in place; post a new comment only when no
match exists. The five existing marker kinds — claim, boundary
(stale-claim removal), park report, decision acknowledge, and abort — SHALL be
written through this behavior; no factory write path posts blind.
<!-- implements FR11, UX3 of harden-task-branch-contract -->

#### Scenario: Crash-retry updates instead of duplicating
- **WHEN** an instance posts a park report, dies before confirming the write,
  and the next pickup re-drives the same park
- **THEN** the existing park comment is found by its content-identity marker
  and updated in place — the thread gains no duplicate report

#### Scenario: A different instance re-delivers the same intent
- **WHEN** a resuming instance re-delivers a report another instance already
  posted for the same task and intent
- **THEN** the upsert matches on content identity, not on the posting instance,
  and updates the existing comment

### Requirement: Kill-safe ordering of tracker write sequences
A human decision SHALL be durable on the task branch before its acknowledge
marker posts to the tracker. Tracker sequences SHALL write
truth markers before the label flips that index them: the abort marker before
the ready flip, the finish marker before the delivered flip, the park marker
before the needs-human/working label pair — so a kill window freezes
`IndexLagging`, a swept shape, and the recorded fact (abort count, finished
fact, park report) is never lost with an unflipped label. Every kill window
SHALL land in a named tracker shape either idempotently re-drivable by the
next pickup or owned by the reaper. The abort ordering trades a possible
under-count for an over-count toward parking, which fails safe.
<!-- implements FR12 of harden-task-branch-contract -->

#### Scenario: Kill between branch append and acknowledge
- **WHEN** an instance appends a decision to the branch and dies before posting
  the acknowledge marker
- **THEN** the next pickup finds the decision on the branch and re-drives only
  the acknowledge (upsert, no duplicate) — the decision is never lost

#### Scenario: Kill between abort marker and ready flip
- **WHEN** an instance posts the abort marker, then dies before the ready flip
- **THEN** the abort is already counted (over-count, toward parking) and the
  frozen `IndexLagging` state is returned to ready by the sweep

#### Scenario: Kill between finish marker and delivered flip keeps terminality
- **WHEN** an instance posts the finish marker, then dies before the flip
- **THEN** the finished fact is already derivable, the sweep completes the
  flip, and the delivered work is never re-executed

### Requirement: Label and transport failures join the retryable hierarchy
Label-operation failures and HTTP-transport failures of the tracker SHALL be
classified as retryable under the same policy as tracker-unavailable failures
wherever a bounded terminal-write retry exists; neither SHALL surface as a
distinct terminal error that skips an available retry budget.
<!-- implements FR18 of harden-task-branch-contract -->

#### Scenario: Label flip failure during a terminal write is retried
- **WHEN** the ready-label transition of a terminal write fails with a 5xx
- **THEN** the write retries under the same bounded backoff as a
  tracker-unavailable failure instead of failing the transition terminally

#### Scenario: Transport failure is not terminal
- **WHEN** a tracker write fails with a connection reset before any response
- **THEN** the failure classifies as retryable tracker-unavailable, and the
  bounded terminal-write retry consumes it like any outage
