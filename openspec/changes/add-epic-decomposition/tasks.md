# Tasks — add-epic-decomposition

## 1. Plan artifact and schema

- [ ] 1.1 Define the decomposition-plan model in the domain (verdict,
      children with stable keys/briefs/edges, integration child, schema
      version) with structural validation; verify Spock specs cover accept +
      every rejection (duplicate key, unknown-key edge, cycle, missing
      integration child, over-limit) (FR2)
- [ ] 1.2 Implement the single plan/receipt mapper class owning both write
      and read (design D6); verify the wire round-trip spec iterates every
      enum constant and pins the unknown-token and unknown-version arms
      (FR2, FR4)

## 2. Pipeline config

- [ ] 2.1 Add the optional `decompose:` section to `stage.yaml` loading and
      validation (plan artifact must be a declared output, one
      decomposition-capable stage per pipeline, child limit bounds); verify
      loader specs for each located error and the untouched-pipeline no-op
      (FR6)

## 3. Engine outcome

- [ ] 3.1 Add the `Decomposed` terminal variant to the sealed `TaskOutcome`
      and derive it in the stage loop from a passed verification whose
      validated plan declares an epic (single-task plan advances); verify
      engine specs for all three arms including invalid-plan-burns-attempt
      (FR1)

## 4. Take transition (intent → effect → receipt)

- [ ] 4.1 Implement the mode-neutral decomposition driver: children via
      `createSubtask` in plan order (integration child last, edges at
      creation), receipts batched per convergence pass, parent park report,
      release last — one class, called from outcome dispatch (design D2/D6);
      verify Spock specs with the in-memory tracker for the happy path and
      ordering assertions (FR3, FR5)
- [ ] 4.2 Add the `Decomposed` dispatch arm to both mode flows reaching the
      shared driver; verify a twin-parity spec asserting host and container
      outcome flows invoke the same driver (registry rows per design D6)
      (FR3)
- [ ] 4.3 Implement resume-side convergence: branch classification of the
      four shapes, reconcile-by-stable-key, point-of-no-return guard (no
      engine round after a pushed plan); verify kill-point specs — kill
      after each durable step, assert shape, convergence, and no-op second
      pass (FR4, NFR-R1)

## 5. Branch persistence

- [ ] 5.1 Persist plan and receipts in the factory-owned branch area as
      single pushed commits via the shared mapper; verify the classifier
      names partial-receipt shapes and missing keys from the branch alone
      (FR3, FR4)

## 6. Parent lifecycle and sweep

- [ ] 6.1 Finish the parent from the integration child's delivery (single
      writer through existing finish machinery); verify an integration spec:
      delivering the integration child finishes the epic with a linking
      summary (FR5)
- [ ] 6.2 Add the orphan policy to the sweep: cancelled/escalated epic parks
      open children naming the epic; verify sweep specs including
      idempotency of a second pass (FR5)

## 7. Observability — declared wire pair

- [ ] 7.1 Add the `decomposed` outcome token at both ends of the ledger wire
      pair (`LedgerJsonMapper`, `LedgerAggregator`) plus distinct dashboard
      aggregation; verify the values()-iterating round-trip spec and an
      aggregation spec pass (NFR-O1)

## 8. Documentation and gates

- [ ] 8.1 Add glossary entries (decomposition plan, integration child,
      stable child key reference, orphan policy) and extend the operator
      guide (decompose flow, manual-advancement veto pattern); verify terms
      match code naming (FR1–FR5)
- [ ] 8.2 Run full checks (`:domain:check`, `:application:check`,
      `:adapters:check`) with PIT; verify the mutation gate passes and any
      new exemption carries its written rationale (all FRs)
