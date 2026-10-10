# gnomish-plugin-api

The contract surface a third-party Gnomish Factory adapter is written against.
Everything in this artifact is an interface or a value type you may implement,
call, or construct. Nothing in it is a factory internal.

Implements FR4, FR5, UX3 of the `split-into-modules` change.

## Depending on it

One declared dependency — that is the contract (UX3):

```groovy
dependencies {
    implementation 'com.github.oinsio:gnomish-plugin-api:0.2.0'
}
```

The `domain` value types the ports are typed in (`TrackerConfig`, `ConfigError`,
`Finding`, `PollStatus`, `VerifyCheck`, …) arrive transitively — the module
exposes `:domain` as an `api` dependency, so you never declare it yourself. The
proof lives in `gnomish-plugin-api/sample`: a stand-in third-party adapter whose
build file declares this artifact and nothing else. If the surface ever stops
being self-sufficient, that module stops compiling and `check` goes red.

## What is in it

| Surface | Type |
|---|---|
| Tracker port | `app.port.tracker.Tracker` plus its DTO family (`TaskRef`, `TrackerTask`, `TaskSnapshot`, `TrackerTaskState`, `ClaimResult`, `ClaimVersion`, `HeartbeatResult`, `RemoveStaleClaimResult`, `ReadyTask`, `OpenTask`, `HumanReply`, `AbortFacts`, `AbortRecord`, `ParkReason`, `InstanceId`, `TrackerUnavailableException`) |
| Tracker SPI | `app.TrackerAdapterFactory` — constructs a live `Tracker` for one `tracker.type`, expands short refs, declares its credential env vars |
| Check SPI | `app.CheckClientFactory` — constructs an `ExternalCheckClient` for one `provider`, declares its credential env vars, contributes pin paths |
| Config-validation SPI | `app.TrackerSubsectionValidator`, `app.CheckSubsectionValidator` — grade your adapter-owned `tracker.<type>` / `factory.check.<provider>` config subsection; `app.CheckParamsValidator` grades a manifest check's `params` |
| SPI contexts | `app.TrackerAdapterContext`, `app.CheckClientContext` — everything the host hands a factory's one `create`: secrets, configuration, instance id and claim epochs (tracker) or run context (check), and the host's `TimeEquipment` |
| Run context | `app.CheckRunContext` — the closed set of run-scoped values (`task.id`, `task.branch`, `stage.name`) a check may interpolate |
| Secrets port | `app.port.secrets.SecretsProvider` — the only way an adapter reaches a credential (NFR-S1) |
| Check + workspace ports | `domain.engine.port.ExternalCheckClient`, `domain.engine.port.Workspace` — reached transitively through `:domain` |

`TrackerHealthTracker` ships alongside the port as a ready-made transparent
decorator; it is not something you implement.

## What is deliberately not in it

- **Implementations.** GitHub, the in-memory reference tracker, the git and
  agent-CLI adapters all live in their own modules.
- **`application` / `bootstrap` internals.** Use cases, the composition root and
  Spring wiring are not contract. A build check
  (`:gnomish-plugin-api:verifyModuleLayering`) fails if this module ever
  reaches a project outside `:domain` (M3).
- **Vendor internals.** The github plugin is built over a private HTTP core —
  client, rate-limit accounting, conditional-request cache, retry configuration
  — and none of it is here. A gate (`PluginApiSurfaceSpec`) fails the build if a
  vendor type ever reaches this module.
- **`@DoNotMutate`.** A build-tooling marker, not contract.

## Discovery and trust posture

Providers are found by `ServiceLoader`, one registry per port, keyed by
`type()` / `provider()`. Ship a `META-INF/services` entry naming your factory and
your provider becomes selectable — no core edit, no `plugins/` folder, no
registration call. The bundled github plugin travels that exact path: it is a jar
with the same two entries any third party would ship, and removing it disables
the github providers without touching a line of core source.

**A provider jar runs inside the factory process, with credential access.** There
is no classloader or OS isolation, so the posture is explicit: *only trusted —
first-party or operator-vetted — jars go on the classpath.* That is an operator
responsibility, not something the factory can enforce for you. What it does give
you in return is visibility: at startup it logs the discovered provider set of
every port, each entry with the artifact it came from, so an unexpected provider
is visible before any task runs. Signed jars and a managed marketplace are
non-goals for now. The sandbox port is deliberately not pluginized — a
self-declared capability passport from an untrusted jar would be a trust hole.

