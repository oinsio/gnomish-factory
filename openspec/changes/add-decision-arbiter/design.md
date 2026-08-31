# Design: add-decision-arbiter

## Context

Driven by FR1–FR10, NFR-S1/S2, NFR-R1 of this change's proposal. The
architect session (2026-08-29) validated the shape against external
research: LLM-cascade literature (worker → stronger model → human, cost
curve flattens at two AI tiers), Aider's architect/editor split (the
deciding model never writes), and LLM-judge injection results
(JudgeDeceiver: the adjudicated party authors what the adjudicator reads).
The code audit located the existing seams this design reuses.

## Decisions

**D1 — Insert the arbiter at the engine's single NeedsDecision owner.**
The consult happens inside the stage attempt loop at the one domain-side
transition that today turns `DECISION_NEEDED` into an escalation
(`StageAttemptLoop`), between recording the unburned round and returning
the escalation. A decided verdict continues the round loop instead of
returning. *Rationale:* the audit confirmed this is a genuinely single
owner; inserting at the application/park boundary instead would touch at
least five sites and restate the commit-before-ack ordering for a
non-human author. *Alternative rejected:* application-layer interception
(TakeOutcomeMapper / park machinery) — scatters the tier across take,
manual-run, and container twins, and the park machinery would need to
distinguish "parking" from "asking" states it was never designed for.

**D2 — Arbiter port modeled on JudgeVoter, wired additively through
EnginePorts.** A new `ArbiterVoter`-style port (request + context +
workspace → verdict) with a CLI adapter and an interactive console
adapter, selected by the same adapter-selection path as judges. EnginePorts
gains the component through its established additive-constructor pattern
(single production construction site). A no-arbiter default overload keeps
every existing construction site compiling and behaving unchanged.
*Rationale:* the JudgeVoter shape (one vote, engine owns policy, workspace
as opaque marker) already answers the same design questions. *Alternative
rejected:* reusing JudgeVoter itself with a synthetic "criteria" file — a
judge grades output against criteria, an arbiter selects among options
under rules; conflating them muddles both prompts, both usage accounting
streams, and the read-context guarantees.

**D3 — Read-only access = judge machinery, not new mechanism.** The
arbiter subprocess runs with the narrow-only read allowlist
(`JUDGE_READ_ONLY_TOOLS` policy: a manifest can only narrow, never widen)
and reads a fresh environment materialized from the attempt's harvested
commit — the FreshJudgeEnvironments pattern — never the gnome's live box.
*Rationale:* both mechanisms exist, are spec-covered, and were built for
exactly this threat ("a poisoned round box cannot grade itself").
*Alternative rejected:* giving the arbiter the live worktree read-only —
cheaper (no box materialization) but hands the adjudicated party's
uncommitted state to the adjudicator and has no isolation story in host
mode.

**D4 — Closed verdict schema: the arbiter is a selector, not a generator.**
The verdict grammar is `decided(optionId ∈ request.options, rationale,
notify?) | cannotDecide(reason)`; anything else (including free-form
prose) classifies as cannot-decide. The decision text injected into later
prompts is the *request's own option text* plus the rationale, delimited
as a decision record. *Rationale:* this is the highest-leverage
prompt-injection hardening available (Willison's action-selector shape;
JudgeDeceiver shows steering the adjudicator is practical) — an injected
instruction in the working copy can at worst flip a choice between two
gnome-authored options, never mint new instructions. It also makes M3
testable. *Alternative rejected:* free-form arbiter guidance — richer
answers, but converts the arbiter into an unaudited instruction channel
into future prompts, defeating the law freeze from the side.

**D5 — decision-rules.md joins the pipeline law freeze.** One more entry
in the frozen-law set read at invocation start from the factory clone,
with the same resume semantics (a human edit + resume re-freezes).
*Rationale:* the freeze mechanism's anti-reward-hacking property is
already argued and tested; rules the gnome could edit mid-task would be
no rules. *Alternative rejected:* reading rules from the task branch —
would let the plan stage (a gnome) author its own decision policy.

**D6 — Structured request gate before any consult.** The decision-file
schema gains required fields (question, options[≥2], whyBlocked); the
existing tolerant parse (garbage → raw question) still parks to the
*human* path unchanged, but an arbiter consult requires the full schema —
a request that names no options gets quality feedback naming the missing
fields, and the round is recorded as today's decision round. *Rationale:*
the selector grammar (D4) is only as strong as the option list; and
without the gate, gnomes learn to launder hard work into vague decision
requests (forced-deferral pattern). *Alternative rejected:* letting the
arbiter answer optionless questions free-form — reopens D4's hole.

