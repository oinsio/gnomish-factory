# Proposal: add-tracker-port

## Why

The factory can already drive one ad-hoc task through a pipeline (`gnomish run`) and
persist everything in git, but tasks still arrive as pasted text. The core promise of
the architecture — "gnomes take tasks from a task tracker, humans handle only
escalations" — needs a tracker integration: a `Tracker` port designed around the
factory's needs, a reference implementation, a GitHub adapter, and a single-task CLI
mode that takes a real tracker task from claim to delivery. Multitask scheduling
stays out: this change makes one task from a tracker work end-to-end, by any instance.

## What Changes

### ADDED

- `Tracker` port (application layer, beside `TaskRepository`): one interface covering
  feed, coordination, and correspondence — `listReady`, `fetchTask`,
  `collectDecisions`, `claim`, `release`, `park`, `finish`, `recordAbort`,
  `acknowledgeDecision`, `postNote`.
- Logical task-state dictionary (`Ready`, `Working`, `AwaitingHuman`, `Finished`)
  with a transition matrix; three levels kept distinct: tracker state (coordination
  language), run outcome (engine event), scheduler slot (instance-internal, never
  written to the tracker).
- In-memory reference adapter + port contract spec that every adapter must pass
  (including a simulated concurrent-claim race).
- GitHub adapter: mutually exclusive state labels with configurable names and colors,
  idempotent label provisioning, lease-pattern claim over structural comments
  (earliest comment id wins), structural comments for machine-readable coordination
  facts (claim, abort, ack), feed via the List Issues API with PR filtering.
- `gnomish take` subcommand: explicit mode (`take <ref>` — operator mandate with a
  full disposition matrix over task states) and bare auto mode (`take` — head of the
  ready queue, one task, exit). Always git mode; no ad-hoc flags.
- Task snapshot at first claim (id/title/body frozen into `TaskContext`/`task.json`);
  resume collects only human decisions, never re-reads the snapshot.
- Resume protocol: decisions are comments after the last ack; interactive wait
  listens to TTY and tracker simultaneously (first non-empty answer wins); every
  consumed decision is acknowledged with an "acting on decision" comment.
- Abort protocol: structural abort marker + return to `Ready` in one operation;
  abort facts (count, last time) recoverable from comments by any instance; K-abort
  fuse diverts the task to `AwaitingHuman` with an infrastructure report.
- Task revocation detection at round boundaries (task closed / claim lost): salvage
  commit, best-effort push, structural note, release.
- Canonical task identity `github:owner/repo#42` (host included only for non-default
  `api-url`), reusing the existing branch-name sanitize unchanged.
- Two instructions as change artifacts: operator guide (labels, escalations,
  snapshot behavior, Projects v2 bridge with a reference cron workflow) and
  third-party adapter author guide (Redmine as the worked example).

### MODIFIED

- `.gnomish/config.yaml` gains a `tracker` section: core keys (`type` discriminator,
  `abort-threshold`) plus a typed adapter-owned subsection (`github:` with mandatory
  `api-url`, `repo`, `labels`), validated by the adapter with fail-fast aggregation.

### REMOVED

- Nothing.

## Capabilities

### New Capabilities

- `tracker-port`: the `Tracker` port abstraction — operations, task-state dictionary
  and transition matrix, snapshot/decision/abort semantics, port contract properties,
  the in-memory reference adapter, and the adapter author guide.
- `github-tracker`: the GitHub adapter — label mapping and provisioning, lease-claim
  protocol, structural comment formats, feed queries and PR filtering, rate-limit
  economy (ETag polling), `tracker.github` config subsection, canonical id mapping.
- `tracker-take`: the `gnomish take` CLI — explicit and bare auto single-task modes,
  disposition matrix, snapshot at claim, resume with the console-vs-tracker decision
  race, revocation at round boundaries, abort handling with the K fuse, and the
  operator guide.

### Modified Capabilities

- `pipeline-config`: `.gnomish/config.yaml` gains the `tracker` section — the loader
  knows the core keys (`type`, `abort-threshold`) and delegates the typed adapter
  subsection to the adapter's own validator; mismatched or missing subsections are
  load errors.

## Goals

- **G1**: An operator hands a task to the factory by putting a label on an issue, and
  a single `gnomish take` run carries it from claim to a delivered branch with a
  final report — no ad-hoc text involved.
