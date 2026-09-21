# Design: fix-envelope-medium

## Context

See proposal.md — Why. What shapes the approach:

- **The tip reader already exists and already serves two media.** `GitShowTip(runner, repo,
  revision)` reads a file at a revision through `git show` and gates the invocation outcome
  (`GitReadGate.answered`): a read cut off before git exits throws
  `BranchTipUnavailableException` instead of answering "absent". Its own javadoc lists "a
  worktree with `HEAD`" as one of the media it serves; today only the ref-based media use it
  (`RefTipSource` for `status`, `usage`, take routing). The classifier (`TipEnvelopeReader` →
  `BranchTipFactsReader` → `BranchShapeClassifier`) reads through the same seam.
- **The host-mode reads that bypass it**, all found on 2026-09-20 by grepping `Files.` in
  `adapters/git/src/main` and the application `app` package:

  | Site                                                | Reads                                  | Decides                                                                                                                                                          |
  |-----------------------------------------------------|----------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------|
  | `GitTaskStore.readTaskRecord` / `readRecordedState` | worktree `task.json` / `state.json`    | resume route (`HostResumeMechanics.loadBranch` returns `null` on `NoSuchFileException` → delivered path), final state, fresh-claim bootstrap, completion outcome |
  | `CleanupCommit.commit`                              | `Files.exists(worktree/.gnomish-task)` | whether cleanup is owed                                                                                                                                          |
  | `TerminalWriteMarker.clearPending`                  | worktree `task.json`                   | the DTO the confirm commit rewrites                                                                                                                              |
  | `GitTaskRepository.readCurrentDto`                  | worktree `task.json`                   | the DTO an outcome / decision / resume commit rewrites                                                                                                           |
  | `StateFileWrite.currentCursor`                      | worktree `state.json`                  | the denial cursor carried into a regenerated `state.json`                                                                                                        |

  Every other `Files.` read in those trees is not an envelope read (`TaskWorktreeManager`
  and `DirectoryWorkspace` test a directory with `Files.isDirectory`; `WorktreeJanitor` lists
  and walks workspace roots; `AdHocTaskSynthesizer` reads an operator's task file). Note that
  the first two use only `Files.isDirectory`, so a scan that omits that call would never reach
  them — which is why D6 includes it.
- **The worktree is reused as-is between pickups.** `TaskWorktreeManager.ensureWorktree`
  returns a registered worktree without touching it; `ReplicaPairReconciler` resyncs the
  working tree only when the local ref moves (BEHIND / DIVERGED) — on EQUAL it returns before
  any working-tree command. So a worktree can hold a staged removal, or a half-written envelope,
  across a restart of the same instance.
- **The restore-from-tip mechanism exists** (`WorktreeSalvage.restoreFactoryFiles`, over
  `FactoryOwnedPaths`, FR5 / D11 of `harden-task-branch-contract`) and already tests the tip
  (`git cat-file -e HEAD:.gnomish-task`) rather than the disk — but it runs inside salvage, on
  the ordinary-resume path only, after `TakeResumeBootstrap` has read `task.json` from disk and
  `TakeLoadedBranchRoutes` has routed on it. The terminal routes (`CompletedUncleaned`, an
  orphaned park) never reach it.
- **`DeliveredBranchReader` reads `tip^` unconditionally** and records the concern in its
  javadoc: it assumes the tip is the cleanup commit. On a `CompletedUncleaned` tip that
  assumption reads the last attempt commit; on a branch that gained commits after cleanup it
  reads whatever the parent happens to be. `GitShowTip.cleanupCommitInHistory` already walks the
  history for the cleanup commit but returns only a boolean.
- **The kill-point matrix** (`TransitionKillPointSpec` over `KillPointWorlds`;
  `FinishKillPoints` for completion) kills after each durable step named in a transition's
  `steps` list. The completion row names three: the outcome commit, the tracker finish, the
  cleanup commit. The staged removal inside the third is a durable index write with no row.
- Precedents reused: `ClaimlessGitBoundarySpec` / `BaseHeadDefaultBoundarySpec` (allowlisted
  whole-tree scans in `:bootstrap` asserting they reached every allowlisted file);
  `TipEnvelopeReader` (one classify-then-read implementation for two callers).
- **The envelope's path names have three spellings.** `GnomishTaskPaths` (package-private,
  `adapters/git`, 33 uses in that package) declares `.gnomish-task`, `task.json`, `state.json`,
  `decisions`; its own javadoc names `ContainerTipReader` (`:bootstrap`, lines 47 and 74) as the
  known out-of-module survivor that retypes both file paths, and calls the missing scan gate a
  review obligation. `BranchShapeClassifier.TASK_FILE` / `STATE_FILE` (`:domain`) declare the
  two file names again for diagnosis text; `GnomishTaskPaths` mentions them as "deliberate layer
  decoupling" without the `Kept in sync with` marker, the classifier carries none, and the
  registry has no row. `adapters/git` and `:bootstrap` both already depend on `:domain`.
