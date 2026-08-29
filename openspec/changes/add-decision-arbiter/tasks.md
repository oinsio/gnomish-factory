# Tasks: add-decision-arbiter

Sequencing: rebase on `harden-task-branch-contract` (implemented; archive
first) — decision durability (FR12 there) and the kill-point harness are
prerequisites. Coordinate with `fix-denial-attribution-durability` on the
stage-engine "Outcome and report model" requirement text (whichever
archives first, the other rebases its delta). TDD throughout
(`.claude/rules/testing.md`).

## 1. Vocabulary and law

- [ ] 1.1 Glossary entries: **arbiter** (decides forks before work
      continues; contrast with judge, which grades results), **decision
      scope**, **advisory decision**; ban "referee"/"resolver" as synonyms
      (D4, FR9)
- [ ] 1.2 `decisions:` section in the stage manifest: DTO, mapper,
      validation (rulesFile confined to `.gnomish/`, positive cap, settings
      vocabulary), pipeline-validator rule; fixtures (FR1)
- [ ] 1.3 Decision-rules file joins the pipeline law freeze set + reader;
      spec: gnome edits invisible, resume re-freezes (FR2, D5)

## 2. Structured decision request

- [ ] 2.1 Extend the decision-file schema: question, options[≥2]{id, text},
      whyBlocked; tolerant parse keeps the raw-text human path; document
      the schema in the executor prompt template (FR3, D6)
- [ ] 2.2 Both transports accept the structure; place `Kept in sync with`
      markers on both ends of `DecisionFileTransport` ↔ `BranchDecisionFile`
      and update the registry row (D11)
- [ ] 2.3 Malformed-request arm: on an arbiter-enabled stage, feedback
      names the missing fields, no consult, round recorded as today (FR3)

## 3. Arbiter port and adapters

- [ ] 3.1 Domain port (request + context + workspace → closed verdict) and
      the verdict model: decided(optionId, rationale, notify?) |
      cannotDecide(reason); out-of-vocabulary output folds to
      cannotDecide (FR4, D2, D4)
- [ ] 3.2 CLI adapter: read-only tool policy (narrow-only allowlist),
      prompt = frozen rules as instruction + request and file content
      delimited as untrusted data; structured-output parse (FR7, NFR-S2)
- [ ] 3.3 Environment: consult reads a fresh environment from the
      attempt's harvested commit via the existing judge-environment
      abstraction — no new environment source (D3; sync-pair rule-of-three:
      reuse, don't add a parallel source)
- [ ] 3.4 Interactive console adapter (human stands in for the arbiter, for
      rule debugging without paying for consults) + adapter selection
      beside the judge selector; EnginePorts additive constructor,
      no-arbiter default overload (D2)

## 4. Engine integration

- [ ] 4.1 Consult at the single NeedsDecision transition: decided →
      durable decision commit → continue loop; cannotDecide / cap / none →
      today's escalation with consult history attached (FR4, FR5, FR6, D1)
- [ ] 4.2 `maxDecisions` engine wall with per-stage consult accounting in
      the persisted state (additive field); exhaustion parks with history
      (FR6, NFR-C1)
- [ ] 4.3 Consult infrastructure failures: retry policy, no attempt
      burned, persistent → human park "cannot consult" (NFR-R2)
- [ ] 4.4 Crash consistency per `.claude/rules/crash-consistency.md`: name
      the consult windows (answered-uncommitted; committed-unacted),
      recovery = re-consult / no-op; kill-point specs join the harness,
      second recovery pass asserted no-op (NFR-R1)

## 5. Decision records: owner, scope, injection

- [ ] 5.1 Single decision-append owner stamping author + scope with
      commit-before-ack ordering; migrate the four construction sites; the
      two list-size-diff detection sites consume its explicit result
      (FR10, D7)
- [ ] 5.2 Wire DTOs gain scope + supersedes additively (state and
      bare-objects media identical); place `Kept in sync with` markers on
      `GitTaskRepository` ↔ `GitObjectsTaskRepository` and on the resume
      runner twins; update registry rows (FR9, D8, D11)
- [ ] 5.3 Scope-filtered, supersede-aware decision rendering in executor
      and judge prompt builders — verbatim, uninterpreted, delimited (FR9)

## 6. Advisory notify

- [ ] 6.1 Notify verdict posts one attributed marked-comment (decision,
      author, scope); text is display data; veto documented as
      park-and-supersede through the existing flow (FR8, D9, UX3)

## 7. Usage accounting

- [ ] 7.1 Extract the shared usage wire vocabulary consumed by state.json,
      status.json, and usage.json (rule-of-three; removes the three
      independent judge-usage DTO trees); round-trip spec per the wire
      vocabulary rule (D10)
- [ ] 7.2 `arbiterUsage` on the attempt record (map-only, additive);
      totals stay executor-only with the asymmetry documented at the fold
      site; usage/status render the new column (NFR-O1, D10)

## 8. Observability and operator surfaces

- [ ] 8.1 Structured consult log line; escalation report renders consult
      history and distinguishes "arbiter could not decide" from "no
      arbiter configured" (NFR-O1, UX2)
- [ ] 8.2 Operator guide section: configuring the arbiter, writing
      decision rules, the veto flow, cost expectations (UX1)

## 9. Verification and closure

- [ ] 9.1 Adversarial spec: planted instruction in a working-copy file
      cannot move the verdict outside the enumerated options (M3, NFR-S1)
- [ ] 9.2 Paid-smoke scenario: rules-covered fork resolves without a park;
      no-arbiter control parks as before (M1)
- [ ] 9.3 Full `./gradlew check` green including mutation gates in touched
      modules; kill-point matrix green twice (M2)
- [ ] 9.4 Traceability grep per `.claude/rules/traceability.md`; recommend
      a Conventional Commits message referencing add-decision-arbiter
