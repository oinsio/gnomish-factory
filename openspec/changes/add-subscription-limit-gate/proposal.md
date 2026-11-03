# Proposal: add-subscription-limit-gate

> **Status: placeholder — queued, not designed.** This proposal records the problem and a
> direction so the change holds its place in the queue. The design (`/architect`, then
> `/opsx:propose-checked` or `/opsx:update`) finalises every item below; requirement IDs marked
> *provisional* may be renumbered, reworded or dropped then. No `design.md`, delta specs or task
> plan exist yet.

## Why

A gnome, judge or arbiter round that hits the agent subscription's usage limit — a limit that
resets after a known time, typically a few hours — is not recognised today. Facts verified in
code on 2026-11-02:

- No adapter code recognises a usage or rate limit: no match for "usage limit", "rate limit" or
  "resets" (in that sense) in the main sources of `adapters/agent`, `application` or `domain`.
- A CLI round that ends without its result becomes `failed/infrastructure`
  (`AgentRoundFailure` in `adapters/agent`), which `ExecutorOutcomeClassifier` (`domain/engine`)
  turns into an escalation (`CannotExecute`): the task parks until a human returns it.
- The contract's `failed/limit` class means the stage's own budget today (`BUDGET_EXHAUSTED`,
  awaiting a human), not an account limit.
- *Premise to verify at design:* a CLI result line with an `error_*` subtype may pass as an
  ordinary round and burn an attempt on failing checks.

Operator scenario: the limit resets in about two hours, the operator is away for eight. The
task — and, under `serve`, every next task the daemon claims — parks and waits for the human,
although the work could have resumed on its own after the reset.

## Direction (not decided)

The limit is a property of the account, shared by every round of this factory instance. By
decision 5 of `docs/adr/0019-operator-preconditions.md` (the level of a precondition follows the
owner of its truth) it is therefore a **daemon condition**, like the remote outage
(`DaemonConditionKind.REMOTE`, `RemoteOutageGates`), not a task park. The direction to evaluate:

- recognise the limit and its reset time in the agent adapter, for stage rounds, judge votes and
  arbiter rounds;
- burn no attempt and do not escalate;
- close the daemon's claim gate until the reset (or a probe passes), with one operator line;
- resume the interrupted task from its stage afterwards, without a human.

This is a direction, open for the design; its alternatives are weighed there.

## What Changes

To be decided at design. Likely surfaces: the agent adapter's round-result reading, the outcome
classification, the daemon conditions of `serve`, and the resume path of an interrupted round.

## Goals

- G1 (provisional): no task waits for a human solely because the agent subscription hit its
  usage limit.
- G2 (provisional): a limit hit under `serve` stops further claims until the reset instead of
  parking each next claimed task.

## Non-Goals

- NG1 (provisional): limits of providers other than the agent subscription, unless the design
  widens the scope.
- NG2: predicting limits or budgeting usage in advance — the territory of `add-usage-budgets`
  (named as the pickup of NG12 of `define-executor-contract`; no change folder exists yet).

## Users & Scenarios

- U1: an unattended operator whose `serve` daemon hits the subscription limit mid-round; on
  return the work has resumed after the reset, with one log line explaining the pause.
- U2: an operator running `take` or `run` who hits the limit; what the command reports and
  whether it waits is open (Q5).

## Requirements

All provisional, finalised at design.

- FR1: a round that ends on a subscription usage limit is recognised as such, with the reset
  time when the agent reports one — for stage rounds, judge votes and arbiter rounds.
- FR2: such a round burns no stage attempt and raises no escalation.
- FR3: under `serve`, the limit opens a daemon condition that holds claims until the reset or a
  passing probe.
- FR4: the interrupted task resumes from its stage after the condition closes, with no human.
- NFR-R1: resume after the limit is idempotent; a kill while the condition is open leaves a
  state the next pickup can name (`crash-consistency.md`).
- NFR-O1: the condition's opening and closing each write one operator line naming the reset
  time.

## Operator Experience Criteria

- UX1 (provisional): the operator sees one line when the gate closes (with the reset time) and
  one when it reopens; no tracker park, no escalation comment.

## Success Metrics

To be set at design (e.g. zero escalations caused by a subscription limit in a scripted
fake-agent scenario that reports one).

## Open Questions

- Q1: what `claude -p` reports in `stream-json` on a usage limit — result subtype, text, reset
  time (external research).
- Q2: how a task interrupted mid-round resumes; relation to the `transform` pending/resume of
  `extract-agent-round-runner` (design D11).
- Q3: judge vote behaviour — today a Resilience4j retry, then cannot-verify; how a limit is told
  apart from an outage of the judge.
- Q4: **NG15 of `define-executor-contract` must be addressed.** Its pickup condition is met by
  the first change that returns a parked task to work without a human for any cause, including
  a gate that resumes work when its cause clears. This change either takes describe failures
  into the same mechanism or re-defers them explicitly in its Non-Goals, with the condition
  restated.
- Q5: host versus container modes, and `take`/`run` versus `serve`: who waits, and whether a
  single-shot command waits or exits.
- Q6: whether the gate is closed for the reported reset time, by a probe, or both.

## Sequencing

Queued, not designed. Dependencies to confirm at design; likely after
`extract-agent-round-runner` (Q2).
