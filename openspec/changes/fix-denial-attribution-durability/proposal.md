# Change: fix-denial-attribution-durability

Depends on: `fix-denial-report-attachment` (archived 2026-08-20; its denial
slot, its durable per-round cursor, and its finding DTO are this change's
starting point). A 2026-08-28 architecture session found that predecessor's
cursor feature **inert in production**: the `LeasedEnvironment` view forwards
only six of the port's methods, so the interface's constant defaults swallow
`denialCursor()` / `denialFindings()` / `restoreDenialCursor()` — no cursor is
ever committed and no restore ever runs in a real container run, while both
spec halves stay green over hand-rolled doubles. Repairing that wiring — and
closing the defect class it belongs to — is in this change's scope (FR6, FR9).

Coordination: lands before `add-sandbox-hardening`, whose `sandbox-egress`
delta modifies the same requirement ("Denials are captured as structured
findings"); that block is rebased onto the merged text after this change
archives.

Coordination: lands after `harden-task-branch-contract`, whose
`git-task-persistence` delta modifies the same requirement ("State directory
with one writer per file" — the initial `state.json` is written once by
`TaskRepository` in the STARTED commit); this change's block for that
requirement is rebased onto the merged text after that change archives. Three
further consequences of that ordering, absorbed into this change's tasks:

- The resume restore reads the task branch tip through the branch-shape
  classifier of the `task-branch-contract` capability, not through an ad-hoc
  file-presence check: the newest committed position is offered only for the
  shapes that carry one, and a quarantining shape yields no position at all
  rather than a parse attempt.
- The best-effort cursor-persistence contract (NFR-R1) changes shape: a cursor
  write is no longer a write of its own that may independently fail. It rides
  the atomic single-commit transitions of that change's FR4 and FR5 — the
  position lands in the same commit as the attempt it delimits, written
  through the shared atomic writer. Best-effort still governs *reading* the
  position and the FR4 fallback; it no longer describes a partial or torn
  write of the position itself, which the atomicity guarantee removes.
- That change's `TaskLifecycleCommitWriter.putTaskAndState` currently rewrites
  `state.json` with the cursorless mapper overload, so a RESUMED commit erases
  the committed cursor from the tip. The cursor-preservation rule (no lifecycle
  rewrite drops a committed cursor) is specified here; whichever change is
  still open when the fix is written carries the code, and the other verifies
  it.

## Why

`fix-denial-report-attachment` routes a guard denial onto the attempt record
of the round that earned it. Checking that change (its design D1a) surfaced
two remaining holes, both of which land denials in the wrong place or in no
place at all:

1. **A round that dies before its close has no attempt record.** A
   `roundTimeout` kill or a missing result event escalates as
   `CannotExecute` — no attempt is burned and no round is recorded — so
   there is no slot to attach denials to. Today they are drained and written
   to the factory's own log only, invisible in `state.json` / `status.json`.
   This is precisely the interesting case: a gnome that hung while trying to
   send data out is the one a reviewer most needs to see.
2. **The read position advanced by a failed round's drain dies with the
   process.** The predecessor's FR5 (grown during its implementation, after
   this change was first drafted) already made the position durable along
   the attempt path: it is committed in `state.json` with the attempt it
   delimits, stamped with the identity of the guard container it was read
   from, and offered back on resume. But the position advances on *every*
   read — including the drain of a round that died before its close — and
   that read has no attempt commit to ride. The drain's advance is lost with
   the process, so a resume by another factory instance restores the last
   attempt's position and re-reads the drained denials, attaching them to
   the first round after the resume: the wrong round, on top of hole 1's
   wrong surface.

The architecture session added a third hole and sharpened the first two:

3. **The whole cursor mechanism is disconnected in production.** The
   `LeasedEnvironment` delegating view never overrides the port's three
   denial default methods, so production always sees "no cursor, no
   findings, no restore" while the specs pass over test doubles that do
   implement them. The invariant "the position becomes durable only with the
   record it delimits" is spread over five classes in three modules with no
   owner type and no production-wiring spec — which is why a decorator that
   forgot three methods could silently delete the feature. A repo-wide sweep
   found one latent sibling (`ObservedSandboxLifecyclePass` drops the
   caller's extra sink through an inherited default) and confirmed the rest
   of the codebase clean.

All three break the same guarantee the reviewer relies on: the denials shown
against an attempt are that attempt's own, and no denial is silently lost.
External review (Kafka/Flink offset-with-output, auditd/Falco loss counters)
confirmed the committed-position design and added two practices this change
adopts: every recorded denial carries a source-assigned identity so re-reads
merge idempotently, and loss (a saturated tail cap, a dead source) is made
visible in the report itself rather than only in the factory log.

## What Changes

- **MODIFIED** `stage-engine`: the `CannotExecute` escalation report gains a
  denials list — the drained findings of the round that could not execute —
  without changing how the outcome is classified (still an infrastructure
  failure, still no attempt burned, still no round recorded).
- **MODIFIED** `status-report`: `lastEscalation` surfaces those denials for
  the `cannotExecute` kind, additively under contract v1, and the text render
  lists them.
- **MODIFIED** `git-task-persistence`: `task.json` carries the escalation's
  denials alongside the escalation it belongs to, together with the read
  position their drain advanced — the same position-with-the-record pattern
  `state.json` already follows for attempts.
- **MODIFIED** `sandbox-egress`: the denial read position is specified to
  advance durably only together with the record carrying the denials it
  delimits — an attempt or an escalation — so a resume by a new factory
  process never re-reports denials already recorded, and a lost write
  degrades to a duplicate rather than to silence. Additionally: every
  recorded denial carries a source-assigned identity so a fallback re-read
  merges idempotently instead of duplicating, and denial loss (tail-cap
  saturation, a vanished source) surfaces as a marker in the report itself.
- **MODIFIED** `execution-environment`: the port's denial surface (findings,
  cursor, restore) SHALL survive delegation — a delegating view forwards all
  three, and denial findings are paired with their source-assigned identity.
- **MODIFIED** `sandbox-lifecycle`: the observing sweep-pass decorator
  preserves the caller's extra verdict sink instead of dropping it through an
  inherited default.
- **MODIFIED** `quality-gates`: a named ArchUnit rule fails the build when a
  delegating implementer of an interface leaves any of its default methods
  unforwarded — the mechanical closure of hole 3's defect class.

## Capabilities

### New Capabilities

None — every gap is a requirement change in an existing capability.

### Modified Capabilities

- `stage-engine`: the `CannotExecute` escalation report carries denials
- `status-report`: escalation denials in the JSON contract and the text render
- `git-task-persistence`: escalation denials in `task.json`; denial entries
  carry the source-assigned identity; no lifecycle rewrite drops a committed
  cursor
- `sandbox-egress`: the denial read position advances durably with the
  record it delimits, on both paths; denial identity and idempotent merge;
  in-report loss visibility
- `execution-environment`: the denial surface survives delegation; findings
  carry identity
- `sandbox-lifecycle`: the observing pass preserves the caller's extra sink
- `quality-gates`: delegating-decorator completeness gate

## Impact

- `:domain` — `EscalationReport.CannotExecute` gains a component; a
  `gnomish-plugin-api` type, so the api-compat gate arms and the api version
  moves again (current baseline 0.4.0 after two prior bumps; pre-1.0
  breaking = MINOR, both `compat-baseline/` jars regenerated)
- `:adapters:git` — the task JSON mapper, and the escalation write that now
  carries the drained read position; denial DTO gains the identity field
- `:application` — status JSON mapper, text renderer,
  `ObservedSandboxLifecyclePass`; `:test-fixtures` — the
  `status-report-v1.reference.json` reference document (gains a
  `cannotExecute` escalation sample)
- `:bootstrap` — the resume restore offers the newest committed position
  through the branch-shape classifier; the new architecture spec
  (decorator-completeness gate) joins the existing eight
