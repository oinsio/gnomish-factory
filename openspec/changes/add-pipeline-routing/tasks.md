# Tasks — add-pipeline-routing

## 1. Domain model

- [ ] 1.1 Introduce the task-type value in `:domain` over designator kind
      `type` of the mechanism from `add-base-ref-resolution` (the port's
      absent / single / conflict shapes reused, no parallel type); verify
      the module compiles and the existing designator and port specs stay
      green (FR2, design D1)
- [ ] 1.2 Add `name` and the structural hash to `PipelineDefinition` (stage
      names/order, verify checks, executor kinds, artifact declarations —
      instruction and criteria content excluded; a spec asserts an
      instructions-only edit leaves the hash unchanged) and move tracker
      config off the definition onto the tree-wide load outcome; verify
      board/dashboard/take reach tracker config through the new seam and
      existing specs pass (FR1, design D4, D5)

## 2. Loader and routing table

- [ ] 2.1 Extend the loader to the named-pipelines shape with the `routing:`
      block (mandatory `default:` when present) and legacy single-shape
      compatibility as pipeline `default`; verify loader specs: both shapes,
      stable hash, table→missing-pipeline located error (FR1, FR5)
- [ ] 2.2 Re-scope validation: artifact-id uniqueness per pipeline (same id
      legal in disjoint pipelines), dangling check as union over pipelines,
      missing-manifest per pipeline; verify each scenario of the
      pipeline-config delta with data-driven specs (FR5)

## 3. Type extraction — selection rule

- [ ] 3.1 Extend the port contract suite with the three shapes of kind
      `type` (GitHub: labels under a `tracker.github.designators.type`
      rule; in-memory: the designator test operation); verify it runs red
      first (TDD) and then green for both adapters with no adapter
      production-code change (FR2)
- [ ] 3.2 TDD the two startup checks over the factory seam's
      configured-kinds query: kind `type` extracted with no routing table,
      and a routing table with type entries while no adapter extracts kind
      `type`, are located `ConfigError`s naming both places; a matching
      pair loads, and a legacy single-pipeline tree with no rule loads;
      verify a remapped `kind/(.+)` rule types `kind/bug` as `bug` end to
      end (FR2, design D1)

## 4. Resolver and pinning

- [ ] 4.1 Implement the single routing resolver (type facts + table →
      definition | routing escalation) with no other call path; verify
      resolver specs: match, default, typeless-no-default error, unknown
      type, conflict (FR3)
- [ ] 4.2 Pin name + hash in `task.json` within the task-creation commit
      via `TaskJsonMapper` behind the version gate (legacy reads as
      default-pinned, absent hash skips verification); verify mapper
      round-trip spec and a branch spec asserting the creation commit
      carries the pin (FR4, NFR-R1)
- [ ] 4.3 Wire resolve-and-pin into both fresh-claim twins and
      read-pin-and-verify into both resume twins via the shared resolver
      (registry rows per design D6); verify twin-parity specs: identical
      pin from both fresh paths, pinned-pipeline resume ignoring a changed
      table, hash-mismatch escalation (FR3, FR4)
- [ ] 4.4 Route the no-match/conflict escalation through the standard
      escalation exit without burning attempts; verify take specs: park
      report names designators and table, return-after-fix resumes and pins
      (FR3)

## 5. Serve and manual run

- [ ] 5.1 Freeze each slot's law from its task's selected pipeline at the
      task's law commit through the law source of `add-base-ref-resolution`
      (the startup load stays validation/display only); verify a serve spec
      with two concurrently routed tasks on different pipelines *and*
      different bases, each bound to its own base's definition; a pipeline
      name absent from the base's tree parks the task (FR3, design D3, D5)
- [ ] 5.2 Add `--pipeline` to `gnomish run` (default: routing default;
      unknown name fails fast listing pipelines) and resolve `--from-stage`
      against the selection before mode dispatch; verify manual-run specs
      for the three scenarios of the delta (FR6)

## 6. Documentation and gates

- [ ] 6.1 Update the operator guide (routing table authoring, the
      `tracker.github.designators.type` rule beside it and remapping,
      retype policy, hash-mismatch escalation handling) with a documented
      3-type starter example
      (`feature`/`bugfix`/`research` incl. spike contract); add glossary
      entries (task type, routing table, pipeline pin); verify terms match
      code naming (FR1–FR6)
- [ ] 6.2 Run full module checks (`:domain:check`, `:adapters:check`,
      `:application:check`) with PIT; verify the mutation gate passes and
      any new exemption carries its written rationale (all FRs)
