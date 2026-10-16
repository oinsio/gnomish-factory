# Proposal: retire three requirements whose scenario titles are wrong

## Why

OpenSpec 1.14.1 cannot rename or drop a scenario. `MODIFIED` fails at archive when the block lacks
a scenario of the current requirement, `REMOVED` plus `ADDED` of one header in one change is a
section conflict, and `RENAMED` changes only the requirement header. A scenario therefore leaves a
spec only together with its requirement: one change removes the requirement, the next adds it back
under the same header (`delta-specs.md`, "A scenario cannot be renamed or dropped through
`MODIFIED`").

Three scenario titles found while tightening the spec contract are wrong (F1, F39, F28 of
`tighten-spec-contract`): one is shared by two scenarios of one capability, two name a thing the
scenario no longer describes. The audience is every reader and every test marker that cites the
title; the urgency is that other pending changes will modify the same requirements, and each day
a title stays wrong adds one more citation to rename.

This change is the first half of a pair. **It must be archived together with
`restore-renamed-scenarios`, immediately before it, in one session**; archived alone it leaves the
three requirements missing from `main`.

## What Changes

- **REMOVED** — `Contract suite covers the returned fact` from `tracker/tracker-port`.
- **REMOVED** — `Pin-check guards external checks` from `verification/verification-hardening`.
- **REMOVED** — `Enforced acyclic dependency direction` from `build-system/module-layering`.

No code, test or build file changes in this change; the markers follow in the second one.

## Goals

- G1: the three requirements are absent from `openspec/specs/` after this change archives, and
  present again, verbatim apart from one scenario title each, after the second one archives.

## Non-Goals

- NG1: changing any requirement text, header, `**Implements**:` line or any other scenario.
- NG2: renaming the candidates F58, F60 and F64; their titles go stale only when other pending
  changes archive, so they wait for a later pair.
- NG3: archiving this change without its partner.

## Users & Scenarios

- U1: a maintainer archives the pair back to back and `main` ends with three correctly titled
  scenarios and a green `checkSpecContract`.

## Requirements

### Functional

- FR1: the delta removes the three requirements, each under its existing header.

### Non-Functional Reliability

- NFR-R1: the pair is verified in a scratch worktree before the real archive; the three
  resulting `spec.md` files differ from the originals in exactly three scenario titles.

## Operator Experience Criteria

- UX1: none; no operator-visible behavior changes.

## Success Metrics

- M1: after both archives, `openspec validate --specs --strict` and `./gradlew checkSpecContract`
  exit 0.
- M2: `grep -c "Suite passes on both adapters" openspec/specs/tracker/tracker-port/spec.md`
  prints 1.

## Open Questions

- None.

## Impact

`openspec/specs/tracker/tracker-port`, `verification/verification-hardening`,
`build-system/module-layering`; no module is touched.
