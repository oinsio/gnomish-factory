# Factory loop: scope notes

> Not a design — an agreed scope boundary, so the `factory loop` change starts
> from this list instead of re-litigating what belongs to it. Captured
> 2026-07-19 to 2026-07-20 while scoping `add-tracker-port`.

## What belongs to factory loop

- **Autonomous feed**: a long-running poll loop over `listReady`; empty queue →
  wait a configured interval and poll again (no inbound HTTP, so no webhooks).
  The single-task auto mode ("take the head of the queue and exit") already
  exists in `add-tracker-port` — the loop turns it into a continuous cycle.
- **Slots and parallelism limit**: a scheduler running N tasks concurrently,
  claiming the next one as a slot frees up.
- **Hold/release slot policy**: explicit, mandatory configuration; hold-polling
  (the slot watches whether a human returned the task) and release-parking.
  Consequence accepted deliberately: all slots can end up waiting on humans.
- **Explicit multi-task mode**: several ids in one run, unclaimed ones skipped
  with a report, a final summary; exit-code aggregation is a loop design
  question.
- **Heartbeat / stale-claim protocol**: new `Tracker` port operations (the
  port abstraction is designed to extend); removing a dead instance's claim;
  explicit takeover of a stuck `Working` task by id. Until heartbeat ships,
  the manual operator escape hatch (flipping state by hand) documented in
  `add-tracker-port` covers this. Implemented ahead of the rest of the loop by
  `add-claim-heartbeat`; see [ADR 0002](adr/0002-claim-lease-protocol.md).
- **Backoff filter on the feed, driven by abort facts**: a task in `Ready`
  with an unexpired backoff is invisible to the autonomous feed; backoff shape
  (exponential with a ceiling) and parameters are config. The abort facts
  (`abortCount`, `lastAbortAt`) are already exposed by the tracker port from
  `add-tracker-port` — only the core policy is new here.
- **Worktree cleanup by age**: worktrees of reverted/escalated tasks
  accumulate — clean up by age.

## Adjacent deadline

Sandboxing of command checks cannot be deferred past factory loop — autonomous
mode executes third-party commands with no human at the console.
