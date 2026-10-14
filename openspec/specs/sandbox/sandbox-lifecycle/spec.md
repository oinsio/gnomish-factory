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
**Implements**: FR2, NFR-S1 of add-serve-sandbox-lifecycle

#### Scenario: Object is born owned
- **WHEN** a slot creates a task container concurrently with a sweep evaluation
- **THEN** the container already carries task key, mode, and project labels at the instant it becomes listable, and the sweep classifies it by those labels — never as unknown

#### Scenario: No secret material in labels
- **WHEN** any factory object's labels are inspected on the host
- **THEN** they contain only the ownership marker, the sanitized environment key, the mode, and the project identity

### Requirement: Liveness oracle over claim heartbeat
Liveness of a `tracked` object SHALL be derived from the tracker's claim-heartbeat lease: the object is alive if and only if the task named by its key currently holds a claim whose heartbeat is fresh per the existing staleness TTL. A stale claim SHALL count as dead. Each claim's staleness SHALL be judged by the existing claim-heartbeat staleness policy, including its cross-tick observation memory.
**Implements**: FR3, NFR-C2 of add-serve-sandbox-lifecycle
**Notes**: A stale claim counts as dead because the claim-heartbeat protocol removes it from circulation and makes the task available to any instance; under the observation memory a claim version is stale only after the TTL has elapsed since this observer first saw that version.

#### Scenario: The live key set is recomputed on every evaluation
- **WHEN** a sweep evaluation runs after an earlier one, and the open tasks' claim facts changed in between
- **THEN** the live key set is computed forward again, from the listed open tasks' claim facts to environment keys, and no stored result of an earlier tick is reused

#### Scenario: One evaluation costs at most one open-task listing
- **WHEN** a sweep evaluation derives the live key set
- **THEN** it costs at most one tracker open-task listing, which may be the one the claim reaper already made

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
The sweep SHALL decide per object by ownership × role × state — the decision matrix, whose rows are this requirement's scenarios — and escalation SHALL be one-way, running → stopped → disposed, each step reversible-cheaper than the next. The main box and its volume are the only possible holders of un-harvested work and SHALL never be disposed directly from the running state.
**Implements**: FR4, NFR-S2, NFR-C1 of add-serve-sandbox-lifecycle
**Notes**: Guard, judge, verification, and seed-helper objects are reconstructible by construction and never hold durable work; the seed helper only writes into a task volume that is itself a separate, independently governed object.
**Notes**: An unrecognized role is a factory-labelled object this build cannot classify, for example one created by a newer build.
**Notes**: Roles are lifecycle-level concepts; how each runtime adapter realizes them (naming, labels) is that adapter's contract — for the container adapter, see `execution-environment`.

#### Scenario: An alive object is untouched in any role and state
- **WHEN** the sweep evaluates an object, of any role and in any state, whose task holds a fresh claim
- **THEN** the object is untouched

#### Scenario: An unowned stopped main box is left to the aged reaper
- **WHEN** the sweep evaluates a stopped main box whose task holds no fresh claim
- **THEN** the box is neither restarted nor disposed by the matrix, and its fate is left to the aged reaper

#### Scenario: Unowned guard removed at once
- **WHEN** the sweep finds a guard whose task holds no fresh claim
- **THEN** the guard is disposed immediately without waiting for any age threshold

#### Scenario: An unrecognized object is removed by its own name
- **WHEN** the aged reaper disposes an unowned object of an unrecognized role
- **THEN** the object is removed by its own name, never through its environment key, since its name matches no known pattern and the key's own objects belong to something else

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
**Implements**: FR4 of add-serve-sandbox-lifecycle

#### Scenario: Concurrent launch survives a sweep tick
- **WHEN** a sweep tick runs while another slot is between object creation and its first observable heartbeat
- **THEN** the just-created objects are under the minimum age and are untouched

### Requirement: Aged reaper for kept environments
Unowned stopped environments and container-less remnants SHALL be disposed once their age exceeds a configured threshold (default 7 days): stopped boxes measured by the runtime's finished-at metadata, container-less remnants by the runtime's creation timestamp — never by file mtimes inside volumes.
**Implements**: FR5, NFR-R2 of add-serve-sandbox-lifecycle; FR11, NFR-R2 of add-sandbox-core
**Notes**: Aged container disposal migrated here from factory-serve's worktree cleaner (add-sandbox-core).
**Notes**: The cost of a reaped kept environment is bounded: a later resume falls back to a fresh materialize from the task branch, and only the un-salvaged tail of the last round is lost.

#### Scenario: Disposal of an already-gone environment is a no-op
- **WHEN** the aged reaper disposes a kept environment some of whose objects an earlier pass or another instance already removed
- **THEN** the disposal completes for what is left, and a second pass over the result issues no further action

#### Scenario: Kept environment survives the human turnaround
- **WHEN** a task escalates and its stopped environment is 3 days old at the next sweep tick
- **THEN** the environment is reported kept-under-threshold and untouched

#### Scenario: Forgotten kept environment is reaped
- **WHEN** a kept environment's finished-at age exceeds the threshold
- **THEN** box, volume, and network are disposed, and a later resume materializes fresh from the branch

