# Delta spec: status-report

## ADDED Requirements

### Requirement: Single report model behind every render
`StatusReport` SHALL be built by a pure function of `(TaskContext, TaskState, live activity)`; the human-readable text and the JSON document SHALL both be renders of that one model. Consumers SHALL never parse logs or console output to obtain status.
<!-- implements FR11 of add-manual-run -->

#### Scenario: Text and JSON agree
- **WHEN** `status` and `status --json` are issued at the same prompt
- **THEN** both outputs derive from the same `StatusReport` instance

### Requirement: JSON contract v1
The JSON document SHALL carry `"version": 1` and use camelCase names, ISO-8601 UTC timestamps, millisecond durations, and a lowerCamel `"type"` discriminator for sealed variants. Sections: `task` (id, title), `position` (`atStage(stage)` | `pipelineEnd`), `activity` (live-only, nullable: `executing` | `verifying(checkRef)` | `awaitingInput(prompt)`; every variant carries `since`), `outcome` (nullable mid-run: `completed` | `paused(passedStage)` | `escalated(report)` | `aborted(failedAt, cause)`), `currentStage` (nullable: null at `pipelineEnd`, where the attempt history has been reset by advancement; otherwise attemptsUsed, attemptLimit, attempts with `round`, `result` = `passed` | `qualityFailure` | `cannotVerify` | `decisionNeeded`, `startedAt`, checks with ref/verdict/findings/duration, executor `usage`, and `judgeUsage` with per-vote token counts), `totals` (cumulative executor usage for the whole task; judge tokens stay per-attempt in `judgeUsage`), `lastEscalation` (nullable; the five report kinds, including question and options for `decisionNeeded`), `lastDecision` (nullable; text, author, stage, time). Findings SHALL be carried in full — truncation is a text-render concern.
<!-- implements FR11 of add-manual-run -->

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
        "usage": { "wallMillis": 183000, "tokensIn": null, "tokensOut": null, "byTool": [] },
        "judgeUsage": { "perVote": [] } } ]
  },
  "totals": { "wallMillis": 232000, "tokensIn": null, "tokensOut": null, "byTool": [] },
  "lastEscalation": { "type": "decisionNeeded", "stage": "plan", "at": "2026-07-16T14:20:44Z",
                      "question": "Refactor the retry helper or patch in place?",
                      "options": ["refactor", "patch"] },
  "lastDecision":   { "text": "patch in place", "author": "operator", "stage": "plan",
                      "at": "2026-07-16T14:21:30Z" }
}
```

### Requirement: Fields partitioned by derivability
Every field SHALL be classified as state-derivable (computable from `TaskContext` + `TaskState` alone — required) or live-only (`activity`, pending prompt — nullable). A consumer building the report from a persisted state file SHALL produce a document equal to the live, event-built report at any attempt boundary, where no live activity exists.
<!-- implements FR11 of add-manual-run -->

#### Scenario: Attempt boundary equivalence
- **WHEN** a report is built from events and another from the same task's context and state at an attempt boundary
- **THEN** the two reports are equal

### Requirement: Optional usage
Token and tool-aggregate fields SHALL be optional everywhere in the contract; a run executed entirely by a human reports absent tokens and empty aggregates while remaining valid. Wall-clock fields are always present.
<!-- implements NFR-C1 of add-manual-run -->

#### Scenario: Human-only run validates
- **WHEN** a manual run with zero token usage renders JSON
- **THEN** the document is contract-valid with null token fields and empty `byTool`

### Requirement: Reference anchor and versioning policy
The contract SHALL be anchored by `status-report-v1.reference.json` in test resources: serializing the reference `StatusReport` (built with an injected clock and a deterministic sample) SHALL be byte-identical to that file. Additive fields SHALL NOT bump `version`; renaming, removal, or semantic change SHALL. Future consumers (the external `gnomish status` of the git-workflow change) SHALL verify against the same reference file.
<!-- implements FR11 of add-manual-run -->

#### Scenario: Contract drift caught
- **WHEN** a field is renamed in the serializer
- **THEN** the reference comparison fails showing the exact JSON diff
