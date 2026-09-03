# sandbox-lifecycle

## Purpose

Ownership-based lifecycle policy for factory-created Docker objects: labels
stamped atomically at creation, liveness derived from the claim-heartbeat
lease instead of a name snapshot, a per-object sweep decision matrix, an
aged reaper for kept environments, project scoping, and a uniform verdict
vocabulary — so cleanup is safe under concurrent slots, multiple instances,
and multiple projects on one Docker host.

## Requirements

### Requirement: Ownership labels at creation
Every factory-created Docker object (container, volume, network — box, egress guard, judge box, verification box alike) SHALL be stamped atomically at creation with labels carrying: factory ownership, the sanitized task environment key, the ownership mode (`tracked` for claim-backed tasks, `manual` for `gnomish run`), and the project identity. No factory object SHALL ever exist without these labels — there is no post-creation labelling step. Labels SHALL carry no credentials or secrets.
<!-- implements FR2, NFR-S1 of add-serve-sandbox-lifecycle -->

#### Scenario: Object is born owned
- **WHEN** a slot creates a task container concurrently with a sweep evaluation
- **THEN** the container already carries task key, mode, and project labels at the instant it becomes listable, and the sweep classifies it by those labels — never as unknown

#### Scenario: No secret material in labels
- **WHEN** any factory object's labels are inspected on the host
- **THEN** they contain only the ownership marker, the sanitized environment key, the mode, and the project identity

### Requirement: Liveness oracle over claim heartbeat
Liveness of a `tracked` object SHALL be derived from the tracker's claim-heartbeat lease: the object is alive if and only if the task named by its key currently holds a claim whose heartbeat is fresh per the existing staleness TTL. A stale claim SHALL count as dead, consistent with the claim-heartbeat protocol, under which a stale claim is removed from circulation and the task becomes available to any instance. The live key set SHALL be recomputed forward — from the listed open tasks' claim facts to environment keys — for every sweep evaluation, never reused as a stored result across ticks; each claim's staleness SHALL be judged by the existing claim-heartbeat staleness policy, including its cross-tick observation memory (a claim version is stale only after the TTL has elapsed since this observer first saw that version). One evaluation SHALL cost at most one tracker open-task listing, which MAY be shared with the claim reaper's existing listing.
<!-- implements FR3, NFR-C2 of add-serve-sandbox-lifecycle -->

#### Scenario: Freshly claimed task is protected
- **WHEN** the sweep evaluates a running box whose task holds a claim with a fresh heartbeat
- **THEN** the verdict is checked-alive and the object is untouched

#### Scenario: Stale claim means dead owner
- **WHEN** a box's task has a claim whose heartbeat exceeded the staleness TTL
- **THEN** the sweep treats the object as unowned and applies the decision matrix

#### Scenario: Fresh observer grants a grace period
- **WHEN** a just-started daemon's first sweep tick observes claims it has never seen before
- **THEN** no tracked object is judged unowned until the staleness TTL elapses from that first observation — the grace period of the claim-heartbeat staleness policy holds for the sweep by construction

### Requirement: Sweep decision matrix
The sweep SHALL decide per object by ownership × role × state, and escalation SHALL be one-way — running → stopped → disposed — with each step reversible-cheaper than the next:

| Ownership | Role | State | Action |
|---|---|---|---|
| Alive (fresh claim) | any | any | Untouched |
| Unowned / stale | main box | running | **Stop** (box, volume, network preserved) |
| Unowned / stale | main box | stopped | Left to the aged reaper |
| Unowned / stale | volume or network with no container | — | Left to the aged reaper |
| Unowned / stale | guard, judge box, verification box, seed helper | any | Disposed immediately |
| Unowned / stale | unrecognized role | any | Stopped if running, then left to the aged reaper |

Guard, judge, verification, and seed-helper objects are reconstructible by construction and never hold durable work — the seed helper only writes into a task volume that is itself a separate, independently-governed object; the main box and its volume are the only possible holders of un-harvested work and SHALL never be disposed directly from the running state. An unrecognized role (a factory-labelled object this build cannot classify, e.g. one created by a newer build) SHALL take the fail-safe fallback row — never immediate disposal — and SHALL be removed by its own name, never through its environment key: its name matches no known pattern, so the key's own objects belong to something else. Roles are lifecycle-level concepts; how each runtime adapter realizes them (naming, labels) is that adapter's contract — for the container adapter, see `execution-environment`.
<!-- implements FR4, NFR-S2, NFR-C1 of add-serve-sandbox-lifecycle -->

