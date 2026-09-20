# Proposal: fix-envelope-medium

**Sequenced after:** `type-untrusted-text` (it rewrites the same `adapters/git` files —
`CleanupCommit`, `GitShowTip`, `WorktreeSalvage`, `DeliveredBranchReader`, `GitTaskStore`,
`StateFileWrite`, `TerminalWriteMarker`; this change lands on top of that work, never beside it).

## Why

In host mode the factory answers questions about its own envelope — the `.gnomish-task/`
directory that records a task's identity, outcome and pipeline position — from two different
places. The branch-shape classifier and every container-mode reader answer them from the **branch
tip**. Everything that runs *after* classification in host mode answers them from the **worktree
on disk**: the resume bootstrap reads `task.json` there, the resume mechanics read `state.json`
there, the cleanup guard tests the directory there, and three lifecycle rewrites read the file
they are about to replace there. Nothing keeps the two media equal, and the project's own
principle already says which one is the truth: ADR 0003 ("the media are the journal") and the
factory-owned-paths policy of `harden-task-branch-contract` (design D11) state that factory-owned
envelope files come from the tip and never from a dirty worktree — but that restore runs only
inside salvage, after every worktree read has already been made and acted on.

The `/check-issue` of 2026-09-20 showed the consequence. A kill between the `git rm -r` and the
commit of the completion cleanup leaves a worktree without the envelope over a tip that still
carries it. On the same machine the next pickup reuses that worktree as-is, reads `task.json`
from it, finds nothing, and concludes "already delivered": it routes to the delivered path whose
destructive step is empty, so the cleanup is never re-driven, the worktree is never removed, and
the delivered state is read from the wrong revision (`tip^`, which is the last attempt commit
rather than the `Completed` commit). The branch stays a `Completed` tip with its envelope for
good. Nothing is red: the kill-point matrix kills only between the sequence's coarse steps, and
every component spec is correct in isolation. The external practice is uniform — recovery is a
function of the durable medium (write-ahead logging, event-history replay, level-triggered
reconciliation, git's own three-trees model) — and it matches ADR 0003; this change makes the
host medium obey it.

The audit of 2026-09-21 that followed the check added three findings on the same envelope and
its neighbours, folded in here by the user's decision rather than opened as separate changes:
(a) the spelling of the envelope's paths has no owner outside `adapters/git` — `GnomishTaskPaths`
is package-private, so `ContainerTipReader` in `:bootstrap` types both file paths by hand and
`BranchShapeClassifier` in `:domain` declares the two file names again, an undeclared pair with
no shared classpath; (b) `GitCommandResult`'s stderr excerpt bounds the *input* to line
flattening, not its rendered output, so a stderr of 1 400 line-separator characters renders as
8 400 and the log's tail-keeping cap then drops the caller's prose and the `exited N` head the
bound was sized to keep — the ASCII fixtures never see it; (c) the glossary bans the word
`whitelist` and it survives as a constant name, a parameter name and published javadoc in eleven
files.

## What Changes

- **MODIFIED** `git-task-persistence`: in host mode every read of a factory-owned envelope file
  resolves at the worktree's `HEAD` — the branch tip — never at the working-tree file. The
  worktree is where the next envelope version is staged for its commit, not where the current
  one is read. Absence at the tip is a typed result, not an I/O exception.
- **MODIFIED** `git-task-persistence`, "Cleanup on completion": the host cleanup guard tests the
  tip, and the destructive step converges from every frozen state of its own two-command
  sequence — a removal already staged by a killed predecessor lands in the commit instead of
  failing or being skipped.
- **MODIFIED** `lifecycle/task-branch-contract`, "One recovery owner per shape": convergence
  no longer depends on which machine picks the task up — the instance whose stale worktree froze
  the state converges it too.
- **MODIFIED** `tracker-take`, "A Completed-without-cleanup tip is finished, never re-executed":
  the delivered state is read from the `Completed` commit located by its cleanup commit, not from
  the tip's parent by assumption. This closes the durability concern `DeliveredBranchReader`
  records in its javadoc (add-claim-heartbeat task 6.5).
- **ADDED** an architecture gate in `:bootstrap`: no filesystem read of a `.gnomish-task/` path
  in the git adapter or the application layer outside the allowlisted owner. The gate is what
  keeps the fix single-owner after this change (`implementation.md`, item 4).
- **ADDED** a kill point inside the host completion cleanup — after the staged removal, before
  its commit — to the finish kill-point matrix.
- **MODIFIED** `git-task-persistence`: the envelope's path names have one owner in `:domain`,
  consumed by the git adapter, the container tip reader and the shape classifier; a literal
  spelling outside the owner fails the build.
- **MODIFIED** `factory-logging`, "Untrusted text enters logs only sanitized": a caller-bounded
  excerpt bounds its rendered output, so the prose around it survives the record cap whatever
  the excerpt contains.
- **REMOVED** the word `whitelist` from code, tests and javadoc — renamed to the glossary's
  `allowlist`; no behavior change, no published-type change.
- **REMOVED** the host-side worktree read helpers: `GitTaskStore`'s direct file reads, the
  `NoSuchFileException`-typed control flow in `HostResumeMechanics`, and the worktree-file read
  in `StateFileWrite`'s cursor carry-forward.

## Goals

- **G1** — One reader for the envelope in host mode: every question about the current envelope
  is answered from the tip, through one owner, in every path (fresh claim, resume, reconcile,
  manual run, cleanup).
- **G2** — Every frozen state of the completion cleanup converges on the next pickup, on any
  instance including the one that died, and the second recovery pass is a no-op.
- **G3** — The invariant is enforced by the build, not by review: a new worktree read of an
  envelope file fails `check`.
- **G4** — The envelope's path names are spelled once, in the lowest module that needs them, and
  every other module compiles against that spelling.
- **G5** — A log record that quotes git's stderr keeps the words that say what failed, for any
  stderr content.

## Non-Goals

- **NG1** — Unifying the host and container *write* paths (host lifecycle commits built from bare
  objects, as `GitObjectsTaskRepository` does). That is the second step of the same direction
  and dissolves several declared pairs; it is a separate change, designed once this one shows
  what divergence remains between the media.
- **NG2** — The `index.lock` window: a kill *during* a git command that holds the index lock
  leaves a lock file every later command refuses. That exposure predates this change and
  applies to every host git command alike.
- **NG3** — The container medium's *reads*: they already resolve at the tip and keep doing so;
  the only container-side edit is `ContainerTipReader` taking the path names from the owner.
- **NG4** — Salvage policy for gnome-owned work files: what a dirty worktree may contribute stays
  as `harden-task-branch-contract` FR5 defines it.
- **NG5** — The host confirm commit's `git add -A` sweeping unrelated dirty files into the
  receipt commit; noted, not addressed here.
- **NG6** — The record-level log cap (`DEFAULT_CAP_CHARS`) and the sink's per-record cap: they
  bound the record and are allowed to bound its input, since the sink is what guarantees no
  record exceeds the cap. Only the caller-bounded excerpt changes.
- **NG7** — Any semantic change to the http-check variable set or its validation: the rename is
  a rename.

## Users & Scenarios

- **U1 — Operator running `gnomish serve` on one machine.** An instance dies inside the
  completion cleanup; the same instance restarts and takes the task. The branch converges to a
  clean tip and the worktree is removed, with no operator action.
- **U2 — Operator running `gnomish run --resume` by hand.** A resume on a machine whose worktree
  is stale (an earlier process staged a removal, or an earlier kill left the envelope half
  written) continues from what the branch records, never from what the disk happens to hold.
- **U3 — Maintainer adding a new host-mode reader.** A `Files.readString` of an envelope path
  fails the build with the name of the owner to use instead.
- **U4 — Maintainer adding a reader in any module.** A hand-typed `".gnomish-task/task.json"`
  fails the build naming the owner constant.
- **U5 — Operator reading a failure line.** A git refusal whose stderr is full of
  line-separator characters still logs as "the fetch exited 128: …", not as a wall of escapes
  with no head.

## Requirements

### Functional

- **FR1 — Envelope reads resolve at the tip.** `TaskStoreGit.readRecordedState` and
  `readTaskRecord` read `state.json` / `task.json` at `HEAD` of the worktree's repository — the
  checked-out branch tip — through the existing tip-read seam, never through the working-tree
  file. Absence is returned as a typed empty result; only an invocation that did not run to its
  own exit throws (the existing `BranchTipUnavailableException`).
- **FR2 — Lifecycle rewrites read what they replace from the tip.** The three
  read-modify-write sites (`TerminalWriteMarker.clearPending`, `GitTaskRepository.readCurrentDto`,
  `StateFileWrite.currentCursor`) take their input from the tip, so a stale or half-written
  worktree file can never be carried forward into a lifecycle commit.
- **FR3 — Cleanup guard on the tip; destructive step convergent.** `CleanupCommit` tests whether
  the tip carries `.gnomish-task/`; when it does, the removal lands whether the worktree still
  holds the directory or a killed predecessor already staged its removal, and the commit follows.
  A tip without the directory is the same no-op as in container mode.
- **FR4 — One host predicate for "the tip carries the state directory".** The cleanup guard and
  the salvage restore guard consult one owner for that fact; the container medium's
  bare-objects twin stays a declared pair with it.
- **FR5 — Resume mechanics carry typed absence.** `HostResumeMechanics` distinguishes "the tip
  carries no envelope" (delivered) from "the tip carries `task.json` but no `state.json`"
  (pre-contract) through the reader's typed result, with no exception-cause inspection.
- **FR6 — Delivered state located from the cleanup commit.** `DeliveredBranchReader` reads the
  `Completed` envelope at the parent of the cleanup commit it locates in the tip's history — or
  at the tip itself when the tip still carries the envelope — never at `tip^` by assumption.
- **FR7 — Kill point inside the host cleanup.** The finish kill-point row gains a step for the
  host medium: after the staged removal, before its commit; the frozen shape is
  `CompletedUncleaned`, the pickup on the same worktree converges to `Delivered`, and the second
  pass is a no-op.
- **FR8 — Build gate.** An architecture spec in `:bootstrap` scans `adapters/git/src/main` and
  the application layer's `app` package for filesystem reads of envelope paths, allowlists the
  owner by file with a reason, and asserts the scan reached every allowlisted file.
- **FR9 — One owner for the envelope's path names.** The directory name, the two file names,
  the decisions subdirectory and the paths derived from them are declared once in `:domain`;
  `adapters/git`, `ContainerTipReader` and `BranchShapeClassifier` consume that declaration, the
  package-private `GnomishTaskPaths` is removed, and the FR8 gate additionally rejects the
  literals `".gnomish-task"`, `"task.json"` and `"state.json"` in any production source outside
  the owner (error-message prose and javadoc excepted by construction, since the scan matches
  string literals).
- **FR10 — A caller-bounded excerpt bounds its rendered output.** `UntrustedText.excerpt(cap)`
  returns at most `cap` characters *after* line flattening, keeping the tail and the truncation
  marker, so the headroom `STDERR_CAP_CHARS` reserves under the record cap is real for every
  stderr content; the `STDERR_CAP_CHARS` javadoc states the bound in output characters.
- **FR11 — `whitelist` is renamed to `allowlist`.** The constant `HttpCheckVariables.WHITELIST`,
  the `whitelisted` parameter of `HttpCheckVariableException`, and every javadoc and comment use
  in production, test and plugin-API sources take the glossary's word; no signature of a
  published type changes.

### Non-Functional

- **NFR-R1 (reliability)** — Recovery of every named frozen state of the completion sequence is
  idempotent and convergent on the same instance and on another; the kill-point matrix asserts
  both.
- **NFR-R2 (reliability)** — A tip read that cannot be answered (timeout, interruption) is
  reported as unavailability, never as absence; no envelope read may classify a live branch as
  delivered because git did not finish.
- **NFR-P1 (performance)** — Each envelope read costs one bounded `git show`; the paths that read
  it do so a handful of times per take, so the added cost is below one second per task.
- **NFR-O1 (observability)** — A cleanup that lands a removal a predecessor already staged says
  so at INFO with the task id, so an operator reading the log can tell a converged crash from a
  first run.
- **NFR-S1 (security)** — Envelope text minted at the tip read keeps the `BRANCH_DOCUMENT`
  provenance `type-untrusted-text` gives it; no new sink. The excerpt change tightens a bound
  and loosens none: neutralization order (strip, cap, flatten) is unchanged, only a second cap
  after flattening is added.
- **NFR-C1 (cost)** — Unchanged: a `Completed` task still runs zero engine rounds on recovery.

## Operator Experience Criteria

- **UX1** — After a crash inside the cleanup, the next take on the same machine leaves a clean
  branch tip and no leftover worktree, with one INFO line naming the converged removal.
- **UX2** — A maintainer who adds a worktree read of an envelope file gets a build failure that
  names the file, the banned call, and the owner to route through.
- **UX3** — A git failure line in the log always begins with the caller's words ("the fetch
  exited 128: …"), whatever git printed.

## Success Metrics

- **M1** — The finish kill-point matrix has one more host row; every row, including the new
  one, asserts convergence and a no-op second pass, and the whole matrix is green.
- **M2** — `grep -rn "Files\.\(readString\|exists\|notExists\|isRegularFile\|readAllBytes\|newBufferedReader\|lines\)" adapters/git/src/main application/src/main/java/com/github/oinsio/gnomish/app`
  hits only files on the gate's allowlist.
- **M3** — The `manual-sync-pairs.md` registry rows for the cleanup pair and the cursor pair
  state a tip-only invariant; no row describes two media for one read.
- **M4** — A spec that stages the removal by hand and then calls `finishCleanup` on the same
  worktree ends with a tip carrying no `.gnomish-task/` — the reproduction of the 2026-09-20
  report, red before this change and green after.
- **M5** — The `DeliveredBranchReader` javadoc no longer records the `tip^` durability concern,
  and a spec reads the delivered state correctly from a branch that gained commits after cleanup.
- **M6** — `grep -rn '"\.gnomish-task\|"task\.json"\|"state\.json"' --include='*.java' */src/main adapters/*/src/main sandbox/*/src/main`
  hits only the owner in `:domain`; `GnomishTaskPaths.java` no longer exists.
- **M7** — A spec logging a `GitCommandResult.failureDetail("fetch")` whose stderr is 1 400
  `U+2028` characters finds `the fetch exited 128` in the rendered record.
- **M8** — `grep -rin whitelist --include='*.java' --include='*.groovy' --include='*.md' .`
  outside `openspec/changes/archive/` returns only the glossary's own *Never:* line.

## Open Questions

- **Q1** — None blocking. Whether NG1 (one writer for both media) follows immediately is decided
  after this change is audited, from the pair rows that remain.
- **Q2** — Whether the record cap (`DEFAULT_CAP_CHARS`, NG6) should also bound output is left to
  the logging capability's owner: the sink guarantees the record bound today, so nothing is
  wrong, but the input/output asymmetry between `forLog()` and `excerpt()` is worth a line in
  the audit.

## Capabilities

### New Capabilities

- none

### Modified Capabilities

- `git-task-persistence`: host envelope reads resolve at the branch tip (new requirement in the
  capability, including the one-owner spelling of the envelope paths, FR9); "Cleanup on
  completion" gains the tip guard and the convergent staged-removal case (FR1–FR4, FR7).
- `factory-logging`: "Untrusted text enters logs only sanitized" — a caller-bounded excerpt
  bounds its rendered output (FR10). **Sync order:** `add-subprocess-access-log` also modifies
  this requirement and is sequenced before this change; this change's delta is layered on that
  delta's text.
- `lifecycle/task-branch-contract`: "One recovery owner per shape" — convergence on the
  instance that froze the state, not only on another (FR3, FR5, NFR-R1).
- `tracker-take`: "A Completed-without-cleanup tip is finished, never re-executed" — the
  delivered state is read from the located `Completed` commit (FR6).

## Impact

- `adapters/git`: `GitTaskStore`, `GitShowTip`, `CleanupCommit`, `WorktreeSalvage`,
  `TerminalWriteMarker`, `GitTaskRepository`, `StateFileWrite`, `DeliveredBranchReader`.
- `application`: the `TaskStoreGit` port's read signatures (typed absence),
  `HostResumeMechanics`, `TakeResumeBootstrap`, `TakeFreshClaim`, `GitResumeRunner`,
  `GitModeRunner`, `GitResumeContinuation` (call sites of the changed signatures).
- `bootstrap`: one new architecture spec; `FinishKillPoints` gains a host step;
  `ContainerTipReader` takes the envelope paths from the owner.
- `domain`: the new envelope-paths owner; `BranchShapeClassifier` consumes it.
- `untrustedtext`: `UntrustedText.excerpt` / `TextSafety` (output-bounded excerpt).
- `adapters` (`adapter/check/http/*`), `application` (`StalenessMemory`), `atomicfile`
  (`NonAtomicWrite`), `gnomish-plugin-api` (`CheckRunContext`, `CheckClientFactory` javadoc
  only — no signature change, no version bump): the rename.
- Docs: `docs/adr/0003-crash-consistency.md` (per-medium table: where host reads resolve),
  `.claude/rules/crash-consistency.md` (checklist item), `.claude/rules/manual-sync-pairs.md`
  (registry rows), `docs/glossary.md` (the **envelope** term, used throughout the code and
  undefined until now).
- No new dependencies; no change to the container medium or to the published plugin contract.
