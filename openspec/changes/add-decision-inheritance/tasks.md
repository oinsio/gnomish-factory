# Tasks — add-decision-inheritance

## 1. Decision record growth

- [ ] 1.1 Add the additive mini-ADR fields (scope, status/supersedes,
      rejected alternatives, premises) to the decision model and the single
      append owner's validation (subtree scope requires rejected
      alternatives; accepted records immutable — supersede, never edit);
      verify Spock specs for each rule and legacy-default parsing (FR1)
- [ ] 1.2 Extend `task.json` mapping round-trip for the new fields; verify
      the round-trip spec iterates all status/scope constants and an old
      fixture file parses with defaults (FR1)

## 2. Epic decisions file

- [ ] 2.1 Implement the single epic-file mapper (read + write + binding
      view: accepted subtree records, provenance, superseded dropped) used
      by all three call sites per design D5; verify a values()-iterating
      round-trip spec and a binding-view spec (FR2)
- [ ] 2.2 Persist the file in the factory-owned branch area (seed from plan,
      append roll-ups as single pushed commits) and classify its states in
      the branch contract; verify classifier specs name the missing-roll-up
      shape from a fixture branch (FR2, FR3)

## 3. Roll-up protocol

- [ ] 3.1 Implement the mode-neutral roll-up driver: export subtree records
      to the epic branch before the tracker finish, under the child's claim,
      through the single append owner; verify ordering specs and the
      kill-between-roll-up-and-finish kill-point spec (converges, second
      pass no-op) (FR3, NFR-R1)
- [ ] 3.2 Implement the integration child's completeness check at claim:
      verify every finished sibling's exports present, re-derive missing
      ones from the sibling branch idempotently; verify repair specs
      including the no-op second pass (FR3, NFR-R1)

## 4. Downward injection

- [ ] 4.1 Implement inherited-context materialization at claim/resume
      bootstrap (epic-file binding view + plan brief, frozen per invocation;
      fetch failure → infrastructure escalation); verify freeze semantics
      (mid-invocation epic-file change invisible) and the
      unreachable-branch escalation spec (FR4)
- [ ] 4.2 Enforce the binding-set bound with escalate-on-oversize; verify
      the oversize spec asserts escalation and that no truncated round runs
      (NFR-C1)

## 5. Briefing

- [ ] 5.1 Render the inherited-context section (brief + binding decisions
      verbatim with provenance and rejected alternatives + conflict rule)
      in executor briefings and inside hardened delimiters for judges;
      verify rendering specs and the byte-for-byte unchanged briefing for
      non-hierarchical tasks (FR4, FR5)

## 6. Conflict gate

- [ ] 6.1 Implement the append-gate rejection of subtree records
      contradicting an inherited binding record (cited-id match against the
      frozen set) converting to the proposed-supersede escalation; verify
      gate specs: contradiction escalates with the referenced record, no
      record lands, resolution unblocks the returned child (FR5)

## 7. Documentation and gates

- [ ] 7.1 Add glossary entries (binding decision, roll-up, epic decisions
      file, proposed supersede) and extend the operator guide (resolving a
      proposed supersede, oversize escalation); verify terms match code
      naming (FR1–FR5)
- [ ] 7.2 Run full checks (`:domain:check`, `:application:check`,
      `:adapters:check`) with PIT; verify the mutation gate passes and any
      new exemption carries its written rationale (all FRs)