- **G2**: A third-party developer can build a new tracker adapter (e.g. Redmine) from
  the adapter author guide, the port contract spec, and the in-memory reference alone
  — without reading factory core code.
- **G3**: Coordination survives instance death: every coordination fact (claim,
  abort history, acks, reports) lives in the tracker, so any instance can resume a
  returned task from the tracker plus the task branch.
- **G4**: A cron-driven "minimal factory" works before the factory loop exists: bare
  `gnomish take` on a schedule processes a queue one task at a time.

## Non-Goals

- **NG1**: Factory loop — parallelism, slot scheduling, hold-slot policy, continuous
  polling, heartbeat/stale-claim protocol (separate change; backlog recorded in
  `explore-notes-factory-loop.md`).
- **NG2**: Jira adapter (port is designed for it; implementation later).
- **NG3**: External CI checks on the task branch (deferred to a QC-focused change;
  scope frozen in explore notes Р17).
- **NG4**: Projects v2 as an adapter source of truth (documented as rejected for v1;
  the board is a display, the bridge workflow is an operator-side recipe).
- **NG5**: Mid-round cancellation of a running gnome (revocation latency = one round
  by design).
- **NG6**: Multi-id explicit runs (`take` accepts exactly one ref; batches belong to
  the loop).
- **NG7**: Closing issues after merge — that is tracker/human life, not the factory's
  (`Finished` = "delivered for review", never "closed").

## Users & Scenarios

- **U1 — Operator hands off a task**: puts `gnomish:ready` on an issue; `gnomish take`
  claims it, works it, flips the label to `gnomish:delivered` with a final report
  comment; the operator reviews the branch.
- **U2 — Operator resolves an escalation**: sees `gnomish:needs-human` and a report
  comment; replies with a decision and moves the label back to ready; the next
  `take <ref>` (or the still-waiting interactive dialog) consumes the decision,
  acknowledges it, and continues from the branch.
- **U3 — Adapter author**: implements the port for Redmine following the guide, runs
  the shared contract spec suite against it, ships it without touching core.
- **U4 — Cron minimal factory**: a scheduled bare `gnomish take` drains the ready
  queue one task per run; aborted tasks come back with backoff, repeated
  infrastructure failures trip the fuse to a human instead of burning tokens.

## Requirements

### Functional

- **FR1**: The `Tracker` port SHALL expose exactly the v1 operations `listReady`,
  `fetchTask`, `collectDecisions`, `claim`, `release`, `park`, `finish`,
  `recordAbort`, `acknowledgeDecision`, `postNote`, speaking the factory's language
  (tasks, states, decisions) with tracker-specific mapping confined to adapters.
- **FR2**: Task coordination SHALL follow the logical state dictionary (`Ready`,
  `Working`, `AwaitingHuman(escalation|checkpoint|infra)`, `Finished`) and its
  transition matrix; only the factory and humans initiate transitions — the gnome
  never does, and scheduler-slot state is never written to the tracker.
- **FR3**: An in-memory reference adapter SHALL implement the full port and serve as
  the executable example for adapter authors, including simulation of concurrent
  claim races.
- **FR4**: A port contract spec SHALL exist that every adapter must pass, covering at
  minimum: `listReady` filtering (no `Working`/`AwaitingHuman`/`Finished`, no
  non-ready, only tasks — never other artifact types such as PRs; backoff NOT
  filtered — that is core policy over adapter-provided facts), observable claim
  atomicity (two concurrent claims → exactly one winner), and round-trip of
  structural markers (abort facts readable back by any instance;
  `collectDecisions` empty after an ack until a new human reply).
- **FR5**: The GitHub adapter SHALL map logical states to mutually exclusive labels
  with configurable names and colors (defaults: `gnomish:ready`/green,
  `gnomish:working`/blue, `gnomish:needs-human`/red, `gnomish:delivered`/purple),
  maintain exclusivity via point label add/remove (never whole-set replacement), and
  idempotently provision missing labels at startup with colors and operator-hint
  descriptions — applying color only at creation, never recoloring existing labels.
- **FR6**: The GitHub adapter SHALL implement claim as a lease: set label + post a
  structural claim comment, re-read claim comments since the last boundary, earliest
  comment id wins; the loser annuls its marker and backs off.
