# status-report — delta

## MODIFIED Requirements

### Requirement: JSON contract v1
The JSON document SHALL carry `"version": 1` and use camelCase names, ISO-8601 UTC timestamps, millisecond durations, and a lowerCamel `"type"` discriminator for sealed variants. Sections: `task` (id, title), `position` (`atStage(stage)` | `pipelineEnd`), `activity` (live-only, nullable: `executing` | `verifying(checkRef)` | `awaitingInput(prompt)`; every variant carries `since`; `executing` additionally carries nullable live executor detail — `currentTool`, `toolCalls`), `outcome` (nullable mid-run: `completed` | `paused(passedStage)` | `escalated(report)` | `aborted(failedAt, cause)`), `currentStage` (nullable: null at `pipelineEnd`, where the attempt history has been reset by advancement; otherwise attemptsUsed, attemptLimit, attempts with `round`, `result` = `passed` | `qualityFailure` | `cannotVerify` | `decisionNeeded`, `startedAt`, checks with ref/verdict/findings/duration, `denials` with the finding shape, executor `usage`, and `judgeUsage` with per-vote token maps), `totals` (cumulative executor usage for the whole task; judge tokens stay per-attempt in `judgeUsage`), `lastEscalation` (nullable; the five report kinds, including question and options for `decisionNeeded`), `lastDecision` (nullable; text, author, stage, time). Usage objects SHALL carry `wallMillis`, `byTool`, and `tokensByModel` — a map from resolved model id to an object with `input`, `output`, `cacheCreation`, `cacheRead`; an empty map means unreported. Findings SHALL be carried in full — truncation is a text-render concern.
<!-- implements FR11 of add-manual-run -->
<!-- implements FR5, FR7, FR9 of add-agent-executor -->
<!-- implements FR4 of fix-denial-report-attachment -->

#### Scenario: Canonical mid-run document
- **WHEN** a run is verifying attempt 2 after an earlier decision escalation
- **THEN** the JSON matches the shape of the canonical example:

```json
{
  "version": 1,
  "task":     { "id": "manual-20260716-143502-x7", "title": "Fix flaky OrderServiceSpec" },
  "position": { "type": "atStage", "stage": "implement" },
  "activity": { "type": "verifying", "checkRef": "command:./gradlew test",
                "since": "2026-07-16T14:41:02Z" },
  "outcome":  null,
  "currentStage": {
    "attemptsUsed": 1,
    "attemptLimit": 3,
    "attempts": [
      { "round": 1,
        "result": "qualityFailure",
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

#### Scenario: Executing activity carries live detail
- **WHEN** a CLI executor round is mid-flight on its third tool call
- **THEN** the `activity` section reads `{ "type": "executing", "since": …, "currentTool": "Edit", "toolCalls": 3 }`

## ADDED Requirements

### Requirement: Attempt denials in the report
Each attempt in the JSON document SHALL carry a `denials` array of finding objects (same shape as check findings: `message`, `location`, `details`), holding the egress denials recorded during that attempt's round. The field is additive under contract v1: it is present as an empty array when the attempt had no denials, and consumers of older documents without the field SHALL read it as empty. Denials SHALL NOT influence the attempt's `result` or any other derived field, and SHALL carry only structured metadata — never request bodies. The text render SHALL surface an attempt's denials alongside its findings.
<!-- implements FR4, NFR-O1, NFR-S1, UX1, UX2 of fix-denial-report-attachment -->

#### Scenario: Passing attempt shows its denial
- **WHEN** a reviewer reads `status.json` for a task whose passing attempt recorded a denied egress request
- **THEN** that attempt carries `"result": "passed"` and a `denials` entry naming the denied host, path, and method

#### Scenario: Quiet task reports no noise
- **WHEN** a task ran with zero guard denials
- **THEN** every attempt's `denials` array is empty
