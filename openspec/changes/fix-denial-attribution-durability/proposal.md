# Change: fix-denial-attribution-durability

Depends on: `fix-denial-report-attachment` (archived 2026-08-20; its denial
slot, its durable per-round cursor, and its finding DTO are this change's
starting point).

Coordination: lands before `add-sandbox-hardening`, whose `sandbox-egress`
delta modifies the same requirement ("Denials are captured as structured
findings"); that block is rebased onto the merged text after this change
archives.

Coordination: lands after `harden-task-branch-contract`, whose
`git-task-persistence` delta modifies the same requirement ("State directory
with one writer per file" — the initial `state.json` is written once by
`TaskRepository` in the STARTED commit); this change's block for that
requirement is rebased onto the merged text after that change archives.

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

Both break the same guarantee the reviewer relies on: the denials shown
against an attempt are that attempt's own, and no denial is silently lost.

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
  degrades to a duplicate rather than to silence.

## Capabilities

### New Capabilities

None — every gap is a requirement change in an existing capability.

### Modified Capabilities

- `stage-engine`: the `CannotExecute` escalation report carries denials
- `status-report`: escalation denials in the JSON contract and the text render
- `git-task-persistence`: escalation denials in `task.json`
- `sandbox-egress`: the denial read position advances durably with the
  record it delimits, on both paths

## Impact

- `:domain` — `EscalationReport.CannotExecute` gains a component; a
  `gnomish-plugin-api` type, so the api-compat gate arms and the api version
  moves as it did for `AttemptRecord` in the predecessor change
- `:adapters:git` — the task JSON mapper, and the escalation write that now
  carries the drained read position
- `:application` — status JSON mapper, text renderer; `:test-fixtures` —
  the `status-report-v1.reference.json` reference document
- `:bootstrap` — the resume restore offers the newest committed position
  instead of `state.json`'s only
- `:sandbox:docker` — untouched in its cursor mechanics: the durable
  position, its container-identity stamp, and the resume offer already exist
  (predecessor FR5); this change adds a second committed carrier, not new
  guard-side storage
- `:adapters:agent` — the failure-path drain carries its findings out instead
  of only logging them
- No new dependencies

## Goals

- G1: a denial recorded during a round that never closed is visible in
  `status.json` and `task.json`, not only in the factory log.
- G2: after a resume by any factory instance, every attempt and escalation
  shows only the denials recorded during its own round.
- G3: no denial disappears: each one appears at least once across the task's
  report surfaces, whichever process observed it.
- G4: none of this changes a stage outcome — a denial still gates nothing.

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
  be possible only through the FR4 fallback.

### Non-Functional Reliability

- NFR-R1: reading or persisting the read position SHALL be best-effort: a
  failure yields the FR4 fallback and never fails a round, an attempt, a
  check, or the report.
- NFR-R2: the position SHALL share the lifetime of the guard log it indexes:
  when that log is gone, a fresh position is correct, not a fallback.

### Non-Functional Observability

- NFR-O1: no denial is silently dropped between a failed round and the report
  a human reads; the escalation surface is the failed round's place to land.
- NFR-O2: a fallback re-read (FR4) SHALL be visible in the log, so duplicated
  denials in a report are explainable rather than mysterious.

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

## Open Questions

- Q1: none open — which document carries the escalation-side position and
  how the restore picks between the committed positions are settled in
  design.md (D2); the requirements (FR3, NFR-R2) only fix its lifetime and
  its ordering against the record it delimits.