## Versioning

Semver, versioned independently of the rest of the build (FR5). The promise
covers this module **plus the `:domain` types it exposes transitively** — an
incompatible change to either is an api-level break. `application` internals and
`domain` types the api does not expose may change without a version bump.

Breaking releases so far — pre-1.0, a break is a MINOR bump:

| Version | Break |
|---|---|
| 0.2.0 | `AttemptRecord` and both `ExecutionResult` variants gained a `denials` component, replacing their old canonical constructors (fix-denial-report-attachment). |
| 0.3.0 | `ClaimResult.Acquired` and `ClaimVersion` gained the claim epoch, replacing their old canonical constructors (harden-task-branch-contract, FR13). |
| 0.4.0 | `Tracker` gained `repairIndex`; `removeStaleClaim` takes the observed `ClaimFacts`; `OpenTask` and `ReadyTask` gained their raw facts (harden-task-branch-contract, FR19). |
| 0.5.0 | `EscalationReport.CannotExecute` gained a `denials` component (fix-denial-attribution-durability). |
| 0.6.0 | `BranchShape.StaleEpoch`, `RecoveryDisposition.DISCARD` and the epoch components of `BranchTipFacts` were removed; `ClaimEpoch` is no longer `Comparable` (fix-claim-epoch-fence). |
| 0.8.0 | Tracker- and check-controlled free text is carried as `UntrustedText` rather than `String` (`TaskSnapshot`, `AbortRecord`, `Verdict.CannotVerify` and the records carrying the same text) (type-untrusted-text). |
| 0.9.0 | `PipelineValidator.validate` takes the configured check providers beside the model (remove-interactive-console). |
| 0.10.0 | `AttemptRecord` gained `stop`, `RoundOutcome.NeedsDecision` lost its question and options, `BranchTipFacts` gained the recorded position, and `Position` gained `AwaitingApproval` (make-checkpoint-gate-durable). |
| 0.11.0 | `TrackerAdapterFactory` and `CheckClientFactory` each have one `create`, taking a host-built `TrackerAdapterContext` / `CheckClientContext`; the overload chains are gone. The `:domain` `Clock` port gave way to `java.time.InstantSource` and real time travels as `TimeEquipment` (supervise-daemon-loops-and-embed-dashboard). |

0.7.0 was a non-breaking bump: no signature changed, but the `untrustedtext` and `operatorevent`
leaves entered the published jar graph (split-logtext-leaves).

`japicmp` guards the surface as a **failing gate**, wired into `check`: a
binary-incompatible change to this module — or to a `:domain` type it re-exposes
— breaks the build rather than landing in a report (FR14).

The baseline is **committed**, in `compat-baseline/`, because nothing is
published yet and a check that skips whenever its baseline is missing is not a
gate. Two jars live there: this module's, and the `:domain` one it re-exposes
through its `api` dependency. Third-party libraries are not compared — they are
not our contract, and their releases must not be able to fail our gate.

An **intended** surface change is accepted deliberately, the way an `apiDump`
workflow works:

```bash
./gradlew :gnomish-plugin-api:updateApiCompatibilityBaseline
```

The regenerated jars are committed alongside the version bump that justifies
them, so a reviewer sees both in one diff. Compatible additions need no
re-baselining — only the breaking subset fails. Once the api is published to a
repository, `-PapiBaselineVersion=<semver>` compares against that release
instead of the committed jars.

## Writing an adapter

Implement `TrackerAdapterFactory` (and `TrackerSubsectionValidator` if your
adapter owns config keys) and return your `Tracker` from its one
`create(TrackerAdapterContext)`. The context carries everything the host hands
you: the `SecretsProvider` for credentials, your validated config, the instance
id, the claim-epoch record, and the host's `TimeEquipment` — stamp and wait on
that, never on a clock of your own. A check provider does the same through
`CheckClientFactory.create(CheckClientContext)`. When the host gains a
collaborator to hand you, it becomes a new accessor on the context, never a
second `create`. `gnomish-plugin-api/sample/src/main/java/...` is a complete,
compiling skeleton of exactly that.