- **The stderr excerpt bounds its input.** `UntrustedText.excerpt(cap)` is
  `TextSafety.forLog(raw, cap)` = `flatten(capTail(strip(raw), cap))`; `LogText`'s javadoc states
  the consequence — a kept `U+2028` renders as six characters, "the worst case, and still a
  bound". `GitCommandResult.STDERR_CAP_CHARS = 1 400` is sized so the excerpt plus the caller's
  prose fit under `DEFAULT_CAP_CHARS = 2 000`, whose `capTail` keeps the tail; that arithmetic
  holds in input characters only. The sink's per-record cap (`SafeMessageConverter`) is also
  tail-keeping.
- **The envelope's file names also appear as bare literals in five more production classes**
  of `adapters/git` that do not go through `GnomishTaskPaths`: `state/TaskJsonMapper` and
  `state/StateJsonMapper` (the file-name label handed to `StateFileVersionGate.readGated`),
  `state/PinnedRefGate` (the same label), and `HarvestedBoundaryCheck` / `RoundBoundaryCheck`
  (a message that opens with `".gnomish-task/ was modified…"`). The literal scan of D8 rejects
  all five, so they are consumers of the owner, not prose exemptions.
- **`whitelist`** appears in eighteen code and test files (constant `HttpCheckVariables.WHITELIST`,
  the `whitelisted` parameter of `HttpCheckVariableException`, javadoc in the published
  `CheckRunContext` and `CheckClientFactory`, comments in `StalenessMemory`, `NonAtomicWrite`,
  `RunCheckRunContext`, `ProviderDispatchingExternalCheckClient`, the `HttpCheck*` specs) against
  the glossary's *Never:* on the **Allowlist** entry.

## Goals / Non-Goals

Design-level boundaries beyond the proposal's:

- The host medium keeps its write path exactly as it is: an envelope version is written to the
  worktree file through `AtomicFileWriter`, staged, and committed by the existing lifecycle
  commit. Only *reads* move. The write-path unification is NG1 of the proposal.
- No new port method and no new module. The two read methods of `TaskStoreGit` change their
  return type; nothing else on the port changes.
- No new git command family: every new read is a `git show` or `git cat-file -e` at `HEAD` of
  the worktree, both already in the runner's read-only vocabulary and already bounded by
  `GitReadGate`.

## Decisions

