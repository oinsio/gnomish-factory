## MODIFIED Requirements

### Requirement: Take runs the heartbeat thread and the reaper duty
A take run SHALL start the beat-only instance heartbeat thread at its first
successful claim and stop it when no claim is held (terminal result reached or
claim lost). Independently of any claim, the run SHALL start the standing
reaper at the run start and stop it when the invocation ends: while the run
lives, the reaper lists open tasks, updates observations, and removes stale
claims on each tick, whether or not the run currently holds a claim of its
own. Reaping is a duty of the run itself, never a byproduct of holding a
claim — the same standing-reaper mechanism `serve` uses, scoped to one
invocation; the heartbeat thread performs no reaper duty.
<!-- implements FR1, FR4 of add-claim-heartbeat -->
<!-- implements FR1, FR5 of fix-reaper-idle-liveness -->

#### Scenario: Beat starts with the claim
- **WHEN** bare `take` claims the queue head
- **THEN** the claim comment starts receiving beats within one interval, until
  the run reaches its terminal result

#### Scenario: Long run reaps a neighbor
- **WHEN** a take run works a multi-hour task while another instance died
  holding a claim
- **THEN** before the run ends, the dead claim is removed and its task is
  `Ready` — unclaimed by the reaping run

#### Scenario: Reaping outlives the beat thread
- **WHEN** a take run's heartbeat thread dies abnormally while a foreign
  claim in the listing goes stale
- **THEN** the standing reaper, on its own thread, still removes the stale
  foreign claim before the run ends

### Requirement: Operator guide
The change SHALL ship an operator guide (`docs/operator-guide.md`) covering: quick
start (tracker config section, token env variable, factory config layers), handing
off a task via the ready label and automatic label provisioning, the label
dictionary with who moves what, the escalation/decision/ack flow (reply, return
to ready, re-run), snapshot behavior (issue edits do not affect a taken
task; influence via decisions or revoke-and-recreate), stuck-`Working`
recovery — automatic reaping whenever any factory instance is running, claim
in hand or not, bounded only by runs too short to observe a full TTL — the
confirmed `take <ref>` takeover with its headless flag, and the
honest limitation that one-shot cron runs cannot observe longer than TTL so
cron-only operation keeps the manual label flip until `serve` exists — the
heartbeat/TTL settings with the shared write-budget coupling (beat interval ×
concurrent tasks vs the shared token's write limits), Projects v2 boards as a
display-only parallel universe with the shipped reference "column → ready label"
cron workflow (`docs/examples/board-bridge.yml`), the fork warning ("fix
`tracker.repo`"), and the `take` CLI reference with exit behavior.
<!-- implements FR19 of add-tracker-port -->
<!-- implements FR6 of add-claim-heartbeat -->
<!-- implements NFR-P1, UX2, UX3 of add-claim-heartbeat -->
<!-- implements FR1 of fix-reaper-idle-liveness -->
<!-- implements UX1 of fix-reaper-idle-liveness -->

#### Scenario: Guide covers the operator surface
- **WHEN** an operator follows the guide against a fresh repository
- **THEN** every step from configuration to first delivered task and first
  escalation round-trip is described without reference to factory source code

#### Scenario: Guide states when recovery is automatic
- **WHEN** an operator reads the stuck-`Working` section
- **THEN** it distinguishes automatic reaping (any running instance whose run
  outlives a TTL — no claim of its own required), explicit takeover (any time,
  confirmed), and the cron-only manual escape hatch, and names the
  write-budget consequence of shortening the beat interval