#### Scenario: Abandoned running box is stopped, not destroyed
- **WHEN** an instance dies mid-task and a sibling's sweep tick finds the running box after the claim went stale
- **THEN** the box is stopped and box, volume, and network remain; a later resume salvages un-harvested work from the volume

#### Scenario: Unowned judge box removed at once
- **WHEN** the sweep finds a judge box or verification box whose task holds no fresh claim
- **THEN** the object is disposed immediately without waiting for any age threshold

#### Scenario: Orphaned seed helper removed at once
- **WHEN** a runtime restart mid-seed left the one-shot seed-helper container behind and its task holds no fresh claim
- **THEN** the helper container is disposed immediately, while the task volume it was seeding remains governed by its own matrix row

#### Scenario: Unrecognized object falls back safely
- **WHEN** the sweep finds an unowned factory-labelled object whose role it cannot classify
- **THEN** the object is stopped if running and left to the aged reaper — never disposed immediately

#### Scenario: Crash residue converges through the same policy
- **WHEN** a crash mid-materialize or mid-dispose left a volume and network with no container
- **THEN** the remnants are classified unowned, left to the aged reaper, and disposed once past the age threshold — no special casing

### Requirement: Minimum object age protection
An object younger than a configured minimum age SHALL never be touched, regardless of any liveness verdict. This bound covers residual races at creation boundaries — an object listable an instant before its claim's first beat is observable — independently of the oracle.
<!-- implements FR4 of add-serve-sandbox-lifecycle -->

#### Scenario: Concurrent launch survives a sweep tick
- **WHEN** a sweep tick runs while another slot is between object creation and its first observable heartbeat
- **THEN** the just-created objects are under the minimum age and are untouched