- **FR7**: Coordination facts (claim holder, aborts, acks) SHALL live in structural,
  machine-recognizable comments — never in labels; the marker format is
  adapter-private as long as round-trip holds; report rendering (domain report →
  text) is core's job, adapters receive finished text plus structural fields.
- **FR8**: The GitHub feed SHALL use the List Issues API (`state=open` + ready label,
  ascending by number) — not the Search API — and SHALL filter out pull requests.
- **FR9**: `gnomish take <ref>` SHALL implement the explicit-mode disposition matrix:
  claim `Ready` (mandate overrides the readiness criterion, abort backoff, and the K
  fuse for one attempt without resetting the abort counter); resume
  `AwaitingHuman` by collecting a decision (escalation) or a confirmation
  (checkpoint); refuse `Working` held by another instance with an error naming the
  holder; skip `Finished` ("already done") and closed/nonexistent tasks with clear
  errors. Short refs (`42`, `#42`) expand via the configured binding; a canonical id
  pointing at a foreign repo is an error.
- **FR10**: Bare `gnomish take` SHALL take the head of the `listReady` queue (adapter
  order), process exactly one task, and exit; an empty queue is a clean no-op run.
  Core SHALL hide `Ready` tasks with unexpired abort backoff from this auto feed.
- **FR11**: At first claim the factory SHALL snapshot id/title/body into
  `TaskContext` and `task.json`; subsequent issue edits SHALL NOT affect the running
  or parked task; resume SHALL NOT re-read the snapshot — it collects decisions only.
- **FR12**: Decision collection SHALL return human reply comments posted after the
  last ack; consuming a decision from any source (tracker comment or TTY) SHALL post
  an "acting on decision: <text>" ack comment — the single mechanism that mirrors
  console input, resolves the two-answers race, and anchors future pairing.
- **FR13**: The interactive decision wait SHALL listen to TTY input and tracker
  polling concurrently — first non-empty answer wins, the other source is cancelled
  — and SHALL tell the operator both channels are watched. Headless (no TTY, no
  tracker answer): leave the task `AwaitingHuman` and report "reply in the tracker
  and re-run".
- **FR14**: On an infrastructure abort the factory SHALL log ERROR, post a
  best-effort structural abort comment (cause, instance, time), release the claim,
  and return the task to `Ready`; when the consecutive-abort count reaches K
  (`abort-threshold`, shared across instances via comments) the task SHALL go to
  `AwaitingHuman` with an infrastructure report instead. The counter resets on the
  first durably committed round after claim.
- **FR15**: At each round boundary the factory SHALL verify the task is still ours
  and alive (not closed, claim intact, state unchanged by a human); on revocation:
  salvage-commit uncommitted work, best-effort push, structural "work stopped" note,
  release claim, leave tracker state untouched, keep the branch.
- **FR16**: Task identity SHALL be the canonical string `github:owner/repo#42`, with
  the host included only when `api-url` differs from the github.com default; the id
  is self-contained, a code constant (not configuration), and flows unchanged into
  `task.json`, logs, and structural comments, reusing the existing branch sanitize.
- **FR17**: `.gnomish/config.yaml` SHALL gain a `tracker` section: core keys `type`
  (discriminator; absent section = `take` unavailable, `run` unaffected) and
  `abort-threshold` (default 3), plus a typed adapter subsection owned and validated
  by the adapter (for GitHub: mandatory `api-url` with no code default, `repo`,
  `labels` as `{name, color}` objects). `type` without its subsection, or a
  subsection not matching `type`, is a load error.
- **FR18**: On `Finished` the factory SHALL post a final report comment (stages,
  attempts, branch link, usage) and never touch the task again; re-running a
  finished task is a new task, not a resume.
- **FR19**: The change SHALL ship two instructions as artifacts: an operator guide
  (quick start, label dictionary and who moves what, escalation/decision flow,
  snapshot behavior, stuck-`Working` escape hatch, Projects v2 board caveat plus a
  reference "column → ready label" cron workflow, fork warning, CLI reference) and
  an adapter author guide (state model, port semantics, contract spec as law,
  physical-mapping worked example for Redmine, config subsection ownership, known
  limitations).

### Non-Functional — Reliability

- **NFR-R1**: Claim SHALL be observably atomic: in any concurrent claim race exactly
  one instance proceeds; the contract spec enforces this on every adapter.
