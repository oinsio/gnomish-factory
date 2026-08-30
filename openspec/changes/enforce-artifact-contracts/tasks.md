# Tasks: enforce-artifact-contracts

Every code task follows TDD (red Spock spec first) and carries traceability comments
(`Implements FR-X of enforce-artifact-contracts`). Design decisions referenced as D1–D7.

## 1. Configuration model and load-time validation (D1, D6)

- [ ] 1.1 Add the optional `path` to the wire DTO (`ArtifactOutputDto`) and to the domain
      `ArtifactOutput` (nullable/absent-capable, inert data per the existing no-throwing
      convention); verify with mapper/DTO specs that a manifest with and without `path`
      round-trips into the typed model unchanged (FR1)
- [ ] 1.2 Implement lexical path validation (relative, normalized — no `.`/`..` — not
      absolute, valid `glob:` syntax) as located `ConfigError`s naming the stage manifest
      and output id, wired into the existing aggregation pass beside the pin-path rule;
      verify with a data-driven spec covering absolute, `..`, `.`, malformed-glob, and
      valid cases (FR2, NFR-S1)
- [ ] 1.3 Confirm path-less mode is untouched: extend the loader specs to assert a
      pipeline without any `path` loads byte-equal to before (same model, no errors, no
      warnings) and that no existence check ever reads outside `.gnomish/` (FR1, UX2);
      verify with `./gradlew :adapters:test :domain:test`

## 2. Engine port and the single matching rule (D4)

- [ ] 2.1 Add the `ArtifactFileSource` engine port (enumerate relative paths of the
      current persisted working-copy state) to `EnginePorts` with javadoc traceability;
      verify existing engine specs still compile/pass with a trivial fake (FR7)
- [ ] 2.2 Implement the domain glob-matching rule (one listing in, claims in, missing
      claims out; "at least one match" resolves; symlink entries matched as listed paths,
      never followed) as a single pure component; verify with a data-driven spec covering
      exact path, `*`, `**`, no-match, and multi-claim-single-listing cases (FR3, FR4,
      NFR-P1, NFR-S1)

## 3. Gates in the engine (D2, D3, D7)

- [ ] 3.1 Implement the consumer gate: before the first round of each stage entered in a
      run, resolve every internal input to its producer's declared path and probe via the
      port; a miss short-circuits to `Escalated(CannotExecute)` before any executor or
      persistence call for that stage; verify with engine specs asserting no attempt
      burned, no executor invoked, and cause naming stage/id/path/gate (FR4, FR5)
- [ ] 3.2 Implement the producer gate: after a passing round is persisted (position
      already advanced) and before the next stage executes, probe the passed stage's
      path-declaring outputs; a miss escalates `CannotExecute` preserving the recorded
      passing round; verify with engine specs asserting the record survives and
      `attemptsUsed` is unchanged (FR3, FR5)
- [ ] 3.3 Generalize `EscalationReport.CannotExecute` javadoc and the engine's cause
      construction per D3 (factory-fault wording; stage, artifact id, path, gate named;
      ERROR log with the (taskId, stage) key carrying the same detail); verify with a spec
      asserting log line and cause agree and the wording never blames the gnome (FR5,
      NFR-O1, UX1)
- [ ] 3.4 Convergence and no-op specs: consumer gate re-run on the same state escalates
      identically; restored artifact resumes normally; a fully path-less pipeline produces
      an event/outcome/log stream identical to pre-change reference runs (NFR-R1, UX2,
      M1) — extend the reference-pipeline spec with both gate scenarios

## 4. Git adapter for the port (D2, D4, D5)

- [ ] 4.1 Implement the single git `ArtifactFileSource` adapter (`git ls-tree -r
      --name-only <tip>` of the task branch tip), constructor-parameterized by repo
      directory; verify against local bare-repo fixtures listing committed paths only
      (FR7)
- [ ] 4.2 Wire it in both composition roots: host mode over the worktree repo, container
      mode over the factory clone — one class, two wirings, no sync pair (D5); verify via
      the existing mode-parity/composition specs and confirm `grep -rn "Kept in sync
      with"` gains no new undeclared surface (FR7)

## 5. Briefing enrichment (FR6)

- [ ] 5.1 Thread the producer-path resolution to the briefing call sites and render the
      declared path beside the producer id in `BriefingSections.renderInputArtifacts`;
      verify with a spec asserting the path-carrying rendering and byte-identical output
      for path-less and `source` inputs (FR6, M2)

## 6. Documentation

- [ ] 6.1 Add the **Artifact contract** entry to `docs/glossary.md` (Pipeline execution
      section: the declared, machine-verifiable path of a stage artifact and its two
      gates) and reference the term from the touched javadoc; verify the entry exists and
      no banned synonym is introduced
- [ ] 6.2 Document the `path` field in the stage-manifest examples the repo carries
      (e.g. `StageDto` wire-shape javadoc); verify the example parses in the DTO spec

## 7. Verification

- [ ] 7.1 Traceability sweep: `grep -rn "enforce-artifact-contracts"` shows at least one
      implementing entity per FR/NFR/UX of the proposal; fix any gap
- [ ] 7.2 Full gate: `./gradlew check` green in every touched module, PIT mutation score
      at target with no new exemptions beyond the documented categories (M3)