### Requirement: Aged reaper for kept environments
Unowned stopped environments and container-less remnants SHALL be disposed once their age exceeds a configured threshold (default 7 days): stopped boxes measured by the runtime's finished-at metadata, container-less remnants by the runtime's creation timestamp — never by file mtimes inside volumes. Disposal SHALL be idempotent. The cost of a reaped kept environment is bounded and stated: a later resume falls back to a fresh materialize from the task branch; only the un-salvaged tail of the last round is lost.
<!-- implements FR5, NFR-R2 of add-serve-sandbox-lifecycle -->
<!-- implements FR11, NFR-R2 of add-sandbox-core (aged container disposal migrated here from factory-serve's worktree cleaner) -->

#### Scenario: Kept environment survives the human turnaround
- **WHEN** a task escalates and its stopped environment is 3 days old at the next sweep tick
- **THEN** the environment is reported kept-under-threshold and untouched

#### Scenario: Forgotten kept environment is reaped
- **WHEN** a kept environment's finished-at age exceeds the threshold
- **THEN** box, volume, and network are disposed, and a later resume materializes fresh from the branch

### Requirement: Manual mode is governed by age alone
Objects labelled `manual` SHALL be exempt from the claim oracle. An unowned running manual box SHALL be stopped only after a configured threshold (default 24 hours) since the runtime's started-at timestamp; stopped manual environments and manual remnants follow the same aged-reaper policy as tracked ones. Manual objects SHALL be policed only by sweeps of their own project.
<!-- implements FR7 of add-serve-sandbox-lifecycle -->

#### Scenario: Live manual debugging session is invisible to the daemon
- **WHEN** a daemon's sweep tick runs while a container `gnomish run` session started an hour ago on the same host
- **THEN** the manual box is under the running-stop threshold and untouched

#### Scenario: Forgotten manual zombie is eventually stopped
- **WHEN** a crashed manual run's box has been running longer than the manual threshold
- **THEN** the box is stopped and enters the kept population for the aged reaper

### Requirement: Project scoping
Sweep and reaper SHALL act only on objects carrying this factory's own project identity label.
Objects of another project SHALL be excluded at listing and never touched, in any ownership mode or
state. When the identity derives from the `origin` URL and the digest of the raw, un-normalized URL
differs from the normalized one, this factory's own identities SHALL include that **legacy**
identity in addition to the identity it stamps, so objects created before normalization stay in
scope instead of being orphaned; with an override set or no `origin` configured, no legacy identity
exists. Objects SHALL be stamped with the normalized identity only, and no object SHALL ever be
relabelled. The legacy scope SHALL cost at most one extra listing per object kind per pass, and
only while a legacy identity exists. A failed legacy listing SHALL abort the pass under the existing fail-closed rule
rather than degrade to a partial object set. When legacy-labelled objects are found, the pass SHALL
log one INFO naming the count.
<!-- implements FR8 of add-serve-sandbox-lifecycle -->
<!-- implements FR3, NFR-R2, NFR-O1, NFR-C1 of normalize-project-identity-url -->

#### Scenario: Two projects share one Docker host
- **WHEN** project A's sweep runs while project B has live and kept objects on the same daemon
- **THEN** project B's objects appear in no verdict of project A's sweep

#### Scenario: Objects labelled before normalization stay in scope
- **WHEN** a sweep runs against objects stamped with the digest of the raw `origin` URL, while the
  normalized identity differs from it
- **THEN** those objects are classified and acted on exactly as if they carried the current
  identity, and one INFO records how many were found

#### Scenario: A legacy listing failure yields no verdicts
- **WHEN** the extra legacy listing cannot be obtained
- **THEN** the pass emits no verdicts and no completed tick, exactly as for any other failed listing

#### Scenario: No extra cost when no legacy identity exists
- **WHEN** no legacy identity exists (an override is set, there is no `origin`, or the URL is
  already normal)
- **THEN** the pass performs no additional listing

### Requirement: Project identity derivation
The project identity SHALL be resolved, in precedence order, from: the operator's explicit
override (`factory.sandbox.project-id`); otherwise a stable truncated digest of the clone's
**normalized** `origin` remote URL; otherwise a stable truncated digest of the clone's own
canonical absolute path. Normalization SHALL remove the URL's userinfo, lower-case the scheme and
host, remove an explicitly written default port of the scheme (`http` 80, `https` 443, `ssh` 22,
`git` 9418), remove one trailing `/`, remove a trailing `.git`, and render the scp-style
`[user@]host:path` form in the same shape as the equivalent `ssh://host/path` URL — so one project
keeps one identity across a credential rotation and across the cosmetic variants that name the
same remote.
Normalization SHALL NOT conflate remotes that differ in host, path, non-default port, or scheme
(beyond case):
those remain distinct identities. A remote URL in a shape the normalization does not recognize
SHALL fall back to the raw string rather than failing — an unusual remote costs identity stability,
never a run or a sweep pass. The raw remote URL SHALL never be used as the identity, since it may
carry an embedded credential, and no removed userinfo SHALL appear in any label, log line, or error
message. A clone with no `origin` SHALL NOT fall back to a shared constant — that would place
every origin-less project on a host into one sweep scope, the exact cross-project reach the label
exists to prevent. An override SHALL be rejected unless it matches `[A-Za-z0-9._-]+`: the label
set is rendered and read back as `k1=v1,k2=v2`, so a value carrying a comma or an equals sign
could forge a second label pair and strip an object of its ownership mode. A rejected override
SHALL name the property without echoing the offending value.
<!-- implements FR8 of add-serve-sandbox-lifecycle -->
<!-- implements FR1, FR2, FR4, NFR-R1, NFR-S1 of normalize-project-identity-url -->

#### Scenario: A rotated credential keeps the project identity
- **WHEN** the clone's `origin` URL embeds a credential and that credential is replaced with a new one
- **THEN** the resolved identity is unchanged, and objects created before the rotation stay in the
  sweep's scope

#### Scenario: Cosmetic URL variants of one remote resolve alike
- **WHEN** the same remote is written with and without a `.git` suffix, with and without a trailing
  slash, with a differently-cased host, with an explicitly written default port, or in the
  scp-style form
- **THEN** every variant resolves to the same identity

#### Scenario: Distinct remotes keep distinct identities
- **WHEN** two clones name remotes differing in host, path, non-default port, or scheme (beyond
  case)
- **THEN** they resolve to different identities and neither appears in the other's verdicts

#### Scenario: An unparseable remote URL does not fail the run
- **WHEN** the `origin` URL is in a shape the normalization does not recognize
- **THEN** the identity is derived from the raw string and the run and the sweep proceed normally

#### Scenario: Two checkouts of one origin-less repository ignore each other
- **WHEN** a sweep runs in a clone that has no `origin` and no configured override, while a second
  checkout of the same repository has objects on the same daemon
- **THEN** the two resolve different identities and neither appears in the other's verdicts

#### Scenario: An override that could forge a label is refused
- **WHEN** `factory.sandbox.project-id` is set to a value containing a comma or an equals sign
- **THEN** resolution fails naming the property, and no object is created or swept under it

### Requirement: Fail-closed verdicts
"No verdict" SHALL be distinct from "no claims": a tracker or runtime error during evaluation SHALL skip the affected decisions — removing nothing, emitting skipped-no-verdict — and SHALL never degrade to an empty live set. A destructive action the runtime refused SHALL likewise emit skipped-no-verdict with the failure as its reason, never the action it did not complete. An object listing that could not be obtained affects every decision at once and SHALL abort the whole pass: no verdicts, and — since a tick that reached no object is not a tick that found no work — no completed tick either. A sweep skip never blocks startup or a slot and is retried on the next scheduled pass.

The skipped-no-verdict guarantee SHALL extend to per-object read failures: an
object whose inspection fails or whose inspected shape cannot be interpreted
SHALL emit skipped-no-verdict naming the read failure — an enumerated object
never leaves a pass without a verdict event. Verdict sinks that log SHALL
grade the line's level by category: steady-state categories (checked-alive,
kept-under-threshold) below the default level, action categories at INFO, and
skipped-no-verdict at WARN, so a degraded sweep is distinguishable from a
healthy one on the operator plane.
<!-- implements NFR-R1, NFR-R3 of add-serve-sandbox-lifecycle -->
<!-- implements FR5, FR12 of harden-logging-observability -->

