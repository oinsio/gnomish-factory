# Spec Delta: status-report

## MODIFIED Requirements

### Requirement: JSON contract v1
Layered on "JSON contract v1" as modified by `make-run-headless` (sequenced before this change). The JSON document SHALL carry `"version": 1` and use camelCase names, ISO-8601 UTC timestamps, millisecond durations, and a lowerCamel `"type"` discriminator for sealed variants. Sections: `task` (id, title), `position` (`atStage(stage)` | `awaitingApproval(stage)` | `pipelineEnd`), `activity` (live-only, nullable: `executing` | `verifying(checkRef)`; every variant carries `since`; `executing` additionally carries nullable live executor detail — `currentTool`, `toolCalls`), `outcome` (nullable mid-run and null for any task whose last park was consumed by a decision, an approval or a resumed write: `completed` | `paused(passedStage)` | `escalated(report)` | `aborted(failedAt, cause)`), `currentStage` (nullable: null at `pipelineEnd`; at `awaitingApproval` it describes the stage that passed, with its passing round last; otherwise attemptsUsed, attemptLimit, attempts with `round`, `result` = `passed` | `qualityFailure` | `cannotVerify` | `decisionNeeded`, `stop` (`none` | `decisionNeeded(question, options)` | `cannotVerify(check, reason, details)`), `startedAt`, checks with ref/verdict/findings/duration, `denials` with the finding shape, executor `usage`, and `judgeUsage` with per-vote token maps), `totals` (cumulative executor usage for the whole task; judge tokens stay per-attempt in `judgeUsage`), `lastEscalation` (nullable; the five report kinds, including question and options for `decisionNeeded`, and `denials` for `cannotExecute`), `lastDecision` (nullable; text, author, stage, time). Usage objects SHALL carry `wallMillis`, `byTool`, and `tokensByModel` — a map from resolved model id to an object with `input`, `output`, `cacheCreation`, `cacheRead`; an empty map means unreported. Findings SHALL be carried in full — truncation is a text-render concern. The `awaitingApproval` position and the attempt `stop` are pre-release amendments; the version stays 1. The text render SHALL show a gate as "awaiting approval after '<stage>'".
<!-- implements FR10, FR12, UX1 of make-checkpoint-gate-durable -->
<!-- implements FR6 of make-run-headless -->

#### Scenario: Canonical mid-run document
- **WHEN** a run is verifying attempt 2 after an earlier decision escalation
- **THEN** the JSON matches the shape of the canonical example, pinned byte-exactly by `status-report-v1.reference.json` in test resources (the "Reference anchor and versioning policy" requirement), whose `activity` is `verifying` and whose first attempt carries `stop: decisionNeeded` with its question and options

#### Scenario: Executing activity carries live detail
- **WHEN** a CLI executor round is mid-flight on its third tool call
- **THEN** the `activity` section reads `{ "type": "executing", "since": …, "currentTool": "Edit", "toolCalls": 3 }`

#### Scenario: Activity variants are exactly two
- **WHEN** the activity round-trip spec iterates every sealed variant
- **THEN** it finds `executing` and `verifying` only, and a document carrying `"type": "awaitingInput"` is rejected as unknown

#### Scenario: A gate renders as awaiting approval
- **WHEN** `status` reads a tip whose position is `awaitingApproval("release")`
- **THEN** the JSON `position` is `{ "type": "awaitingApproval", "stage": "release" }`, `currentStage` describes `release` with its passing round last, and the text render says "awaiting approval after 'release'"

#### Scenario: A consumed park is not shown
- **WHEN** `status` reads a tip whose last park was consumed by an approval or a resumed write and whose process then died mid-stage
- **THEN** `outcome` is null and the position is the one the continuation advanced to
