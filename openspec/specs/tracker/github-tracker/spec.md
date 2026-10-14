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
whole-set replacement, so concurrent human label edits are not lost.
**Implements**: FR5 of add-tracker-port; FR7 of add-tracker-port; FR1, FR2 of enforce-finish-terminality

#### Scenario: Exclusive transition
- **WHEN** a task moves from `Ready` to `Working`
- **THEN** the adapter removes the ready label and adds the working label with two
  point calls, leaving all unrelated labels untouched

#### Scenario: Human return is visible
- **WHEN** a human moves `needs-human` back to `ready` on a parked issue
- **THEN** a subsequent `fetchTask` reports the task as `Ready`

#### Scenario: Coordination facts never ride on labels
- **WHEN** an instance claims a task, records an abort, and acknowledges a
  decision
- **THEN** the issue's labels change only by the state transitions; the claim
  holder, the abort and the acknowledge are recorded in structural comments
  alone, and no label names them

#### Scenario: A closed issue is a revocation carrying its state reason
- **WHEN** a human closes an issue the factory is working
- **THEN** a subsequent `fetchTask` reports the task as `Gone` (revoked), with
  the issue's `state_reason` as the closure reason in the revocation context

### Requirement: Finished fact from the finish marker
The GitHub adapter SHALL derive the finished fact structurally: true iff the
issue thread contains a `finish`-kind marker. The derivation SHALL reuse the
per-issue comments fetch (and its conditional-request cache) the feed
enrichment already performs — no additional GitHub API calls.
**Implements**: FR1 of enforce-finish-terminality; NFR-P1 of enforce-finish-terminality

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
**Implements**: FR4 of enforce-finish-terminality; NFR-R1, NFR-O1 of enforce-finish-terminality

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
**Implements**: FR5 of add-tracker-port; NFR-R4 of add-tracker-port; UX1 of add-tracker-port

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
label first, then post the structural claim comment, then re-read claim
comments posted since the newest boundary marker and treat the earliest comment
id (GitHub's server-side total order) as the winner.
**Implements**: FR6 of add-tracker-port; NFR-R1 of add-tracker-port; FR12 of harden-task-branch-contract
**Notes**: The working label is the write admitting the task into the sweep universe, and the race-winning comment is the last claim write, so every kill window between the claim writes freezes a swept shape.

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

#### Scenario: The losing claimant leaves the labels as they stand
- **WHEN** an instance loses the claim race
- **THEN** it deletes only its own claim comment, does not revert the
  working-label add, and reports `Held(winner)`

#### Scenario: An unverifiable claim deletes its own marker
- **WHEN** the verify-read keeps failing after retries
- **THEN** the adapter best-effort deletes its own claim comment before
  failing the claim as infrastructure, and a failed delete does not mask the
  original failure

#### Scenario: Kill after the claim comment freezes a dead-holder claim
- **WHEN** the instance dies after posting its claim comment and before the
  verify-read
- **THEN** the frozen state is a `Claimed` task whose holder is dead — a shape
  the sweep enumerates — never a state outside the sweep universe

### Requirement: Structural comments carry coordination facts
Claim, abort, ack, note, park, finish, progress, stale-claim-removal, and
index-repair comments SHALL carry a machine-recognizable structural marker plus
human-readable text. The marker SHALL additionally carry a content identity —
the task and the write's intent, never the bot account — that keys the upsert
primitive. Park and finish SHALL be distinct marker kinds, the park marker
carrying the park reason as its payload; no marker kind is shared between
lifecycle events.
**Implements**: FR7 of add-tracker-port; NFR-O1 of add-tracker-port; FR4 of fix-abort-progress-reset; FR1, FR2 of enforce-finish-terminality; FR11, NFR-S1 of harden-task-branch-contract
**Notes**: The recommended marker shape is a leading hidden HTML comment holding one-line JSON — kind, instance, time, format version.

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

#### Scenario: Markers carry no paths, hostnames, or credentials
- **WHEN** the adapter writes a marker of any kind, with or without an epoch
  stamp, for a task on an enterprise host
- **THEN** the hidden marker and its epoch stamp carry only task identity and
  counters — no filesystem path, no hostname, no credential material

#### Scenario: The retired report kind is not recognized
- **WHEN** a fresh instance parses a comment whose marker carries the retired
  dual-use `report` kind
- **THEN** the comment is not recognized as any marker kind

#### Scenario: Abort facts read back on any instance
- **WHEN** one instance records an abort and a fresh instance fetches the task
- **THEN** the fresh instance reads the same abort facts back from the markers

#### Scenario: Decisions are collected only after the last ack
- **WHEN** an issue thread carries human replies before and after the latest
  acknowledge marker and decisions are collected
- **THEN** only the replies posted after that acknowledge are returned

### Requirement: Abort count anchored to the latest progress marker
The GitHub adapter SHALL reconstruct a task's abort count as the number of
`abort` markers that appear strictly after the latest `progress` marker on the
issue; `abort` markers at or before the latest `progress` marker SHALL NOT be
counted. The `progress` marker SHALL NOT act as a claim boundary in
claim-holder resolution.
**Implements**: FR3 of fix-abort-progress-reset

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

#### Scenario: Without a progress marker the claim-streak count applies
- **WHEN** an issue's comments carry claim and abort markers but no `progress`
  marker
- **THEN** the abort count is the existing claim-streak reconstruction,
  unchanged

### Requirement: Feed via List Issues with PR filtering
`listReady` SHALL query the List Issues API (`state=open`, ready label, sorted
ascending by creation) — not the Search API — and SHALL exclude pull requests
(entries carrying the `pull_request` field). The default readiness criterion is
"open + ready label"; the default queue order is ascending issue number, both
served by the query itself.
**Implements**: FR8 of add-tracker-port

#### Scenario: PR wearing the ready label is not a task
- **WHEN** the repo contains an open PR labeled `gnomish:ready` and two ready
  issues
- **THEN** `listReady` returns only the two issues, oldest first

### Requirement: Canonical task identity
Task ids SHALL take the canonical form `github:owner/repo#42`, including the host
only when the configured `api-url` differs from `https://api.github.com` after
normalization (trim, lowercase scheme/host, drop one trailing slash) —
e.g. `github:ghe.example.com/owner/repo#42`. The canonical form is a code
constant, not configuration.
**Implements**: FR16 of add-tracker-port
**Notes**: Core treats the id as opaque; only the adapter reads its parts.

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
- **THEN** the operation proceeds with an INFO; a redirect to any other repo is
  refused with an error naming both repos

#### Scenario: The canonical id flows unchanged into the records
- **WHEN** a task with a canonical id is claimed and worked
- **THEN** `task.json` and the log MDC carry the id unchanged, the structural
  comments carry the task's repository-scoped identity (`owner/repo#42`, no
  host), and the task branch name is the id passed through the existing
  git-task-persistence sanitize, unchanged