### Requirement: Manual mode is governed by age alone
Objects labelled `manual` SHALL be exempt from the claim oracle. An unowned running manual box SHALL be stopped only after a configured threshold (default 24 hours) since the runtime's started-at timestamp; stopped manual environments and manual remnants follow the same aged-reaper policy as tracked ones. Manual objects SHALL be policed only by sweeps of their own project.
**Implements**: FR7 of add-serve-sandbox-lifecycle

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
identity in addition to the identity it stamps.
**Implements**: FR8 of add-serve-sandbox-lifecycle; FR3, NFR-R2, NFR-O1, NFR-C1 of normalize-project-identity-url
**Notes**: The legacy identity keeps objects created before normalization in scope instead of orphaning them; with an override set or no `origin` configured, no legacy identity exists.

#### Scenario: Objects are stamped with the normalized identity and never relabelled
- **WHEN** a legacy identity exists and the factory creates an object, or a pass acts on a legacy-labelled one
- **THEN** a new object is stamped with the normalized identity only, and no existing object is relabelled

#### Scenario: A legacy identity costs one extra listing per object kind
- **WHEN** a pass runs while a legacy identity exists
- **THEN** it issues at most one extra listing per object kind for that identity

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
canonical absolute path. A clone with no `origin` SHALL NOT fall back to a shared constant.
**Implements**: FR8 of add-serve-sandbox-lifecycle; FR1, FR2, FR4, NFR-R1, NFR-S1 of normalize-project-identity-url
**Notes**: Normalization keeps one identity for one project across a credential rotation and across the cosmetic variants that name the same remote; an unusual remote costs identity stability, never a run or a sweep pass.
**Notes**: A shared constant would place every origin-less project on a host into one sweep scope, the exact cross-project reach the label exists to prevent.
**Notes**: The override's character set exists because the label set is rendered and read back as `k1=v1,k2=v2`, so a value carrying a comma or an equals sign could forge a second label pair and strip an object of its ownership mode.

#### Scenario: Normalization rewrites only the cosmetic parts of the URL
- **WHEN** the clone's `origin` URL is normalized
- **THEN** its userinfo is removed, its scheme and host are lower-cased, an explicitly written default port of the scheme (`http` 80, `https` 443, `ssh` 22, `git` 9418) is removed, one trailing `/` and a trailing `.git` are removed, and the scp-style `[user@]host:path` form is rendered in the same shape as the equivalent `ssh://host/path` URL

#### Scenario: No credential reaches the identity, a label, a log line or an error
- **WHEN** the clone's `origin` URL embeds a credential
- **THEN** the raw URL is never used as the identity, and the removed userinfo appears in no label, log line, or error message

#### Scenario: An override outside the label-safe set is refused without its value
- **WHEN** `factory.sandbox.project-id` is set to a value that does not match `[A-Za-z0-9._-]+`
- **THEN** resolution fails naming the property, and the offending value is not echoed

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
"No verdict" SHALL be distinct from "no claims": a tracker or runtime error during evaluation SHALL skip the affected decisions — removing nothing, emitting skipped-no-verdict — and SHALL never degrade to an empty live set.
**Implements**: NFR-R1, NFR-R3 of add-serve-sandbox-lifecycle; FR5, FR12 of harden-logging-observability
**Notes**: A pass whose object listing failed publishes no completed tick because a tick that reached no object is not a tick that found no work.
**Notes**: Verdict sinks grade the line's level by category so that a degraded sweep is distinguishable from a healthy one on the operator plane.

#### Scenario: A skipped sweep blocks nothing and is retried on the next pass
- **WHEN** a sweep pass, at startup or on a daemon tick, ends with skipped-no-verdict or no verdicts at all
- **THEN** neither startup nor any slot is blocked, and the next scheduled pass evaluates the objects again

#### Scenario: Sweep actions log at INFO
- **WHEN** a logging verdict sink receives a stopped-orphan, disposed-aged or disposed-reconstructible verdict
- **THEN** the line is logged at INFO

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
  uninterpretable shape during a pass, and the object still exists
- **THEN** the object emits skipped-no-verdict naming the read failure instead
  of silently dropping out of the pass
- **AND** an object gone since the listing gets no verdict, since its absence
  is not a degradation

#### Scenario: Quiet tick, loud degradation
- **WHEN** a sweep tick evaluates objects that are all alive and under
  threshold
- **THEN** the logging sink emits nothing at INFO or above for them, while any
  skipped-no-verdict in the same tick logs at WARN

### Requirement: Uniform verdict events
Every sweep evaluation SHALL emit one verdict event per object — category (checked-alive, kept-under-threshold, stopped-orphan, disposed-aged, disposed-reconstructible, skipped-no-verdict), object name, role, ownership mode, task key, reason, and age when one was measured — through a listener seam. All entry points (`run`, `take`, `serve`) SHALL evaluate the same policy component and differ only in where events sink.
**Implements**: FR9, NFR-O4 of add-serve-sandbox-lifecycle; FR6 of fix-denial-attribution-durability

#### Scenario: A manual age stop is a stopped-orphan with mode manual
- **WHEN** the sweep stops a manual box because it ran past the manual threshold
- **THEN** the verdict is stopped-orphan with mode `manual`, so sinks can distinguish a routine age-policy stop from a dead-instance symptom

#### Scenario: Same vocabulary in daemon and one-shot logs
- **WHEN** the same unowned stopped box is evaluated by a daemon tick and by a `take` startup pass
- **THEN** both log the identical line, category and reason; the daemon also writes the verdict to its ledger when it is an acting one (stopped-orphan, disposed-aged, disposed-reconstructible), with the same reason and the category in the ledger's wire spelling (`stoppedOrphan`)

#### Scenario: An extra sink survives the observing decorator
- **WHEN** an entry point runs the sweep pass through the observing decorator and passes an extra verdict sink
- **THEN** the extra sink receives every verdict event the decorator's own sinks receive