**D1 — Host envelope reads go through `GitShowTip` at the worktree's `HEAD`.** `GitTaskStore`
builds `new GitShowTip(runner, worktree, HEAD)` and reads `task.json` / `state.json` through
`readAtTip`, minting `BRANCH_DOCUMENT` text exactly as the ref-based readers do. `HEAD` of a
task worktree is the checked-out task branch, so this *is* the tip the classifier read. The two
port methods return `Optional<TaskRecord>` / `Optional<TaskState>`: absence is a value, and the
one exception that can escape is `BranchTipUnavailableException` (a read that did not run to its
exit). *Rationale:* the seam exists, gates its invocation outcome, and is what the classifier
already trusts; a second implementation over `GitObjects` would add a third envelope reader and
a new compile edge for the host adapter. *Alternative rejected:* keep the disk reads and run
`restoreFactoryFiles` first in every bootstrap — the ordering fix. It leaves the forked read
path in place and makes correctness a temporal convention ("the restore already ran"), which no
gate can check; the next reader added before the restore reproduces the defect with a green
build. *Alternative rejected:* read through `GitObjects` (the container medium's reader) so both
media share one class. `GitObjects` is opened per bare repository with a temp directory and is
wired only in the container support and the law source; giving the host adapter a second
`GitObjects` instance per worktree is the write-path unification of NG1, not a read fix.

**D2 — Typed absence replaces exception-cause inspection.** `HostResumeMechanics.loadBranch`
maps `Optional.empty()` of `readTaskRecord` to the delivered route and `readFinalState` maps an
empty `readRecordedState` to the first-stage state (the pre-contract tip); the
`catch (UncheckedIOException) … getCause() instanceof NoSuchFileException` arms are deleted.
`TakeResumeBootstrap` is the one reader `loadBranch` reaches the tip through, so it must *not*
unwrap: `bootstrap` returns `Optional<ResumeBootstrap>`, empty when the tip carries no task
envelope, and `TakeResumeRunner.bootstrap` hands that on as `@Nullable` — that is the value
`loadBranch` routes on. Only the entry points that have no delivered route unwrap:
`TakeFreshClaim`, `GitResumeRunner.bootstrap` (the manual `run --resume` caller of the same
`TakeResumeBootstrap`), `GitModeRunner`, `GitResumeContinuation` call `orElseThrow` with the
application's existing `InternalErrorException` (message: the task id, the file, and that it is
absent at `HEAD` of a worktree the same run committed it to), since on those paths the envelope
was committed moments earlier and its absence is an invariant violation, exactly the class of
impossible state `GitResumeContinuation` already reports with that exception. `BranchStateFileMissingException`
is *not* the type here: it lives in `adapters/git`, and `application` has no compile edge to
that module (only `testImplementation`); it stays the adapter-internal failure of the ref-based
readers and of `DeliveredBranchReader` (D5). *Rationale:* the delivered/pre-contract
distinction is a routing decision; it belongs in a value the compiler sees, not in the cause
chain of an I/O exception. *Alternative rejected:* keep the exception protocol and throw
`UncheckedIOException(NoSuchFileException)` from the tip reader to preserve the call sites —
fakes a filesystem failure for a git answer and keeps the cause-inspection code the change
exists to remove. *Alternative rejected:* a new port-level exception in `app.port.git` for
"envelope absent where it was committed" — a type whose only throw sites are five
`orElseThrow` lambdas for a state that cannot occur; the application already has one exception
for impossible states.

**D3 — One host predicate for "the tip carries the state directory", shared by cleanup and
salvage restore.** `GitShowTip` gains `carries(String path)` — `git cat-file -e
<revision>:<path>` through `GitReadGate.answered`. `CleanupCommit.commit` guards on it at `HEAD`;
`WorktreeSalvage.restoreFactoryFiles` replaces its inline `cat-file -e` with it. The container
twin `GitObjectsTerminalCommits.cleanUp` keeps `gitObjects.exists(tip, DIR_NAME)`; the declared
pair's invariant becomes "both test the tip", which is now literally true. *Rationale:* rule of
three — after this change the host adapter would hold two subprocess implementations of one
predicate (cleanup, salvage) beside the bare-objects one; the two same-medium ones collapse into
the seam that already owns tip reads. *Alternative rejected:* a new `WorktreeTip` class wrapping
`GitShowTip` — a wrapper with one method and no decision, exactly the file-size-only split
`process-invariants.md` forbids.

**D4 — The host cleanup converges from every state of its own two-command sequence.** With the
guard on the tip, the destructive step can meet a worktree in any of these states while the tip
still carries the directory:

