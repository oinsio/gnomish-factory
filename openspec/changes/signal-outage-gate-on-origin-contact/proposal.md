# Proposal: signal-outage-gate-on-origin-contact

Sequenced after `add-base-ref-resolution`: both delta specs below are layered
on that change's deltas, not on `openspec/specs/`.

## Why

The remote outage gate (`add-base-ref-resolution` FR14, design D9) resets its
probe interval to the idle floor only after the first successful base refresh
that follows a close — that is what makes a flapping remote (probe answers,
fetch fails) meet a growing pause instead of a claim-and-release cycle. Since
the 2026-09-11 revision (task 7.7 of `add-base-ref-resolution`) the signal is
emitted at the base read itself, by a decoration of the slot's `BaseRefGit`,
and it is derived from the outcome's success arm: `Refreshed` for a fresh
claim, `Bound` for a resume.

The success arm answers "is the base bound", not "did origin answer". Two
adapter paths return it with no network round trip at all: a base pinned to a
commit SHA the clone already holds (`CommitBaseFetch` reads the object locally
and fetches only when absent), and a resume in a clone with no `origin` remote
(`ResumeBaseResolution` binds from the local tip). The decoration reads both
as a successful refresh, so a SHA-pinned task claimed right after a close
resets the interval although origin was never contacted, and the next real
fetch failure reopens the gate at the idle interval instead of the grown one.
The port does not carry the fact the gate needs, so no consumer can be
correct; the decoration's javadoc records this as an accepted imprecision.
This change closes it by making the port say whether origin was contacted.

## What Changes

- **MODIFIED** `git-task-persistence` "Base refresh fetch before task
  creation": a successful refresh outcome SHALL record whether origin was
  contacted, as a typed fact set by the adapter at the point that knows —
  a fetch that delivered the ref or the object contacted origin; a commit
  already held by the clone did not. (FR1)
- **MODIFIED** `factory-serve` "Remote outage gate holds the feed off the
  tracker": the "successful base refresh" that resets the probe interval,
  and that records the remote's last successful contact, SHALL be one that
  contacted origin; a success served from the clone alone is not a signal.
  (FR2, FR3)
- The resume-time success outcome carries the same fact: fetched from a
  configured origin is a contact, bound from the local tip of a clone with no
  origin is not. (FR1)
