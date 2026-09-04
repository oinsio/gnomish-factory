# git-task-persistence — delta for add-base-ref-resolution

## MODIFIED Requirements

### Requirement: Task branch naming and base
The task branch SHALL be named `gnomish/` + the sanitized taskId: every character outside `[A-Za-z0-9._-]` replaced by `-`, consecutive `-` collapsed, leading/trailing `.`/`-` stripped; an empty result or `.lock` suffix rejects the taskId. The authoritative taskId lives inside `task.json` — never parsed back from the ref name. The branch SHALL be created from the base ref the resolution decision names (see base-ref-resolution): in autonomous paths that ref is refreshed by the base fetch below before the branch is created; in manual `run` without `--base` the decision is the clone's local HEAD and no fetch or remote query runs, preserving the offline behavior exactly. `--base <ref>` overrides on the paths that accept it today. Exactly one code path SHALL turn the resolution decision into a start point — no adapter keeps a private HEAD fallback. The runner SHALL NOT pull on any path.
<!-- implements FR2, FR7 of add-git-workflow -->
<!-- implements FR4, FR6, FR8, FR10 of add-base-ref-resolution -->

#### Scenario: Unsafe characters sanitized deterministically
- **WHEN** the taskId is `PROJ 42: fix/it`
- **THEN** the branch is `gnomish/PROJ-42-fix-it` while `task.json` keeps the original id

#### Scenario: Manual run stays offline
- **WHEN** `gnomish run` starts a task without `--base` in a clone with no
  reachable remote
- **THEN** the branch is created from the local HEAD with no fetch, no
  remote query, and no new failure mode

#### Scenario: One start-point owner
- **WHEN** any of the four fresh-start paths (host/container × run/take)
  creates a task branch
- **THEN** the start point comes from the shared resolution decision, and no
  path-local default substitutes for it

### Requirement: Resume from the recorded branch
`--resume <task>` SHALL locate the branch: local → remote-tracking → narrow fetch of exactly `gnomish/<task>` — that locate step fetches nothing else, then continue by `task.json` outcome: escalated → decision dialog; paused → confirmation; null → continue from the recorded position; completed → report "task done" and exit. When the task working copy does not exist locally (another machine, or removed), resume SHALL materialize it through the bound task environment from the branch state alone.
<!-- implements FR8 of add-git-workflow -->
<!-- implements FR6 of add-sandbox-core -->

#### Scenario: Another instance resumes from origin
- **WHEN** the branch exists only on origin
- **THEN** resume fetches that single ref and materializes an environment that continues from the recorded position

## ADDED Requirements

### Requirement: Base refresh fetch before task creation
In autonomous fresh starts the factory SHALL refresh the resolved base with a
narrow fetch of exactly that ref — branch or tag; a base given as a bare
commit SHA is instead verified present locally, fetched by SHA only where the
remote permits it — after the claim is hardened and before the task is
created. The fetch SHALL NOT touch the operator clone's working tree, HEAD,
or local branches; it updates factory-read remote state only. It inherits the
bounded-network rules (deadline, stall detection, credential scrubbing) and
the existing git infrastructure retry policy. The refresh is fail-closed: no
task branch is created from a base whose freshness could not be established,
and a refresh failure classifies as an infrastructure failure — no stage
attempt burned. Pull remains forbidden on every path.
<!-- implements FR6, FR9, NFR-P1 of add-base-ref-resolution -->

#### Scenario: Fresh base at claim
- **WHEN** origin's `develop` has advanced past the factory clone's last
  fetch and a task resolving to `develop` is claimed
- **THEN** the task branch starts from origin's current `develop` tip, and
  the clone's own local branches and HEAD are unchanged

#### Scenario: Tag base is fetched like a branch
- **WHEN** the resolution decision names tag `v2.3.0`
- **THEN** the narrow fetch retrieves the tag and the branch starts from the
  tagged commit

#### Scenario: Unreachable remote creates no branch
- **WHEN** the base fetch exhausts its bounded retries against a dead remote
- **THEN** no task branch exists, no stage attempt is burned, and the
  failure is reported as infrastructure, not as a gnome or quality failure

### Requirement: Base pin in task.json
`task.json` SHALL carry the base pin — the resolved ref, the commit SHA the
branch was created from, and the source rule that produced the decision —
written in the task-creation commit. The pin extends the existing
`baseCommit` slot behind the wire version gate: legacy files carrying only
`baseCommit` SHALL stay readable, reporting an absent ref and rule. The rule
vocabulary is a wire vocabulary: writer and reader SHALL round-trip every
constant, with the documented forward-compatible unknown-token behavior.
Resume SHALL read the pin and never re-resolve (see base-ref-resolution).
<!-- implements FR7 of add-base-ref-resolution -->
<!-- implements NFR-O2 of add-base-ref-resolution -->

#### Scenario: Creation commit carries the pin
- **WHEN** the task-creation commit on a fresh branch is inspected
- **THEN** its `task.json` already names the resolved ref, the SHA, and the
  source rule

#### Scenario: Legacy task file reads as unpinned
- **WHEN** a pre-pin `task.json` carrying only `baseCommit` is read
- **THEN** it parses under the version gate with ref and rule absent, and
  resume proceeds exactly as before this change

#### Scenario: Pin round-trips the wire
- **WHEN** a pinned task file is written and read back
- **THEN** ref, SHA, and rule survive unchanged, covered by a data-driven
  round-trip spec over every rule constant