#### Scenario: The canonical id round-trips to issue coordinates
- **WHEN** the adapter parses a canonical id it built, with or without a host
- **THEN** it recovers the same host, owner, repo and issue number, and
  rebuilding from them yields the same canonical string

### Requirement: tracker.github config subsection owned by the adapter
The adapter SHALL declare and validate its `tracker.github` subsection:
`api-url` (mandatory, no code default), `repo` (`owner/name`),
`labels.{ready,working,needs-human,delivered}` as `{name, color}` objects with
hex color validation, and an optional `designators` map of designator kind →
regular expression. Instead of the inline `api-url`, the subsection MAY
reference an operator-config connection profile as `connection: <name>`.
Validation SHALL aggregate errors and fail fast at load.
**Implements**: FR17, NFR-S1 of add-tracker-port; FR18, NFR-S1 of add-sandbox-core; FR16 of add-plugin-architecture; FR17 of add-plugin-architecture; UX3 of add-plugin-architecture; FR3 of add-base-ref-resolution
**Notes**: Designator kinds are open; `base` and `type` are the ones the factory consumes. The connection profile is the vendor-connection-profile capability; the error aggregation is consistent with pipeline-config error reporting.

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

#### Scenario: Designator rule without exactly one capture group is a load error
- **WHEN** `designators.base` is `"base:.+"` (no group) or `"(base):(.+)"`
  (two groups)