- `:sandbox:docker` — `LeasedEnvironment` gains the three denial forwards;
  `GuardDenialLog` carries the daemon timestamp onto each parsed denial as
  its identity; the loss marker is emitted where the tail cap saturates.
  The cursor mechanics themselves (position, container-identity stamp,
  resume offer) are unchanged
- `:adapters:agent` — the failure-path drain carries its findings out instead
  of only logging them
- No new dependencies (ArchUnit is already in the build)

## Goals

- G1: a denial recorded during a round that never closed is visible in
  `status.json` and `task.json`, not only in the factory log.
- G2: after a resume by any factory instance, every attempt and escalation
  shows only the denials recorded during its own round.
- G3: no denial disappears: each one appears at least once across the task's
  report surfaces, whichever process observed it.
- G4: none of this changes a stage outcome — a denial still gates nothing.
- G5: the cursor path is exercised over the real production wiring — a
  feature can no longer be green in specs and dead in a real run.
- G6: a denial the factory could not read (truncated, source gone) is
  visible as loss in the report, never as an absence.

## Non-Goals

- NG1: gating on denials (failing a stage because one occurred) — unchanged
  from the predecessor: this is observability, not a gate.
- NG2: tracker rendering of denials — the per-task canon stays the task
  branch and `gnomish status` (predecessor NG1).
- NG3: new denial sources (L7 violations, stripped tools, spend anomalies) —
  they remain `add-sandbox-hardening`'s.
- NG4: denials of verification and judge environments (fresh boxes used by
  checks after the round) — still out of the per-round attachment point.
- NG5: recovering denials of a factory process that died outright mid-round;
  a round nobody ever finished has nothing to attach them to.

## Users & Scenarios

- U1: a reviewer opens `gnomish status <id>` for a task parked with "could
  not execute" and sees that the round hung while attempting a denied egress
  to a paste site, with host, path, and method.
- U2: an operator resumes a task parked yesterday on another machine; the
  first attempt after the resume reports only what that attempt actually
  triggered, not the denials of every attempt before it.
- U3: an operator reading the same task after several resumes counts each
  denial exactly once across the report.

## Requirements

### Functional

- FR1: the `CannotExecute` escalation report SHALL carry a denials list of
  structured guard findings — the denials drained from the round that failed
  before its close. The list SHALL NOT change the outcome's classification:
  no attempt burned, no round recorded, no verdict affected.
- FR2: those denials SHALL reach `task.json` and `status.json` through the
  existing escalation surfaces, additively under contract v1 (an absent field
  reads as an empty list), and SHALL be rendered by the text renderer beside
  the escalation reason.
- FR3: a `CannotExecute` escalation that carries denials SHALL also durably
  record, in the same write, the read position advanced by draining them —
  extending to the escalation path the position-with-the-record pattern the
  attempt commit already follows — so that a round's read starts past every
  denial already recorded against an attempt or an escalation of that task,
  including after a resume by a different factory process. The position
  SHALL never advance durably ahead of the record carrying the denials it
  delimits.
- FR4: when the read position cannot be recovered, the read SHALL fall back
  to reporting more rather than less — duplicates are acceptable noise,
  silence is not — and SHALL say so in the log.
- FR5: a denial SHALL be attributable to exactly one recorded round or
  escalation for as long as the read position is intact; re-reporting SHALL
  be possible only through the FR4 fallback. Attribution assigns a denial to
  the round whose read collected it, which after a crash may be the round
  following the one that triggered it — a declared relaxation, not an
  accident. No lifecycle rewrite of a state file SHALL drop a committed
  cursor.