| Worktree / index                                                                                                                                                                                       | What `git rm -r --ignore-unmatch .gnomish-task` does          | Then `git commit`                            |
|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------|----------------------------------------------|
| directory present, tracked                                                                                                                                                                             | removes from index and working tree                           | lands the removal                            |
| removal already staged (killed after `rm`, before commit)                                                                                                                                              | no-op, exit 0                                                 | lands the staged removal; INFO line (NFR-O1) |
| files gone from disk, index still tracks them (killed inside `rm` after it unlinked the files but before it wrote the index — `git rm` removes the working-tree files first and writes the index last) | removes the index entries; the missing files are not an error | lands the removal                            |

`--ignore-unmatch` is what turns the second row from a `pathspec did not match` failure (exit
128) into a no-op; the third row matches the index and needs no tolerance. A kill inside `rm`
also leaves `index.lock` behind, which is NG2 — every later git command refuses loudly until it
is removed, so that state never reaches the commit silently. The guard on the tip is what keeps
the fourth state — tip already clean — a no-op in both media. The INFO line for the staged-removal row is emitted when
`git rm` reports nothing to remove while the tip carried the directory: that combination is
reachable only through a predecessor's kill. *Rationale:* this is the four-cell matrix the
crash-consistency rule asks for (directory present/absent × commit landed/not), written down and
tested (FR7) instead of assumed. *Alternative rejected:* build the host cleanup commit from tree
objects (`git commit-tree` with the directory dropped) so no index state matters — correct, but
it is the write-path unification of NG1 and would leave the worktree's `HEAD` ahead of its
index, which the existing `git add -A` lifecycle commits would then mis-stage.

