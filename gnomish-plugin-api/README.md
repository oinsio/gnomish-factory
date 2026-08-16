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
| Config-validation SPI | `app.TrackerSubsectionValidator` — grades your adapter-owned `tracker.<type>` config subsection |
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
- **A check-side SPI factory.** `CheckClientFactory` does not exist yet; change B
  introduces it here together with its `provider()` discriminator.
- **Plugin discovery.** There is no `ServiceLoader` runtime and no `plugins/`
  folder in this change — adapters are still wired by the composition root.
  Discovery is change B.
- **`@DoNotMutate`.** A build-tooling marker, not contract.

## Versioning

Semver, versioned independently of the rest of the build (FR5). The promise
covers this module **plus the `:domain` types it exposes transitively** — an
incompatible change to either is an api-level break. `application` internals and
`domain` types the api does not expose may change without a version bump.

`japicmp` tracks the surface in **report-only** mode for now: the split is still
relocating types, so a hard gate would fight every move. Point it at a released
baseline with `-PapiBaselineVersion=<semver>`; without one it skips. Change B
flips it to a failing gate once the api is first consumed from outside.

## Writing an adapter

Implement `TrackerAdapterFactory` (and `TrackerSubsectionValidator` if your
adapter owns config keys), take a `SecretsProvider` for credentials, and return
your `Tracker`. `gnomish-plugin-api/sample/src/main/java/...` is a complete,
compiling skeleton of exactly that.
