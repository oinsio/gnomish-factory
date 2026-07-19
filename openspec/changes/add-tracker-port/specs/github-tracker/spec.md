# github-tracker

## Purpose

The GitHub adapter of the `Tracker` port: label mapping and provisioning, the
lease-claim protocol over structural comments, feed queries with PR filtering,
canonical task identity, rate-limit economy, and the `tracker.github` config
subsection.

## ADDED Requirements

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
Claim, abort, ack, note, and report comments SHALL carry a machine-recognizable
structural marker plus human-readable text (recommended shape: leading hidden
HTML comment with one-line JSON — kind, instance, time, format version). The
round-trip law of the port contract SHALL hold over these markers: abort facts
readable back by any instance; decisions collected only after the last ack.
<!-- implements FR7 of add-tracker-port -->
<!-- implements NFR-O1 of add-tracker-port -->

#### Scenario: Markers are invisible to humans, visible to machines
- **WHEN** the adapter posts a claim comment
- **THEN** the rendered GitHub comment shows only the human-readable line, and a
  fresh adapter instance parses holder, time, and kind from the comment body

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
never reach the gnome process environment or prompts.
<!-- implements FR17 of add-tracker-port -->
<!-- implements NFR-S1 of add-tracker-port -->

#### Scenario: Missing api-url is a load error
- **WHEN** `tracker.github` lacks `api-url`
- **THEN** loading fails with a located error; no built-in default is applied

#### Scenario: Token stays out of the gnome
- **WHEN** a stage executes via the agent CLI while a tracker task is being worked
- **THEN** the gnome process environment contains no tracker credential variables

### Requirement: Conditional-request polling economy
All repeated polls (feed, decision wait, round-boundary check) SHALL use
conditional requests (`If-None-Match`/ETag), treating `304 Not Modified` as "no
change" without consuming rate limit. Steady-state single-task operation SHALL
stay within the primary limit (5000 req/h) and secondary write limits by design:
a state transition costs 2–3 writes.
<!-- implements NFR-P1 of add-tracker-port -->

#### Scenario: Unchanged poll is free
- **WHEN** the decision wait polls an unchanged issue thread
- **THEN** the request carries `If-None-Match` and a `304` response is handled as
  "no new decisions"
