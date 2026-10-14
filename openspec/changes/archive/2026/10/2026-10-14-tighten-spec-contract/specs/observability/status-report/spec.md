# Spec Delta: observability/status-report

## MODIFIED Requirements

### Requirement: Single report model behind every render
`StatusReport` SHALL be built by a pure function of `(TaskContext, TaskState)` plus the outcome and last escalation recorded beside them; the human-readable text and the JSON document SHALL both be renders of that one model. Every input is read from the persisted task state — no field is live-only. Consumers SHALL never parse logs or console output to obtain status.
**Implements**: FR11 of add-manual-run; FR6 of make-run-headless

#### Scenario: Text and JSON agree
- **WHEN** `gnomish status <task>` and `gnomish status <task> --json` read the same branch tip
- **THEN** both outputs derive from the same `StatusReport` built from that tip

### Requirement: JSON contract v1, state-derived
The JSON document SHALL carry `"version": 1` and use camelCase names, ISO-8601 UTC timestamps, millisecond durations, and a lowerCamel `"type"` discriminator for sealed variants, with the sections `task`, `position`, `outcome`, `currentStage`, `totals`, `lastEscalation` and `lastDecision`.
**Implements**: FR11 of add-manual-run; FR6 of make-run-headless; FR10, FR12, UX1 of make-checkpoint-gate-durable
**Notes**: The withdrawn live-only fields — the `activity` section (`executing`, `verifying`, `awaitingInput`) and `currentStage.attemptLimit` — were never filled by any producer outside a test fixture, and `attemptLimit` was emitted as `0` by every production render.
**Notes**: The withdrawal, the `awaitingApproval` position and the attempt `stop` are pre-release amendments: no release has shipped contract v1, so the version stays 1.

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
- **THEN** `currentStage` carries `attemptsUsed` and `attempts`, each with `round`, `result` (`passed` | `qualityFailure` | `cannotVerify` | `decisionNeeded`), `stop` (`none` | `decisionNeeded(question, options)` | `cannotVerify(check, reason, details)`), `startedAt`, checks with ref, verdict, findings and duration, `denials` with the finding shape, the executor `usage`, and `judgeUsage` with per-vote token maps

#### Scenario: Totals carry the task's executor usage
- **WHEN** any status document is rendered
- **THEN** `totals` carries the cumulative executor usage for the whole task, and judge tokens stay per attempt in `judgeUsage`

#### Scenario: The last escalation and the last decision
- **WHEN** `status` reads a tip that recorded an escalation and a decision
- **THEN** `lastEscalation` is one of the five report kinds, carrying the question and options for `decisionNeeded` and `denials` for `cannotExecute`, and `lastDecision` carries text, author, stage and time; each is null when none was recorded

#### Scenario: A usage object carries wall time, tools and tokens by model
- **WHEN** a usage object is rendered
- **THEN** it carries `wallMillis`, `byTool` and `tokensByModel` — a map from resolved model id to an object with `input`, `output`, `cacheCreation` and `cacheRead` — and an empty map means unreported

#### Scenario: Findings are carried in full
- **WHEN** a check finding longer than the text render shows is rendered to JSON
- **THEN** the document carries the finding in full; truncation is a text-render concern

### Requirement: Attempt denials in the report
Each attempt in the JSON document SHALL carry a `denials` array of finding objects (same shape as check findings: `message`, `location`, `details`), holding the egress denials recorded during that attempt's round. Denials SHALL NOT influence the attempt's `result` or any other derived field.
**Implements**: FR4, NFR-O1, NFR-S1, UX1, UX2 of fix-denial-report-attachment

#### Scenario: Passing attempt shows its denial
- **WHEN** a reviewer reads `status.json` for a task whose passing attempt recorded a denied egress request
- **THEN** that attempt carries `"result": "passed"` and a `denials` entry naming the denied host, path, and method

#### Scenario: Quiet task reports no noise
- **WHEN** a task ran with zero guard denials
- **THEN** every attempt's `denials` array is empty

#### Scenario: An attempt without the denials field reads as empty
- **WHEN** a consumer reads an older document whose attempts carry no `denials` field
- **THEN** it reads each attempt's denials as empty — the field is additive under contract v1

