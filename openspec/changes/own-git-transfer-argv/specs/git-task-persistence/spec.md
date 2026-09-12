# git-task-persistence — delta for own-git-transfer-argv

"Base refresh fetch before task creation" is layered on that requirement as
modified by `signal-outage-gate-on-origin-contact` (sequenced before this
change), which is itself layered on `add-base-ref-resolution`: the text below
is written over the outage-gate delta's text, not over `openspec/specs/`, so
syncing in that order merges cleanly. If this change syncs first, the later
sync must merge by hand — the origin-contact paragraph and its two scenarios
are what must survive from that change; the owner sentence and the submodule
scenario are what must survive from this one. The other two requirements are
modified by no active change and are written over `openspec/specs/`.

## MODIFIED Requirements

### Requirement: Harvest protocol
The factory SHALL collect results by fetching the task branch from the environment with a factory-fixed refspec (never names produced inside the box), fast-forward-only; rewritten history SHALL be refused. The harvest fetch is a transfer built by the one transfer owner (see `git-transfer`): it follows no tag, writes no `FETCH_HEAD`, recurses into no submodule, runs under a protocol allowlist naming the container transport alone, validates every object it receives, and reads no operator configuration. A tag, a submodule reference, or a configuration value authored inside the box SHALL change nothing in the factory clone beyond the one task branch ref. Harvest SHALL precede any push; pushes continue to run outside the environment with factory credentials. Branch-tip observation SHALL be polled and rate-limited on the factory side; event-driven tip detection, if enabled, SHALL watch `.git/logs/HEAD` (refs may be silently packed into `packed-refs`) and SHALL only wake the rate-limited poll.
<!-- implements FR5 of add-sandbox-core -->
<!-- implements FR6, NFR-S1, UX3 of own-git-transfer-argv -->

#### Scenario: Rewritten history is refused at the boundary
- **WHEN** the branch history inside the box was rewritten
- **THEN** the harvest fetch fails non-fast-forward and the factory treats it as the existing history-rewrite violation

#### Scenario: Hooks do not cross the boundary
- **WHEN** the gnome installs git hooks in the in-box clone
- **THEN** harvest transfers branch content only; no hook becomes active in any factory-managed copy

#### Scenario: A box-authored tag does not cross the boundary
- **WHEN** the gnome creates a tag inside the box pointing into the task branch's history
- **THEN** after harvest the factory clone holds no such tag, and `FETCH_HEAD` in the factory clone is untouched

#### Scenario: A malformed object is refused with the rewrite's report shape
- **WHEN** the box's branch carries an object git's validation rejects
- **THEN** the harvest is refused, the task ends as a boundary violation, and the report names what was refused in the same shape as a history-rewrite refusal

### Requirement: Sandboxed working copy is an independent full clone
In sandboxed mode, materialize SHALL create the working copy as a clone from the factory's local clone into the environment, built by the one transfer owner (see `git-transfer`): through git's transport path, never its local-path mode, with no hardlinks, a single branch, no tags, and validated objects — no network, no credentials, no remote address inside; the clone SHALL set the agent identity and `gc.auto 0`. The box SHALL hold exactly the task branch: none of the operator's local tags, and no other branch.
<!-- implements FR3 of add-sandbox-core -->
<!-- implements FR7, NFR-S1 of own-git-transfer-argv -->

#### Scenario: The box holds no way to the server
- **WHEN** an environment is materialized in container mode
- **THEN** the in-box clone has no configured remote pointing at the real server and no credentials anywhere in the box

#### Scenario: No shared objects with the factory clone
- **WHEN** the clone is created from the local factory clone
- **THEN** it shares no hardlinked objects with it, so in-box corruption cannot reach the factory's repository

#### Scenario: Operator tags do not enter the box
- **WHEN** the factory clone holds local tags, lightweight or annotated, pointing into the task branch's history
- **THEN** the in-box clone holds no tag

#### Scenario: The seed clone takes the transport path
- **WHEN** the factory clone holds an object git's validation rejects on the task branch
- **THEN** the seed clone refuses it — the local-path shortcut that would have copied it unchecked is never taken

### Requirement: Base refresh fetch before task creation
In autonomous fresh starts the factory SHALL refresh the resolved base with a
narrow fetch of exactly that ref, after the factory clone is hardened and
before the task is created. The destination is fixed per kind: a branch
updates its remote-tracking ref `refs/remotes/origin/<name>` (forced); a tag
is written to `refs/tags/<name>` without force, so an existing local tag of
that name pointing at another commit refuses the fetch and parks the task
with a report naming both commits; a bare commit SHA is checked for
presence locally first, fetched by SHA only when absent, and verified as a
commit object afterwards — no ref is written for it. The fetch is a transfer
built by the one transfer owner (see `git-transfer`), so it follows no other
tag, writes no `FETCH_HEAD` (the SHA is read back from the destination ref
or the object), recurses into no submodule, validates every object it
receives, and touches none of the operator clone's working tree, index,
HEAD, `refs/heads/*`, or any pre-existing `refs/tags/*` entry. It inherits
the bounded-network rules (deadline, stall detection, credential scrubbing)
and the existing git infrastructure retry policy, and it runs under the
clone's mutation lock like every other fetch. The refresh is fail-closed:
no task branch is created from a base whose freshness could not be
established. A reachability failure classifies as an infrastructure failure
— no stage attempt burned, resolution and fetch precede every durable
task-branch write; a diverging local tag, a remote refusing fetch-by-SHA, or
an object that fails validation is a task-level park. Pull remains
forbidden on every path.

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
<!-- implements FR5, NFR-R1 of own-git-transfer-argv -->

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

#### Scenario: A populated submodule does not widen the refresh
- **WHEN** the operator's clone has a submodule initialized and origin's
  new base commit references a newer submodule commit
- **THEN** the refresh contacts the submodule's remote not at all, updates
  no submodule ref, and the task branch starts from the delivered base

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
