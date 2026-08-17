# gnomish-plugin-api

The contract surface a third-party Gnomish Factory adapter is written against.
Everything in this artifact is an interface or a value type you may implement,
call, or construct. Nothing in it is a factory internal.

Implements FR4, FR5, UX3 of the `split-into-modules` change.

## Depending on it

One declared dependency — that is the contract (UX3):

```groovy
dependencies {
    implementation 'com.github.oinsio:gnomish-plugin-api:0.1.0'
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
adapter owns config keys), take a `SecretsProvider` for credentials, and return
your `Tracker`. `gnomish-plugin-api/sample/src/main/java/...` is a complete,
compiling skeleton of exactly that.