- **THEN** loading fails with a located error naming
  `tracker.github.designators.base`, aggregated with any other config errors

#### Scenario: Token stays out of the gnome
- **WHEN** a stage executes via the agent CLI while a tracker task is being worked
- **THEN** the task environment's allowlisted env contains no tracker credential

#### Scenario: Backend switch requires no adapter change
- **WHEN** the operator switches the configured `SecretsProvider` adapter
- **THEN** the tracker adapter resolves the same secret name with no code change

#### Scenario: A designator rule that does not compile or has a blank kind is a load error
- **WHEN** a `designators` value is not a valid regular expression, or a
  `designators` key is blank
- **THEN** loading fails with a located error for each, aggregated with any
  other config errors

#### Scenario: The token comes only from the secrets provider
- **WHEN** the subsection carries a token-shaped key, or the process
  environment holds a token under another name
- **THEN** the yaml key is a load error and the adapter reads neither: it
  resolves the token through the `SecretsProvider` port by its credential
  name, which the env/file adapter backs with `GNOMISH_GITHUB_TOKEN`

#### Scenario: The declared credential name keeps the token out of every allowlist
- **WHEN** the adapter declares its credential name through the SPI's
  connection-aware credential declaration, with or without a `connection:`
  profile that renames the credential
- **THEN** the declared name is the one actually resolved — the
  profile-supplied name when a profile is referenced — and that variable is
  never admitted into a child-environment allowlist

#### Scenario: The token never reaches a prompt
- **WHEN** a stage's prompt is assembled while a tracker task is being worked
- **THEN** the prompt contains no tracker credential

### Requirement: Conditional-request polling economy
All repeated polls (feed, round-boundary check) SHALL use
conditional requests (`If-None-Match`/ETag), treating `304 Not Modified` as "no
change" without consuming rate limit. Steady-state single-task operation SHALL
stay within the primary limit (5000 req/h) and secondary write limits by design:
a state transition costs 2–3 writes.
**Implements**: NFR-P1 of add-tracker-port

#### Scenario: Unchanged poll is free
- **WHEN** the round-boundary check re-reads an unchanged issue
- **THEN** the request carries `If-None-Match` and a `304` response is handled as
  "no change"

### Requirement: Beat is an in-place edit of the claim comment
The adapter SHALL implement `heartbeat` as an edit (PATCH) of the existing
claim comment: the comment id — the lease anchor decided by earliest-id — never
changes; the body keeps the structural claim marker and gains/refreshes the
human-readable progress line. A 404 on the edit — the comment no longer
exists — SHALL map to the port's "claim gone" signal, as SHALL a 404 on the
comment listing that resolves the claim.
**Implements**: FR1, FR5, FR8 of add-claim-heartbeat
**Notes**: A 404 on the listing means the issue itself is gone — the strongest form of a lost claim.

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

#### Scenario: A beat costs one write
- **WHEN** the holder beats its claim once
- **THEN** the adapter issues exactly one write — the PATCH of the claim
  comment — and no label or comment-creation call

#### Scenario: The claim version is the comment id and its update time
- **WHEN** `listOpen` reports a working task with a live claim
- **THEN** the claim version is the pair (comment id, `updated_at`) of the
  claim comment, and a beat changes only its `updated_at` half

#### Scenario: Other beat failures are infrastructure
- **WHEN** the beat PATCH or the comment listing that resolves the claim fails
  with a network error or any non-404 non-2xx status
- **THEN** the adapter reports an infrastructure failure, never a lost claim

### Requirement: Stale-claim removal physics
`removeStaleClaim` SHALL post the structural "stale claim removed" boundary
marker naming the dead (or last-known) holder and the removed claim's
identity, delete the dead claim comment, and flip the working label back to
ready with point calls. Before acting it SHALL re-read the claim facts and
compare them against the caller's observation, changing nothing when they no
longer match.
**Implements**: FR4, FR5 of add-claim-heartbeat; NFR-R2, NFR-O1 of add-claim-heartbeat; FR19, FR12 of harden-task-branch-contract
**Notes**: All instances operate under one token, so deleting another instance's claim comment is physically possible. An observed dead footprint is the `ClaimAbandoned` repair: a footprint with no live version. On GitHub that footprint is a claim comment a boundary already voided and that is still in the thread; the removal scopes its marker by the epoch that comment carries and deletes it, so a removal killed after its own marker and re-driven lands on that marker.

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

