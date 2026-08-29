# Tasks: add-stage-iteration

Sequencing: implement after `harden-task-branch-contract` archives (item
transitions ride its atomic single-commit machinery and kill-point
harness) AND after `fix-denial-attribution-durability` lands its
cursor-preservation work (the iteration cursor extends that rule — landing
earlier re-creates the RESUMED-erasure defect it fixes). Soft dependency
on `add-decision-arbiter`: FR9's decision requests route to the arbiter
when configured, else to the human park — implementable and testable
without the arbiter change. TDD throughout (`.claude/rules/testing.md`).

## 1. Vocabulary and manifest

- [ ] 1.1 Glossary entries: **item**, **item cursor**, **item snapshot**,
      **adoption**, **discovery budget**, **repair item**, **partial**
      (verified pass with declared scope reduction); ban "subtask"/"step"
      as synonyms for item (FR1–FR8)
- [ ] 1.2 `iterate:` manifest section: DTO, mapper, validation (declared
      input source, perItemChecks type restriction to builtin/command,
      positive limits, unknown-key rejection); fixtures; mapper and
      validator live in the single loader pipeline — no cross-module
      mirrored-validation pair (FR1, D9, D10, resolves Q1)

## 2. Domain: snapshot, cursor, lifecycle

- [ ] 2.1 Checklist parser: leaf checkbox items with continuation bodies,
      stable ids, content hashes; section text is context, not items (FR2)
- [ ] 2.2 Iteration state model: ordered snapshot (id, hash, state,
      per-item attempts, provenance), cursor, progress records; additive
      to TaskState with the non-iterating path untouched (FR2, FR3, D1)
- [ ] 2.3 New transitions: start-item (in-progress mark, at most one),
      record-item-pass-and-hold (cursor advance, stage position held,
      history kept), record-item-quality-failure (per-item attempt),
      adopt-items (boundary snapshot extension) — `recordPassAndAdvance`
      fires only on snapshot exhaustion + stage-end pass (FR6, D2, D4)
- [ ] 2.4 Unify the pre-existing duplications this loop would triple:
      one owner for the attempt-limit predicate (today checked in the
      loop and pre-flight) and one owner for round numbering (today
      derived from history size at two sites and leaked into the decision
      file name) (design Risks)
- [ ] 2.5 Property spec: for generated plans and kill sequences, engine
      truth (cursor) never regresses, never skips, and completed items
      never re-enter (M1, M2)

## 3. Engine loop

- [ ] 3.1 Item loop inside the attempt loop for iterating stages:
      per-item rounds under `attemptLimitPerItem`, per-item feedback
      scoping, oversized plan at entry = quality failure attributed to
      the plan input (FR2, FR5, NFR-C1)
- [ ] 3.2 Item prompt assembly: law + full plan document + item focus +
      in-scope decisions + progress records of completed items; item and
      adopted-item text delimited as data with provenance (FR4, FR12,
      NFR-S1, D5)
- [ ] 3.3 Structured item result channel (done / discovered items /
      item-obsolete / plan-invalidated + progress record), file-based
      beside the decision file; absent/unparseable result =
      infrastructure failure, unburned (FR9, FR11)
- [ ] 3.4 Boundary diff and adoption: append-only after the cursor,
      completed-region insertions refused, dedup by hash, budget wall,
      human-era additions counted against maxItems only; overflow raises
      a decision request with the pending items as payload (FR7, FR8, D6)
- [ ] 3.5 Plan-invalidated / item-obsolete → decision request routed to
      the decision tier; per-item exhaustion → whole-task escalation
      naming the item, findings-first report (FR9, FR10, D7, UX2)
- [ ] 3.6 Stage-end verify split per the manifest designation; stage-end
      quality failure adopts a repair item (engine provenance, budget-
      exempt, capped); repair exhaustion escalates naming it (FR5, D8, D9)

## 4. Persistence and sync pairs

- [ ] 4.1 Wire DTOs for iteration state, additive under v1; identical
      serialization in both media; place `Kept in sync with` markers on
      `GitAttemptPersistence` ↔ `EnvironmentAttemptPersistence` and update
      the registry row (FR2, D1, D10)
- [ ] 4.2 Single-commit item transitions in both media (item pass,
      adoption, repair entry); STARTED initial state and RESUMED rewrite
      carry/preserve iteration state in `GitTaskRepository` ↔
      `GitObjectsTaskRepository`; markers at both ends (FR6, NFR-R2, D10)
- [ ] 4.3 Item result file path joins the `.gnomish-task/` allowed-path
      rule at both ends of `RoundBoundaryCheck` ↔ `HarvestedBoundaryCheck`;
      markers at both ends (D10)
- [ ] 4.4 Resume routing in both take-resume twins: in-progress-item shape
      re-enters the item round on the working copy as it stands; cursor
      reconstruction; cross-instance resume spec (FR10, D4, M2)

## 5. Observability and operator surfaces

- [ ] 5.1 Item-boundary structured log line (item id, verdict, adopted
      count); body-change trace for not-yet-completed items; heartbeat
      progress carries "item k/N (+d)" (FR7, NFR-O1)
- [ ] 5.2 `status` list + single-task + JSON render item progress; non-
      iterating rendering byte-identical (UX1)
- [ ] 5.3 Operator guide: authoring an iterating stage, the plan-stage
      conventions (no verification tail block, notation vocabulary,
      budget stated in planner instructions), reading escalations (UX2,
      UX3, NFR-C1)

## 6. Kill-point matrix and verification

- [ ] 6.1 Kill-point specs for every new durable step (in-progress mark,
      item pass commit, adoption commit, repair entry, decision-request
      raise), both media: converged named shape + no-op second recovery
      (NFR-R1, M1)
- [ ] 6.2 Plan-shrink adversarial spec: deleting a frozen id never yields
      a completed stage (M3); checkbox-flip spec: glyphs never advance the
      cursor (G2)
- [ ] 6.3 Full `./gradlew check` green including mutation gates in touched
      modules; paid-smoke iterating stage measuring per-item token cost
      (design Risks)
- [ ] 6.4 Traceability grep per `.claude/rules/traceability.md`; recommend
      a Conventional Commits message referencing add-stage-iteration
