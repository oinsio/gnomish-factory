## ADDED Requirements

### Requirement: Serve assesses operator preconditions at startup
Before any tracker claim, `serve` SHALL run the checks a slot runs for the operator preconditions whose answer does not depend on a task — the factory clone's ref format, the sandbox plan, the image contract, the box's git floor — in the order a slot meets them, and SHALL exit as a startup failure on the first refusal, printing that check's own message. The assessment SHALL write nothing to the tracker, the clone or the container runtime.
**Implements**: FR13, NFR-R3, UX3, G6 of add-sandbox-base-image
**Notes**: The factory's own git version check is the precedent; the per-task checks stay for what the operator can change under a running daemon (design D17).

#### Scenario: A clone on the files backend refuses the daemon at startup
- **WHEN** `serve` starts on a factory clone whose ref backend is `files` and the tracker holds a `Ready` task
- **THEN** the process exits as a startup failure with the ref-format refusal and its migration remedy, and the tracker shows no claim and no working label

#### Scenario: An unreachable container runtime refuses the daemon at startup
- **WHEN** `serve` starts with a container-bound pipeline while the container runtime is unreachable
- **THEN** the process exits as a startup failure naming the runtime, before the label-provisioning smoke test runs

#### Scenario: A stale image refuses the daemon at startup
- **WHEN** `serve` starts with a sandbox image whose contract label is missing or names another version
- **THEN** the process exits as a startup failure printing the contract refusal with its `FROM` line, and no box was created

#### Scenario: Preconditions that hold let startup continue unchanged
- **WHEN** every precondition holds
- **THEN** startup proceeds to the label-provisioning smoke test and the assessment left no trace: no claim, no clone write, one container start at most (the floor probe)

### Requirement: A precondition refusal in a slot or the janitor is a daemon condition
A typed operator-precondition refusal met after a claim SHALL end the claimed run with a typed result that reaches every sink a terminal result reaches, SHALL release the claim with no abort marker, backoff or fuse charge, and SHALL open that precondition's condition. The janitor's branch pass SHALL report the same refusals to their condition and continue. Only a typed refusal SHALL open a condition.
**Implements**: FR14, FR15, FR17, NFR-R2, NFR-O3, G7 of add-sandbox-base-image
**Notes**: The refusals are the clone's ref format, the container runtime (met first by the sandbox plan), the box's git floor and the image contract; anything untyped stays a crash with its abort accounting (design D18, D19, D21).
**Notes**: The release is the lease path's (the reaper returns the task after TTL) until `add-claim-return` lands; the open condition is what stops the feed from claiming the task again.

#### Scenario: A slot's ref-format refusal is a result, not a crash
- **WHEN** the operator migrates the factory clone to the `files` backend under a running daemon and the next slot's first touch of the clone refuses it
- **THEN** the slot's outcome log, the drain report and the ledger each record the typed precondition result, no uncaught-crash event is logged, the claim is released, and the task's abort facts read zero

#### Scenario: One claim per free slot for the whole episode
- **WHEN** a precondition is broken under a three-slot daemon for an hour and then fixed
- **THEN** the tracker sees at most three claims and three releases from the episode, none after the condition opened, and the daemon is still running

#### Scenario: The janitor's refusal opens the condition and the pass continues
- **WHEN** the janitor's branch pass meets the ref-format refusal on one branch
- **THEN** the condition opens, the pass logs no failed-tick event, the remaining branches are visited, and the next tick names the condition instead of refusing again at WARN

#### Scenario: The container runtime stops under a running daemon
- **WHEN** the container runtime becomes unreachable while `serve` runs a container-bound pipeline and the next slot's sandbox plan meets it
- **THEN** the slot ends with the typed precondition result, no uncaught-crash event is logged, the claim is released, the container-runtime condition opens, and no task is claimed until its probe passes

#### Scenario: An unclassified failure opens no condition
- **WHEN** a slot's run dies with a runtime exception no arm classifies
- **THEN** no condition opens, the crash arm runs the abort protocol, and the feed keeps claiming

## MODIFIED Requirements

### Requirement: Remote outage gate holds the feed off the tracker
`serve` SHALL keep one set of daemon conditions with one owner, one condition per precondition that can change while the daemon runs: the remote (per remote target), the container runtime, the clone's ref format, the image contract and the box's git floor. A slot's base-refresh infrastructure failure SHALL open the remote condition; a typed precondition refusal from a slot or the janitor SHALL open its condition. The feed SHALL read one gate over all conditions before every claim and SHALL claim nothing while any is open. While a condition is open the daemon SHALL probe it by repeating the check that opened it, on the growing, jittered, capped cadence, and only a passing probe closes it. Slots already working SHALL continue under an open condition.
**Implements**: FR14, NFR-R3, M4 of add-base-ref-resolution; FR2, FR3, NFR-O1, UX1 of signal-outage-gate-on-origin-contact; FR15, FR16 of add-sandbox-base-image
**Notes**: The remote outage gate's state machine, schedule and three-phase probe are unchanged; it is the first condition of the set (design D19).

#### Scenario: At most one claim per slot for the whole outage
- **WHEN** the remote is unreachable for an hour under a three-slot serve
- **THEN** the tracker sees at most three claims and three releases from the
  outage, none after the gate opened, and the daemon is still running

