# Spec Delta: status-report

## MODIFIED Requirements

### Requirement: JSON contract v1, state-derived
The JSON document SHALL carry `"version": 1` and use camelCase names, ISO-8601 UTC timestamps, millisecond durations, and a lowerCamel `"type"` discriminator for sealed variants. Sections: `task` (id, title), `position` (`atStage(stage)` | `awaitingApproval(stage)` | `pipelineEnd`), `outcome` (nullable mid-run, and null for any task whose last park was consumed by a decision, an approval or a resumed write: `completed` | `paused(passedStage)` | `escalated(report)` | `aborted(failedAt, cause)`), `currentStage` (nullable: null at `pipelineEnd`, where the attempt history has been reset by advancement; at `awaitingApproval` it describes the stage that passed, with its passing round last; otherwise attemptsUsed, attempts with `round`, `result` = `passed` | `qualityFailure` | `cannotVerify` | `decisionNeeded`, `stop` (`none` | `decisionNeeded(question, options)` | `cannotVerify(check, reason, details)`), `startedAt`, checks with ref/verdict/findings/duration, `denials` with the finding shape, executor `usage`, and `judgeUsage` with per-vote token maps), `totals` (cumulative executor usage for the whole task; judge tokens stay per-attempt in `judgeUsage`), `lastEscalation` (nullable; the five report kinds, including question and options for `decisionNeeded`, and `denials` for `cannotExecute`), `lastDecision` (nullable; text, author, stage, time). Usage objects SHALL carry `wallMillis`, `byTool`, and `tokensByModel` — a map from resolved model id to an object with `input`, `output`, `cacheCreation`, `cacheRead`; an empty map means unreported. Findings SHALL be carried in full — truncation is a text-render concern. The former live-only fields — the `activity` section (`executing`, `verifying`, `awaitingInput`) and `currentStage.attemptLimit` — are withdrawn as a pre-release amendment (no release has shipped contract v1): no producer ever filled them outside a test fixture, and `attemptLimit` was emitted as `0` by every production render; the version stays 1. The `awaitingApproval` position and the attempt `stop` are likewise pre-release amendments; the version stays 1. The text render SHALL show a gate as "awaiting approval after '<stage>'".
<!-- implements FR11 of add-manual-run; FR6 of make-run-headless -->
<!-- implements FR10, FR12, UX1 of make-checkpoint-gate-durable -->

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
      { "round": 1,
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
        "usage": { "wallMillis": 183000,
                   "tokensByModel": {
                     "claude-sonnet-5": { "input": 1200, "output": 5400,
                                          "cacheCreation": 30000, "cacheRead": 410000 } },
                   "byTool": [ { "name": "Edit", "calls": 4, "totalMillis": 2100 } ] },
        "judgeUsage": { "perVote": [] } } ]
  },
  "totals": { "wallMillis": 232000,
              "tokensByModel": {
                "claude-sonnet-5": { "input": 1450, "output": 6100,
                                     "cacheCreation": 30000, "cacheRead": 512000 } },
              "byTool": [ { "name": "Edit", "calls": 4, "totalMillis": 2100 } ] },
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
