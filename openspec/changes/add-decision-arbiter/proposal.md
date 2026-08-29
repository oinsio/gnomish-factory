# Proposal: add-decision-arbiter

## Why

Today every decision fork a gnome cannot resolve parks the task for a human
(`DECISION_NEEDED` → `AwaitingHuman`), even when the project's own decision
policy answers it mechanically ("MVP phase: breaking changes allowed, notify"
vs "production: backward compatibility mandatory"). Each such park costs
hours of wall-clock time and a human context switch. An automatic decision
tier — a stronger model armed with a law-frozen decision-rules file and
read-only access to the work — resolves the routine forks and reserves the
human for the genuinely undecidable ones, raising factory autonomy without
weakening any verification gate.

## What Changes

- ADDED: an **arbiter** tier in the decision escalation chain: gnome →
  arbiter → human. Configured per stage; absent configuration keeps today's
  behavior (park to human) exactly.
- ADDED: a per-stage `decision-rules.md` control file, frozen with the rest
  of the pipeline law at invocation start.
- MODIFIED: the decision-file request format becomes structured (question,
  ≥ 2 enumerated options, why-blocked); a malformed request is quality
  feedback to the gnome, not an arbiter consult.
- MODIFIED: decision records gain author attribution and scope; a single
  application-side owner replaces today's four scattered append sites.
- ADDED: advisory ("notify") verdict flavor — work continues, a tracker
  comment informs the human, veto is a defined transition.
- ADDED: arbiter token usage accounting beside executor and judge usage.

## Capabilities

### New Capabilities

- `decision-arbiter`: the arbiter tier — configuration, law-frozen decision
  rules, consult protocol, closed verdict schema, read-only workspace
  access, caps, and fallback to human.

### Modified Capabilities

- `stage-engine`: the NeedsDecision transition consults the arbiter before
  escalating; decided verdicts continue the stage without parking; a
  per-stage decision cap bounds consults.
- `agent-executor`: the decision-file protocol gains the structured request
  schema (enumerated options) and the malformed-request quality-feedback
  arm.
- `pipeline-config`: `stage.yaml` gains the optional `decisions:` section;
  `decision-rules.md` joins the law freeze set; validation rules.
- `git-task-persistence`: decision records carry author and scope
  additively; the append flows through one owner.

## Goals

- G1: routine decision forks (answerable from the decision-rules file) are
  resolved without human involvement.
- G2: no verification gate weakens: an arbiter verdict can select among
  gnome-enumerated options, never rewrite instructions, criteria, or checks.
- G3: every decision is attributable forever: who decided (worker request /
  arbiter model / human), under which rules, in which scope.
- G4: the human keeps a veto: advisory decisions are visible in the tracker
  before the task completes, and vetoing one is a normal tracker operation.

## Non-Goals

- NG1: no multi-tier AI cascades — exactly one arbiter tier, then human
  (cascade cost curves flatten at two tiers for low-volume decisions).
- NG2: no arbiter write access of any kind — the arbiter never edits the
  working copy, the plan, or the law.
- NG3: no automatic decision *discovery* — the arbiter only answers forks
  the gnome explicitly raised via the decision file.
- NG4: no changes to the human escalation protocol itself (park, resume,
  takeover stay as specified today).

## Users & Scenarios

- U1: An operator enables the arbiter on the `implement` stage with rules
  "MVP: breaking changes allowed, must notify". The gnome hits a protocol
  fork, the arbiter picks the breaking option, posts a notify comment, and
  the task finishes without a park.
- U2: A gnome raises a fork the rules do not cover. The arbiter returns
  cannot-decide with its reasoning; the task parks for the human with both
  the gnome's question and the arbiter's analysis attached.
- U3: A human reviews a finished task, sees an advisory decision comment
  they disagree with, and reopens/supersedes it through the normal tracker
  flow.

## Requirements

### Functional

- FR1: `stage.yaml` MAY declare a `decisions:` section (arbiter model,
  settings, `rulesFile`, `maxDecisions`). Absent section = today's behavior
  unchanged.
- FR2: The referenced decision-rules file is read once at invocation start
  from the factory-owned clone into the frozen pipeline law; the gnome
  cannot affect it mid-task.
