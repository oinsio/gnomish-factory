# Proposal: restore three requirements with corrected scenario titles

## Why

Second half of the pair started by `retire-misnamed-scenarios`: OpenSpec cannot rename a scenario,
so the first change removes three requirements and this one adds them back under the same headers
with the corrected scenario title (F1, F39, F28 of `tighten-spec-contract`). It also carries the
test-marker renames and the re-pointing of the pending change that cites one of the old titles,
because those must land in the same commit as the archive. **It must be archived immediately after
`retire-misnamed-scenarios`, in the same session.**

Sequenced after `retire-misnamed-scenarios`.

## What Changes

- **ADDED** — `Contract suite covers the returned fact` (`tracker/tracker-port`); the second
  `Suite passes on both adapters` becomes `Returned-fact suite passes on both adapters`. The
  scenario of `Contract suite covers lease maintenance` keeps its title.
- **ADDED** — `Pin-check guards external checks` (`verification/verification-hardening`);
  `Interactive client with nothing declared passes the pin` becomes
  `A check with no declared pin paths passes the pin vacuously`.
- **ADDED** — `Enforced acyclic dependency direction` (`build-system/module-layering`);
  `The baseref leaf stays empty of dependencies` becomes
  `The baseref leaf depends only on the untrustedtext leaf`.
- Test markers citing the old titles are renamed; the `own-git-invocation-policy` delta, which
  modifies `Enforced acyclic dependency direction`, carries the new title.

## Goals

- G1: each requirement is restored verbatim — header, text, `**Implements**:`, `**Notes**:`,
  every scenario — except one scenario title.
- G2: no marker, delta or spec cites an old title afterwards.

## Non-Goals

- NG1: any change to requirement text, headers or other scenarios.
- NG2: the stale-title candidates F58, F60, F64; each waits for the change that makes its title
  wrong (`add-pipeline-entry-precondition`, `add-claim-return`, `add-sandbox-cloud-executor`).
- NG3: archiving without `retire-misnamed-scenarios` first.

## Users & Scenarios

- U1: a maintainer archives both changes back to back; `checkSpecContract` is green again.
- U2: the author of `own-git-invocation-policy` archives it later without hitting the
  "current spec contains scenario(s) not present in the modified block" error.

## Requirements

### Functional

- FR1: the three requirements are added back with exactly one scenario title changed each.
- FR2: the three test markers naming an old title (and the second marker of the F1 pair) name the
  new one; the lease-maintenance marker keeps the old.
- FR3: the `own-git-invocation-policy` MODIFIED block for `Enforced acyclic dependency direction`
  uses the new title.

### Non-Functional Reliability

- NFR-R1: both archives are rehearsed in a scratch worktree; the three spec files differ from
  the originals in exactly three titles.

## Operator Experience Criteria

- UX1: none.

## Success Metrics

- M1: `openspec validate --specs --strict` and `./gradlew checkSpecContract` exit 0 after both
  archives.
- M2: a grep for each old title over `openspec/specs`, `openspec/changes` (excluding archive) and
  all test sources finds 0 hits, except the single lease-maintenance scenario and its marker.

## Open Questions

- None.

## Impact

Three `spec.md` files under `openspec/specs/`; marker comments in
`GithubTrackerContractSpec`, `InMemoryTrackerContractSpec`, `PinCheckedExternalCheckClientSpec`,
`HttpCheckClientFactorySpec`, `ModuleBuildFileSpec`; the delta of `own-git-invocation-policy`.
