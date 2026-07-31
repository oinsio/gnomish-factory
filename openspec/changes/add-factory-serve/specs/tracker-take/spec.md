# tracker-take — delta

## MODIFIED Requirements

### Requirement: take subcommand surface
`gnomish take` SHALL be a separate subcommand, always in git mode, with three
forms: `take <ref>` (explicit mode), `take <ref> <ref> ...` (batch mode, two
or more refs), and bare `take` (auto mode). Supported flags: `--dir`,
`--interactive[=executor|judge]` (single explicit form only),
`--base` (single explicit-mode start only), `--discard-work` (resume with
diverged branches), and the headless takeover flag. `take` SHALL have no
`--mode`, no ad-hoc source flags (`--task`, `--task-file`, `--task-id`,
`--resume`), and no `--from-stage`; the bare form SHALL reject start
modifiers (`--base`); the batch form SHALL reject `--interactive` and
`--base`. The `gnomish run` flag matrix SHALL remain unchanged. Short refs
(`42`, `#42`) expand via the configured binding; a full canonical id naming a
foreign repo is an error (subject to the adapter's rename tolerance).
<!-- implements FR9 of add-tracker-port -->
<!-- implements FR2, FR3 of add-factory-serve -->

#### Scenario: Flag validation
- **WHEN** `take` is invoked with `--mode`, `--task`, `--resume`, or bare
  `take` with `--base`
- **THEN** each invocation fails with a validation error before touching the
  tracker

#### Scenario: Batch rejects interactivity
- **WHEN** `take 42 43 --interactive` is invoked
- **THEN** the invocation fails with a validation error before touching the
  tracker

#### Scenario: Short ref expansion
- **WHEN** the operator runs `take 42` with a configured GitHub binding
- **THEN** the run targets the canonical id built from the binding and issue 42

### Requirement: Bare auto mode takes the head of the queue
Bare `gnomish take` SHALL fetch the ready queue via `listReady`, hide tasks
whose abort backoff (exponential from base, capped; computed by core from
adapter abort facts) has not expired, prefer returned tasks over fresh ones,
respect the WIP limit for fresh tasks (claimed only while open fronts < W;
returned tasks always claimable), claim from the head zone — a random pick
among the first K eligible, oldest-first as a soft preference — process
exactly one task to its terminal result, and exit. An empty or fully blocked
queue SHALL be a clean no-op run naming the reason (nothing eligible, or the
WIP limit). Losing the claim race SHALL fall through to the next eligible
task.
<!-- implements FR10 of add-tracker-port -->
<!-- implements NFR-C1 of add-tracker-port -->
<!-- implements FR6, FR9 of add-factory-serve -->

#### Scenario: One task per run
- **WHEN** the queue holds three ready tasks and bare `take` runs
- **THEN** exactly one task from the head zone is processed and the process
  exits after its terminal result

#### Scenario: Backoff hides a task
- **WHEN** the queue head aborted moments ago and its backoff has not expired
- **THEN** bare `take` claims the next eligible task instead

#### Scenario: WIP limit blocks a fresh start
- **WHEN** open fronts equal W and only fresh tasks are ready
- **THEN** bare `take` exits as a clean no-op naming the WIP limit

#### Scenario: Returned task preferred
- **WHEN** the queue holds an older fresh task and a younger returned task
- **THEN** bare `take` claims the returned task

## ADDED Requirements

### Requirement: Batch take works the list with a summary and one exit code
Batch `take <ref> <ref> ...` SHALL apply the explicit-mode disposition matrix
to each ref independently, working refs through the scheduler up to N
concurrently: skipped and refused refs are reported with their reason and the
run continues. `Working` refs SHALL be skipped unless the headless takeover
flag authorizes takeover (batch never prompts). The run SHALL end with a
summary naming every ref's outcome and exit with one aggregate code in which
the "tool could not operate" family (codes below 10) dominates the
"legitimate outcome" family (10 and above); a batch where every ref delivered
exits 0.
<!-- implements FR2, FR3, FR4 of add-factory-serve -->
<!-- implements NFR-O2 of add-factory-serve -->

#### Scenario: Mixed batch summarized
- **WHEN** `take 42 43 44` delivers 42, skips 43 as held by another instance,
  and parks 44 as an escalation
- **THEN** the summary lists all three outcomes and the exit code comes from
  the legitimate-outcome family

#### Scenario: Tool failure dominates
- **WHEN** one ref fails with a pipeline load failure and the others deliver
- **THEN** the aggregate exit code comes from the below-10 family

### Requirement: Operator guide covers autonomous operation
The operator guide SHALL gain the autonomous-operation surface: the
`serve` / batch / drain CLI reference with lifecycle behavior (SIGTERM grace,
drain semantics, restart); the feed states and what the WIP-limit message
means (answering escalations is the unblocking action); the instance knobs
(slots N, idle interval, grace) versus the protocol constants in `.gnomish/`
(`wip-limit` beside the heartbeat keys); the shared write budget — heartbeat
dominates steady-state writes, bounding total concurrency ΣN at the default
beat interval, with the beat interval (not instance count) as the scaling
knob; the WIP method boundary (W limits how many branches are open, not
whether they merge — integration discipline stays with the pipeline author);
the autonomy gate ("who can set the ready label can execute code on the
factory host" — never auto-`ready` from untrusted sources); and CI hygiene
for gnome branches (workflows triggered by `gnomish/*` pushes carry no
privileged secrets; `GITHUB_TOKEN` read-only). The cron path SHALL now point
to drain mode, with the manual label flip demoted to a last-resort escape
hatch.
<!-- implements NFR-P2, NFR-S1, NFR-S2 of add-factory-serve -->
<!-- implements UX1, UX2, UX4 of add-factory-serve -->

#### Scenario: Guide explains the stalled factory
- **WHEN** an operator sees the daemon idle at the WIP limit
- **THEN** the guide names the state, the reason (open fronts ≥ W), and the
  unblocking action (answer escalations; returned tasks drain first)

#### Scenario: Guide states the autonomy gate
- **WHEN** an operator considers bridging external issues to the ready label
- **THEN** the guide forbids auto-`ready` from untrusted sources, equating
  ready-label access with code execution on the factory host
