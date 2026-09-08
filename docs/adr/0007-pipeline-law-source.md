# ADR 0007: Pipeline Law Source

Status: accepted (2026-09-08, introduced by `add-base-ref-resolution`)

## Context

A pipeline's *law* — the `.gnomish/` stage manifest, stage instructions, and
judge criteria — used to be read straight off the factory's shared clone:
`GnomishFiles`, `ReferencedFiles`, `PipelineLoader` and `PipelineLawReader`
all resolved paths against whatever the clone's working tree currently held.
Once a task can branch from any resolved ref (ADR 0006), that source stops
being correct: the clone's checkout is one commit, a task's base is another,
and the pin guard compared attempts against the literal `"HEAD"` of the
shared clone regardless of which ref the task actually resolved. The loader
also validated stage file references against the project's `.gnomish/`
directory while the runtime read the same strings against the repository
root — a load-vs-run divergence that made every fixture keep two copies of
its law files to avoid disagreeing.

Two further facts push the design past "read the file differently":

- The factory clone is shared and long-lived (ADR 0006's context): checking
  it out to a task's base would mutate state concurrent `serve` slots and the
  operator depend on.
- Resume is not a re-run. Industry configuration-pinning (CI, Argo CD,
  Renovate) exists to make a *re-run reproduce* an earlier result exactly.
  This factory's resume *continues* a task a human may have just unblocked by
  fixing criteria on the base — pinning the law as tightly as a re-run model
  would silently hide that fix from the very escalation loop that exists to
  deliver it.

## Decision

### Law is read by ref from git objects; the working tree is never a law source once a ref is resolved

A law source abstraction (`LawSource`, module `:adapters`) exposes exactly
three operations — read a file, test whether a path is a regular file, list
a directory — all relative to one law root, and nothing else.
`GnomishFiles`, `ReferencedFiles`, `PipelineLoader` and `PipelineLawReader`
read through it, so "which tree is the law in" is answered once, at
assembly, instead of once per reader.

Two realizations, deliberately no more, both `LawSource`:

- `WorkingTreeLawSource` — a directory of the factory clone. Used only where
  no ref was resolved: the in-place mode and manual `run` without `--base`,
  where an uncommitted edit is meant to be law (this preserves the pipeline
  author's edit-and-run loop, FR8/UX3).
- `GitObjectsLawSource` — bound to one commit, the **law commit**, read
  through `GitObjects` tree listing (`ls-tree`) with no checkout. Used by
  every path that resolved a ref: `take`, `serve`, container mode, resume,
  and manual `run --base` (the last resolving locally, offline, with no
  fetch).

Selection is by *fact*, not by mode: whichever binding actually resolved a
ref reads git objects; nothing else does. This is the same mechanism the
wider industry uses for reading configuration without a checkout — GitLab
through Gitaly, Jenkins' `SCMFileSystem`, GitHub Actions and Renovate through
content APIs, and the principle OWASP CICD-SEC-4 names — and it adds no
durable step, since it only reads: ADR 0003's kill-window list is unchanged.

The two realizations are two strategies behind one interface, not a
hand-synchronized pair: `LawSourceContractSpec` runs both over identical
trees and asserts one verdict.

`LawBinding` (module `:application`) is the value the use-case layer states:
`workingTree(repositoryRoot)`, `atRevision(repositoryRoot, revision)`, or
`atCheckout(repositoryRoot)` — never a bare commit id, because peeling a
revision needs git, and because resume (below) has a ref *name* before its
narrow fetch and only a commit after it. The adapter-side factory resolves
the revision to a commit id exactly once, at the point the law source is
opened, and returns the law source and that id together — so the law and
the external-check pin come from one SHA by construction. This closes two
double-resolve incidents observed in Argo CD (#26530, #16601); JGit and
libgit2 shape their own APIs the same way, resolve once, then take the
object id.

### The two-tier model: a trusted tier at startup, a task tier per claim

Pipeline configuration splits into two tiers, bound at different times from
different sources:

- **Trusted tier** — `tracker:`, `task-branch:`, and future selector
  sections. Bound once at `serve`/`take` startup from the refreshed
  repository default branch, and never re-read per claim. A merged change to
  it takes effect only on the next start, the same lifecycle the `tracker:`
  section already had. This is what `board`/`dashboard` and pipeline routing
  read.
- **Task tier** — stages, stage instructions, judge criteria, the remainder
  of `config.yaml`. Bound per task, once, from the task's own law commit,
  and frozen from it for the task's lifetime.

A load error on the trusted tier is a startup fail-fast, unrelated to any
task. A load error on a task's tier — including on its base — is
deterministic, so it parks the task with a configuration report: it is
neither an infrastructure failure (no claim-release retry loop) nor a
quality failure (no attempt burned).

*Rationale for splitting rather than re-reading everything per claim:*
policy and network cost stay startup-scoped, the claim path keeps exactly
one refs read plus one narrow fetch, the trusted tier has one source and one
binding time (no "which tip did this claim see" question), and startup's
consumers read the same tier the resolver does.

*Alternative rejected:* refreshing the default branch and re-reading the
trusted tier on every claim — a second narrow fetch per claim whenever the
base is not the default branch, a trusted tier that could change between two
slots of one daemon, and a startup definition that stops being *the*
definition the slots resolve under.

### Resume binds the task tier from the tip of the pinned ref name — a deliberate deviation from the re-run model

The base itself is never re-resolved on resume: the base pin
`(resolved ref, SHA, source rule)` written at task creation is final. But the
*law commit* resume binds from is not that frozen SHA — it is the current
tip of the pinned ref **name** (for a tag or SHA base, the two coincide).

Security is identical either way, since a base tip is human-merged content
either way; the choice is freshness versus determinism. The industry pins
configuration for *re-runs*, because a re-run's entire value is that it
reproduces. This factory's resume *continues* a task, and the escalation
loop's value is exactly that a human fix to criteria on the base reaches the
returned task without a re-pin operation — the existing "resume picks up
human-fixed criteria" scenario. Pinning the law commit as tightly as a
re-run model would defeat that loop for no security benefit.

Mechanically: autonomous resume narrow-fetches the pinned ref name before
binding — the same bounded, fail-closed fetch as the base refresh (ADR
0006), with the same per-kind destinations. A SHA base needs no fetch at
all, since the object is already in the task branch's history; an
unreachable remote is an infrastructure failure that releases the claim.
Manual resume in a clone without a reachable remote binds from the local ref
tip. A pinned ref that no longer resolves anywhere (a series branch deleted
after release) parks the task with a report — the task is probably obsolete,
and silently falling back to the pinned SHA would hide that.

This is a recorded, deliberate deviation from "re-run reproduces exactly."
The corresponding hazard — a base moving structurally under a parked task —
is closed by the pipeline pin of `add-pipeline-routing`, whose structural
hash therefore covers stage list, order and verify checks only, never
instruction or criteria content; otherwise a human fix would park the very
task it was meant to unblock.

### The law-root rule: `.gnomish/` is the one root, in every medium

Both `LawSource` realizations are rooted at the **law root** — the
`.gnomish/` directory of the working tree or of the law commit's own tree —
the same root the loader already validates references against. This closes
the load-vs-run divergence described in Context: a reference that validates
at load now reads at run, in every medium, because the root is spelled in
exactly one place (`LawBinding.LAW_ROOT`) and no call site resolves
`.gnomish` on its own. `LawRootBoundarySpec` (a whole-tree source scan, since
the subject is a string constant no bytecode analysis can distinguish) pins
this: only `LawBinding`, plus the operator's unrelated `~/.gnomish` *home*
directory in `ObservabilityPaths`/`ManualRunConfiguration`, may spell the
directory name in production code.

The assembly carries the law binding and the repository root as two typed
values, never one `Path` doing both jobs, because the repository root also
serves the external-check pin guard — whose pin paths are repository- (or,
equivalently, working-copy-) relative by design, a different root from the
law root. A field is relative to exactly one root:

| Manifest field                                  | Relative to                    |
|--------------------------------------------------|---------------------------------|
| `instructions`, `criteriaFile`                    | law root (`.gnomish/`)          |
| external-check pin paths, artifact output paths   | working copy root               |

This table is shared with `enforce-artifact-contracts`, which sequences
after this change specifically to read it from here rather than re-deriving
it: `path` on artifact outputs resolves against the working copy root, the
same root external-check pins already used, leaving the law root as the
only root `instructions`/`criteriaFile` ever meant.

**Both realizations refuse a symlink entry under the law root, at any
segment, never following it — regardless of the target.** The first draft
kept the working-tree realization's pre-existing `realpath` guard, which
accepted a symlink whose target stayed under the root; one commit would then
be valid law under manual `run` and invalid under `take`, reading the same
tree two ways. `Kustomize`'s `LoadRestrictionsRootOnly` and Argo CD's
out-of-bounds symlink scan — adopted after three CVEs of one class — take
the same fail-closed line: a configuration tree has no legitimate link, so
the target is never parsed. One segment walk (`LawPathWalk`) now owns the
verdict for both realizations: each realization supplies tree entries (file,
directory, symlink, absent), and the walk refuses any symlink entry
regardless of target. In git objects, `ls-tree` cannot see through a
`120000` entry and reports a missing tree — indistinguishable from a deleted
directory — which is why the walk, not git, classifies. Because every
segment is now classified without following links, the working tree's
`realpath` comparison can never disagree with the lexical guard, so it was
dropped rather than kept as a line no mutation test could kill. An absolute
reference is refused in both realizations.

### Reserved non-goal: escaping the law root by an explicit repository-anchored prefix

Bazel's `//` and Argo CD's leading `/` both let a reference deliberately
escape their default root and anchor at the repository root instead. This
factory reserves the same idea — an explicit repository-anchored prefix — as
a **named non-goal with the syntax reserved**, not built now: no
manifest field may currently name a path outside the law root by any prefix.
The reservation exists so a later change can extend the grammar to admit
one, rather than the guard being loosened ad hoc the first time someone
wants it.

## Alternatives Considered

- **A detached worktree at the base as the law root.** Steelmanned and
  rejected: it would collapse the realization count from two law-source
  types down to one working-tree read, leaving every path-based reader
  untouched and the existing binding spec passing unmodified. But a worktree
  is a create-plus-delete durable pair — a new kill window, orphan sweeping,
  and a project tree materialized on the host that the factory's own clone
  hardening deliberately neutralizes elsewhere. The `task-branch.base`
  section still has to be read by ref before any worktree could be created
  from it, and the pre-existing `"HEAD"` pin bug stays wrong unless fixed
  separately anyway — the worktree buys nothing the git-objects realization
  does not already give for free.
- **Checking the base out in the shared factory clone.** Rejected outright:
  the clone is shared across concurrent `serve` slots and is also the
  operator's own clone, whose working tree, index, `HEAD`, and local
  branches/tags the factory has promised never to touch (ADR 0006's
  context, tracing to design decision D7 of `add-git-workflow`). A checkout
  breaks that invariant for every concurrent slot at once.
- **Re-reading the trusted tier on every claim instead of once at startup.**
  Rejected: doubles the per-claim network cost whenever the base differs
  from the default branch, and lets the trusted tier drift between two slots
  of the same running daemon (see the two-tier section above).
- **Pinning resume's law commit to the frozen base SHA, matching the re-run
  model.** Rejected: identical security, worse operator experience — it
  would silently defeat the "resume picks up a human's fix to criteria"
  scenario that makes the escalation loop useful, for no compensating
  benefit (see the resume section above).

## Consequences

Positive: one law source abstraction serves every medium and mode by fact,
not by branching on it; the load-vs-run divergence that forced fixtures to
keep two copies of every law file is closed structurally, by construction,
rather than by discipline. Law and pin provably come from one commit. The
trusted/task tier split gives every reader of pipeline configuration one
unambiguous binding time. Resume's "continues, does not re-run" framing is
now written down rather than an implicit accident of how the code happened
to be ordered.

Negative: reading law by ref removes the working-tree realization's
`realpath` freedom — a pipeline author's local symlink to a law file, valid
before this change, stops working under `run` with `--base`, accepted as
one rule enforced identically across media. A change to allowed bases (or
any trusted-tier setting) merged to the default branch is invisible to an
already-running `serve` until restart. Resume from the pinned ref's tip can
in principle bind a law that changed *structurally* (not just in wording)
since the task started; this is accepted as today's behavior and closed
separately by pipeline routing's structural pipeline hash.

## See also

- `docs/adr/0005-dependency-outage-accounting.md`,
  `docs/adr/0006-base-refresh-fetch.md` — the base resolution and refresh
  mechanics this law source is bound from.
- `docs/glossary.md` — *law commit*, *law root*, *working copy root*,
  *trusted tier*, *task tier*, *base pin*.
- `.claude/rules/manual-sync-pairs.md` — why the two `LawSource`
  realizations are a shared abstraction, not a declared pair.
- `openspec/changes/enforce-artifact-contracts/design.md` — the artifact
  output path convention that reuses the working copy root named here.