#### Scenario: A vanished footprint still gets the marker and the flip
- **WHEN** `removeStaleClaim` runs for an observed dead footprint whose voided
  claim comment a racing remover deletes after the pre-action re-read
- **THEN** the delete answered 404 is tolerated, and the adapter still posts
  the boundary marker naming the last-known holder and flips the label to ready

#### Scenario: Racing removals converge
- **WHEN** two reapers remove the same stale claim concurrently
- **THEN** the issue ends without the dead claim comment and with the ready
  label, and neither remover fails: a delete answered 404 and a claim found
  already gone are both harmless

### Requirement: Returned fact from recorded thread markers
The GitHub adapter SHALL derive the returned fact from the structural markers
already recorded in the issue thread — the `park` marker (human-returned)
and the holder-transition ("stale claim removed") marker (reaper-returned) —
using the existing marker anchors, without introducing any new coordination
artifact or label.
**Implements**: FR7 of add-factory-serve; NFR-P1 of add-factory-serve; FR2 of enforce-finish-terminality

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

#### Scenario: Returned-fact listing reads are conditional

- **WHEN** `listReady` re-reads an unchanged queue to compute the returned fact
- **THEN** every read carries `If-None-Match`, and the `304` answers are
  handled as "no change" without consuming rate-limit budget

### Requirement: Index-repair physics
`repairIndex` SHALL bring the issue's labels to the state its recorded truth
implies with point calls: for an observed working label with no claim
footprint (`ClaimPending`) it flips the working label back to ready; for an
observed boundary marker newer than the labels it implies (`IndexLagging`) it
flips the labels to the marker's implied pair — delivered for a finish
marker, needs-human for a park marker, ready for an abort or stale-claim
removal marker.
**Implements**: FR19, FR12 of harden-task-branch-contract
**Notes**: The repair marker implies no state of its own, which is why a kill after it leaves the observed shape for a later tick to repair again.

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

#### Scenario: Park and return marker flips are completed
- **WHEN** `repairIndex` runs for an issue whose newest boundary marker is a
  park marker, or an abort or stale-claim removal marker, while its labels
  still imply an older state
- **THEN** the issue ends with the needs-human label for the park, and with
  the ready label for the abort or the removal

#### Scenario: The repair marker is posted before the flips
- **WHEN** `repairIndex` acts on an observed shape
- **THEN** the structural repair marker posts before any label flip

#### Scenario: The repair marker is not a claim boundary
- **WHEN** a repair marker follows a boundary marker in the thread and the
  claim facts are resolved
- **THEN** the boundary the repair completed is still the latest boundary —
  the repair marker displaces nothing and anchors no claim race

#### Scenario: A repair whose observation no longer holds reports the current facts
- **WHEN** the pre-action re-read of labels and comments no longer matches
  the caller's observation
- **THEN** the adapter writes nothing and reports the current facts

#### Scenario: Racing repairs converge
- **WHEN** two instances repair the same observed shape concurrently
- **THEN** the issue ends with the flipped labels the recorded truth implies,
  and neither repair fails

### Requirement: Open-task listing reports facts via state labels
`listOpen` SHALL query open issues carrying the working or needs-human labels
(List Issues API, PR entries excluded). For each task the adapter SHALL report
facts only — the state labels present, the claim facts (live claim with holder
and version, dead footprint with last-known holder, or none), and the latest
boundary-marker kind — and SHALL NOT omit, reinterpret, or judge any
combination; the core classification decides.
**Implements**: FR4, FR5 of add-claim-heartbeat; NFR-P1 of add-claim-heartbeat; FR19 of harden-task-branch-contract

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

#### Scenario: An unchanged open listing is a free 304
- **WHEN** `listOpen` polls an unchanged set of open issues again
- **THEN** each request carries `If-None-Match`, and a `304` response is
  handled as "no change" without consuming rate limit

