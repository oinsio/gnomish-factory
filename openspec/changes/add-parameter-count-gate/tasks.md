## 1. The check, built and proven before anything depends on it

- [ ] 1.1 Add a `build-logic` subproject holding the project Error Prone check, compiled
      against the Error Prone check API at the version already in the shared catalog;
      verify it builds standalone and that its dependency lockfile is generated.
- [ ] 1.2 Implement the check: report a constructor or method with more than seven
      parameters, with a message naming the count, the limit and the transformation to use
      (FR1, NFR-O1); verify the message reads usefully on its own, without the rule file.
- [ ] 1.3 Implement the two exemptions from the syntax tree (D3): never report a method
      whose owner is a record (FR2), never report an overriding or implementing method
      (FR3); verify each has its own functional-test case.
- [ ] 1.4 Write the functional test covering all five cases of NFR-R1 — eight parameters
      fails, seven passes, record passes, override passes, suppressed site passes —
      following `TestTimeInjectionCheckFunctionalSpec`'s shape; verify it runs under
      `build-logic`'s `functionalTest` suite and fails when the check is disabled (M2).
- [ ] 1.5 Decide the wiring question (Risks): confirm the check reaches a consuming
      module's `errorprone` processor path from the included build. If it does not, stop
      and take D1's recorded fallback, writing down the approximation limits accepted;
      verify the decision and its reason are in the task report either way.

## 2. The last ten signatures (FR6)

- [ ] 2.1 Introduce the shared agent-round head as one record (D6) and apply it to
      `ExecutorRoundExecution.run:49` and `JudgeRoundExecution.run:47`; verify both drop to
      five parameters and the agent adapter's specs pass unedited (NFR-R2).
- [ ] 2.2 Add the `Kept in sync with` marker to both ends of the newly declared
      executor/judge round pair, naming the shared head and the launch/failure invariant,
      and add the row to `.claude/rules/manual-sync-pairs.md`; verify
      `grep -rn "Kept in sync with" adapters/agent/src/main` returns both ends.
- [ ] 2.3 Bring the container environment builders under the limit —
      `ContainerEnvironments` ctor:106 and `forTask:64`,
      `ContainerTaskExecutionEnvironment` ctor:82, `ContainerMaterializer.create:68` and
      `reattach:38`, `ContainerEnvironmentBuilder.build:22`; verify each takes seven
      parameters or fewer and `:sandbox:docker:check` passes with no spec expectation
      edited.
- [ ] 2.4 Bring `GithubMarkerJson` ctor:62 and `PipelineModelBuilder.mapAndValidate:50`
      under the limit; verify `:adapters:github:check` and `:adapters:check` pass with no
      spec expectation edited.
- [ ] 2.5 Run the parameter-count scan over every module's `src/main` and verify the count
      is zero before the gate is wired (G1, NG5); record the scan output in the task report.

## 3. Switching the gate on

- [ ] 3.1 Wire the check into `java-conventions.gradle` so every module gets it with no
      per-module configuration (FR5), at error severity; verify a deliberately added
      eight-parameter method in one module fails `compileJava`, then remove it.
- [ ] 3.2 Run `./gradlew check` across every module with the gate on; verify it passes with
      zero suppressions in the repository (M1, M3) — `grep` the suppression form and record
      that the result is empty.
- [ ] 3.3 Measure clean-build wall time before and after and verify the difference is
      within noise (NFR-C1, M4); record both numbers.
- [ ] 3.4 Regenerate the affected dependency lockfiles and verify OSV-Scanner's inputs
      still resolve, per the locking convention in `java-conventions.gradle`.

## 4. Rules and closing the loop

- [ ] 4.1 Rewrite the parameter-count section of `.claude/rules/process-invariants.md`
      (FR7): the limit, the two exemptions and why each is by construction, the per-site
      suppression form with its "reason on the line" requirement, and the gate as the named
      enforcement — replacing the "a mechanical build gate should land together with..."
      sentence, which this change discharges.
- [ ] 4.2 Add D4's criterion to the same section: when a record earns the exemption
      (recurring group, domain name, absorbed behavior) and when it is an argument bag
      wearing it; verify the text names the three tests explicitly, since no gate can apply
      them.
- [ ] 4.3 Verify the whole family's outcome end to end: 63 violations at the start of
      `introduce-take-order`, 0 now, gate on, and record the final scan beside the
      2026-09-12 baseline.
- [ ] 4.4 Recommend a Conventional Commits subject line for the diff since the last commit
      (the agent never commits).
