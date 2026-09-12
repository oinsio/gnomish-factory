# git-task-persistence — delta for signal-outage-gate-on-origin-contact

Layered on "Base refresh fetch before task creation" as added by
`add-base-ref-resolution` (sequenced before this change): the requirement
below is written over that delta's text, not over `openspec/specs/`, so
syncing in that order merges cleanly. If this change syncs first, the later
sync must merge by hand — the origin-contact sentence and its two scenarios
are what must survive.

## MODIFIED Requirements

### Requirement: Base refresh fetch before task creation
In autonomous fresh starts the factory SHALL refresh the resolved base with a
narrow fetch of exactly that ref, after the factory clone is hardened and
before the task is created. The destination is fixed per kind: a branch
updates its remote-tracking ref `refs/remotes/origin/<name>` (forced); a tag
is written to `refs/tags/<name>` without force, so an existing local tag of
that name pointing at another commit refuses the fetch and parks the task
with a report naming both commits; a bare commit SHA is checked for
presence locally first, fetched by SHA only when absent, and verified as a
commit object afterwards — no ref is written for it. The fetch SHALL NOT
auto-follow other tags, SHALL NOT be read through `FETCH_HEAD` (the SHA is
read back from the destination ref or the object), and SHALL NOT touch the
operator clone's working tree, index, HEAD, `refs/heads/*`, or any
pre-existing `refs/tags/*` entry. It inherits the bounded-network rules
(deadline, stall detection, credential scrubbing) and the existing git
infrastructure retry policy, and it runs under the clone's mutation lock
like every other fetch. The refresh is fail-closed: no task branch is
created from a base whose freshness could not be established. A
reachability failure classifies as an infrastructure failure — no stage
attempt burned, resolution and fetch precede every durable task-branch
write; a diverging local tag or a remote refusing fetch-by-SHA is a
task-level park. Pull remains forbidden on every path.

A successful refresh SHALL state whether origin was contacted, as a fact
with exactly two values set where the path taken is known: a branch or tag
narrow fetch and a fetch-by-SHA that delivered the object are contacts; a
commit SHA served from the clone's own object store is not. The resume-time
resolution of a pinned base carries the same fact: a refresh through a
configured origin carries the refresh's value, a bind from the local tip of
a clone with no origin is not a contact. The fact is not persisted and
changes no binding, park, or release decision; its one reader is the remote
outage gate (see factory-serve, "Remote outage gate holds the feed off the
tracker").
<!-- implements FR6, FR9, NFR-P1, NFR-R1 of add-base-ref-resolution -->
<!-- implements FR1, FR4, NFR-R1 of signal-outage-gate-on-origin-contact -->

#### Scenario: Fresh base at claim
- **WHEN** origin's `develop` has advanced past the factory clone's last
  fetch and a task resolving to `develop` is claimed
- **THEN** the task branch starts from origin's current `develop` tip, and
  the clone's own local branches and HEAD are unchanged

#### Scenario: A name origin holds as both a branch and a tag parks the task
- **WHEN** the resolution decision names `hotfix` and origin holds both
  `refs/heads/hotfix` and `refs/tags/hotfix`
- **THEN** the task parks with a report naming both commits, no fetch of
  either is attempted, and neither namespace is preferred over the other

#### Scenario: A ref origin holds in neither namespace parks the task
- **WHEN** the resolution decision names a ref origin answers about and
  holds under neither `refs/heads/` nor `refs/tags/`
- **THEN** the task parks with a report naming the ref, and absence is
  recorded only because origin answered — an unanswered remote stays an
  infrastructure failure

#### Scenario: Tag base lands in refs/tags without force
- **WHEN** the resolution decision names tag `v2.3.0` and the clone holds
  no such tag
- **THEN** the narrow fetch writes `refs/tags/v2.3.0`, no other tag
  appears, and the branch starts from the tagged commit

#### Scenario: Diverging local tag parks the task
- **WHEN** the clone already holds `v2.3.0` pointing at a commit other than
  origin's
- **THEN** the fetch refuses to move the tag, the task parks with a report
  naming the local and origin commits, and no branch is created

#### Scenario: SHA base already present needs no network
- **WHEN** the resolution decision names a commit SHA the clone already
  holds
- **THEN** no fetch runs, the object is verified as a commit, and the
  branch starts from it

#### Scenario: Absent SHA is fetched as objects only
- **WHEN** the resolution decision names a commit SHA the clone does not
  hold and origin serves it
- **THEN** the fetch brings the object with no ref written for it,
  `FETCH_HEAD` is never read, and the branch starts from the SHA

#### Scenario: Unreachable remote creates no branch
- **WHEN** the base fetch exhausts its bounded retries against a dead remote
- **THEN** no task branch exists, no stage attempt is burned, and the
  failure is reported as infrastructure, not as a gnome or quality failure

#### Scenario: A clone-served success says origin was not contacted
- **WHEN** the resolution decision names a commit SHA the clone already
  holds, or a resume rebinds a pinned ref from the local tip of a clone
  with no origin remote
- **THEN** the successful outcome states that origin was not contacted,
  and the commit it names is the same one the refresh named before this
  fact existed

#### Scenario: A fetched success says origin was contacted
- **WHEN** a branch, a tag, or an absent commit SHA is delivered by a
  narrow fetch, or a resume rebinds through a configured origin
- **THEN** the successful outcome states that origin was contacted, with no
  additional fetch, probe, or subprocess run to learn it
