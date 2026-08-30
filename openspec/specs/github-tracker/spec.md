# github-tracker

## Purpose

The GitHub adapter of the `Tracker` port: label mapping and provisioning, the
lease-claim protocol over structural comments, feed queries with PR filtering,
canonical task identity, rate-limit economy, and the `tracker.github` config
subsection.

## Requirements

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

### Requirement: Idempotent label provisioning as startup smoke test
At startup the adapter SHALL create missing configured labels with their
configured colors and an operator-hint description, idempotently. Color SHALL
apply only at creation — existing labels are never recolored. Provisioning
failure (e.g. no write access to the configured repo, as after a fork with a
stale binding) SHALL fail the run at startup with an error naming the repo and
the likely cause, never mid-task.
<!-- implements FR5 of add-tracker-port -->
<!-- implements NFR-R4 of add-tracker-port -->
<!-- implements UX1 of add-tracker-port -->

#### Scenario: Second start changes nothing
- **WHEN** the adapter starts twice against the same repo
- **THEN** the second start issues no label mutations

#### Scenario: Operator recolored a label
- **WHEN** an existing label's color differs from the configured color
- **THEN** provisioning leaves the label untouched

#### Scenario: Fork with stale binding dies at startup
- **WHEN** the token cannot write to the configured repo
- **THEN** startup fails with an error naming the repo, before any task is claimed

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

### Requirement: Abort count anchored to the latest progress marker
The GitHub adapter SHALL reconstruct a task's abort count as the number of
`abort` markers that appear strictly after the latest `progress` marker on the
issue; `abort` markers at or before the latest `progress` marker SHALL NOT be
counted. When no `progress` marker is present, the adapter SHALL fall back to
the existing claim-streak reconstruction unchanged. The `progress` marker SHALL
NOT act as a claim boundary in claim-holder resolution — the active claim is
still resolved over `claim`/`abort` markers alone.
<!-- implements FR3 of fix-abort-progress-reset -->

#### Scenario: Feed reader resets on progress
- **WHEN** an issue's comments read claim, abort, progress, abort and the
  adapter builds the `listReady` abort facts
- **THEN** the reported count is one (only the abort after the progress marker)

#### Scenario: fetchTask reader resets on progress
- **WHEN** the same comment stream is read via `fetchTask`
- **THEN** the reported abort count is one, identical to the feed reader

#### Scenario: Progress does not disturb claim-holder resolution
- **WHEN** an issue reads claim(A), progress, and the adapter resolves the
  active claim
- **THEN** the active claim is still A (the progress marker is not treated as a
  later boundary that hides the claim)

### Requirement: Feed via List Issues with PR filtering
`listReady` SHALL query the List Issues API (`state=open`, ready label, sorted
ascending by creation) — not the Search API — and SHALL exclude pull requests
(entries carrying the `pull_request` field). The default readiness criterion is
"open + ready label"; the default queue order is ascending issue number, both
served by the query itself.
<!-- implements FR8 of add-tracker-port -->

#### Scenario: PR wearing the ready label is not a task
- **WHEN** the repo contains an open PR labeled `gnomish:ready` and two ready
  issues
- **THEN** `listReady` returns only the two issues, oldest first

### Requirement: Canonical task identity
Task ids SHALL take the canonical form `github:owner/repo#42`, including the host
only when the configured `api-url` differs from `https://api.github.com` after
normalization (trim, lowercase scheme/host, drop one trailing slash) —
e.g. `github:ghe.example.com/owner/repo#42`. The canonical form is a code
constant, not configuration. The id SHALL flow unchanged into `task.json`, log
MDC, and structural comments; branch names reuse the existing git-task-persistence
sanitize unchanged. The adapter SHALL round-trip its own canonical form back to
issue coordinates; core treats the id as opaque. When a canonical id names a repo
other than the configured binding, the adapter SHALL resolve the id's repo and
accept with a WARN if GitHub's rename redirect lands on the configured repo,
otherwise refuse with an error naming both repos.
<!-- implements FR16 of add-tracker-port -->

#### Scenario: Default host is omitted
- **WHEN** `api-url` is `https://api.github.com/` (trailing slash) and issue 42 of
  `acme/widgets` is claimed
- **THEN** the canonical id is `github:acme/widgets#42`

#### Scenario: Enterprise host is included
- **WHEN** `api-url` is `https://ghe.example.com/api/v3`
- **THEN** canonical ids take the form `github:ghe.example.com/owner/repo#42`

#### Scenario: Renamed repo is tolerated
- **WHEN** a canonical id names `old-org/widgets` and GitHub redirects that repo
  to the configured `acme/widgets`
- **THEN** the operation proceeds with a WARN; a redirect to any other repo is
  refused with an error naming both repos