### Requirement: Feed entries carry claim facts
Each `listReady` entry SHALL carry the same claim facts as the open listing,
resolved from the per-issue comments fetch the feed enrichment already
performs (no additional API calls), so the sweep enumerates a ready-labeled
issue still carrying a claim footprint instead of losing races to a ghost.
**Implements**: FR19 of harden-task-branch-contract

#### Scenario: Ready issue with a live claim is visible to the sweep
- **WHEN** a delayed claim comment landed on a ready-labeled issue
- **THEN** the feed entry reports the live claim fact with no extra API read,
  and the issue routes to repair, never treated as cleanly claimable

### Requirement: Factory comments are written find-then-upsert
Every factory-authored comment other than the claim SHALL be written through
one shared find-then-upsert behavior: locate an existing comment by its hidden
content-identity marker and update it in place; post a new one only when none
matches. The claim comment SHALL be posted, then verified by a read-back
of the thread that deletes any duplicate. The identity SHALL be the marker kind
qualified by its occurrence (the claim epoch, for a write under the writer's
own claim), never the task alone.
**Implements**: FR11, FR13, UX3 of harden-task-branch-contract
**Notes**: Scoping by occurrence is what lets a re-driven logical write converge while a new occurrence lands as a new comment; the claim's read-back is design D6 of harden-task-branch-contract.
**Notes**: Scoping by task alone would freeze the lease anchor: abort, park, finish and stale-claim removal are claim boundaries, the lease anchors on the latest boundary by position in the thread, and an in-place update does not move a comment.
**Notes**: The claim mints the epoch and so cannot be scoped by it; it must obtain a fresh comment id to race with, which is why its find is fused into the lease race's verify-read rather than paid for as a separate read.

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

#### Scenario: Every marker kind is written through the upsert
- **WHEN** the adapter writes a claim, abort, decision acknowledge, note, park
  report, finish report, progress, stale-claim removal, or index-repair
  comment
- **THEN** every kind but the claim goes through the shared find-then-upsert
  behavior, and the claim is posted and verified by its read-back, which
  deletes a duplicate — no factory write path posts blind

#### Scenario: Each tenure ends with its own abort comment
- **WHEN** a task is aborted in one tenure, claimed again, and aborted in the
  next tenure
- **THEN** the second abort lands as a new comment, not as an edit of the
  first, and re-driving either abort updates only its own tenure's comment

#### Scenario: Each removed claim gets its own removal marker
- **WHEN** stale claims of two different tenures of one task are removed in
  turn
- **THEN** each removal marker is scoped by the epoch of the claim it removes,
  so the second removal lands as a new comment, while re-driving one removal
  updates its own comment

#### Scenario: Each answered decision gets its own acknowledge
- **WHEN** one tenure acknowledges two different decisions, and then re-drives
  the acknowledge of the first
- **THEN** the two decisions get two acknowledge comments, the re-drive updates
  the first in place, and no answered decision is collected twice

#### Scenario: Claimless correspondence is keyed by its text
- **WHEN** the adapter posts a note after the claim was dropped, or declines a
  finished task, and later re-drives the same text and posts a different one
- **THEN** the same text updates its own comment and the different text lands
  as a new comment, and neither carries an epoch stamp

### Requirement: Kill-safe ordering of tracker write sequences
A human decision SHALL be durable on the task branch before its acknowledge
marker posts to the tracker. Tracker sequences SHALL write truth markers before
the label flips that index them: the abort marker before the ready flip, the
finish marker before the delivered flip, the park marker before the
needs-human/working label pair. Every kill window SHALL land in a named tracker
shape either idempotently re-drivable by the next pickup or owned by the
reaper.
**Implements**: FR12 of harden-task-branch-contract
**Notes**: The abort ordering trades a possible under-count for an over-count toward parking, which fails safe.

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

#### Scenario: Kill between park marker and label flip keeps the report
- **WHEN** an instance posts the park marker, then dies before the
  needs-human/working label pair flips
- **THEN** the park report is already recorded, the frozen state is
  `IndexLagging`, and the sweep completes the flip to needs-human