**D7 — One decision-append owner in the application layer.** A single
`DecisionRecorder` (name illustrative) owns append + author + scope +
commit-before-ack ordering; the four existing construction sites
(tracker resume twins, operator dialog, and now the arbiter) delegate to
it. The two list-size-diff detection sites consume its explicit result
instead of diffing. *Rationale:* the audit flagged the scatter; adding a
fourth author value to four sites is how divergence bugs are born.
*Alternative rejected:* leaving the sites and adding a fifth — fails the
same review that produced `manual-sync-pairs.md`.

**D8 — Scope and supersede are additive record fields.** Decision records
gain `scope` (item/stage/task; default task) and `supersedes` (optional
reference), additively under the v1 wire contract (absent = today's
semantics). Prompt rendering filters to in-scope records. *Rationale:*
unbounded decision accumulation blows the context budget and stale
decisions mislead; ADR-style supersede-with-attribution is the canonical
answer. *Alternative rejected:* pruning old decisions — destroys the
audit trail the whole tier depends on.

**D9 — Advisory notify rides the marked-comment upsert.** A notify
verdict posts one attributed tracker comment through the existing
marked-comment primitive; veto = the human parks/supersedes via the
normal escalation flow — no new tracker states. *Rationale:* no inbound
HTTP exists and none is wanted; the primitive already solves idempotent
comment identity. *Alternative rejected:* a new tracker status for
"advisory pending" — multiplies the tracker shape set for a state that
blocks nothing.

**D10 — Arbiter usage follows the judge convention, and the usage wire
vocabulary is extracted first.** `arbiterUsage` is map-only
(empty = unreported, never fabricated zeros) and joins `AttemptRecord`
additively. The audit found judge usage already serialized by three
independent DTO trees (state.json / status.json / usage.json); adding a
fourth usage kind to three trees triggers the rule-of-three, so this
change extracts one shared usage wire vocabulary consumed by all three
documents before adding the new field. Totals stay executor-only, now
documented at the fold site. *Rationale:* the manual-sync-pairs rule is
explicit: a third implementation extracts the abstraction — this change
would otherwise add the third-and-fourth copies knowingly. *Alternative
rejected:* copying `arbiterUsage` into all three trees and declaring the
pairs — three-way declared sync for mechanical DTO mapping is exactly
what the rule's preference order forbids when extraction is possible.

## Sync surfaces (mandatory)

**D11 — Sync surfaces.** This change touches these declared pairs from
`manual-sync-pairs.md`, and the mirrored edits are in scope:

- `DecisionFileTransport` ↔ `BranchDecisionFile` — the structured request
  schema (D6) changes what both readers accept; both ends change together
  and both gain the `Kept in sync with` marker in this change.
- `GitTaskRepository` ↔ `GitObjectsTaskRepository` — `appendDecision`
  gains author/scope fields at both ends (via D7's single caller); markers
  added at both ends.
- `TakeResumeRunner` ↔ `TakeContainerResumeRunner` — both twins stop
  constructing decisions inline and delegate to the D7 owner; the
  container twin's pre-append environment disposal stays where it is
  (mid-run arbiter decisions have no disposal step — the environment is
  still live and stays so).

New parallel implementations: none — the arbiter adapter is a *first*
implementation behind a new port (D2), and D7/D10 *remove* parallelism
(four append sites → one owner; three usage DTO trees → one shared
vocabulary). No new pair is declared.

## Risks / Trade-offs

- Arbiter answers are stochastic; a re-consult after a crash may differ →
  bounded by NFR-R1: a committed verdict is never re-consulted, and no
  verdict is acted on before it is durable.
- The consult adds latency and cost to decision rounds → bounded by
  `maxDecisions` (engine wall) and exactly-one-consult-per-request.
- The structured request schema raises the bar for gnome-authored
  questions; early runs may see more malformed-request feedback loops →
  the executor prompt documents the schema, and the malformed arm burns
  nothing.
- Extracting the usage wire vocabulary (D10) widens the diff beyond the
  arbiter itself → accepted: the alternative knowingly creates the fourth
  copy of a hand-synced vocabulary.