### Requirement: tracker.github config subsection owned by the adapter
The adapter SHALL declare and validate its `tracker.github` subsection:
`api-url` (mandatory, no code default), `repo` (`owner/name`), and
`labels.{ready,working,needs-human,delivered}` as `{name, color}` objects with
hex color validation. In place of the inline `api-url`, the subsection MAY
reference a named operator-config connection profile as `connection: <name>`,
resolving the endpoint and the token's credential name from the profile (the
vendor-connection-profile capability). A referencing subsection MAY still
declare inline keys the referenced profile does not define — they overlay the
profile — but declaring an inline key the referenced profile also defines is
ambiguous and SHALL be a load error, one per overlapping key. Validation SHALL aggregate errors and fail fast at load,
consistent with pipeline-config error reporting. The token SHALL be resolved
through the `SecretsProvider` port by name — never from yaml and never read
from process env directly; the env/file adapter backs the name with
`GNOMISH_GITHUB_TOKEN`. The token SHALL never reach a task environment or
prompts; the adapter SHALL declare its credential name — through the SPI's
connection-aware credential declaration, resolving the profile-supplied name
when a `connection:` profile is referenced — so the variable can
never be admitted into a child-environment allowlist.
<!-- implements FR17, NFR-S1 of add-tracker-port -->
<!-- implements FR18, NFR-S1 of add-sandbox-core -->
<!-- implements FR16 of add-plugin-architecture -->
<!-- implements FR17 of add-plugin-architecture -->
<!-- implements UX3 of add-plugin-architecture -->

#### Scenario: Missing api-url is a load error
- **WHEN** `tracker.github` lacks `api-url` and declares no `connection:`
  profile reference
- **THEN** loading fails with a located error; no built-in default is applied

#### Scenario: Subsection resolves its connection from a named profile
- **WHEN** `tracker.github` declares `connection: <name>` and the operator
  config defines that profile
- **THEN** the adapter resolves the endpoint and the token's credential name
  from the profile; inline keys the profile does not define overlay it

#### Scenario: Overlapping inline key alongside a profile reference is a load error
- **WHEN** `tracker.github` declares `connection: <name>` and, inline, a key
  the referenced profile also defines
- **THEN** loading fails with a located error per overlapping key, naming both
  the reference and the ambiguous inline key

#### Scenario: Token stays out of the gnome
- **WHEN** a stage executes via the agent CLI while a tracker task is being worked
- **THEN** the task environment's allowlisted env contains no tracker credential

#### Scenario: Backend switch requires no adapter change
- **WHEN** the operator switches the configured `SecretsProvider` adapter
- **THEN** the tracker adapter resolves the same secret name with no code change

### Requirement: Conditional-request polling economy
All repeated polls (feed, round-boundary check) SHALL use
conditional requests (`If-None-Match`/ETag), treating `304 Not Modified` as "no
change" without consuming rate limit. Steady-state single-task operation SHALL
stay within the primary limit (5000 req/h) and secondary write limits by design:
a state transition costs 2–3 writes.
<!-- implements NFR-P1 of add-tracker-port -->

#### Scenario: Unchanged poll is free
- **WHEN** the round-boundary check re-reads an unchanged issue
- **THEN** the request carries `If-None-Match` and a `304` response is handled as
  "no change"

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

### Requirement: Stale-claim removal physics
`removeStaleClaim` SHALL: post the structural "stale claim removed" boundary
marker naming the dead (or last-known) holder and the removed claim's
identity (the marker is a claim boundary — it anchors subsequent claim
verify-reads exactly like release/park/abort/finish markers); delete the dead
claim comment (all instances operate under one token, so deletion is
physically possible); and flip the working label back to ready with point
calls. Before acting it SHALL re-read the claim facts and compare them
against the caller's observation: a live observed version compares as the
(id, `updated_at`) pair today; an observed dead footprint (no live version —
the `ClaimAbandoned` repair) compares by the footprint comment's identity and
no-ops when a live claim comment has appeared since; when the footprint
comment is already gone there is nothing to delete and the marker and label
flip still proceed. Racing removals converge: a second remover finds the
comment already gone and the label already flipped, both harmless. A 404 on
the pre-action re-read (the issue itself is gone) SHALL be the same safe
no-op reporting an absent version, never an infrastructure failure.
<!-- implements FR4, FR5 of add-claim-heartbeat -->
<!-- implements NFR-R2, NFR-O1 of add-claim-heartbeat -->
<!-- implements FR19, FR12 of harden-task-branch-contract -->

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

#### Scenario: Dead footprint removal guards on footprint identity
- **WHEN** `removeStaleClaim` runs for an observed dead footprint while a new
  live claim comment landed meanwhile
- **THEN** the re-read finds the live claim, the adapter changes nothing, and
  the result reports the live claim facts

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

