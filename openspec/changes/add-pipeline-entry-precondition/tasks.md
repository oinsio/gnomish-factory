# Tasks: add-pipeline-entry-precondition

Traceability: FR/NFR IDs from `proposal.md`; decisions D1–D8 from `design.md`.
TDD per `.claude/rules/testing.md`: every task's spec is written red first.

## 1. Configuration model and glossary

- [ ] 1.1 Add the optional pipeline-level `entry-precondition` section (command +
      timeout) to the `pipeline.yaml` model and loader (FR1, D2); verify with
      loader Spock specs: valid section loads typed, absent section loads exactly
      as before with no warning
- [ ] 1.2 Add located-`ConfigError` validation for the section — blank command,
      non-positive/malformed timeout, unknown keys — aggregated with existing
      errors (FR1); verify with data-driven loader specs, one row per rejection,
      plus a no-execution assertion (the loader never runs the command)
- [ ] 1.3 Add the **entry precondition** glossary entry to `docs/glossary.md`
      (FR7); verify the entry defines the term per the IDEF0 entry-precondition
      framing and `grep -n "entry precondition" docs/glossary.md` finds it

## 2. Probe execution (shared component, D4/D5)

- [ ] 2.1 Implement the baseline probe environment source: fresh environment
      keyed per the lifecycle decision matrix, `materialize(branch, baseCommit)`,
      dispose without harvest, following the `SandboxCheckEnvironmentSource`
      pattern but keyed to the baseline commit, not an attempt commit (FR3,
      NFR-S1); verify with Spock specs: materialize pin equals `baseCommit`,
      dispose always called, harvest never called (including on exceptions)
- [ ] 2.2 Implement probe execution and classification: exec with declared
      timeout, exit 0 → green; 126/127, materialize failure, runtime outage,
      interruption → infrastructure; other exit → red with bounded output tail;
      timeout → red naming the elapsed bound (FR4, FR5, NFR-P1, D5); verify with
      a data-driven classification spec, one row per class
- [ ] 2.3 Implement the entry-precondition runner: skip when no declaration, skip
      on matching green verdict, re-run on key mismatch, record verdict before
      dispose, return proceed/park decision (FR2, FR6, NFR-C1); verify with Spock
      specs on fake ports covering skip-silent, skip-cached, mismatch-rerun, and
      verdict-before-dispose ordering (M2, M3)

## 3. Verdict persistence (D6)

- [ ] 3.1 Add the optional `entryPrecondition` component to `StateJsonDto` +
      `StateJsonMapper` (verdict, baseline SHA, image identity, bounded tail,
      timestamp), additive under contract v1 with no `TaskState` counterpart,
      following the `egressCursor` precedent (FR6); verify with mapper round-trip
      specs including the wire round-trip of the verdict vocabulary over all
      constants plus unknown-token behavior (`.claude/rules/testing.md`), and a
      pre-existing-file spec reading the field as absent
- [ ] 3.2 Implement the factory-side verdict commit: own commit between creation
      and first round, epoch-stamped, mutually-implied fields in one commit,
      best-effort push; later state writes preserve the field across rounds and
      lifecycle rewrites (FR6, NFR-R1); verify with local-bare-repo Spock specs:
      verdict commit shape, preservation through a round write and a park/resume
      rewrite

## 4. Wiring into the mode recipes (D1, D8 — mirrored edits, both ends)

- [ ] 4.1 Insert the runner into `TakeFreshClaim` between `createTask`/bootstrap
      and `execution.run` (FR2); verify with host-mode take specs: green
      proceeds, red parks with zero attempts and no executor call
- [ ] 4.2 Mirror the same insertion into `TakeContainerFreshClaim` between
      `createTask` and `execution.run(support, ...)`, reading the baseline SHA
      via the container-mode task repository (FR2, D8); verify with the
      container-mode twin specs asserting the identical recipe outcome
- [ ] 4.3 Guard the resume-before-first-round paths in `TakeResumeRunner` and
      `TakeContainerResumeRunner` (and the manual-mode runner pairs if their
      pre-first-round paths bypass the take recipes — confirm and cover or state
      why not) (FR2, D8); verify with resume specs: unprobed created task probes,
      cached green skips, both modes
- [ ] 4.4 Update `.claude/rules/manual-sync-pairs.md`: fresh-claim recipe rows'
      invariant becomes "harden → synthesize → createTask → entry precondition →
      run", and add/refresh `Kept in sync with` markers on every touched pair end
      (D8); verify `grep -rn "Kept in sync with"` enumerates each touched end

## 5. Red-baseline escalation (D3)

- [ ] 5.1 Synthesize the `Escalated(CannotVerify)` outcome for a red baseline —
      check reference naming the entry precondition, reason `baseline red at
      <SHA>`, bounded tail as details — and route it through the existing outcome
      recording and park protocol (FR4, NFR-O1, UX1); verify with specs asserting
      the park lands via the existing protocol, `attemptsUsed` is 0, the attempt
      history is empty, and the rendered report carries SHA and tail
- [ ] 5.2 Add probe observability: log start, verdict, key, and duration; no log
      lines at all on the skip path (NFR-O1, UX2); verify with a logging spec and
      the skip-silent assertion of 2.3

## 6. Crash consistency and end-to-end verification (D7)

- [ ] 6.1 Confirm windows W1/W2 classify to the existing `Created`-family shape
      of the `task-branch-contract` capability with the classifier unchanged, and
      that a verdict-bearing zero-round branch (W3) classifies unchanged too
      (NFR-R1); verify with classifier specs over branches frozen in each window
- [ ] 6.2 Verify recovery ownership and idempotence: pickup from W1/W2 re-runs
      the probe with identical outcome to an uncrashed run; pickup from W3
      continues forward (green → first round, red → re-driven park); second
      recovery pass is a no-op (NFR-R1); Spock specs on fake ports
- [ ] 6.3 Add kill-point specs to the kill-point matrix: kill after creation
      commit, after probe exec, after verdict commit (green and red variants);
      assert the frozen shape, the convergence, and the no-op second pass
      (NFR-R1, M1); verify via the matrix suite going green
- [ ] 6.4 Run the full existing suite for pipelines without a declaration and
      confirm zero behavioral diff (M3): `./gradlew check` green with no
      modification to pre-existing specs' expectations

## 7. Coordination notes (execute at apply/archive time)

- [ ] 7.1 Reconcile with `add-pipeline-routing` apply order: if routing applied
      first, move the `entry-precondition` section into the per-pipeline block
      and confirm the content hash covers it; if this change applies first,
      record a note in routing's tasks to subsume the section (D2, proposal
      Impact); verify by loader specs in whichever shape is current
- [ ] 7.2 Reconcile the `state.json` additive-field sequencing with
      `harden-task-branch-contract`, `add-stage-iteration`, and
      `fix-denial-attribution-durability` (reference JSON / version-gate bumps
      land once, by the change that applies last); verify the state-file contract
      spec and `status-report-v1.reference.json` equivalence spec stay green
