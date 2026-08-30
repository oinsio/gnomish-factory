# Proposal: add-stage-finished-event

## Why

A stage boundary — the moment a stage passes verify and the task advances — is the most
meaningful progress milestone the factory produces, yet the sealed `EngineEvent` stream never
says it explicitly: today a consumer must infer it from an `AttemptFinished` whose
`newState.position()` moved, which is fragile and forces every consumer to re-derive the same
rule. Operators also have no way to be told about progress without watching logs or the
tracker: the factory runs autonomously for hours, and "stage X just passed" is exactly the
ping a human wants. Canonical prior art shapes the design: Tekton emits CloudEvents from a
component decoupled from the run so notification failure structurally cannot fail the
pipeline; Airflow callbacks are best-effort observers. The engine already has that structure —
listeners are swallow-and-log observability, never effects — so an explicit stage-boundary
event plus a notification listener is a small, safe addition.

## What Changes

- **ADDED**: a new `EngineEvent.StagePassed` variant, emitted after a stage's passing round is
  durably persisted (the persist → `AttemptFinished` ordering invariant is preserved; the
  new event follows both) and advancement is applied, carrying the passed stage and the
  advanced-to position.
- **MODIFIED**: the `stage-engine` "Engine events" requirement grows from seven variants to
  eight; existing exhaustive switches over the sealed interface (MDC, logging, status,
  heartbeat-progress listeners) gain an arm (compiler-enforced).
- **ADDED**: an operator-notification capability — a webhook notifier implemented as one more
  `EngineEventListener` wired into the bootstrap composite when configured, posting a JSON
  payload on stage boundaries, best-effort by construction.
- **ADDED**: operator configuration surface `factory.notify.webhook` in the factory's own
  config — never in the target project's `.gnomish/`; the pipeline must not know about the
  operator's messenger.
- **MODIFIED**: the `factory-egress-allowlist` capability extends its egress rules (https-only,
  blocked address classes, redirect re-check, response/time bounds) to the notification
  webhook — the factory's second credential-free outbound HTTP path.

## Goals

- **G1** — a consumer can react to a stage boundary from one explicit event, with zero
  position-diffing inference.
- **G2** — an operator can point the factory at one HTTPS URL and receive a notification for
  every stage pass and every run end, with no change to any target project repo.
- **G3** — notification failure is structurally incapable of altering a run's outcome or
  blocking its critical path.

## Non-Goals

- **NG1** — richer messenger adapters (Slack blocks, Telegram bots, e-mail). Future changes;
  this change ships exactly one minimal outbound-webhook adapter.
- **NG2** — per-event subscription/filter configuration. The notified event set is fixed in
  this change.
- **NG3** — durable/retried notification delivery (queues, outbox). Delivery is best-effort,
  at-most-once; a crash between persist and emit loses the notification, by design.
- **NG4** — feeding the new event into serve-observability snapshot/ledger JSON. The wire
  vocabularies are untouched (see design D4); a later change may surface it on the dashboard.
- **NG5** — migrating existing listeners off position-diffing where they infer boundaries
  today. Assessed in design D5; migration is not forced.
- **NG6** — inbound HTTP of any kind. The factory stays webhook-out, poll-in.

## Users & Scenarios

- **U1 — operator on the move**: runs the factory over lunch; gets a webhook-driven message
  ("`plan` passed, now at `implement`") in their own messenger via a relay they control
  (e.g. a Slack incoming-webhook proxy they allowlist), without opening logs.
- **U2 — status-view / tooling author**: consumes the event stream and reacts to stage
  boundaries directly instead of diffing `AttemptFinished` positions.
- **U3 — operator with a broken webhook**: endpoint is down for a day; every run proceeds
  untouched, WARN lines record each failed delivery.

## Requirements

### Functional

- **FR1** — the engine SHALL emit a new sealed `EngineEvent` variant `StagePassed` when a
  stage's verify passes and advancement is applied, carrying the task id, the passed stage
  name, and the advanced-to position. It SHALL be emitted only after the passing round —
  whose persisted state already carries the advanced position — is durably persisted, and
  after that round's `AttemptFinished`, preserving the existing persist-before-event ordering
  invariant.
- **FR2** — `StagePassed` SHALL fire for every passing stage regardless of advancement mode:
  an AUTO advance to the next stage, a MANUAL pause, and the final stage's advance to
  pipeline end. It SHALL NOT fire for a run that starts at pipeline end (nothing passed in
  that run) nor be re-emitted on resume for a stage whose pass was persisted by a previous
  run.
- **FR3** — a webhook notifier SHALL be provided as an `EngineEventListener`, registered in
  the bootstrap composite only when its configuration is present; absent configuration wires
  nothing and changes nothing.