### Requirement: Index-repair physics
`repairIndex` SHALL bring the issue's labels to the state its recorded truth
implies with point calls: for an observed working label with no claim
footprint (`ClaimPending`) it flips the working label back to ready; for an
observed boundary marker newer than the labels it implies (`IndexLagging`) it
flips the labels to the marker's implied pair — delivered for a finish
marker, needs-human for a park marker, ready for an abort or stale-claim
removal marker. It SHALL post the structural repair marker before the label flips, following the
kill-safe ordering, so its own kill window freezes a shape the sweep still
enumerates: the repair marker SHALL NOT be a claim boundary — it implies no
state of its own and must never displace the boundary whose flip it is
completing — so a kill after it leaves the observed shape unchanged
(`IndexLagging` when a boundary's flip was being completed, `ClaimPending` when
a claim was being rolled back), and a later tick runs the same repair again. Before acting it SHALL re-read labels and
comments and no-op, reporting the current facts, when they no longer match
the caller's observation; racing repairs converge on the flipped labels.
<!-- implements FR19, FR12 of harden-task-branch-contract -->

#### Scenario: ClaimPending rolls back to ready
- **WHEN** `repairIndex` runs for a working-labeled issue whose thread carries
  no claim comment
- **THEN** the issue ends with the ready label and a repair marker in the
  thread, and a re-read finding a fresh claim comment instead changes nothing

#### Scenario: Finish marker's flip is completed
- **WHEN** `repairIndex` runs for an issue whose newest boundary marker is a
  finish marker while the issue still wears the working label
- **THEN** the issue ends with the delivered label, the finish marker and
  report untouched — no work re-executes

#### Scenario: Killed repair stays inside the sweep universe
- **WHEN** the repairing instance dies after posting the repair marker but
  before the label flips
- **THEN** the frozen state classifies as the very shape being repaired —
  `IndexLagging` for a flip being completed, `ClaimPending` for a claim being
  rolled back — and a later tick runs the same repair again

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
match exists. All eight existing marker kinds — claim, abort, decision acknowledge, note,
park report, finish report, progress, and stale-claim removal — SHALL be
written through this behavior; no factory write path posts blind.

The identity SHALL be scoped so that re-driving one logical write converges
while a genuinely new occurrence does not. Concretely, the intent is the marker
kind qualified by its occurrence, and the occurrence is the tenure's claim epoch
for every write made under the writer's own claim. Scoping by task alone is
forbidden: four kinds — abort, park, finish, stale-claim removal — are claim
boundaries, the lease anchors on the latest boundary by position in the thread,
and updating a comment in place does not move it, so a task-scoped boundary
marker would freeze the anchor behind claims that have already passed it. The
same scoping keeps the abort count honest, since at most one abort ends a
tenure. Three writes name their occurrence differently, each for a stated
reason: the claim, which mints the epoch and therefore cannot be scoped by it,
scopes by the claiming instance and searches only the window after the newest
boundary marker — so a reclaim past any boundary lands as a new comment and
mints a strictly greater epoch; the stale-claim removal, which acts on another
instance's tenure, scopes by the epoch of the claim it removes; and the decision
acknowledge, which a tenure may perform more than once, scopes by the tenure and
the decision it answers together, so no answered decision is collected twice.
The claimless correspondence paths — a note posted after the claim was dropped,
and the decline of a finished task — scope by their own text, which is their
occurrence.

The claim's find MAY be fused into the verify-read its lease race already
performs rather than paid for as a separate read, since the claim must obtain a
fresh comment id to race with and cannot begin by updating an existing comment:
a claim comment of the same identity already present in the window is a prior
attempt's, it holds the lease by earliest id, the current attempt adopts it as
its epoch, and the duplicate is deleted.
<!-- implements FR11, FR13, UX3 of harden-task-branch-contract -->

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

#### Scenario: A later tenure's boundary marker is a new comment
- **WHEN** a task is parked, returned by a human, claimed again, and parked
  again with a different report
- **THEN** the second park lands as a new comment at the end of the thread — not
  as an edit of the first — so the lease anchors on the second park and a claim
  posted between the two no longer counts as live

#### Scenario: A reclaim past a boundary mints a new claim comment
- **WHEN** the same instance claims a task, the task is returned by an abort,
  and that same instance claims it again
- **THEN** the second claim posts its own comment with a strictly greater id,
  because the first is outside the post-boundary window its identity is
  searched in

#### Scenario: A crashed claim attempt leaves no duplicate
- **WHEN** an instance posts its claim comment, dies before the verify-read,
  and re-drives the same claim within the same boundary window
- **THEN** the earlier comment wins by earliest id and becomes the tenure's
  epoch, and the duplicate the retry posted is deleted

#### Scenario: Every marker of a tenure carries its epoch
- **WHEN** an instance holding a claim writes a park, finish, abort,
  acknowledge, or progress marker
- **THEN** the marker carries the epoch that claim was issued, so a reader can
  tell a superseded tenure's write from the current one

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