**D5 — `DeliveredBranchReader` locates the `Completed` commit instead of assuming `tip^`.**
`GitShowTip.cleanupCommitInHistory()` is generalized to `cleanupCommit()` returning
`Optional<String>` (the located commit id; the boolean form is kept as a one-line wrapper for the
classifier's fact). The reader then reads at the tip when the tip carries `task.json`, else at
`<cleanupCommit>^`, else reports `BranchStateFileMissingException`. *Rationale:* the classifier
already defines `Delivered` as "cleanup commit found in history, tolerating post-cleanup
commits"; the reader that recovers a delivered state must find that same commit, not guess its
position. *Alternative rejected:* walk `rev-list` for the first commit whose tree carries
`task.json` — finds the right commit but by a second definition of "delivered" that can drift
from the classifier's.

**D6 — Enforcement: an allowlisted whole-tree scan in `:bootstrap`.** `EnvelopeMediumBoundarySpec`
scans `adapters/git/src/main` and `application/src/main/java/com/github/oinsio/gnomish/app`
for the ten filesystem read calls `Files.readString`, `Files.exists`, `Files.notExists`,
`Files.isRegularFile`, `Files.isDirectory`, `Files.readAllBytes`, `Files.newBufferedReader`,
`Files.lines`, `Files.list`, `Files.walk` — `isDirectory` included because it is the one call
that could test `worktree/.gnomish-task` without tripping an `exists` ban — over sources with
comments stripped (`RepoSourceTree`'s existing helper, what the compiler sees), allowlists each
file that legitimately reads a non-envelope path with its reason (`TaskWorktreeManager` —
`isDirectory` on the worktree path before registration; `DirectoryWorkspace` — `isDirectory` on
the workspace root; `WorktreeJanitor` — `list`/`walk` over workspace roots;
`AdHocTaskSynthesizer` — `readString` of an operator's task file), asserts the scan reached
every allowlisted file, and names the owner (`GitTaskStore` over `GitShowTip`) in its failure
message. The `dashboard` and `serveobservability` packages (siblings of `app` under
`application/src/main/java/com/github/oinsio/gnomish/`) are outside the scanned trees on
purpose: they read their own ledger and snapshot files, never an envelope.
*Rationale:* `implementation.md` item 4 — the parameter type cannot enforce this (the reads
take a `Path` a caller can always resolve a file under), so the gate is the scan, in the module
that sees every layer. *Alternative rejected:* a `WorktreeTip` token type that only the owner
mints and every read requires — enforces the read but not the ban on `Files.readString` beside
it; the scan is what bans the old way.

**D7 — The kill-point row.** `FinishKillPoints` gains a fourth step for the host medium only:
"the staged removal" (`git rm -r .gnomish-task` run in the world's worktree without a commit).
The frozen shape stays `CompletedUncleaned`; the pickup runs the ordinary recovery
(`FinishEffect` with the recovery owner's `finishCleanup` — the store's cleanup commit *and*
the worktree disposal behind it, as `HostResumeMechanics.finishCleanup` sequences them) on the
*same* worktree and converges to `Delivered`; the second pass is a no-op. The worktree-disposal
invariant is asserted per window, on the windows whose pickup drives the finish: the window
after the cleanup commit is already settled, so its pickup returns before any disposal. The container world has no such step, so the row is
declared per medium. *Rationale:* the matrix's contract is "kill after each durable step"; the
index write is durable, and the reproduction of the report is exactly this row. *Alternative
rejected:* a standalone adapter spec only — it proves the guard, not the convergence through the
recovery owner.

**D8 — The envelope's path names are owned by `:domain`.** A new `domain.branch.EnvelopePaths`
declares `DIR_NAME`, `DIR`, `TASK_JSON_PATH`, `STATE_JSON_PATH`, `DECISIONS_DIR` and the two bare
file names; `BranchShapeClassifier.TASK_FILE` / `STATE_FILE` become references to it (kept as
public constants for their existing readers), `GnomishTaskPaths` is deleted and its 33 uses in
`adapters/git` and `FactoryOwnedPaths`'s two derivations re-point to the domain owner, and
`ContainerTipReader`'s two literals do the same, as do the five bare-literal sites in
`adapters/git` that never went through `GnomishTaskPaths` (`state/TaskJsonMapper`,
`state/StateJsonMapper`, `state/PinnedRefGate` — the version-gate label becomes
`EnvelopePaths.TASK_FILE` / `STATE_FILE`; `HarvestedBoundaryCheck`, `RoundBoundaryCheck` — the
message opens with `EnvelopePaths.DIR + " was modified…"`). The FR8 scan (D6) additionally
rejects the three literals in any production source outside `EnvelopePaths.java`; it runs over
comment-stripped sources, so the `{@code "task.json"}` mentions in javadoc
(`UnsupportedStateFileVersionException`, `BranchShape`, `StateFileVersionGate`,
`RoundBoundaryViolationException`) are not hits. *Rationale:* a third module
already types the names by hand, so this was never a two-ended pair to declare — it is an owner
that sits one module too high for two of its consumers; `:domain` is the lowest module every
consumer already depends on, and the domain already owns the shape vocabulary the names serve.
*Alternative rejected:* declare the `GnomishTaskPaths` ↔ `BranchShapeClassifier` pair with
markers and a registry row — leaves `ContainerTipReader` as a third spelling with no marker at
all, and `manual-sync-pairs.md` requires the abstraction at three. *Alternative rejected:* a
public owner in `application`'s port package — `:domain` cannot depend on it, so the classifier
would keep its own copy.

**D9 — `excerpt(cap)` bounds its rendered output.** `UntrustedText.excerpt` becomes
`flatten(capTail(flatten(capTail(strip(raw), cap)), cap - TRUNCATION_MARKER_RESERVE))` — the
inner cap keeps the flattening cost bounded as today, the outer one makes `cap` a statement
about what leaves. Two details, found while implementing (2026-09-21) and revised here rather
than left to the code: the outer cap runs under `cap - TRUNCATION_MARKER_RESERVE`, because
`capTail` *prepends* its marker and would otherwise return `cap` characters plus the marker —
`RecordCap`'s existing "marker inside the bound" shape, applied to the tail end; and the
flattening runs once more afterwards, because that marker is written with a newline in it and
the outer cap runs after the flattening that would have neutralized it. On already-flattened
text the second `flatten` is the identity, so it costs nothing and touches only the marker.
Together they also give `excerpt` a floor, `MIN_EXCERPT_CAP_CHARS = 2 * TRUNCATION_MARKER_RESERVE`
(128): under it the bound could not be honoured at all, and the shortest bound any call site
asks for is the decision-file preview's 500. Only the
caller-bounded exit changes: `forLog()` at `DEFAULT_CAP_CHARS` stays input-bounded (NG6), because
the record bound is the sink's guarantee and the sink already renders idempotently over exit
output. The `STDERR_CAP_CHARS` javadoc is rewritten to say the bound is in output characters and
that the headroom under the record cap therefore holds for any content. *Rationale:* the constant
exists to keep the caller's prose and the `exited N` opening; an input bound keeps them only for
text that does not expand. *Alternative rejected:* divide `STDERR_CAP_CHARS` by the worst-case
expansion (six) — cuts the ordinary ASCII excerpt to 233 characters to defend against a case a
second cap handles exactly. *Alternative rejected:* make `forLog()` output-bounded too — changes
the sink's idempotence property (`factory-logging`: "renders byte-identically with and without
the sink layer") for every exit at once, a logging-capability decision this change does not own
(proposal Q2).

**D10 — The rename is a rename.** `WHITELIST` → `ALLOWLIST`, `whitelisted` → `allowlisted`,
every javadoc and comment sentence reworded; no method, field or type visible from
`gnomish-plugin-api` changes its signature, so `japicmpApiGate` stays green and no version bump
is owed. *Rationale:* `process-invariants.md` bans a glossary's *Never:* word everywhere, and the
rename touches published javadoc, which is why it is recorded as a decision rather than done
silently. *Alternative rejected:* a separate change — the user chose to fold it in on
2026-09-21; the cost of the fold is one task group with no coupling to the rest.

### Sync surfaces

This change touches four declared pairs and adds no new parallel implementation:

| Pair                                                                          | Decision                                                         | What the invariant becomes                                                                                                                                                                                                         |
|-------------------------------------------------------------------------------|------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `CleanupCommit` ↔ `GitObjectsTerminalCommits.cleanUp`                         | declared pair kept (different media: subprocess vs bare objects) | both test the **tip** for `.gnomish-task/` and both are a no-op on a clean tip; the host end additionally converges the staged-removal states of its own two-command sequence, which the single-commit container end cannot freeze |
| `TerminalWriteMarker.clearPending` ↔ `GitObjectsTerminalCommits.clearPending` | declared pair kept                                               | both read the DTO they rewrite **from the tip** and clear exactly `trackerWritePending`                                                                                                                                            |
| `StateFileWrite.currentCursor` ↔ `TaskLifecycleCommitWriter.tipStateCursor`   | declared pair kept                                               | both read the cursor from the **tip's** `state.json` and degrade to no cursor; the registry row's "the media differ" clause is rewritten — the media are now the same commit, read by subprocess vs objects                        |
| `WorktreeSalvage` ↔ `EnvironmentSalvage`                                      | declared pair kept                                               | unchanged invariant; the host end's tip test moves to the shared predicate (D3), which the marker text names                                                                                                                       |

The "does the tip carry the state directory" predicate had three implementations before this
change (cleanup on disk, salvage restore by `cat-file`, container by `GitObjects.exists`); D3
collapses the two host ones, leaving the declared pair above.

One undeclared pair is dissolved rather than declared: `GnomishTaskPaths` ↔
`BranchShapeClassifier.TASK_FILE/STATE_FILE` (the envelope's file names, two modules, no marker on
either end, no registry row) becomes the single owner of D8, which `ContainerTipReader` — the
third, hand-typed spelling — also consumes.

### Single-owner mechanisms

| Owner                                            | Value (type)                                  | Consumers                                                                                                                                                                                                                                                                                         | Old way removed                                                                                                                                                                        | Enforced by                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
|--------------------------------------------------|-----------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `GitTaskStore` over `GitShowTip(worktree, HEAD)` | `Optional<TaskRecord>`, `Optional<TaskState>` | `TakeResumeBootstrap` (passes the `Optional` through to `HostResumeMechanics.loadBranch`), `TakeFreshClaim`, `HostResumeMechanics.readFinalState`, `GitResumeRunner.bootstrap` / `continueFrom`, `GitModeRunner`, `GitResumeContinuation`                                                         | `Files.readString(worktree/.gnomish-task/…)` in `GitTaskStore.read`; the `NoSuchFileException` arms in `HostResumeMechanics`                                                           | `EnvelopeMediumBoundarySpec` (D6); the `Optional` return type removes the exception protocol                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `GitShowTip.readAtTip` at `HEAD`                 | `Optional<UntrustedText>` (`BRANCH_DOCUMENT`) | `TerminalWriteMarker.clearPending`, `GitTaskRepository.readCurrentDto`, `StateFileWrite.currentCursor`                                                                                                                                                                                            | the three `Files.readString` calls                                                                                                                                                     | `EnvelopeMediumBoundarySpec`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `GitShowTip.carries(path)` at `HEAD`             | `boolean` (see note)                          | `CleanupCommit.commit`, `WorktreeSalvage.restoreFactoryFiles`                                                                                                                                                                                                                                     | `Files.exists(worktree/.gnomish-task)`; the inline `cat-file -e HEAD:` in salvage                                                                                                      | `EnvelopeMediumBoundarySpec` bans the `Files.exists`/`isDirectory` form; the sweep of task 7.2 asserts the argv `"cat-file", "-e", "HEAD:` appears in `adapters/git/src/main` only inside `GitShowTip`. Exemptions, each a different predicate: `TaskBranchCreator` (`cat-file -e <base>^{commit}`, a commit-existence probe on the clone), `EnvironmentSalvage` (the same `HEAD:` test inside the in-container shell script — the container end of the salvage pair, not a host read), `FactoryCloneHardening` (javadoc) |
| `GitShowTip.cleanupCommit()`                     | `Optional<String>` commit id                  | `DeliveredBranchReader.resolveDeliveredRef`, `cleanupCommitInHistory` (wrapper)                                                                                                                                                                                                                   | the `tip + "^"` literal in `DeliveredBranchReader`                                                                                                                                     | the literal is deleted; `DeliveredBranchReaderSpec` reads a branch with commits after cleanup. Exemption: `HarvestedStateCommitCheck.resolveRef(tip + "^")` is a different question (the parent of a harvested attempt commit, never a cleanup commit) and stays                                                                                                                                                                                                                                                          |
| `domain.branch.EnvelopePaths`                    | `String` constants (see note)                 | every class in `adapters/git` that named `GnomishTaskPaths` (33 uses), `FactoryOwnedPaths`, `ContainerTipReader`, `BranchShapeClassifier`, and the five bare-literal sites `state/TaskJsonMapper`, `state/StateJsonMapper`, `state/PinnedRefGate`, `HarvestedBoundaryCheck`, `RoundBoundaryCheck` | the literals `".gnomish-task"`, `"task.json"`, `"state.json"` in `GnomishTaskPaths`, `ContainerTipReader`, `BranchShapeClassifier` and the five sites; `GnomishTaskPaths.java` deleted | `EnvelopeMediumBoundarySpec` rejects the three literals outside `EnvelopePaths.java`, over comment-stripped sources (D6, D8)                                                                                                                                                                                                                                                                                                                                                                                              |
| `UntrustedText.excerpt(cap)`                     | `String` of at most `cap` characters          | `GitCommandResult.failureDetail`, `cannotVerifyDetail`, every other `excerpt` caller (unchanged call sites)                                                                                                                                                                                       | the input-only bound inside `excerpt`                                                                                                                                                  | `UntrustedTextSpec` asserts `excerpt(cap).length() <= cap` over a `U+2028` corpus; the `STDERR_CAP_CHARS` javadoc states the output bound                                                                                                                                                                                                                                                                                                                                                                                 |

Note on the `boolean` and the `String` constants: a consumer could obtain either elsewhere,
which is why those rows' enforcement is the ban on the old forms (the scan and the `cat-file`
grep, the literal scan), not the type.

Identity claims and their specs: "the host resume reads the same envelope the classifier
classified" is asserted end to end by the new `FinishKillPoints` row (D7) on a real worktree
over a bare origin — that row is the *only* real-medium reproduction of the pickup side of the
report, because no spec drives `HostResumeMechanics` over a real `GitTaskStore`: the
application-layer routing specs (`TakeResumeRoutingSpec`, `TakeResumeShapeTailSpec`) stub
`TaskStoreGit` and can only pin the routing on the typed result, and the read itself is
reproduced at the adapter seam (`GitTaskStoreSpec`, task 1.4); "the delivered
state is the `Completed` commit's" by the `DeliveredBranchReaderSpec` scenario over a branch
with post-cleanup commits (D5). NFR-P1 is pinned on the same media: `GitTaskStoreSpec` counts
the git invocations of one read through a recording git stand-in binary (the
`BaseRefreshCloneSafetySpec` precedent — `new GitProcessRunner(recordingGit(log))` logs every
argv the runner spawns and delegates to the real git; `GitProcessRunner` is final, so the
binary, not the class, is the seam), and the D7 row counts the envelope reads of one pickup
the same way (M9).

## Risks / Trade-offs

- [Each envelope read is now a subprocess where it was a file read] → the reads on a take path
  number under ten and each is a bounded `git show` of a small blob (NFR-P1); the manual-run
  status/usage paths already pay this per invocation today.
- [A worktree left with a staged removal is also left with `HEAD` moved after the converged
  commit; the next `git add -A` lifecycle commit on that worktree would stage nothing wrong,
  but the worktree is removed right after a `Completed` cleanup anyway] → `TaskWorktreeCleanup`
  runs behind `finishCleanup` in `HostResumeMechanics.finishCleanup`; D7's row asserts the
  worktree is gone.
- [`git rm --ignore-unmatch` hides a genuine misspelling of the pathspec] → the pathspec is the
  one constant `GnomishTaskPaths.DIR_NAME`, and the guard just proved the tip carries it; a
  spec asserts the commit's tree lacks the directory, so a silent no-op cannot pass.
- [The scan's allowlist is a list of paths that can rot] → the spec asserts it reached every
  allowlisted file, the precedent's shape; a moved file fails loudly.
- [Callers that `orElseThrow` on a read that used to throw `UncheckedIOException` change their
  failure type] → `InternalErrorException` is what the application already throws for an
  impossible recorded state (`GitResumeContinuation`); the exception-mapping specs of the take
  command are updated in task 3.3.
- [`introduce-take-order` rewrites the same application call sites] → this change is sequenced
  first (proposal header); that change rebases its call-site table on the `Optional` protocol.
- [The `index.lock` window (NG2) still exists] → recorded, unchanged; it fails loudly with git's
  own message on the next command rather than silently.
- [Moving 33 references from `GnomishTaskPaths` to a domain type is a wide mechanical diff in
  the same files `type-untrusted-text` touched] → sequenced after that change is committed; the
  move is one task with a compile-driven checklist and no behavior change.
- [The literal scan catches `"task.json"` in a test fixture or an error message] → the scan
  covers production sources only, and matches string literals — javadoc and message prose that
  mention the name are not literals of the name alone; a message that does embed the bare
  literal re-points to the owner, which is the intent.
- [The outer cap in `excerpt` could cut inside a rendered escape (`\u20` … `28`)] → `capTail`'s
  marker names the cut, and the truncated escape is inert ASCII; readability only.
- [The rename touches published javadoc] → `japicmpApiGate` proves no signature moved; the
  plugin-API version is not bumped.

## Migration Plan

No data migration: branch content, commit messages, and the envelope wire format are unchanged.
Deploy is a factory upgrade; a worktree left in the staged-removal state by a previous version
converges on the first pickup by the new one (that is D4's second row). Rollback: the previous
version reads the worktree again and reproduces the report's behavior on such worktrees — the
branch stays consistent either way, since nothing this change writes is new.

Sequencing: after `type-untrusted-text` (committed as #58 and archived on 2026-09-21 — it
rewrote the same adapter files, and this change builds on that state) and before
`introduce-take-order` (it rewrites the same application call sites; proposal header).

## Open Questions

None. NG1 (one writer for both media) is decided after this change's audit, from the pair rows
that remain in `manual-sync-pairs.md`.
