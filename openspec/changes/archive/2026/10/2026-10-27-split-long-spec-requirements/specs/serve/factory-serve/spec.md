## ADDED Requirements

### Requirement: A condition opens on the refusal that met it
A slot's base-refresh infrastructure failure SHALL open the remote condition; a typed precondition refusal reported by a slot or the janitor SHALL open the condition of its kind and no other.
**Implements**: FR14, NFR-R3, M4 of add-base-ref-resolution; FR2, FR3, NFR-O1, UX1 of signal-outage-gate-on-origin-contact; FR15, FR16 of add-sandbox-base-image; FR2 of split-long-spec-requirements

#### Scenario: A reported refusal opens the condition of its kind
- **WHEN** a slot or the janitor reports a typed precondition refusal of one kind
- **THEN** that kind's condition opens, with the refusal's cause and fix, and no other condition changes

### Requirement: An open condition is probed by its own check
While a condition is open the daemon SHALL probe it by repeating the check that opened it, on the growing, jittered, capped cadence; only a passing probe SHALL close it, and slots already working SHALL continue under an open condition.
**Implements**: FR14, NFR-R3, M4 of add-base-ref-resolution; FR2, FR3, NFR-O1, UX1 of signal-outage-gate-on-origin-contact; FR15, FR16 of add-sandbox-base-image; FR2 of split-long-spec-requirements

#### Scenario: Probes repeat the condition's own check on the capped cadence
- **WHEN** the box-git-floor condition stays open for an hour
- **THEN** every probe is the floor check itself, the intervals between them grow with jitter to the cap, and the condition closes only on a probe that passes

### Requirement: Open conditions are a snapshot section
The serve snapshot SHALL carry a `conditions` section, one entry per condition — state, open since, last error, next probe time, consecutive failures — beside `tracker`; the remote's entry SHALL be the former `remote` section.
**Implements**: NFR-O1, NFR-O3, UX6 of add-base-ref-resolution; NFR-O2, UX4 of add-sandbox-base-image; FR2 of split-long-spec-requirements

#### Scenario: The conditions section carries every condition's entry
- **WHEN** the snapshot is written while one condition is open
- **THEN** `conditions` holds an entry per condition, the open one with its state, open since, last error and next probe time, and the remote's entry is among them

## MODIFIED Requirements

### Requirement: Remote outage gate holds the feed off the tracker
`serve` SHALL keep one set of daemon conditions with one owner, one condition per precondition that can change while the daemon runs: the remote (per remote target), the container runtime, the clone's ref format, the image contract and the box's git floor. The feed SHALL read one gate over all conditions before every claim and SHALL claim nothing while any is open.
**Implements**: FR14, NFR-R3, M4 of add-base-ref-resolution; FR2, FR3, NFR-O1, UX1 of signal-outage-gate-on-origin-contact; FR15, FR16 of add-sandbox-base-image; FR1, FR2 of split-long-spec-requirements
**Notes**: The remote outage gate's state machine, schedule and three-phase probe are unchanged; it is the first condition of the set (design D19).
**Notes**: What opens a condition is the requirement "A condition opens on the refusal that met it"; how it is probed and closed, "An open condition is probed by its own check".

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
Opening a condition SHALL log one WARN with an operator-event code naming the condition, the cause and the fix; closing SHALL log one INFO recovery line with the duration and probe count; failures and probes in between SHALL be DEBUG with a periodic roll-up. A condition open for at least a configured duration SHALL log ERROR once with its own event code.
**Implements**: NFR-O1, NFR-O3, UX6 of add-base-ref-resolution; NFR-O2, UX4 of add-sandbox-base-image; FR1, FR2 of split-long-spec-requirements
**Notes**: The snapshot side of the signal is the requirement "Open conditions are a snapshot section".

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
