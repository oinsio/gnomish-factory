# Change: fix-denial-report-attachment

## Why

Guard egress denials are computed, parsed, and unit-tested, but no production
code attaches them to the task report: `add-sandbox-core` NFR-O1 ("denials are
findings, not silence") and its scenario "denied exfiltration attempt reaches
the report" are unmet — a blocked exfiltration attempt is invisible to the
reviewer (recorded as `add-sandbox-core` Q6 at archive time). This is not a
forgotten one-line call: `add-sandbox-core` task 6.2 delegated attachment to
task 8.2's findings funnel, which routed only judge / external / command
findings — the denial channel was never built. Two structural gaps make
the intended wiring impossible without a model change: the
`TaskExecutionEnvironment` port has no denial accessor (denials are reachable
only by downcasting to `SelfCheckedEnvironment`), and the report model has no
verdict-independent findings slot (findings enter `state.json` / `status.json`
only through a check `Verdict.Fail`, so a passing attempt has nowhere to put a
denial). The gap is the exact twin of the already-fixed `add-sandbox-core`
NFR-O2 gap (a Pass verdict had no field to carry the run URL), resolved then by
an analogous domain-model change. `add-sandbox-hardening` NFR-O1 already presumes this slot exists —
this change is its prerequisite, not its part.

## What Changes

- **MODIFIED** `execution-environment`: the port gains a host-agnostic
  `denialFindings()` accessor — denials become reachable through the contract;
  the dead concrete-only `guard()` accessor is dropped from the public surface.
- **MODIFIED** `sandbox-egress`: the "denials are captured" requirement is
  extended — captured denials now reach the task report; the deferral note
  pointing at `add-sandbox-hardening` is removed.
- **MODIFIED** `git-task-persistence`: the state-file contract gains a
  verdict-independent per-attempt denials list, never participating in the
  stage verdict or prior-failure feedback.
- **MODIFIED** `status-report`: the JSON contract surfaces the same denials
  additively.

## Goals

- G1: a guard denial recorded during a round is visible in `status.json` and
  `state.json` for that attempt, regardless of the attempt's verdict.
- G2: attaching denials never changes any stage outcome: a passing attempt
  stays PASS, `priorFailures` feedback is unaffected.
- G3: the slot is reusable by `add-sandbox-hardening` (L7 denials, stripped
  tools, budget events) without further model changes.

## Non-Goals

- NG1: tracker rendering of denials (park report / escalation dialog). The
  reviewer surface for per-task detail is `status.json` / `gnomish status`
  (established by add-serve-observability design D3: per-task canon is the
  task branch); pushing denials into tracker comments spends the tight GitHub
  write budget for a copy of what the canon already shows. Hardening may
  revisit when it adds its own finding sources.
- NG2: new denial *sources* — L7 violations, tool-stripping findings, spend
  anomalies stay in `add-sandbox-hardening`; this change only routes what the
  egress guard already captures.
- NG3: gating on denials (failing a stage because a denial occurred) — NFR-O1
  is observability, not a gate.

## Users & Scenarios

- U1: a reviewer inspecting a delivered task reads `status.json` (or
  `gnomish status <id>`) and sees that the gnome attempted a denied egress
  during an otherwise passing attempt — host, path, method, no request body.
- U2: any factory instance resuming a task reads `state.json` and sees the
  denial history of past attempts without re-reading guard logs.
- U3: a reviewer reading the report of a task that survived a factory crash
  sees each denial under the round it happened in — the resumed round does not
  inherit the denials of the rounds before it, which already have their own
  attempt records.

## Requirements

### Functional

- FR1: `TaskExecutionEnvironment` SHALL expose `denialFindings()` returning
  the structured denial findings of the environment's guard;
  `SelfCheckedEnvironment` delegates to the guard, non-sandboxed adapters
  return an empty list. The concrete-only `guard()` accessor SHALL no longer
  be part of the environment's public surface.
- FR2: the per-attempt record SHALL carry a denials list independent of
  `checkResults`; it SHALL NOT participate in the overall stage verdict nor
  in prior-failure feedback context.
- FR3: denials SHALL be read from the environment at round close and attached
  to that round's attempt record.
- FR4: `state.json` and `status.json` SHALL surface the attempt's denials
  additively (existing documents without the field remain readable; readers
  of the new field see the same finding shape used by check findings).
- FR5: the denial read position SHALL survive the factory process: it is
  committed in `state.json` with the attempt it delimits (paired with the
  identity of the denial source it was read from) and offered back to the
  environment on resume, so a resumed round reports its own denials instead of
  replaying what the surviving denial source still holds. An offered position
  SHALL be applied only when its source identity matches the environment's live
  denial source, and ignored otherwise.

### Non-Functional Reliability

- NFR-R1: denial read-back is best-effort: an unreadable or missing guard log
  yields an empty list and never fails the round, the attempt, or the report.

### Non-Functional Observability

- NFR-O1: a denied egress attempt is a finding, not silence — it reaches the
  task report even when every check passes (closes `add-sandbox-core` NFR-O1
  / UX3).

### Non-Functional Security

- NFR-S1: surfaced denial findings carry only structured metadata (host,
  query-free path, method) — never request bodies, never a query string —
  preserving the guard's body-free capture contract end to end.

## Operator Experience Criteria

- UX1: the reviewer does not need to know about guard logs or container
  internals: denials appear in the same report surfaces as check findings.
- UX2: a task with zero denials reports an empty (or absent) list — no noise.

## Success Metrics

- M1: a spec asserting that a denied round on a **passing** attempt produces
  a denial finding in `state.json` and `status.json` (the scenario today's
  model cannot represent) is green.
- M2: whole-`src` grep shows at least one `src/main` consumer of
  `denialFindings()`; the dead `guard()` public accessor is gone.
- M3: build stays green including the PIT 100% gate;
  `status-report-v1.reference.json` is updated.

## Open Questions

- Q1: none — the tracker-reach question (whether denials must also reach the
  tracker park report, or `status.json` / `state.json` suffice) is resolved as
  NG1.