- **FR4** — the notifier SHALL POST one JSON payload per `StagePassed` and one per
  `TaskFinished` (the run's terminal boundary) to the configured URL, identifying the task,
  the boundary kind, and the stage/outcome.
- **FR5** — the notification configuration SHALL live in the factory's operator-owned config
  under `factory.notify.webhook` and SHALL NOT be readable from the target project's
  `.gnomish/` in any form.

### Non-Functional Reliability

- **NFR-R1** — a notification failure (connection error, non-2xx, timeout) SHALL be logged at
  WARN and swallowed; it SHALL never alter a run's outcome, burn an attempt, or delay
  persistence. One delivery attempt per event; no retry queue (NG3).
- **NFR-R2** — delivery semantics are at-most-once per stage pass: the event is emitted after
  the durable persist, so a kill in the window between persist and emit loses only the
  notification, never the pass; resume does not re-emit it.

### Non-Functional Performance

- **NFR-P1** — the notifier's `onEvent` SHALL return promptly, per the listener port
  contract: the HTTP exchange runs off the engine's critical path, bounded by a configured
  timeout.

### Non-Functional Observability

- **NFR-O1** — every delivery attempt outcome (sent / failed with reason) SHALL be logged
  with the task id and boundary, so U3's broken-webhook day is fully reconstructable from
  the log file.

### Non-Functional Security

- **NFR-S1** — notification egress SHALL obey the factory-egress-allowlist rules: `https`
  only; link-local, cloud-metadata, and RFC1918 address classes refused (the operator's
  explicit URL does not waive address-class checks unless the literal address is
  allowlisted); redirects re-checked; response size and total time bounded. The URL comes
  from operator config only — no interpolation of manifest-controlled values into it.

### Non-Functional Cost

Not applicable — no model calls, no tokens.

## Operator Experience Criteria

- **UX1** — enabling notifications is one config block (URL, optional timeout) in the
  factory's own config; no target-repo edit, no restart semantics beyond normal config load.
- **UX2** — a malformed or non-`https` webhook URL fails fast at assembly/startup with a
  message naming the property, not silently at the first stage pass.
- **UX3** — the notification payload is self-sufficient: task id, boundary, stage or outcome
  — readable in a raw webhook inspector without consulting the factory.

## Success Metrics

- **M1** — 100% of stage passes in the engine test suite emit exactly one `StagePassed`,
  ordered after persist and `AttemptFinished`; resume-after-pass scenarios emit zero.
- **M2** — with a WireMock endpoint returning 500/timeouts for every delivery, all runs end
  with byte-identical outcomes and state to the no-notifier baseline.
- **M3** — mutation score stays at the module gates (100%/justified exceptions) for all
  touched classes; every new FR/NFR has at least one referencing spec.

## Open Questions

- **Q1** — should richer messenger adapters (Slack, Telegram) become one change each or a
  single `add-messenger-notifiers` change? (Future; NG1.)
- **Q2** — when the dashboard wants stage boundaries, does `StagePassed` enter the snapshot
  wire vocabulary or get derived server-side from state? (Deferred with NG4.)
- **Q3** — is an authenticated webhook (HMAC signature header) worth adding before any real
  messenger adapter exists? (Deferred; the allowlist rules are the current guard.)

## Capabilities

### New Capabilities

- `operator-notifications`: best-effort operator notification of run milestones — the
  listener seam, the webhook adapter, its `factory.notify.webhook` config surface, and the
  failure-isolation contract.

### Modified Capabilities

- `stage-engine`: the "Engine events" requirement gains the eighth sealed variant
  `StagePassed` with its emission point and ordering guarantees.
- `factory-egress-allowlist`: egress governance extends beyond the http check to the
  notification webhook — same scheme, address-class, redirect, and resource rules.

## Impact

- `domain` — `EngineEvent` (new variant), `Engine.runStages` (emission point); every
  exhaustive switch over `EngineEvent` in `application` (`MdcEventListener`,
  `LoggingEventListener`, `StatusEventListener`, `HeartbeatProgress`) gains a compiler-forced
  arm.
- `application` — new notifier listener class(es); `FactoryProperties` grows a `notify`
  section.
- `bootstrap` — `RunAssembler` conditionally adds the notifier to the composite listener.
- Dependencies: none new — `java.net.http.HttpClient` and Jackson are already in the stack
  (ADR 0001). Tests: Spock + WireMock, existing patterns.
- Not touched: `serveobservability` wire mappers, sandbox, tracker adapters, `.gnomish/`
  pipeline config schema.