#### Scenario: Tracker outage removes nothing
- **WHEN** the claims listing fails during a sweep tick
- **THEN** no tracked object is stopped or disposed, the tick reports skipped-no-verdict, and the daemon's slots continue unaffected

#### Scenario: A refused removal is not reported as a removal
- **WHEN** the runtime rejects the stop or removal of an object the matrix decided to clean up
- **THEN** the object's verdict is skipped-no-verdict naming the failed action, and no ledger line or count claims it was stopped or disposed

#### Scenario: An unreachable runtime completes no tick
- **WHEN** the object listing cannot be obtained at all
- **THEN** the pass is abandoned with no verdicts and publishes no tick, so the stall is visible to the tick-overdue alert instead of reading as a healthy zero-work tick

#### Scenario: Empty claim list is a real verdict
- **WHEN** the tracker answers successfully with zero fresh claims
- **THEN** tracked objects are evaluated as unowned per the decision matrix

#### Scenario: Unreadable object still gets a verdict
- **WHEN** an enumerated object's inspection fails or returns an
  uninterpretable shape during a pass
- **THEN** the object emits skipped-no-verdict naming the read failure instead
  of silently dropping out of the pass

#### Scenario: Quiet tick, loud degradation
- **WHEN** a sweep tick evaluates objects that are all alive and under
  threshold
- **THEN** the logging sink emits nothing at INFO or above for them, while any
  skipped-no-verdict in the same tick logs at WARN

### Requirement: Uniform verdict events
Every sweep evaluation SHALL emit one verdict event per object — category (checked-alive, kept-under-threshold, stopped-orphan, disposed-aged, disposed-reconstructible, skipped-no-verdict), object name, role, ownership mode, task key, reason, and age — through a listener seam. A manual box stopped by the age threshold SHALL emit stopped-orphan with mode `manual`, so sinks can distinguish a routine age-policy stop from a dead-instance symptom. All entry points (`run`, `take`, `serve`) SHALL evaluate the same policy component and differ only in where events sink.
<!-- implements FR9, NFR-O4 of add-serve-sandbox-lifecycle -->

#### Scenario: Same vocabulary in daemon and one-shot logs
- **WHEN** the same unowned stopped box is evaluated by a daemon tick and by a `take` startup pass
- **THEN** both emit the identical category and reason; the daemon's sinks to its ledger, take's to its log