#### Scenario: A probe, not a claim, ends the outage
- **WHEN** the remote returns while the gate is open
- **THEN** the next probe succeeds, the gate closes, and only then does the
  feed claim a task — the first successful `ls-remote` precedes the first
  claim

#### Scenario: A flapping remote does not restart the interval
- **WHEN** the gate closes on a successful probe and the very next base
  refresh fails again before any refresh succeeded
- **THEN** the gate reopens with an interval longer than the one before the
  close, and the interval returns to idle only after a base refresh
  succeeds

#### Scenario: Probe intervals grow with jitter and stop at the cap
- **WHEN** eight consecutive probes fail on virtual time
- **THEN** the intervals between them grow from the idle interval toward the
  cap, no two are identical, and none exceeds the cap

#### Scenario: A clone-served success after a close keeps the grown interval
- **WHEN** the gate closes on a successful probe, the next claim's base is a
  commit SHA the clone already holds, and the base refresh after that fails
  against the remote
- **THEN** the gate reopens with an interval longer than the one before the
  close, the remote's last successful contact still names the closing probe,
  and only a later refresh that contacted origin returns the interval to
  idle

#### Scenario: Probes make no tracker call
- **WHEN** the gate is open and the daemon probes the remote
- **THEN** each probe is a reachability check of the remote alone, and no
  probe sends a tracker request

#### Scenario: Slot signals come from the base read, not the outcome
- **WHEN** a slot's base refresh returns, failed or successful, and the
  slot's task later ends in any terminal result
- **THEN** the gate receives the refresh's signal at the instant the base
  read returned, and nothing is signaled from the task's terminal result

#### Scenario: A restart forgets the gate
- **WHEN** the daemon restarts while its remote outage gate is open
- **THEN** the new process starts with the gate closed and opens it again
  only on its own next base-refresh failure

#### Scenario: One gate read covers every condition
- **WHEN** any condition but the remote is open and the feed's poll yields a claim candidate
- **THEN** the feed abandons its permit with no tracker claim call, exactly as it does for the open remote gate

#### Scenario: A condition's probe is its own check
- **WHEN** the image-contract condition is open and the operator rebuilds the image on the right contract
- **THEN** the next probe reads the image's labels through the same check that refused it, the condition closes, and only then does the feed claim a task

#### Scenario: A held feed asks the tracker nothing
- **WHEN** a condition is open for an hour and the tracker holds Ready tasks
- **THEN** no tracker read is made by the feed in that hour, the feed waits the idle interval between cycles, and its view reads idle-blocked

#### Scenario: A drain stops while the feed is held
- **WHEN** `serve --drain` runs while a condition is open and the tracker holds Ready tasks
- **THEN** the feed claims nothing, the running slots finish, and the process exits as on an empty queue

#### Scenario: A probe that cannot answer is a failed probe
- **WHEN** a condition is open and its probe throws instead of answering (the image inspection refused because the tag is gone, a git binary missing)
- **THEN** the feed thread survives and steps again, the condition stays open with the failure's words as its last error, the next probe waits for the grown interval, and the failure's trace is logged once at DEBUG in the probe streak

#### Scenario: A container start is the box git floor's probe cadence
- **WHEN** the box-git-floor condition is open for an hour
- **THEN** every probe is one one-shot container running the image's `git --version`, the intervals grow to the cap, and no box, volume or network is created

### Requirement: Gate transitions are the outage's log and snapshot signal
Opening a condition SHALL log one WARN with an operator-event code naming the condition, the cause and the fix; closing SHALL log one INFO recovery line with the duration and probe count; failures and probes in between SHALL be DEBUG with a periodic roll-up. A condition open for at least a configured duration SHALL log ERROR once with its own event code. The serve snapshot SHALL carry a `conditions` section (one entry per condition: state, open since, last error, next probe time, consecutive failures) beside `tracker`; the remote's entry is the former `remote` section.
**Implements**: NFR-O1, NFR-O3, UX6 of add-base-ref-resolution; NFR-O2, UX4 of add-sandbox-base-image

#### Scenario: One WARN, one recovery line, roll-ups between
- **WHEN** the remote is unreachable for an hour and then returns
- **THEN** the log carries exactly one WARN on open and one INFO on close,
  every probe in between is DEBUG or a roll-up, and the ERROR line appears
  once only if the outage lasted at least the sustained-open duration

#### Scenario: Blocked on the remote is readable without logs
- **WHEN** an operator opens the dashboard during an outage
- **THEN** the `remote` section shows the gate open, since when, the last
  error, and the next probe time

#### Scenario: A precondition condition logs the fix once
- **WHEN** the clone's ref format refuses under a running daemon and is migrated an hour later
- **THEN** the log carries exactly one WARN naming the condition and the migration remedy, one INFO on close, no uncaught-crash event, and every refusal in between is DEBUG or a roll-up

#### Scenario: An open precondition is a dashboard field
- **WHEN** an operator opens the dashboard while the container-runtime condition is open
- **THEN** the `conditions` section names that condition open, since when, the last cause and the next probe time, beside the remote's entry