- FR3: A decision request MUST be structured: question, at least two
  concrete enumerated options, and why the gnome is blocked. A request
  failing the schema is returned to the gnome as quality feedback (no
  arbiter consult, no park, no attempt burned beyond the round's normal
  accounting).
- FR4: On a valid `DECISION_NEEDED` round with an arbiter configured and
  the cap unspent, the engine consults the arbiter exactly once for that
  request. The verdict is a closed schema: `decided(optionId, rationale,
  notify?)` — optionId MUST be one of the request's enumerated options — or
  `cannotDecide(reason)`. Any other output classifies as cannot-decide.
- FR5: A decided verdict is appended to the task's decision records (author
  = arbiter identity, scope attached) and the stage continues with the next
  round; no park, no tracker status change.
- FR6: cannot-decide, an exhausted `maxDecisions` cap, or no arbiter
  configured all park to the human as today, with the arbiter's verdict
  history (if any) included in the escalation report.
- FR7: The arbiter executes with read-only access only: the narrow-only
  read tool allowlist, and a fresh environment materialized from the
  attempt's harvested commit — never the gnome's live working environment.
- FR8: A `notify` flag on a decided verdict posts an advisory tracker
  comment naming the decision, its author, and its scope; work does not
  block. A human veto of an advisory decision is a defined transition:
  park-and-supersede through the existing escalation/resume protocol.
- FR9: Decision records carry scope (item / stage / task) and author
  additively; prompt injection of prior decisions includes only in-scope
  records; a superseding decision references the superseded one.
- FR10: One application-side owner performs every decision append
  (tracker-resume, operator dialog, arbiter); the four existing
  construction sites converge on it.

### Non-Functional Reliability

- NFR-R1: The consult follows intent → effect → receipt: the verdict is
  durable on the task branch before the next round starts; a kill between
  consult and commit re-consults on recovery, and a committed verdict makes
  re-consult a no-op. New kill windows join the kill-point matrix per
  `.claude/rules/crash-consistency.md`.
- NFR-R2: An arbiter infrastructure failure (timeout, 5xx, process error)
  is an infrastructure failure of the consult — retried, never a burned
  attempt, and falls back to the human park if persistent.

### Non-Functional Security

- NFR-S1: A decision can never weaken verification: acceptance criteria,
  instructions, and checks stay law-frozen; the arbiter verdict reaches
  later prompts as data (same channel as human decisions today).
- NFR-S2: All working-copy content in the arbiter prompt is delimited as
  untrusted data; the decision-rules file is the only instruction source
  beside the engine's own template. The notify text is display data, never
  an engine directive.

### Non-Functional Observability

- NFR-O1: Every consult leaves a structured log line (task, stage, request,
  verdict kind, author) and the verdict lands in the escalation/status
  surfaces; arbiter tokens are reported per round beside executor and judge
  usage.

### Non-Functional Cost

- NFR-C1: `maxDecisions` is an engine wall, not a prompt suggestion; the
  default is small (single digits). Exactly one consult per request — a
  cascade never re-pays a tier.

## Operator Experience Criteria

- UX1: The operator can read, in one place per stage, which model arbitrates
  under which rules file and cap.
- UX2: A parked task's report distinguishes "gnome asked, arbiter could not
  decide (here is its analysis)" from "gnome asked, no arbiter configured".
- UX3: Advisory decisions are visible in the tracker as attributed comments
  before the task completes.

## Success Metrics

- M1: In the paid smoke suite, a fork covered by the rules file resolves
  without a park; the same fork with no arbiter configured parks exactly as
  before the change.
- M2: A kill at every new consult window converges per the kill-point
  harness, and a second recovery pass is a no-op.
- M3: An injected instruction planted in a working-copy file cannot move
  the verdict outside the enumerated options (spec-level adversarial case).

## Open Questions

- Q1: Default `maxDecisions` value — proposal: 3 per stage; confirm against
  paid-smoke experience.
- Q2: Should cannot-decide verdicts count against `maxDecisions`? Proposal:
  yes (each consult spends budget) — revisit if it under-asks in practice.
