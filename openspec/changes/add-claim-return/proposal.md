# Proposal: add-claim-return

## Why

A factory instance that gives a task back on purpose — the git remote was
unreachable before any branch existed, the daemon is shutting down on
SIGTERM, a usage error ended the invocation right after the claim — has no
way to say so to the tracker. It calls `release`, a verb designed for the
opposite situation (the claim was lost; a human may already have moved the
label), and `release` therefore moves nothing: the working label stays and
the task returns to `Ready` only when a reaper finds the claim stale, one
claim TTL later. On the GitHub adapter `release` is a documented no-op. The
operator guides and the take/serve result messages had been promising an
"instant return, no TTL wait" that never existed; `add-base-ref-resolution`
corrected the wording and pinned the real semantics with
`TrackerReleaseContract`. This change builds the verb the wording had
assumed.

Every mature lease or queue system offers both paths back to the queue —
an explicit, fenced, immediate return (Azure Service Bus `Abandon`, AMQP
`nack requeue`, SQS visibility 0, Kubernetes leader election
`ReleaseOnCancel`) and expiry as the safety net — and the systems that lack
the explicit path (Temporal activities after a graceful worker shutdown,
GitLab Runner job assignment) show exactly the stuck-until-timeout symptom
the factory has today. Kubernetes treated the missing release on shutdown
as a bug (kubernetes#119905), not a documentation fix.

## What Changes

- ADDED: a second claim-ending verb on the `Tracker` port, **`returnToReady`**:
  the holder gives the task back and the tracker moves it to `Ready` now — a
  boundary marker naming the returning holder and the reason, the claim
  marker removed, the working label flipped to ready — fenced by the
  holder's own claim identity so a stale holder's return is a successful,
  logged no-op, never a write against another instance's tenure. (FR1, FR2,
  FR3)
- ADDED: the return shares its physics with `removeStaleClaim` — one owner
  per adapter for "retire a claim and restore the queue", entered with a
  different marker kind — so no second implementation of the flip exists.
  (FR4)
- MODIFIED: the three deliberate give-back paths use the new verb instead of
  `release`: the pre-work base-refresh infrastructure failure (fresh claim
  and resume), the SIGTERM shutdown of in-flight slots, and the usage-error
  bail-out after a claim. (FR5, FR6, FR7)
- MODIFIED: the shutdown path distinguishes "the daemon is stopping" from "the
  claim was lost" by a typed cause, not by comparing reason strings; only the
  former returns. (FR6)
- UNCHANGED, made explicit: the revocation path keeps plain `release`. There
  a human may already have moved the label, and the no-op is the intended
  behavior — the case FR15 of add-tracker-port was written for. (NG1)
- ADDED: the port contract suite covers the verb on every adapter: return by
  the current holder is `Ready` within one call; return by a stale holder
  changes nothing; a repeated return is a no-op. (FR8)
- ADDED: a durable statement of the principle — two paths back to the queue,
  the explicit one fenced, expiry the safety net — as an ADR; glossary
  entries for *return* beside *release*; the operator guides' "instant"
  promise restored as a true statement. (FR9, UX1)

## Goals

- G1: a task given back deliberately is claimable by another instance within
  one tracker round trip, not one claim TTL.
- G2: no write of this verb ever lands on a claim this instance does not
  hold — the fence is the claim identity, checked by the adapter, on both
  shipped adapters.
- G3: the flip-to-ready physics has exactly one owner per adapter after this
  change; `removeStaleClaim` and `returnToReady` are two entries into it.
- G4: `release` keeps its FR15 meaning unchanged, and the revocation path
  keeps using it.

## Non-Goals

- NG1: changing what `release` does, or moving the revocation path off it.
  A lost claim is not returned; a human may already have acted.
- NG2: removing the reaper's TTL path or shortening the TTL. Expiry stays the
  safety net for every crash; the return only shortens the deliberate cases.
- NG3: a return that carries task state (a park reason, a report). A return
  says "nothing is wrong with the task"; anything wrong is a park or an abort.
- NG4: returning from inside a sandbox box or a gnome subprocess. Only the
  factory process that holds the claim returns it.
- NG5: Jira or any adapter beyond the two shipped ones and the plugin sample.

## Users & Scenarios

- U1: *Operator running `serve` against a flaky git remote.* A slot claims a
  task, the base refresh finds the remote unreachable, the gate opens. The
  task is `Ready` again before the slot's log line lands; when the remote
  returns, the next probe closes the gate and the task is claimed on the
  next feed cycle — not after a TTL that outlives the outage.