- **NFR-R2**: Infrastructure failures of tracker calls SHALL be retried
  (Resilience4j) without burning stage attempts; abort-path tracker writes are
  best-effort — a dead tracker never blocks the abort itself.
- **NFR-R3**: Every coordination fact SHALL be recoverable from the tracker by a
  fresh instance (abort counters, acks, claim holder) — no instance-local state is
  required to resume or to apply the K fuse.
- **NFR-R4**: Label provisioning SHALL be idempotent and act as a startup smoke test
  of the tracker binding — a misconfigured binding (e.g. a fork pointing at a repo
  the token cannot write) fails fast with a clear error, not mid-task.

### Non-Functional — Performance

- **NFR-P1**: GitHub polling SHALL use conditional requests (ETag/304, which do not
  consume the rate limit) for feed and decision polling; steady-state operation
  SHALL stay far below the primary (5000 req/h) and secondary write
  (80 content-writes/min) limits — a state transition costs 2–3 writes.

### Non-Functional — Observability

- **NFR-O1**: All coordination actions SHALL be logged with the canonical task id in
  MDC; structural comments SHALL be machine-recognizable and human-readable, so the
  tracker thread doubles as the audit trail of claims, aborts, decisions, and
  reports.

### Non-Functional — Security

- **NFR-S1**: Tracker credentials SHALL exist only in the factory environment (env
  variable; never read from yaml) and SHALL never reach the gnome process
  environment or prompts; the gnome interacts with the tracker only via factory-fed
  context and `DecisionNeeded` — mirroring the git push monopoly.

### Non-Functional — Cost

- **NFR-C1**: Abort backoff and the K fuse SHALL bound token spend on
  infrastructure-broken tasks: each retry may re-run a full agent round, so a task
  cannot loop through claim→work→abort indefinitely.

## Operator Experience Criteria

- **UX1**: The operator drives the factory entirely through labels and comments in
  the tracker UI — no factory-side commands are needed to hand off, answer, or
  revoke a task; labels carry human-meaningful names and hint descriptions.
- **UX2**: Every refusal of `take <ref>` names the reason in the task's own terms:
  who holds the claim, that the task is already delivered, that it is closed, or
  that the id belongs to a foreign repo.
- **UX3**: The decision dialog states explicitly that both the console and the
  tracker are being watched; whichever answer the factory acts on is visible in the
  tracker as the "acting on decision" ack.
- **UX4**: The tracker issue thread alone tells the story of a task: claim, reports,
  decisions, acks, aborts, final summary — readable without access to factory logs.

## Success Metrics

- **M1**: The same port contract spec suite passes against both the in-memory
  reference and the GitHub adapter (WireMock) — zero adapter-specific exemptions.
- **M2**: The simulated concurrent-claim race yields exactly one winner in 100% of
  contract-test runs for every adapter.
- **M3**: Integration tests demonstrate both full lifecycles end-to-end:
  ready → claim → work → delivered with final report, and
  ready → claim → escalate → human decision → resume → delivered — including resume
  by a different instance than the one that claimed.
- **M4**: Coverage and mutation-score gates per `.claude/rules/testing.md` hold for
  all new production code (100% target; justified exceptions only at integration
  boundaries).

## Open Questions

- **Q1**: How is revocation modeled — a new `TaskOutcome` variant or a runner-level outcome on top of the engine? (design)
- **Q2**: Does `take` support `--from-stage`, and only for `Ready` starts? (design)
- **Q3**: Name of the token env variable (e.g. `GNOMISH_GITHUB_TOKEN`) and exact factory-config keys (`instance-name`, decision poll interval). (design)
- **Q4**: Physical home of the two instructions (`docs/`? beside the `.gnomish/` template?). (design)
- **Q5**: `api-url` normalization rules for the "is it default github.com"
  comparison. (design)
- **Q6**: Foreign-repo canonical-id check vs GitHub owner/repo renames — GitHub serves redirects; warn/verify instead of blind refusal? (design)
- **Q7**: Should the adapter author guide recommend a common structural-marker format for uniformity, despite round-trip being the only hard rule? (design)
- **Q8**: Exact backoff shape and parameters (exponential with a cap?) for abort re-pickup. (design)
- **Q9**: Composition of the final report comment (stages, attempts, usage detail level). (design)