- No change to which outcomes open or close the gate, to how a claim parks or
  releases, or to the trusted-tier startup read. (FR4)

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `git-task-persistence`: the refresh outcome's success arm records whether
  origin was contacted (layered on `add-base-ref-resolution`'s delta).
- `factory-serve`: the gate's interval-reset and last-contact signal fires
  only on a refresh that contacted origin (layered on
  `add-base-ref-resolution`'s delta).

## Goals

- G1: the probe interval never resets, and the remote's last successful
  contact is never advanced, on a base read that did not contact origin.
- G2: every decision a refresh outcome already drives — bind, park, release,
  trusted-tier startup — is unchanged; the fact is read by the gate's
  decoration alone.

## Non-Goals

- NG1: changing when the gate opens or closes. Opening stays on the
  `Unavailable` arm, closing stays on the probe.
- NG2: treating a `Refused` outcome as a contact. Origin answered, but no
  base was refreshed, and FR14 is worded on refreshes; the last-contact
  timestamp stays a refresh-or-probe fact.
- NG3: the manual `gnomish run` paths, which read no remote and signal no
  gate.
- NG4: the startup default-branch discovery, which precedes the gate.
- NG5: adding a network call to learn the fact. The adapter already knows
  which path it took.

## Users & Scenarios

- U1: an operator runs `serve` against a remote that answers `ls-remote` but
  intermittently fails fetches, with some tasks pinned to commit SHAs the
  clone already holds. After an outage closes, one of those tasks is claimed
  first. The probe interval keeps its grown value until a real fetch
  succeeds, so the next flap is met by the longer pause FR14 promises.
- U2: an operator reads the `remote` snapshot section after an outage. Its
  last-successful-contact time names a moment origin actually answered, not
  a moment the clone served a base from its own object store.

## Requirements

### Functional

- FR1: the base-ref port's success outcomes (`BaseRefreshOutcome.Refreshed`,
  `ResumeBaseOutcome.Bound`) SHALL carry a typed origin-contact fact with
  exactly two values: origin was contacted, or the answer came from the clone
  alone. The git adapter SHALL set it at the site that knows the path taken:
  a branch or tag narrow fetch and a fetch-by-SHA are contacts; a commit
  object already present in the clone and a local-tip resume bind in a clone
  with no origin are not. A remote-backed resume carries the value of the
  refresh it delegated to.
- FR2: the remote outage gate's successful-refresh signal SHALL fire only for
  a success outcome whose fact says origin was contacted; a success served
  from the clone alone SHALL neither reset the probe interval nor consume the
  pending reset.
- FR3: the remote's last-successful-contact time in the gate's health SHALL
  advance only on a contacted success or a successful probe.
- FR4: every other consumer of a success outcome (fresh-claim binding, resume
  binding, trusted-tier startup) SHALL be unaffected: same commit, same pin,
  same park and release decisions.

### Non-Functional Reliability

- NFR-R1: no additional subprocess or network call is introduced to learn
  the fact; it is a property of the path the adapter already took. The
  bounded infrastructure retry and the mutation lock are unchanged.

### Non-Functional Observability

- NFR-O1: no new log line, operator-event code, ledger line, or snapshot
  field. The existing `remote.lastSuccessAt` becomes accurate; its shape and
  the snapshot version are unchanged.

### Non-Functional Security

- NFR-S1: the fact is an enum, not text; it adds no untrusted-text carrier
  and no new sink. The `Refused`/`Unavailable` reason fields, which
  `type-untrusted-text` retypes, are untouched.

## Operator Experience Criteria

- UX1: nothing to configure and nothing new to read. The operator only sees
  a pause that is now as long as FR14 said it would be, and a last-contact
  time that means what its name says.

## Success Metrics

- M1: a data-driven spec over the decoration shows, for both success arms,
  that a contacted success resets the pending interval and a clone-served
  success leaves it armed; the pre-change behaviour of the clone-served row
  is red first.
- M2: adapter specs against a local bare origin show the fact set correctly
  on all five construction sites: present commit (not contacted), fetched
  commit (contacted), branch fetch (contacted), tag fetch (contacted),
  no-origin resume bind (not contacted), remote-backed resume (pass-through).
- M3: `:application:check`, `:adapters:git:check` and the `:bootstrap` slot
  specs stay green with 100% of mutations killed; every pre-existing spec
  passes without an assertion changed, only construction sites updated.

## Open Questions

- Q1: none open. The only judgement call — whether a `Refused` after a real
  answer should advance the last-contact time — is recorded as NG2, and can
  be reopened by a later change if the snapshot's readers want it.

## Impact

- `:application` port: `BaseRefreshOutcome.Refreshed`, `ResumeBaseOutcome.Bound`
  gain one typed component; a new enum beside them in
  `app.port.git`. Consumers that pattern-match the records
  (`FreshClaimBaseBinding`, `ResumeLawBinding`, `TrustedTierStartup`,
  `ResumeBaseResolution`) add an ignored binding.
- `:adapters:git`: `CommitBaseFetch` (two sites), `RefreshedTip` (one),
  `ResumeBaseResolution` (two) set the value.
- `:application` serve: `RemoteOutageSignalingBaseRefGit` reads it; its
  "known imprecision" javadoc paragraph goes away.
- Test construction sites of the two records: 13 spec and fixture files
  across `:application`, `:adapters:git`, `:bootstrap` (listed in tasks.md).
- Docs: the glossary entry "Remote outage gate" and the gate section of
  `docs/adr/0005-dependency-outage-accounting.md` gain the contact rule.
- No new dependency, no config, no wire format: the fact is not persisted.
