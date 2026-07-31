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
report comment). State transitions SHALL use point label add/remove calls, never
whole-set replacement, so concurrent human label edits are not lost. Coordination
facts (claim holder, aborts, acks) SHALL never be encoded in labels. Human
transitions SHALL be recognized from label positions (`needs-human` → `ready` =
returned to work) and issue closure (= revocation), with `state_reason` included
in revocation context.
<!-- implements FR5 of add-tracker-port -->
<!-- implements FR7 of add-tracker-port -->

#### Scenario: Exclusive transition
- **WHEN** a task moves from `Ready` to `Working`
- **THEN** the adapter removes the ready label and adds the working label with two
  point calls, leaving all unrelated labels untouched

#### Scenario: Human return is visible
- **WHEN** a human moves `needs-human` back to `ready` on a parked issue
- **THEN** a subsequent `fetchTask` reports the task as `Ready`

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
Claim SHALL be implemented as a lease: set the working label, post a structural
claim comment, re-read claim comments posted since the newest boundary marker
(release/park/abort/finish), and treat the earliest comment id (GitHub's
server-side total order) as the winner. The loser SHALL delete its own claim
comment, leave labels as they stand, and report `Held(winner)`. If the
verify-read persistently fails after retries, the adapter SHALL best-effort
delete its own marker and fail the claim as infrastructure, never proceeding
unverified.
<!-- implements FR6 of add-tracker-port -->
<!-- implements NFR-R1 of add-tracker-port -->

#### Scenario: Concurrent claim race
- **WHEN** two instances post claim comments and both re-read (scripted
  interleaving via WireMock)
- **THEN** the instance with the earlier comment id proceeds and the other
  reports `Held` naming the winner and deletes its marker

#### Scenario: Unverifiable claim backs out
- **WHEN** the verify-read keeps failing after the claim comment was posted
- **THEN** the claim fails as infrastructure and the instance does not start work

### Requirement: Structural comments carry coordination facts
Claim, abort, ack, note, report, and progress comments SHALL carry a
machine-recognizable structural marker plus human-readable text (recommended
shape: leading hidden HTML comment with one-line JSON — kind, instance, time,
format version). The round-trip law of the port contract SHALL hold over these
markers: abort facts readable back by any instance; decisions collected only
after the last ack; the abort count reset by the latest progress marker.
<!-- implements FR7 of add-tracker-port -->
<!-- implements NFR-O1 of add-tracker-port -->
<!-- implements FR4 of fix-abort-progress-reset -->

#### Scenario: Markers are invisible to humans, visible to machines
- **WHEN** the adapter posts a claim comment
- **THEN** the rendered GitHub comment shows only the human-readable line, and a
  fresh adapter instance parses holder, time, and kind from the comment body

#### Scenario: Progress marker is a recognized kind
- **WHEN** the adapter posts a progress comment and a fresh adapter instance
  parses the issue's comments
- **THEN** the progress marker is recognized as the `progress` kind with its
  instance and time, alongside the existing claim/abort/ack/note/report kinds

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
hex color validation. Validation SHALL aggregate errors and fail fast at load,
consistent with pipeline-config error reporting. The token SHALL come only from
the `GNOMISH_GITHUB_TOKEN` environment variable — never from yaml — and SHALL
never reach the gnome process environment or prompts; the adapter SHALL declare
`GNOMISH_GITHUB_TOKEN` as its credential variable for the launcher scrub.
<!-- implements FR17 of add-tracker-port -->
<!-- implements NFR-S1 of add-tracker-port -->

#### Scenario: Missing api-url is a load error
- **WHEN** `tracker.github` lacks `api-url`
- **THEN** loading fails with a located error; no built-in default is applied

#### Scenario: Token stays out of the gnome
- **WHEN** a stage executes via the agent CLI while a tracker task is being worked
- **THEN** the gnome process environment contains no tracker credential variables

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