- FR6: every delegating view of the `TaskExecutionEnvironment` port SHALL
  forward the whole denial surface (findings, cursor, restore) to its
  delegate, and at least one spec SHALL drive the cursor round-trip through
  the production wiring — the real delegating view, not a hand-rolled
  double. The sibling defect (`ObservedSandboxLifecyclePass` dropping the
  caller's extra sink) SHALL be fixed the same way.
- FR7: every recorded denial SHALL carry a source-assigned identity (the
  denial source's own event timestamp), and attaching denials SHALL merge
  idempotently by that identity — a fallback re-read (FR4) re-attaches only
  the denials not already recorded, so duplicates appear in a report only
  when the identity itself was lost.
- FR8: when the factory can see that denials were lost — the tail cap
  saturated a read, or a committed cursor names a source that no longer
  holds its log — it SHALL record a loss marker through the same findings
  channel the denials use, so the report distinguishes "no denials" from
  "no data".
- FR9: a named architecture rule SHALL fail the build when a production
  class that implements an interface and holds a same-type delegate leaves
  any of that interface's default methods unforwarded; justified exemptions
  live in a named allowlist beside the rule.

### Non-Functional Reliability

- NFR-R1: reading the position from the environment SHALL be best-effort: a
  failure yields the FR4 fallback and never fails a round, an attempt, a
  check, or the report. (Persisting it is not best-effort in the old sense:
  the position rides the atomic commit of the record it delimits, so a torn
  or partial position write cannot exist — the write either lands with its
  record or not at all.)
- NFR-R2: the position SHALL share the lifetime of the guard log it indexes:
  when that log is gone, a fresh position is correct, not a fallback.
- NFR-R3: the authoritative position is the one at the branch tip on origin
  after a successful push; a local-only position is advisory, and losing it
  degrades to the FR4 fallback — with FR7's identity merge, to a no-op.

### Non-Functional Observability

- NFR-O1: no denial is silently dropped between a failed round and the report
  a human reads; the escalation surface is the failed round's place to land.
- NFR-O2: a fallback re-read (FR4) SHALL be visible in the log, so duplicated
  denials in a report are explainable rather than mysterious.
- NFR-O3: known loss (FR8) SHALL be visible in the report a human reads —
  in-band, through the same surface as the denials themselves — never only
  in a counter or a factory log line.

### Non-Functional Security

- NFR-S1: escalation-carried denials SHALL be structured metadata only —
  host, path, method, never request bodies — and SHALL pass through the
  findings funnel before reaching any console render, exactly like attempt
  denials do today.

### Non-Functional Cost

- NFR-C1: the read stays bounded — the durable position keeps a read from
  growing with the task's lifetime, and the guard log tail cap stays in force.

## Operator Experience Criteria

- UX1: a reviewer never has to read factory logs or `docker logs` to learn
  that a task's blocked round tried to exfiltrate.
- UX2: a resumed task's report shows no denial twice, and a task with no
  denials shows nothing.
- UX3: a reviewer can tell "this task had no denials" from "this task's
  denial data was lost" without leaving the report.

## Success Metrics

- M1: a spec is green in which a round killed by `roundTimeout` with a guard
  denial produces that denial in `task.json` and `status.json` under the
  escalation, with `attemptsUsed` and the attempt history unchanged.
- M2: a spec is green in which a second factory process resumes a task
  parked on a `CannotExecute` escalation over a surviving guard container
  and its first round's report carries none of the denials already recorded
  by the first process — neither the attempts' nor the escalation's.
- M3: build green including the PIT 100% gate;
  `status-report-v1.reference.json` updated; the state/live report
  equivalence contract still holds.
- M4: a spec is green that drives the cursor round-trip through the real
  `LeasedEnvironment` (production wiring), committing and restoring a
  cursor — the spec whose absence let the predecessor's feature ship dead.
- M5: the decorator-completeness architecture rule is green over the whole
  production tree, and a seeded violation (a delegating class with one
  unforwarded default) fails it in the rule's own spec.

## Open Questions

- Q1: none open — which document carries the escalation-side position and
  how the restore picks between the committed positions are settled in
  design.md (D2); the requirements (FR3, NFR-R2) only fix its lifetime and
  its ordering against the record it delimits.
