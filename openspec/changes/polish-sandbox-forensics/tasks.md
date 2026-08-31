# Tasks: polish-sandbox-forensics

## 1. OOM annotation on exit 137 (FR1, NFR-R1, NFR-O1, UX2 — design D1)

- [ ] 1.1 Extend `DockerCommands.inspectContainerState` to
  `{{.State.Running}} {{.State.FinishedAt}} {{.State.OOMKilled}}` and verify by spec that
  the argv carries the new field and that
  `ContainerTaskExecutionEnvironment.materialize`'s reattach branch still recognizes a
  running container from the extended output (FR1)
- [ ] 1.2 Wrap the `ExecHandle` returned by the container adapter's `exec()` so an
  observed exit code 137 triggers a best-effort inspect of `OOMKilled` and one WARN naming
  the container with "likely container OOM" when it is `true`; verify by Spock spec over a
  fake docker seam: annotation logged on `true`, absent on `false`, absent and non-failing
  when the inspect fails, and exit code / `Wait` outcome unchanged in all three cases
  (FR1, NFR-R1, NFR-O1, UX2)
- [ ] 1.3 Update `Supervision`'s exit-137 javadoc note to mention that the container
  adapter annotates a likely OOM, and verify `./gradlew :subprocess:check` stays green
  (FR1 — documentation touch point cited in the proposal)

## 2. Container names in operator-facing failures (FR2, NFR-S1, UX1 — design D2)

- [ ] 2.1 Embed `FactoryDockerLabels.containerName(key)` in
  `ContainerMaterializer.management` failure messages and verify by spec that a failed
  create/run surfaces the `gnomish-box-<key>` name in the thrown message (FR2, UX1)
- [ ] 2.2 Embed `FactoryDockerLabels.guardName(key)` in the `EgressGuard`
  `GuardUnavailableException` messages and verify by spec that a guard that cannot start
  names `gnomish-guard-<key>` in the thrown message (FR2, UX1)
- [ ] 2.3 Log one INFO from `ContainerEnvironmentKeeper.stopKeeping` naming the kept
  container, and verify by spec that the successful keep path emits it while the
  best-effort failure path still swallows (FR2, NFR-O1, UX1)
- [ ] 2.4 Verify by review of the touched messages that only object names and runtime
  metadata appear — no environment values or credentials (NFR-S1)

## 3. Keep box on failed self-check (FR3, NFR-R1, NFR-R2, NFR-C1, UX3 — design D3, D5)

- [ ] 3.1 In `SelfCheckedEnvironment.materialize`, catch `SelfCheckFailedException`, stop
  the box via `ContainerEnvironmentKeeper` best-effort, log the kept container's name, and
  rethrow the original exception; verify by spec: box stopped and exception propagated on
  a failed probe, original exception still propagated (and logged) when the stop itself
  throws, no stop on a successful self-check (FR3, NFR-R1, UX3)
- [ ] 3.2 In `SandboxCheckEnvironmentSource`, skip the dispose-on-materialize-failure when
  the cause is a `SelfCheckFailedException` (still wrapping into
  `CheckEnvironmentUnavailableException`); verify by spec: self-check failure keeps the
  fresh box undisposed, any other materialize failure still disposes (FR3)
- [ ] 3.3 In `FreshJudgeEnvironments`, ensure a materialize failed on
  `SelfCheckFailedException` does not lead to the box's disposal; verify by spec parallel
  to 3.2 (FR3)
- [ ] 3.4 Add a spec asserting the kept-on-failed-self-check box is enumerated by the
  sweep universe: a container created through the adapter's own create commands carries
  the ownership labels at creation, so `SandboxLifecycleClassification` classifies it with
  its role and key — no self-check-specific sweep logic exists (NFR-R2, NFR-C1, M3)

## 4. Verification and closure

- [ ] 4.1 Run `./gradlew :sandbox:docker:check :adapters:check` (Spock + PIT per module)
  and verify green with the mutation gate intact for all touched classes (M1, M2)
- [ ] 4.2 Grep-verify traceability: every FR/NFR/UX of the proposal is referenced by at
  least one spec, code javadoc, or test touched by this change (`traceability.md`
  verification rule)
- [ ] 4.3 Run `openspec validate polish-sandbox-forensics --strict` and verify it passes
