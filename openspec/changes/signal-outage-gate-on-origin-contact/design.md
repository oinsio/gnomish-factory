# Design: signal-outage-gate-on-origin-contact

## Context

See proposal.md — Why. The relevant current state:

- The base-ref port (`BaseRefGit`, `:application` `app.port.git`) returns
  sealed outcomes: `BaseRefreshOutcome {Refreshed, Refused, Unavailable}` for a
  fresh claim and the trusted-tier startup read, `ResumeBaseOutcome {Bound,
  Refused, Unavailable}` for a resume. The success records carry
  `(ref, commit[, kind])` and nothing about how the answer was obtained.
- The git adapter produces the success arm at five sites: `CommitBaseFetch`
  (object present → no network; object absent → fetch-by-SHA),
  `RefreshedTip` (branch and tag narrow fetch), `ResumeBaseResolution`
  (no-origin clone → local `rev-parse`; otherwise delegates to the refresh
  and re-wraps its outcome).
- The gate's slot-side signals come from `RemoteOutageSignalingBaseRefGit`
  (`add-base-ref-resolution` task 7.7): it switches on the outcome arm and
  calls `RemoteOutageGate.onSuccessfulRefresh()` on either success arm. The
  gate ignores that call while open (its own guard) but honours it while
  closed, which is where a clone-served success does damage (FR2).
- `type-untrusted-text` (active) retypes the `Refused.report` and
  `Unavailable.reason` fields of both outcomes. It does not touch the
  success arms, so the two changes edit different records' components; the
  sequencing between them is free.

Driven by FR1–FR4, NFR-R1, NFR-O1 of proposal.md.

## Goals / Non-Goals

**Goals:**
- The fact travels inside the outcome the adapter already returns, set at
  the one place per path that knows it, and read by exactly one consumer.
- A consumer that does not care compiles with a one-token change and no
  behaviour change.

**Non-Goals:**
- Reworking the outcome hierarchy (merging the two sealed types, adding
  arms). The proposal's NG1/NG2 stand.
- Persisting the fact anywhere (task.json, ledger, snapshot).

## Decisions

**D1 — A typed two-valued fact as a record component, on both success
records.** A new enum `OriginContact { CONTACTED, CLONE_ONLY }` in
`app.port.git`, added as the last component of
`BaseRefreshOutcome.Refreshed` and `ResumeBaseOutcome.Bound`. *Rationale:*
the single-owner rule (design-decisions.md) says a primitive a consumer could
obtain elsewhere is not enforced; a `boolean` reads as "true of what?" at
every construction site, an enum reads as the fact. A component rather than a
separate arm keeps the sealed switch in `FreshClaimBaseBinding`,
`ResumeLawBinding`, `TrustedTierStartup` and `ResumeBaseResolution` at one
success case each — they add `var _` and change nothing else (FR4). The
records have no convenience constructor, so every construction site is
forced to state the value: the compiler, not a review, finds the site that
forgot (FR1).
*Alternative rejected — a new arm `HeldLocally`/`BoundLocally`:* every
sealed switch over either type gains a case with a body identical to the
success case, in four production files and a dozen specs, for a distinction
only one consumer draws. *Alternative rejected — the decoration probes
origin itself on a success:* an extra `ls-remote` per claim (NG5), and it
would double-count against the gate's own probe schedule, the very thing
`GitBaseRefs.probe` keeps out of the retry for.

**D2 — The adapter sets the value where the path forks, never by inspecting
git output.** `CommitBaseFetch.fetch`: `CLONE_ONLY` on the present-object
return, `CONTACTED` on the post-fetch return. `RefreshedTip.of`: `CONTACTED`
(it is only ever called with a completed fetch). `ResumeBaseResolution.resolve`:
`CLONE_ONLY` on the no-origin local-tip bind, and the delegated refresh's own
value on the remote-backed path. *Rationale:* each site already is the
decision "did we go to the network"; the fact is a label on a branch the code
has taken, so no new I/O, no parsing of git's wording (NFR-R1). *Alternative
rejected — derive it in `GitBaseRefs` from whether `NarrowFetch` ran:* that
needs a side channel (a flag or a listener) across three collaborators to
carry what each already knows at its own return statement.

**D3 — The decoration signals only on `CONTACTED`; `CLONE_ONLY` is silent on
both the interval and the last-contact time.** `RemoteOutageSignalingBaseRefGit`
destructures the success arm and calls `onSuccessfulRefresh()` only when the
fact is `CONTACTED`. The gate's own `open` guard stays as it is: the two
guards answer different questions (is this a refresh after the close; did
this refresh reach origin), and each has its own regression spec.
*Rationale:* FR2 and FR3 name the same event, so one branch serves both;
splitting "reset the interval" from "record the contact" would give the gate
two entry points for one fact. *Alternative rejected — pass the fact into the
gate and let it decide:* the gate would then know a port type it has no other
reason to import; the decoration exists precisely to keep port vocabulary out
of the gate.

**Sync surfaces:** none — this change adds no parallel implementation and
touches no declared pair. The same enum on two records is one type with two
carriers, not one rule in two places.

**Single-owner mechanisms:**

| Owner | Value (type) | Consumers | Old way removed | Enforced by |
|-------|--------------|-----------|-----------------|-------------|
| The git adapter's five success-construction sites (`CommitBaseFetch` ×2, `RefreshedTip` ×1, `ResumeBaseResolution` ×2), each stating the fact for the path it returns from | `OriginContact` (enum), as a component of `BaseRefreshOutcome.Refreshed` and `ResumeBaseOutcome.Bound` | `RemoteOutageSignalingBaseRefGit.refresh` and `.resolveForResume` — the only reader that branches on it. `FreshClaimBaseBinding`, `ResumeLawBinding`, `TrustedTierStartup`, `ResumeBaseResolution` (re-wrap) destructure it as `var _` and pass it through | The decoration's arm-only inference ("`Refreshed` ⇒ origin answered") is deleted; the "known imprecision" javadoc paragraph goes with it. No exemption survives: the manual `run` paths construct no outcome and signal no gate | The record component with no default: a construction site that omits it does not compile. `RemoteOutageSignalingBaseRefGitSpec` is data-driven over both values for both arms, so a reader that stops branching on it goes red. `BaseRefreshSpec`/`ResumeBaseResolutionSpec` pin each of the five sites against a local bare origin (M2) |

Identity claim: "a `CONTACTED` outcome is one whose path ran a fetch". The
adapter specs assert it on the real medium per site (a spy on the git runner
sees a `fetch` invocation exactly when the outcome says `CONTACTED`), which is
the invariant spec the row requires.

## Risks / Trade-offs

- [Thirteen test files construct the records and all break at once] → the
  compiler lists them; the update is mechanical (`CONTACTED` wherever the
  spec stubs a remote answer, `CLONE_ONLY` where it stubs a local hit), and
  M3 requires no assertion to change. Kept in one task so the tree is never
  half-migrated.
- [A future adapter path returns a success without stating the right value]
  → the component is required, so the site must choose; the adapter specs
  pin each existing site, and a new site without a spec is the ordinary
  coverage gate's job (PIT 100% on `:adapters:git`).
- [`type-untrusted-text` edits the same two record declarations] → different
  components (`Refused.report`/`Unavailable.reason` there, a new component on
  the success records here); whichever lands second rebases a one-line
  declaration change. Noted in both changes' sequencing notes.
- [The resume pass-through copies the fact from a refresh that a later change
  might make partially local] → the pass-through is one line; a future path
  that answers a resume without a fetch sets `CLONE_ONLY` at its own return,
  the same rule as D2.
