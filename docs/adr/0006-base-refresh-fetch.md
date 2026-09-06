# ADR 0006: Base Refresh Fetch

Status: accepted (2026-09-06, introduced by `add-base-ref-resolution`;
implementation pending)

## Context

Every autonomously started task branches from a base the factory has just
refreshed from the remote. The base may be a branch, a tag, or a bare commit
SHA. The factory's clone is long-lived and shared: concurrent `serve` slots
fetch out of it, and it is also the operator's own clone, whose working
tree, index, `HEAD`, local branches and tags the factory has promised never
to touch (design decision D7 of `add-git-workflow`, kept for pull and
dropped for fetch by `add-base-ref-resolution`).

Git's own mechanics decide most of the design (verified against git 2.55
and the four public forges on 2026-09-06). `FETCH_HEAD` is one unlocked
file per clone, truncated and rewritten by every fetch, so reading it after
one's own fetch is unsafe whenever another fetch may have run in between.
A fetch of `refs/heads/x` with an explicit destination still moves
`refs/remotes/origin/x`, because git applies the configured `remote.<name>.fetch`
refspecs as a destination mapping to whatever was fetched; only an empty
`--refmap=` disables that. Tag auto-following writes into `refs/tags/` even
on a fetch by SHA unless `--no-tags` is given. Two concurrent fetches into
the same ref end in one compare-and-swap failure (`cannot lock ref ... is
at X but expected Y`), not a wait. Fetch-by-SHA is accepted by default on
GitHub, GitLab, Gitea and Codeberg (protocol v2 takes any want; GitLab and
Gitea also enable `uploadpack.allowAnySHA1InWant`); a self-hosted server on
protocol v0 without those options refuses it.

## Decision

### Each base kind lands where git itself would put it

| Base kind | Refspec | Why |
|-----------|---------|-----|
| branch | `+refs/heads/<n>:refs/remotes/origin/<n>` | the remote-tracking ref is the clone's cache of origin; updating it is what fetch is for, it is what the resume locate step already does, and `git log origin/<n>` stays truthful for the operator |
| tag | `refs/tags/<n>:refs/tags/<n>`, without force | tags have no remote-tracking namespace and an ordinary fetch auto-follows them into `refs/tags/` anyway; no force keeps git's own semantics — create if absent, refuse to move an existing tag. A refusal means the operator clone's tag diverges from origin's: a task-level park with a report naming both commits, never a silent pick |
| SHA | `<sha>`, no destination | a commit has no tip to refresh; the only question is "do I hold it". Check `cat-file -e <sha>^{commit}` first (zero network when present, the usual manual `--base` case), fetch by SHA only when absent, check again. No ref is needed: the task-creation commit references the object seconds later, far inside git's prune grace |

### Flags on every refresh

`--no-tags` (narrow means exactly one ref, no auto-followed extras),
`--no-write-fetch-head`, and an empty `--refmap=` (a clone with a
non-standard `remote.origin.fetch` cannot make the fetch move refs the
factory did not name). Full depth, never shallow: resume must resolve
history.

### The SHA is read from the destination, never from `FETCH_HEAD`

After a successful fetch the factory reads `refs/remotes/origin/<n>`,
`refs/tags/<n>`, or verifies the object itself. `FETCH_HEAD` is never read
anywhere in the factory.

### Serialization is the clone lock, not git's ref lock

All mutating git calls against one clone already serialize on the
in-process clone mutation lock, so the compare-and-swap failure of parallel
fetches into one ref is unreachable here. The lock, not a retry on `cannot
lock ref`, is the mechanism; a retry loop around a nondeterministic git
failure is what the lock exists to avoid.

### What the fetch may and may not change

May change: the one `refs/remotes/origin/<n>` named, a `refs/tags/<n>`
that did not exist, the object database. May not change: the working tree,
the index, `HEAD`, any `refs/heads/*`, any pre-existing `refs/tags/*`, any
other `refs/remotes/origin/*`. Pull remains forbidden on every path.

### Resume

Resume never re-resolves the base; it re-fetches the *pinned ref name* to
bind the law from its current tip, with the same destinations and flags. A
SHA base needs no fetch at all: the object is already in the task branch's
history.

### Failure classes

A remote that never answers is a reachability failure, charged to the
daemon per ADR 0005. A diverging local tag, a remote that refuses
fetch-by-SHA (the report names the `uploadpack.allow*SHA1InWant` option),
and a ref origin reports absent are the task's, and park it with a report.

## Alternatives Considered

- **Resolve through `FETCH_HEAD`** — one unlocked file per clone,
  overwritten by any concurrent fetch, and useless later when the tip of
  the same name is needed again.
- **A private `refs/gnomish/` namespace for everything** — git's own
  `refs/prefetch/` is a precedent, but it diverges from the resume locate
  path, leaves `origin/<n>` stale after the factory has already fetched it,
  and buys nothing for tags (git puts them in `refs/tags/` on every ordinary
  fetch) or for SHAs (which need no ref). The surveyed tools (Jenkins,
  GitLab Runner, Renovate, ArgoCD, Atlantis, Woodpecker) use none either:
  fresh clones, `FETCH_HEAD`, or an in-process mutex.
- **Force-write tags** — silently overwrites a tag the operator holds; the
  one real way to touch the operator's refs.
- **A private ref for a fetched SHA** — guards against a prune that cannot
  happen inside the seconds before the task-creation commit references the
  object, and adds a ref to clean up.
- **Branch-only bases** — tag-anchored hotfixes (OneFlow) become
  inexpressible for no savings.
- **A shallow single-ref fetch** — cheaper, but resume must resolve history
  (the GitLab-runner depth caveat).

## Consequences

- The operator's clone stays truthful and untouched where it matters:
  `origin/<n>` reflects the last fetch, local branches and tags are never
  moved, and nothing the factory does is visible in `git status`.
- One fetch recipe serves fresh start and resume, host and container
  medium, since the container clones the already-created task branch.
- The tag policy makes a diverging local tag a visible park instead of a
  silent choice, at the cost of a rare human intervention.
- The SHA path is the only one that can succeed with zero network, which
  is exactly the manual `--base <sha>` case D7 protected.

## See also

- `docs/adr/0005-dependency-outage-accounting.md` — what a reachability
  failure of this fetch does to the daemon and the task.
- `docs/adr/0003-crash-consistency.md` — the fetch precedes every durable
  write; the window it lengthens is the reaper's.
- `docs/glossary.md` — *base ref*, *base pin*, *remote outage gate*.
