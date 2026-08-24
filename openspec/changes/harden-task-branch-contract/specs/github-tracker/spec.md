# github-tracker — delta for harden-task-branch-contract

## MODIFIED Requirements

### Requirement: Lease-pattern claim decided by earliest comment id
Claim SHALL be implemented as a lease: post a structural claim comment first, then
set the working label — the claim comment SHALL be durable before the label
transition (or an equivalently ordered sequence whose every intermediate state is
reapable) — then re-read claim comments posted since the newest boundary marker
(release/park/abort/finish), and treat the earliest comment id (GitHub's
server-side total order) as the winner. The loser SHALL delete its own claim
comment, leave labels as they stand, and report `Held(winner)`. If the
verify-read persistently fails after retries, the adapter SHALL best-effort
delete its own marker and fail the claim as infrastructure, never proceeding
unverified. A kill anywhere between the claim writes SHALL leave a state that is
either idempotently re-drivable by a retry of the same claim or owned by the
reaper — never a shape no reaper will reclaim.
<!-- implements FR6 of add-tracker-port -->
<!-- implements NFR-R1 of add-tracker-port -->
<!-- implements FR12 of harden-task-branch-contract -->

#### Scenario: Concurrent claim race
- **WHEN** two instances post claim comments and both re-read (scripted
  interleaving via WireMock)
- **THEN** the instance with the earlier comment id proceeds and the other
  reports `Held` naming the winner and deletes its marker

#### Scenario: Unverifiable claim backs out
- **WHEN** the verify-read keeps failing after the claim comment was posted
- **THEN** the claim fails as infrastructure and the instance does not start work

#### Scenario: Kill between claim comment and label transition is recoverable
- **WHEN** the instance dies after its claim comment posted but before the
  working label transition
- **THEN** the frozen state (claim comment present, ready label still on) is
  claimable or reapable by ordinary means — no shape is left that neither a
  retry nor the reaper will ever resolve

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

## ADDED Requirements

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

### Requirement: Kill-safe ordering of acknowledge and abort writes
A human decision SHALL be durably appended to the task branch before its
acknowledge marker is posted to the tracker; the abort marker SHALL be posted
before the ready label flip. Every kill window between two tracker writes of
one sequence SHALL land in a state either idempotently re-drivable by the next
pickup or owned by the reaper. The abort ordering trades a possible under-count
for a possible over-count: a crash may leave an abort counted without its ready
flip, which fails safe toward parking, never toward losing an abort and
crash-looping.
<!-- implements FR12 of harden-task-branch-contract -->

#### Scenario: Kill between branch append and acknowledge
- **WHEN** an instance appends a decision to the branch and dies before posting
  the acknowledge marker
- **THEN** the next pickup finds the decision on the branch, re-drives only the
  acknowledge (upsert, no duplicate), and never re-collects or loses the
  decision

#### Scenario: Kill between abort marker and ready flip
- **WHEN** an instance posts the abort marker and dies before flipping the
  label back to ready
- **THEN** the abort is already counted (over-count, toward parking), and the
  frozen working label without a live claim is returned to ready by the reaper

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
