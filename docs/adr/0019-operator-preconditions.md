# ADR 0019: Operator Preconditions Are Assessed Before the First Claim

Status: accepted (2026-10-25, introduced by `add-sandbox-base-image`; its design D17–D22 took
these decisions, recorded here because a change's `design.md` archives with the change and
governs nothing afterwards)

## Context

An **operator precondition** is something the factory needs from the operator's setup and
cannot fix itself: the factory clone on the reftable ref backend, a reachable container runtime
for a boxed pipeline, a sandbox image carrying this factory's image contract, and an image whose
git meets the factory's git floor. Until `add-sandbox-base-image` each was met per task, after
the claim. A `serve` slot claimed a task, met a `files` clone, and crashed uncaught; the release
was a no-op on GitHub, the reaper returned the task after the claim TTL, and the feed claimed it
again — a loop that spent claims and wrote no ledger line, while the operator learned the
precondition from a slot's log (the 2026-10-23 incident).

## Decision

1. **Assessed once, before the first claim.** `serve` runs every operator precondition check a
   slot would meet, in the slot's order, after its tracker section is required and before the
   tracker is touched. The first refusal ends the process as a startup failure, with the check's
   own message and fix. The checks are the per-task ones, reached through the same ports — never
   a second spelling.
2. **Met after the claim, it is a typed result.** A claimed run that meets a refusal ends on
   `TakeResult.OperatorPreconditionUnmet`, which every sink reads; the claim is released plain,
   with no abort marker, backoff or fuse charge; `take` and `run` exit 8. One classifier,
   `OperatorPreconditionRefusals`, decides what counts. On every container path the first box is
   made live before the engine runs, so a box refusal reaches that classifier rather than the
   round's catch-all.
3. **Under a running daemon, it is a daemon condition.** What can change while the daemon runs
   — the clone, the runtime, the image — opens one condition per precondition beside the remote
   outage gate, with one owner, `DaemonConditions`. The feed reads one gate over all of them
   before every claim; a held feed reads nothing from the tracker and waits. A condition's probe
   is its own startup check; only a passing probe closes it. Only a typed refusal opens one: a
   failure nothing classifies stays the task's crash, with its abort accounting.
4. **One failure, one log.** The checks throw without logging; the layer that decides writes the
   one line — the startup, the command, or the condition's opening WARN with its own code.

## Consequences

- An operator precondition never costs more than one claim per free slot per episode, and
  usually none: the startup refuses it before the first.
- A precondition the operator fixes is noticed by the next probe, with no daemon restart.
- `add-doctor-command` builds its non-refusing assessment on the same checks; `add-claim-return`
  replaces the plain release with the fenced immediate return in the one release owner.

## Alternatives rejected

- **No startup assessment, conditions only** — every daemon start pays a claim per free slot
  before the condition opens, and the operator learns the precondition from a slot's log.
- **Opening a condition on any slot failure** — a bug would hold the feed off the tracker with
  no abort recorded; only a positive, typed signal is an operator precondition.
- **A second gate class beside the remote outage gate** — two state machines, two schedules and
  two log vocabularies for one behaviour.
