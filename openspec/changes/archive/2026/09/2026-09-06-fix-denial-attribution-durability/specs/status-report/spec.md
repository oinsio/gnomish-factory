# status-report — delta

## MODIFIED Requirements

### Requirement: JSON contract v1
The JSON document SHALL carry `"version": 1` and use camelCase names, ISO-8601 UTC timestamps, millisecond durations, and a lowerCamel `"type"` discriminator for sealed variants. Sections: `task` (id, title), `position` (`atStage(stage)` | `pipelineEnd`), `activity` (live-only, nullable: `executing` | `verifying(checkRef)` | `awaitingInput(prompt)`; every variant carries `since`; `executing` additionally carries nullable live executor detail — `currentTool`, `toolCalls`), `outcome` (nullable mid-run: `completed` | `paused(passedStage)` | `escalated(report)` | `aborted(failedAt, cause)`), `currentStage` (nullable: null at `pipelineEnd`, where the attempt history has been reset by advancement; otherwise attemptsUsed, attemptLimit, attempts with `round`, `result` = `passed` | `qualityFailure` | `cannotVerify` | `decisionNeeded`, `startedAt`, checks with ref/verdict/findings/duration, `denials` with the finding shape, executor `usage`, and `judgeUsage` with per-vote token maps), `totals` (cumulative executor usage for the whole task; judge tokens stay per-attempt in `judgeUsage`), `lastEscalation` (nullable; the five report kinds, including question and options for `decisionNeeded`, and `denials` for `cannotExecute`), `lastDecision` (nullable; text, author, stage, time). Usage objects SHALL carry `wallMillis`, `byTool`, and `tokensByModel` — a map from resolved model id to an object with `input`, `output`, `cacheCreation`, `cacheRead`; an empty map means unreported. Findings SHALL be carried in full — truncation is a text-render concern.
<!-- implements FR11 of add-manual-run -->
<!-- implements FR5, FR7, FR9 of add-agent-executor -->
<!-- implements FR4 of fix-denial-report-attachment -->
<!-- implements FR2 of fix-denial-attribution-durability -->

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

### Requirement: Reference anchor and versioning policy
The contract SHALL be anchored by two reference documents in test resources, and serializing the deterministic samples (built with an injected clock) SHALL be byte-identical to each: `status-report-v1.reference.json`, one whole canonical document that pins the envelope and every section of a mid-run report; and `status-report-v1.escalations.reference.jsonl`, one compact line per `lastEscalation` kind that pins each sealed variant's own serialized form. Two documents rather than one because the canonical document's `lastEscalation` slot holds exactly one kind: a variant absent from it has nowhere else to be pinned byte-exactly, and replacing the kind the canonical document carries would move the pin rather than add one. The corpus SHALL be complete — a check SHALL fail when the committed line count does not match the number of `EscalationReport` variants — so a newly added kind cannot ship unpinned. Additive fields SHALL NOT bump `version`; renaming, removal, or semantic change SHALL — once a version has been released; a contract whose only consumer ships in this repository and which no archived change has published MAY be amended in place with its reference file regenerated. Future consumers (the external `gnomish status` of the git-workflow change) SHALL verify against the same reference files.
<!-- implements FR11 of add-manual-run -->
<!-- implements FR5 of add-agent-executor -->
<!-- implements FR2 of fix-denial-attribution-durability -->

#### Scenario: Contract drift caught
- **WHEN** a field is renamed in the serializer
- **THEN** the reference comparison fails showing the exact JSON diff

#### Scenario: Pre-release amendment stays v1
- **WHEN** this change reshapes usage fields and regenerates the reference file
- **THEN** the document still carries `"version": 1` and the reference test passes against the regenerated file

#### Scenario: Every escalation kind is pinned byte-exactly
- **WHEN** the escalation corpus is serialized
- **THEN** each of the five `EscalationReport` kinds has its own committed line, and the `cannotExecute` line carries a populated `denials` array

#### Scenario: A new escalation kind cannot ship unpinned
- **WHEN** a sixth `EscalationReport` variant is added without a corpus line
- **THEN** the completeness check fails, naming the count mismatch

## ADDED Requirements

### Requirement: Escalation denials in the report
A `cannotExecute` escalation in the JSON document SHALL carry a `denials` array of finding objects — same shape as check findings and attempt denials — holding the egress denials of the round that could not execute. The round left no attempt behind, so this is that round's only place in the report. The field is additive under contract v1: present as an empty array when there were none, and read as empty in documents written before this addition. It SHALL NOT influence the outcome kind, `attemptsUsed`, the attempt history, or any other derived field, and SHALL carry only structured metadata — never request bodies. The text render SHALL list these denials beside the escalation reason, through the same findings funnel the attempt denials pass.
<!-- implements FR1, FR2, NFR-O1, NFR-S1, UX1, UX2 of fix-denial-attribution-durability -->

#### Scenario: A hung round's blocked exfiltration is visible
- **WHEN** a reviewer reads `status.json` for a task parked after a round was killed on its round timeout having attempted a denied egress request
- **THEN** `lastEscalation` is of kind `cannotExecute` and carries a `denials` entry naming the denied host, path, and method, while `attemptsUsed` and `attempts` are unchanged

#### Scenario: Escalations without denials stay quiet
- **WHEN** a task is parked with a `cannotExecute` escalation and the failed round recorded no denial
- **THEN** the escalation's `denials` array is empty and the text render shows no denial line

#### Scenario: State and live renders agree
- **WHEN** the same task history is rendered from live events and from the persisted files
- **THEN** both carry the same escalation denials, per the reference equivalence contract