### Requirement: Label and transport failures join the retryable hierarchy
Label-operation failures and HTTP-transport failures of the tracker SHALL be
classified as retryable under the same policy as tracker-unavailable failures
wherever a bounded terminal-write retry exists; neither SHALL surface as a
distinct terminal error that skips an available retry budget.
**Implements**: FR18 of harden-task-branch-contract

#### Scenario: Label flip failure during a terminal write is retried
- **WHEN** the ready-label transition of a terminal write fails with a 5xx
- **THEN** the write retries under the same bounded backoff as a
  tracker-unavailable failure instead of failing the transition terminally

#### Scenario: Transport failure is not terminal
- **WHEN** a tracker write fails with a connection reset before any response
- **THEN** the failure classifies as retryable tracker-unavailable, and the
  bounded terminal-write retry consumes it like any outage

### Requirement: Designator candidates are extracted from issue labels by configured rule
For every kind declared under `tracker.github.designators`, the adapter SHALL
apply the kind's regular expression to each label name the issue carries,
take the single capture group of every full match as a candidate value, and
ignore labels that do not match.
**Implements**: FR3 of add-base-ref-resolution

#### Scenario: One matching label yields one candidate
- **WHEN** `designators.base` is `"base:(.+)"` and the issue carries the
  labels `base:release/1.18` and `gnomish:ready`
- **THEN** the `base` designator is the single value `release/1.18` and the
  state label plays no part

#### Scenario: Partial matches do not count
- **WHEN** the rule is `"base:(.+)"` and a label is `my-base:foo`
- **THEN** the label yields no candidate

#### Scenario: Two captures with different values are a conflict
- **WHEN** the issue carries `base:release/1.18` and `base:release/1.19`
- **THEN** the `base` designator is a conflict listing both values, in label
  order as the API returned them

#### Scenario: Undeclared kind is neither extracted nor reported
- **WHEN** the subsection declares no `designators` map
- **THEN** the factory reports no configured kinds and every designator the
  facts carry is absent

#### Scenario: Candidates are classified only by the shared port function
- **WHEN** the adapter has extracted the candidates of a kind for `fetchTask`
- **THEN** the facts carry exactly what the port's shared classification
  function (tracker-port) returns for them — absent, single, or conflict —
  with no resolution, defaulting, or conflict-picking in the adapter

#### Scenario: Only the declared kinds are reported
- **WHEN** the subsection declares a rule for `base` only
- **THEN** the adapter factory seam reports exactly the kind `base`, and the
  `type` designator is never extracted

### Requirement: An interrupt is not a transport failure
The GitHub HTTP client shared by the tracker and the external-check adapters
SHALL treat an interrupt of the calling thread during a request as a
cancellation: it SHALL make no further attempt under its retry policy, SHALL
leave the thread's interrupt set, and SHALL fail with a cancellation that is
distinct from the exhausted-transport failure.
**Implements**: FR11, NFR-R3 of fix-operator-blockers

#### Scenario: One attempt, then a cancellation
- **WHEN** the calling thread is interrupted while GitHub has not yet
  answered a request
- **THEN** GitHub receives exactly one request, the client fails with the
  cancellation rather than a transport failure, and the thread's interrupt
  is set

#### Scenario: Tracker write interrupted mid-request
- **WHEN** a tracker write (park, finish, decline, note) is interrupted
  mid-request
- **THEN** the failure is not translated into a tracker-unavailable outage

#### Scenario: Interrupted external-check poll is not "cannot verify"
- **WHEN** the workflow-runs query of an external check is interrupted
  mid-request
- **THEN** the poll returns no cannot-verify status; the cancellation
  propagates out of the poll unchanged and no outcome line calls it an
  infrastructure failure

#### Scenario: A real transport failure keeps its retries
- **WHEN** a request fails with a connection reset and the thread is not
  interrupted
- **THEN** the client retries under its policy exactly as before

#### Scenario: No message calls a cancellation a failure after retries
- **WHEN** a request is cancelled by an interrupt of the calling thread
- **THEN** neither the cancellation's message nor any log line it produces
  calls it a failure "after retries"