- U2: *Operator deploying a new daemon version.* SIGTERM stops two slots at
  their round boundaries within the grace window. Both tasks are `Ready` when
  the new daemon starts and it claims them immediately, as the serve guide
  has always said.
- U3: *Operator whose instance was reaped while frozen.* The zombie thaws and
  tries to give its task back; another instance holds it now. The return is a
  no-op logged at DEBUG, the live holder is untouched, no double work.
- U4: *Adapter author following the author guide.* Implements the verb as a
  second entry into the same claim-retirement routine `removeStaleClaim`
  uses, with the marker kind as the parameter, and passes the contract suite.

## Requirements

### Functional

- FR1: the `Tracker` port SHALL expose `returnToReady(ref, ownClaim,
  reason)`: given the task, the caller's own claim identity, and a short
  reason, the adapter SHALL as one operation record a structural boundary
  marker of a new kind ("claim returned", naming the returning holder and the
  reason), remove the caller's claim marker, and transition the task to
  `Ready`. It SHALL NOT claim for anyone.
- FR2: the operation SHALL be fenced by the caller's claim identity: the
  adapter re-reads the current claim before acting and proceeds only when it
  is the caller's own (same holder, same claim epoch). When it is not — the
  claim was reaped, taken over, or is already gone — the operation SHALL be a
  safe no-op reporting the current facts, never an error and never a write.
