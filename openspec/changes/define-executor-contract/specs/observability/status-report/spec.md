# observability/status-report — delta for define-executor-contract

## MODIFIED Requirements

Layered on `JSON contract v1, state-derived` as modified by `tighten-spec-contract` (sequenced
before this change): that delta cut the body to the section list, moved the withdrawal into notes
and added the scenarios "The task and position sections" through "Findings are carried in full";
this text replaces the attempt usage, `judgeUsage` and totals wording with the attempt identity,
the participant entries and the usage snapshot (in the canonical example and in the scenarios
"An attempt carries its round record", "Totals carry the task's executor usage" and "A usage
object carries wall time, tools and tokens by model"), and adds the scenarios "Judge votes are
participants" and "Text render names the snapshot's position". Layered likewise on `Optional
usage` as modified by `tighten-spec-contract`: that delta gives the block its `**Implements**:`
line; this text replaces the token-map wording with participant entries and adds the scenario
"Unpriced entry validates". All must survive the later sync.

### Requirement: JSON contract v1, state-derived
The JSON document SHALL carry `"version": 1` and use camelCase names, ISO-8601 UTC timestamps, millisecond durations, and a lowerCamel `"type"` discriminator for sealed variants, with the sections `task`, `position`, `outcome`, `currentStage`, `totals`, `lastEscalation` and `lastDecision`.
**Implements**: FR11 of add-manual-run; FR6 of make-run-headless; FR10, FR12, UX1 of make-checkpoint-gate-durable; FR15, FR16, FR17, UX4 of define-executor-contract
**Notes**: The withdrawn live-only fields — the `activity` section (`executing`, `verifying`, `awaitingInput`) and `currentStage.attemptLimit` — were never filled by any producer outside a test fixture, and `attemptLimit` was emitted as `0` by every production render.
**Notes**: The withdrawal, the `awaitingApproval` position, the attempt `stop`, the attempt identity, the participant list and the snapshot are pre-release amendments: no release has shipped contract v1, so the version stays 1.

#### Scenario: Canonical document
- **WHEN** a task's branch records one failed round of `implement` after an earlier decision escalation
- **THEN** the JSON matches the shape of the canonical example, pinned byte-exactly by `status-report-v1.reference.json` in test resources (the "Reference anchor and versioning policy" requirement):

```json
{
  "version": 1,
  "task":     { "id": "manual-20260716-143502-x7", "title": "Fix flaky OrderServiceSpec" },
  "position": { "type": "atStage", "stage": "implement" },
  "outcome":  null,
  "currentStage": {
    "attemptsUsed": 1,
    "attempts": [
      { "taskId": "manual-20260716-143502-x7",
        "stage": "implement",
        "roundToken": "9f1c2b7a4e6d8c0b1a2f3e4d5c6b7a8f9e0d1c2b",
        "round": 1,
        "result": "qualityFailure",
        "stop": { "type": "none" },
        "startedAt": "2026-07-16T14:35:10Z",
        "checks": [
          { "ref": "builtin:files_exist", "verdict": "pass", "findings": [], "durationMillis": 3 },
          { "ref": "command:./gradlew test", "verdict": "fail",
            "findings": [ { "message": "command exited with 1", "location": null,
                            "details": "…output tail…" } ],
            "durationMillis": 41250 } ],
        "denials": [],
        "usage": [
          { "role": "transform", "executor": "agent-cli", "provenance": "reported",
            "wallMillis": 183000,
            "byTool": [ { "name": "Edit", "calls": 4, "totalMillis": 2100 } ],
            "byModel": {
              "claude-sonnet-5": { "input": 1200, "output": 5400,
                                   "cacheCreation": 30000, "cacheRead": 410000,
                                   "costUsd": 0.1607 } } } ] } ]
  },
  "totals": {
    "byRole": {
      "transform": {
        "claude-sonnet-5": { "input": 1450, "output": 6100,
                             "cacheCreation": 30000, "cacheRead": 512000 } } },
    "cost": { "priced": 0.1922, "unpricedTokens": {}, "complete": true },
    "rounds": 2,
    "through": "9f1c2b7a4e6d8c0b1a2f3e4d5c6b7a8f9e0d1c2b"
  },
  "lastEscalation": { "type": "decisionNeeded", "stage": "plan", "at": "2026-07-16T14:20:44Z",
                      "question": "Refactor the retry helper or patch in place?",
                      "options": ["refactor", "patch"] },
  "lastDecision":   { "text": "patch in place", "author": "operator", "stage": "plan",
                      "at": "2026-07-16T14:21:30Z" }
}
```

#### Scenario: Withdrawn live fields are absent
- **WHEN** any status document is rendered
- **THEN** it carries no `activity` key and its `currentStage` carries no `attemptLimit` key

#### Scenario: A decision round carries its stop
- **WHEN** `status` reads a tip whose current stage's last round is recorded as `decisionNeeded`
- **THEN** that attempt carries `"stop": { "type": "decisionNeeded", "question": …, "options": […] }` with the question and options the round recorded

#### Scenario: A gate renders as awaiting approval
- **WHEN** `status` reads a tip whose position is `awaitingApproval("release")`
- **THEN** the JSON `position` is `{ "type": "awaitingApproval", "stage": "release" }`, `currentStage` describes `release` with its passing round last, and the text render says "awaiting approval after 'release'"

#### Scenario: A consumed park is not shown
- **WHEN** `status` reads a tip whose last park was consumed by an approval or a resumed write and whose process then died mid-stage
- **THEN** `outcome` is null and the position is the one the continuation advanced to

#### Scenario: The task and position sections
- **WHEN** any status document is rendered
- **THEN** `task` carries the id and title, and `position` is one of `atStage(stage)`, `awaitingApproval(stage)` or `pipelineEnd`

#### Scenario: The outcome is null mid-run and after a consumed park
- **WHEN** `status` reads a tip mid-run, or one whose last park was consumed by a decision, an approval or a resumed write
- **THEN** `outcome` is null; otherwise it is one of `completed`, `paused(passedStage)`, `escalated(report)` or `aborted(failedAt, cause)`

#### Scenario: No current stage at the pipeline end
- **WHEN** `status` reads a tip whose position is `pipelineEnd`
- **THEN** `currentStage` is null, because advancement has reset the attempt history

#### Scenario: An attempt carries its round record
- **WHEN** `status` reads a tip whose current stage recorded rounds
- **THEN** `currentStage` carries `attemptsUsed` and `attempts`, each with `taskId`, `stage`, `roundToken`, `round`, `result` (`passed` | `qualityFailure` | `cannotVerify` | `decisionNeeded`), `stop` (`none` | `decisionNeeded(question, options)` | `cannotVerify(check, reason, details)`), `startedAt`, checks with ref, verdict, findings and duration, `denials` with the finding shape, and `usage` — a list of participant entries

#### Scenario: Totals carry the task's executor usage
- **WHEN** any status document is rendered
- **THEN** `totals` is the usage snapshot for the whole task: `byRole` → resolved model id → the four counts, `cost` with `priced`, `unpricedTokens` and `complete`, `rounds` and `through`

#### Scenario: The last escalation and the last decision
- **WHEN** `status` reads a tip that recorded an escalation and a decision
- **THEN** `lastEscalation` is one of the five report kinds, carrying the question and options for `decisionNeeded` and `denials` for `cannotExecute`, and `lastDecision` carries text, author, stage and time; each is null when none was recorded

#### Scenario: A usage object carries wall time, tools and tokens by model
- **WHEN** a participant entry is rendered
- **THEN** it carries `role` (`transform` | `verdict` | `decision` | `effect`), `executor`, `vote` (for `verdict`, else absent), `provenance` (`reported` | `absent`), optional `wallMillis` and `byTool`, and `byModel` — a map from resolved model id to an object with `input`, `output`, `cacheCreation`, `cacheRead` and optional `costUsd` — and an `absent` entry carries an empty map
- **AND** no `judgeUsage` or role-named usage field exists

#### Scenario: Findings are carried in full
- **WHEN** a check finding longer than the text render shows is rendered to JSON
- **THEN** the document carries the finding in full; truncation is a text-render concern

#### Scenario: Judge votes are participants
- **WHEN** a round ran a judge check with two votes
- **THEN** the attempt's `usage` carries two `verdict` entries with `vote` 0 and 1, each with its own `byModel`

#### Scenario: Text render names the snapshot's position
- **WHEN** `gnomish status <task>` reads a tip whose snapshot is incomplete
- **THEN** the text shows the rounds folded and "cost incomplete: N tokens unpriced"

### Requirement: Optional usage
Cost and tool-aggregate fields SHALL be optional everywhere in the contract: a participant entry's `costUsd` may be absent (unpriced — never a fabricated zero), `byTool` may be empty, and an entry with provenance `absent` carries an empty `byModel` map. Token counts in a present model entry and in the snapshot SHALL never be null. Wall-clock fields are always present on the attempt; `wallMillis` on an entry is optional.
**Implements**: NFR-C1 of add-manual-run; FR9, NFR-C1 of add-agent-executor; NFR-O3 of define-executor-contract

#### Scenario: Human-only run validates
- **WHEN** a manual run with zero token usage renders JSON
- **THEN** the document is contract-valid with one `absent` entry per round, empty `byModel` maps and a snapshot with `rounds` counted and no model entries

#### Scenario: Unpriced entry validates
- **WHEN** a judge vote reports tokens without `costUsd`
- **THEN** the document is contract-valid, the entry carries no `costUsd` key and the snapshot's `complete` is false
