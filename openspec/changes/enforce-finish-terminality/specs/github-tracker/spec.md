# github-tracker — delta

## ADDED Requirements

### Requirement: Finished fact from the finish marker
The GitHub adapter SHALL derive the finished fact structurally: true iff the
issue thread contains a `finish`-kind marker. The derivation SHALL reuse the
per-issue comments fetch (and its conditional-request cache) the feed
enrichment already performs — no additional GitHub API calls.
<!-- implements FR1 of enforce-finish-terminality -->
<!-- implements NFR-P1 of enforce-finish-terminality -->

#### Scenario: Reopened Delivered issue reports finished
- **WHEN** an issue finished with a final report is relabeled back to ready
  by a human and `listReady` runs
- **THEN** the entry reports finished true, derived from the `finish` marker
  in the thread with no extra API read

#### Scenario: Never-finished issue does not report finished
- **WHEN** an issue carrying claim, abort, and park markers — but no `finish`
  marker — is listed
- **THEN** the entry reports finished false

### Requirement: Decline write physics
The GitHub decline SHALL restore the delivered label (transition from the
ready label) first, and only after a successful transition post the
explanation as a NOTE-kind structural comment — never a `park` or `finish`
marker — so the decline itself feeds neither the returned nor the finished
derivation. Racing instances resolve at the label transition; the winner
posts the explanation. Declining an already-delivered issue SHALL change
nothing and post no comment.
<!-- implements FR4 of enforce-finish-terminality -->
<!-- implements NFR-R1, NFR-O1 of enforce-finish-terminality -->

#### Scenario: Decline restores the label and explains
- **WHEN** the factory declines a reopened finished issue
- **THEN** the issue carries the delivered label again and a human-readable
  comment explains the task is finished and a new task or bug should be
  opened

#### Scenario: Already-delivered issue is left untouched
- **WHEN** the factory declines an issue that already carries the delivered
  label
- **THEN** the labels are unchanged and no comment is posted

#### Scenario: Decline comment is derivation-neutral
- **WHEN** a fresh instance re-reads the declined issue's thread
- **THEN** the decline NOTE contributes to no fact — finished stays true from
  the original `finish` marker, returned stays false

## MODIFIED Requirements

### Requirement: Logical states map to mutually exclusive labels
The adapter SHALL map logical states to issue labels with configurable names and
colors — defaults `gnomish:ready` (green `2ea44f`), `gnomish:working` (blue
`1f6feb`), `gnomish:needs-human` (red `d73a4a`), `gnomish:delivered` (purple
`8250df`) — one label for all `AwaitingHuman` reasons (the reason lives in the
park comment). State transitions SHALL use point label add/remove calls, never
whole-set replacement, so concurrent human label edits are not lost. Coordination
facts (claim holder, aborts, acks) SHALL never be encoded in labels. Human
transitions SHALL be recognized from label positions (`needs-human` → `ready` =
returned to work) and issue closure (= revocation), with `state_reason` included
in revocation context.
<!-- implements FR5 of add-tracker-port -->
<!-- implements FR7 of add-tracker-port -->
<!-- implements FR1, FR2 of enforce-finish-terminality -->

#### Scenario: Exclusive transition
- **WHEN** a task moves from `Ready` to `Working`
- **THEN** the adapter removes the ready label and adds the working label with two
  point calls, leaving all unrelated labels untouched

#### Scenario: Human return is visible
- **WHEN** a human moves `needs-human` back to `ready` on a parked issue
- **THEN** a subsequent `fetchTask` reports the task as `Ready`

### Requirement: Structural comments carry coordination facts
Claim, abort, ack, note, park, finish, progress, and stale-claim-removal
comments SHALL carry a machine-recognizable structural marker plus
human-readable text (recommended shape: leading hidden HTML comment with
one-line JSON — kind, instance, time, format version). Park and finish SHALL be distinct marker kinds — the park
marker additionally carries the park reason as its payload; no marker kind is
shared between lifecycle events, and the retired dual-use `report` kind SHALL
NOT be recognized. The round-trip law of the port contract SHALL hold over
these markers: abort facts readable back by any instance; decisions collected
only after the last ack; the abort count reset by the latest progress marker.
<!-- implements FR7 of add-tracker-port -->
<!-- implements NFR-O1 of add-tracker-port -->
<!-- implements FR4 of fix-abort-progress-reset -->
<!-- implements FR1, FR2 of enforce-finish-terminality -->

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

#### Scenario: Park and finish are structurally distinct
- **WHEN** one issue is parked with an escalation report and another is
  finished with a final summary, and a fresh instance parses both threads
- **THEN** the park marker parses as kind `park` carrying its reason, the
  finish marker parses as kind `finish`, and neither is mistakable for the
  other by any field-presence inference

### Requirement: Returned fact from recorded thread markers
The GitHub adapter SHALL derive the returned fact from the structural markers
already recorded in the issue thread — the `park` marker (human-returned)
and the holder-transition ("stale claim removed") marker (reaper-returned) —
using the existing marker anchors, without introducing any new coordination
artifact or label. A `finish` marker SHALL NOT count as returned (it sets
the finished fact instead). Listing reads SHALL stay within the
conditional-request (ETag) discipline the adapter already uses, so an
unchanged queue costs no rate-limit budget.
<!-- implements FR7 of add-factory-serve -->
<!-- implements NFR-P1 of add-factory-serve -->
<!-- implements FR2 of enforce-finish-terminality -->

#### Scenario: Human-returned issue

- **WHEN** an issue carrying a park marker is moved back to the ready label
  and `listReady` runs
- **THEN** the entry reports the returned fact true, derived from the thread
  markers alone

#### Scenario: No new artifacts

- **WHEN** the adapter computes the returned fact for a listing
- **THEN** it writes nothing to the issue — the fact is read-only derivation
  from existing markers

#### Scenario: Finish marker is not returned

- **WHEN** an issue whose thread carries a `finish` marker but no park or
  holder-transition marker is relabeled back to ready and `listReady` runs
- **THEN** the entry reports the returned fact false