- FR3: a repeated return of the same task by the same holder SHALL be a
  no-op; two concurrent returns (or a return racing a reaper's removal)
  SHALL converge to one `Ready` task with one boundary marker.
- FR4: in every adapter the return and `removeStaleClaim` SHALL share one
  claim-retirement routine: re-read and compare, write the boundary marker,
  remove the claim marker, flip the labels. The marker kind and the
  comparison key (own identity versus observed stale footprint) are the only
  parameters; a second copy of the flip is a defect.
- FR5: the base-refresh infrastructure failure — fresh claim and resume, host
  and container — SHALL return the task with reason "base refresh: origin
  unreachable" instead of releasing it; the `InfrastructureUnavailable`
  result and the outage gate behavior are unchanged.
- FR6: the SIGTERM shutdown SHALL return the tasks of slots that reach their
  round boundary within grace, with reason "daemon shutting down". The
  claim-loss hand-off SHALL carry a typed cause (shutdown | lost) so the
  round-boundary handler picks the verb by type; a genuine loss keeps the
  revocation protocol and plain `release` unchanged.
- FR7: the usage-error bail-out right after a claim SHALL return the task
  with the usage error's summary as the reason.
- FR8: the port contract suite SHALL cover the verb on every adapter: return
  by the current holder yields `Ready` and a fresh claim by another instance
  succeeds; return by a stale holder leaves the live claim and the state
  untouched; a repeated return is a no-op; a return racing a reaper's
  removal converges.
- FR9: the reaper's classification SHALL be unchanged: after a return the
  task classifies `Ready` (the boundary marker is a return, and a return ends
  the tenure it names — the same rule the stale-claim-removed marker
  follows). No new shape and no new recovery owner.

### Non-Functional — Reliability

- NFR-R1: a return that fails on the tracker (network, 5xx after retries) is
  best-effort at the call site, logged as its own coded event, and leaves
  the task on the TTL path — the reaper converges it exactly as before. The
  return is never the only path back.
- NFR-R2: kill windows of the return sequence (marker written, claim marker
  removed, label flipped) classify to shapes the `claim-heartbeat` capability
  already owns (`IndexLagging`, `ClaimAbandoned`), each converged by the
  reaper; the sequence SHALL join the kill-point matrix.
- NFR-R3: the shutdown return is bounded by the same grace window as the
  round boundary it follows; a tracker that does not answer never delays the
  process-tree kill or the exit.

### Non-Functional — Observability

- NFR-O1: each return logs one INFO line with an operator-event code naming
  the task, the reason, and the holder; a fenced no-op logs one DEBUG line
  naming what the adapter found instead; a failed return logs one ERROR with
  its own code. No line quotes tracker-sourced text unsanitized.
- NFR-O2: the boundary marker's human-readable line reads as "returned to
  ready by <instance>: <reason>", so a human reading the issue thread sees
  why the task came back.

### Non-Functional — Security

- NFR-S1: the reason text written to the tracker is factory-authored only —
  a fixed phrase or the sanitized usage-error summary — never text sourced
  from the tracker, the branch, or a gnome.

## Operator Experience Criteria

- UX1: the serve guide's SIGTERM sequence again says "return → Ready
  (immediate)", and it is true on GitHub; the outage section says the task
  is claimable again as soon as the gate closes.
- UX2: the `InfrastructureUnavailable` result message reads "Task X returned
  to Ready: origin did not answer …", replacing the interim "claim released
  (the reaper returns it …)" wording.
- UX3: an operator reading the issue thread sees one "returned to ready"
  comment per deliberate give-back, and no "work stopped" note for a
  shutdown that returned cleanly.

## Success Metrics

- M1: in the serve outage end-to-end spec, the released task is claimable on
  the first feed cycle after the gate closes with no stand-in for the reaper
  (`harness.returnToReady` removed from the scenario).
- M2: the shutdown lifecycle spec asserts `Ready` for every drained slot
  immediately after `ServeShutdown` completes, on the in-memory tracker and
  on the GitHub adapter through WireMock.
- M3: `grep -rn "\.release(" application/src/main` lists exactly one
  caller: the revocation handler.
- M4: PIT stays at 100% in every touched module; the contract suite's new
  properties pass on both adapters and the plugin sample.

## Capabilities

### New Capabilities

- none

### Modified Capabilities

- `tracker-port`: a new port operation `returnToReady` (ADDED requirement),
  its fence and convergence rules, and the contract suite's coverage of it
  (FR1–FR4, FR8).
- `github-tracker`: the return's physics on GitHub — marker kind, comment
  removal, label flip — as a second entry into the stale-claim-removal
  routine (FR1, FR4).
- `lifecycle/claim-heartbeat`: the "claim returned" boundary marker joins the
  markers that end a tenure, so a returned task classifies `Ready` (FR9);
  the zombie-fencing rule names the fenced return beside the unfenced writes.
- `tracker-take`: the base-refresh infrastructure failure returns instead of
  releasing — layered on `add-base-ref-resolution`'s delta of that
  requirement, which syncs first; the usage-error bail-out returns (FR5,
  FR7); revocation is unchanged (NG1).
- `factory-serve`: the SIGTERM sequence returns the drained slots' tasks
  through the fenced verb, restoring the "immediate return" as a true
  statement (FR6, NFR-R3, UX1).

## Impact

- **Sequencing (noted 2026-09-13)**: the parameter-limit family
  (`introduce-take-order`, then `introduce-slot-wiring`) rewrites the take-chain
  signatures this change edits — `TakeClaimAndWork`, `FreshClaimBaseBinding`'s
  callers, the fresh-claim and resume pairs. This change is rebased onto the new
  signatures after `introduce-take-order` lands (its task 6.2), which is a
  rename of call sites, not a change of this proposal's scope: the release-call
  boundary spec and the `ClaimIdentity` port type are untouched by the refactor.
- `gnomish-plugin-api`: `Tracker` gains one method and one result type; the
  `TrackerHealthTracker` decorator and the plugin `SampleTracker` implement
  it; the adapter author guide's port table gains a row.
- `adapters` (in-memory): `ClaimLeases`/`InMemoryLeaseOps` grow the shared
  retirement routine; `CorrespondenceEntry.Kind` gains `CLAIM_RETURNED`.
- `adapters/github`: `GithubStaleClaimRemoval` becomes the shared routine
  with the marker kind as a parameter; `GithubMarkerKind` gains
  `CLAIM_RETURNED`; the marker reader recognizes it as a boundary.
- `application`: `FreshClaimBaseBinding`, `ResumeLawBinding`,
  `TakeClaimAndWork`, `ServeShutdown`/`ClaimLossFlag`/
  `RevocationCheckingAttemptPersistence`/`TakeEngineExecution` (typed loss
  cause), `EpochRecordingTracker` decorator; operator-event codes for the
  three log lines.
- `test-fixtures`: `TrackerReturnContract` appended to the contract chain;
  kill-point row for the return sequence in `bootstrap`.
- Docs: `docs/adr/0008-two-paths-back-to-the-queue.md`, glossary entries
  *return* / *release*, `operator-guide-serve.md`, `adapter-author-guide.md`.
- Sequencing: proposed while `add-base-ref-resolution` is active and touches
  the same two binding classes; applied only after that change archives.

## Open Questions

- Q1: should a fenced no-op (the zombie's return found another holder) be
  surfaced to the operator at all beyond DEBUG? Today's proposal says no —
  it is the mechanism working — but the dashboard's outage card could count
  them.
