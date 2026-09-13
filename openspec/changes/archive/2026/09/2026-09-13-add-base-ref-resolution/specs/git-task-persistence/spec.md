# git-task-persistence — delta for add-base-ref-resolution

## MODIFIED Requirements

### Requirement: Task branch naming and base
The task branch SHALL be named `gnomish/` + the sanitized taskId: every character outside `[A-Za-z0-9._-]` replaced by `-`, consecutive `-` collapsed, leading/trailing `.`/`-` stripped; an empty result or `.lock` suffix rejects the taskId. The authoritative taskId lives inside `task.json` — never parsed back from the ref name. The branch SHALL be created from the base ref the resolution decision names (see base-ref-resolution): in autonomous paths that ref is refreshed by the base fetch below before the branch is created; in manual `run` without `--base` the decision is the clone's local HEAD and no fetch or remote query runs, preserving the offline behavior exactly. `--base <ref>` overrides on the paths that accept it today. Exactly one code path SHALL turn the resolution decision into a start point — no adapter keeps a private HEAD fallback. The start point handed to the repository port SHALL be the peeled law commit as a typed object id — the same commit the task's law was bound from — never a ref name: the adapters SHALL run no name-to-commit resolution of the base, so a local branch, a local tag, or the absence of a local ref under the base's name cannot change or fail the start point once the refresh has delivered the commit. The runner SHALL NOT pull on any path.
<!-- implements FR2, FR7 of add-git-workflow -->
<!-- implements FR4, FR6, FR8, FR10, FR15 of add-base-ref-resolution -->

#### Scenario: Unsafe characters sanitized deterministically
- **WHEN** the taskId is `PROJ 42: fix/it`
- **THEN** the branch is `gnomish/PROJ-42-fix-it` while `task.json` keeps the original id

#### Scenario: Manual run stays offline
- **WHEN** `gnomish run` starts a task without `--base` in a clone with no
  reachable remote
- **THEN** the branch is created from the local HEAD with no fetch, no
  remote query, and no new failure mode
<!-- implements UX3 of add-base-ref-resolution -->

#### Scenario: One start-point owner
- **WHEN** any of the four fresh-start paths (host/container × run/take)
  creates a task branch
- **THEN** the start point comes from the shared resolution decision, and no
  path-local default substitutes for it

#### Scenario: A stale local branch does not redirect the base
- **WHEN** the clone's local `main` is behind origin's `main` and a task
  resolving to `main` is claimed
- **THEN** the task branch's first parent, the law commit, and the pinned
  SHA are all origin's refreshed `main` tip, and the local `main` is neither
  read nor moved
<!-- implements FR15 of add-base-ref-resolution -->

#### Scenario: An origin-only branch starts the task
- **WHEN** the resolved base is a branch that exists on origin and under no
  local ref of the clone
- **THEN** the refresh delivers it and the task branch is created from the
  delivered commit — no "base ref did not resolve" failure follows a
  successful fetch
<!-- implements FR15 of add-base-ref-resolution -->

#### Scenario: A planted local tag does not redirect the base
- **WHEN** the clone holds a local tag carrying the base branch's name and
  pointing elsewhere, and a task resolving to that branch is claimed
- **THEN** the task branch starts from origin's refreshed branch tip, not
  from the tag's commit
<!-- implements FR15, NFR-S1 of add-base-ref-resolution -->

### Requirement: Resume from the recorded branch
`--resume <task>` SHALL locate the branch: local → remote-tracking → narrow fetch of exactly `gnomish/<task>` — that locate step fetches nothing else, then continue by `task.json` outcome: escalated → decision dialog; paused → confirmation; null → continue from the recorded position; completed → report "task done" and exit. When the task working copy does not exist locally (another machine, or removed), resume SHALL materialize it through the bound task environment from the branch state alone.
<!-- implements FR8 of add-git-workflow -->
<!-- implements FR6 of add-sandbox-core -->
<!-- implements FR7, FR12 of add-base-ref-resolution -->

#### Scenario: Another instance resumes from origin
- **WHEN** the branch exists only on origin
- **THEN** resume fetches that single ref and materializes an environment that continues from the recorded position

#### Scenario: Autonomous resume refreshes the pinned base for the law
- **WHEN** a tracker-driven resume finds the task branch and a pinned base ref name
- **THEN** it narrow-fetches exactly that ref name as well, binds the law from its tip (pipeline-config), and treats an unreachable remote as an infrastructure failure like the base refresh; manual resume without a reachable remote binds from the local ref tip

## ADDED Requirements

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
<!-- implements FR6, FR9, NFR-P1, NFR-R1 of add-base-ref-resolution -->

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

### Requirement: Base pin in task.json
`task.json` SHALL carry the base pin — the resolved ref, its kind (branch,
tag, or commit, as origin classified it at refresh), the commit SHA the
branch was created from, and the source rule that produced the decision —
written in the task-creation commit. The pin extends the existing
`baseCommit` slot behind the wire version gate: legacy files carrying only
`baseCommit` SHALL stay readable, reporting an absent ref, kind, and rule; a
pin written before the kind existed SHALL read with the kind absent. The rule
and kind vocabularies are wire vocabularies: writer and reader SHALL
round-trip every constant of each, with the documented forward-compatible
unknown-token behavior.
Resume SHALL read the pin and never re-resolve (see base-ref-resolution).
The pinned SHA is the fresh start's law commit: the law and the external-check
pin guard bind from it, never from the clone's `HEAD`.
<!-- implements FR7 of add-base-ref-resolution -->
<!-- implements NFR-O2 of add-base-ref-resolution -->

#### Scenario: Creation commit carries the pin
- **WHEN** the task-creation commit on a fresh branch is inspected
- **THEN** its `task.json` already names the resolved ref, its kind, the SHA,
  and the source rule, and the SHA equals the commit's own first parent

#### Scenario: Legacy task file reads as unpinned
- **WHEN** a pre-pin `task.json` carrying only `baseCommit` is read
- **THEN** it parses under the version gate with ref and rule absent, and
  resume proceeds exactly as before this change

#### Scenario: Pin round-trips the wire
- **WHEN** a pinned task file is written and read back
- **THEN** ref, kind, SHA, and rule survive unchanged, covered by a
  data-driven round-trip spec over every rule constant and every kind
  constant