#### Scenario: An attempt denial carries no request body
- **WHEN** an attempt recorded a denied egress request
- **THEN** its `denials` entry carries only structured metadata, never the request body

#### Scenario: The text render shows attempt denials
- **WHEN** the text render shows an attempt that recorded denials
- **THEN** the denials appear alongside the attempt's findings

### Requirement: Escalation denials in the report
A `cannotExecute` escalation in the JSON document SHALL carry a `denials` array of finding objects — same shape as check findings and attempt denials — holding the egress denials of the round that could not execute. It SHALL NOT influence the outcome kind, `attemptsUsed`, the attempt history, or any other derived field.
**Implements**: FR1, FR2, NFR-O1, NFR-S1, UX1, UX2 of fix-denial-attribution-durability
**Notes**: The round left no attempt behind, so the escalation is that round's only place in the report.

#### Scenario: A hung round's blocked exfiltration is visible
- **WHEN** a reviewer reads `status.json` for a task parked after a round was killed on its round timeout having attempted a denied egress request
- **THEN** `lastEscalation` is of kind `cannotExecute` and carries a `denials` entry naming the denied host, path, and method, while `attemptsUsed` and `attempts` are unchanged

#### Scenario: Escalations without denials stay quiet
- **WHEN** a task is parked with a `cannotExecute` escalation and the failed round recorded no denial
- **THEN** the escalation's `denials` array is empty and the text render shows no denial line

#### Scenario: State and live renders agree
- **WHEN** the same task history is rendered from live events and from the persisted files
- **THEN** both carry the same escalation denials, per the reference equivalence contract

#### Scenario: An escalation without the denials field reads as empty
- **WHEN** a consumer reads a document written before the escalation `denials` field was added
- **THEN** it reads the `cannotExecute` escalation's denials as empty — the field is additive under contract v1

#### Scenario: An escalation denial carries no request body
- **WHEN** a `cannotExecute` escalation recorded a denied egress request
- **THEN** its `denials` entry carries only structured metadata, never the request body

#### Scenario: The text render lists escalation denials beside the reason
- **WHEN** the text render shows a `cannotExecute` escalation that recorded denials
- **THEN** the denials are listed beside the escalation reason, through the same findings funnel the attempt denials pass

### Requirement: Optional usage
Token and tool-aggregate fields SHALL be optional everywhere in the contract: `tokensByModel` maps may be empty (unreported — never fabricated zero entries) and `byTool` may be empty; a run executed entirely by a human reports empty maps and empty aggregates while remaining valid. Wall-clock fields are always present.
**Implements**: NFR-C1 of add-manual-run; FR9, NFR-C1 of add-agent-executor

#### Scenario: Human-only run validates
- **WHEN** a manual run with zero token usage renders JSON
- **THEN** the document is contract-valid with empty `tokensByModel` maps and empty `byTool`

### Requirement: Reference anchor and versioning policy
The contract SHALL be anchored by two reference documents in test resources, and serializing the deterministic samples (built with an injected clock) SHALL be byte-identical to each: `status-report-v1.reference.json`, one whole canonical document that pins the envelope and every section of a mid-run report; and `status-report-v1.escalations.reference.jsonl`, one compact line per `lastEscalation` kind that pins each sealed variant's own serialized form.
**Implements**: FR11 of add-manual-run; FR5 of add-agent-executor; FR2 of fix-denial-attribution-durability
**Notes**: Two documents rather than one because the canonical document's `lastEscalation` slot holds exactly one kind: a variant absent from it has nowhere else to be pinned byte-exactly, and replacing that kind would move the pin rather than add one.

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

#### Scenario: An additive field keeps the version
- **WHEN** a field is added to the contract
- **THEN** the document's `version` is unchanged

#### Scenario: A breaking change to a released contract bumps the version
- **WHEN** a field of a released contract version is renamed, removed, or changes meaning
- **THEN** the document's `version` is bumped; only a contract whose only consumer ships in this repository and which no archived change has published may instead be amended in place, with its reference file regenerated

#### Scenario: Future consumers verify against the same reference files
- **WHEN** a future consumer of the contract — the external `gnomish status` of the git-workflow change — is built
- **THEN** it verifies against the same reference files
