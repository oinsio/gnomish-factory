# Rule: manually synchronized pairs

Applies whenever one logical rule, contract, or vocabulary is implemented in
two (or more) places that must be kept in step by hand — with no shared
abstraction the compiler could enforce. Typical shapes in this codebase:

- **Execution-medium twins**: a host-mode and a container-mode implementation
  of the same rule (e.g. salvage, round-boundary check, attempt persistence).
- **Wire vocabulary pairs**: a JSON writer and its reader in decoupled
  modules, each holding its own string table for the same enum.
- **Mirrored validation**: a validator that must accept exactly what a
  resolver elsewhere resolves.

Pairs are not banned — some are deliberate layer decoupling, others are
honest debt from a second medium arriving after the first. What is banned is
an **undeclared** pair: synchronization that exists only in the author's
memory is where divergence bugs are born.

## Preference order

1. **Shared abstraction** — one implementation, two strategies
   (`ResumeMechanics<B>` is the in-repo model). Required at the latest when a
   *third* implementation of the same rule appears (rule of three).
2. **Declared pair** — both ends carry the marker (below). Acceptable when
   the abstraction is premature or the duplication is deliberate decoupling.
3. **Undeclared pair** — not acceptable. An audit finding.

## Declaration marker

Every end of a pair carries, in its class-level javadoc, the exact phrase
`Kept in sync with` followed by a `{@link}` to the other end, plus one line
stating **what** must stay in sync (the invariant, not the file):

```java
/**
 * ...
 * <p>Kept in sync with {@link WorktreeSalvage}: both must produce a salvage
 * commit carrying the claim-epoch trailer and restore factory-owned paths.
 */
```

The phrase is the greppable contract: `grep -rn "Kept in sync with" src/main`
must enumerate every manual synchronization in the codebase. Both ends carry
the marker — a one-directional declaration is a finding.

## Audit obligations (enforced by `/audit-implementation`)

- A change touching one end of a declared pair must either change the other
  end or state in its artifacts why no mirrored change is needed.
- A change adding a second implementation of a rule that already exists in
  another mode/layer must either extract the shared abstraction or declare
  the pair at both ends in the same change.
- A change adding a *third* implementation must extract the abstraction.

## Initial registry

Known pairs predating this rule. Remove a row once both ends carry the
marker, or once the pair is collapsed into an abstraction. Until removed,
audits treat these rows as if the markers were present.

| End A                                            | End B                                                            | Synchronized invariant                                                 |
|--------------------------------------------------|------------------------------------------------------------------|------------------------------------------------------------------------|
| `adapters/git/.../GitAttemptPersistence`         | `adapters/git/.../EnvironmentAttemptPersistence`                 | attempt commit + state-file write sequence. The tip-resolution half of it is no longer hand-synced: both media resolve through `VerifiedTip` (`harden-logging-observability` FR13), so a blank or failed resolution refuses on both by construction. |
| `adapters/git/.../GitTaskRepository`             | `adapters/git/.../GitObjectsTaskRepository`                      | task lifecycle write protocol                                          |
| `adapters/agent/.../DecisionFileTransport`       | `adapters/git/.../BranchDecisionFile`                            | `GNOMISH_DECISION_FILE` env var name, read semantics, size cap         |
| `adapters/agent/.../RoundTimeout`                | `adapters/.../pipeline/AgentSettingsValidator`                   | accepted `roundTimeout` shapes                                         |
| `application/.../app/TakeFreshClaim`             | `application/.../app/TakeContainerFreshClaim`                    | fresh-claim recipe (harden → synthesize → createTask → run)            |
| `application/.../app/TakeEngineExecution`        | `application/.../app/TakeContainerEngineExecution`               | engine execution wiring per mode                                       |
| `application/.../app/TakeResumeRunner`           | `application/.../app/TakeContainerResumeRunner`                  | resume control flow per mode                                           |
| `application/.../app/GitModeRunner`              | `application/.../app/ContainerGitModeRunner`                     | manual-run control flow per mode                                       |
| `application/.../app/GitResumeRunner`            | `application/.../app/ContainerResumeRunner`                      | manual-resume control flow per mode                                    |
| `adapters/agent/.../HostRoundEnvironmentSource`  | `adapters/git/.../SandboxRoundEnvironmentSource`                 | round environment contract per mode                                    |
| `serveobservability/json/LedgerJsonMapper`       | `dashboard/LedgerAggregator` + `dashboard/SweepActionAggregator` | ledger wire tokens (`TaskOutcome`, `SweepVerdictCategory`)             |
| `serveobservability/json/SnapshotJsonMapper`     | `serveobservability/json/SnapshotJsonReader`                     | snapshot wire tokens (`FeedPhase`, `HeartbeatState`, `LifecycleState`) |
| `application/.../app/serve/FeedState`            | `serveobservability/FeedPhase`                                   | deliberate layer decoupling: constant sets must match                  |
| `application/.../app/serve/HeartbeatWorkerState` | `serveobservability/HeartbeatState`                              | deliberate layer decoupling: constant sets must match                  |

## Declared pairs with no shared classpath

Both ends carry the marker, so the rule above would have them leave the
registry — but neither end can name the other with a resolvable `{@link}`,
because the two deliberately share no compile edge. The registry is their only
navigational index, so these rows stay listed for as long as the pair does.

| End A                        | End B                                               | Synchronized invariant                                                                    |
|------------------------------|-----------------------------------------------------|-------------------------------------------------------------------------------------------|
| `logtext/.../logtext/LogText` | `gnomish-plugin-api/.../findings/FindingsSanitizer` | ANSI/control stripping table + tail-cap semantics; newline handling deliberately differs. Verified by `SanitizerPairEquivalenceSpec` over a shared adversarial corpus. |
| `logtext/.../logtext/OperatorEvent` | `domain/.../engine/{AttemptJournal,Events,RoundExecution,VerifyOrchestrator}` | the four operator-event codes the domain emitters repeat as literal `[GFnnn]` message heads; `:domain` takes no `:logtext` edge (ADR 0004, accepted deviation 1). Verified by `DomainOperatorEventHeadSpec`, in both directions. |
