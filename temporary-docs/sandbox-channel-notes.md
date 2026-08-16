# Factory ↔ Box Channel — Exploration Notes

Working notes for `add-sandbox-core` deferred questions (#2 factory-writes-into-box,
plus decision-file durability). Not an OpenSpec artifact — to be folded into the
change's deltas after the discussion rounds are done, then deleted.

## Write inventory (who writes what)

### Factory (trusted zone)

| What | Why | Where | When |
|---|---|---|---|
| task branch | task isolation | factory clone | task start |
| initial `.gnomish-task/` + commit | resumability from zero | working copy → branch | materialize |
| `state.json` + `trace.jsonl` + `add -A` + round commit (ONE commit) | strict attempt persistence | working copy → branch | every round end |
| `task.json` outcome + service commit (two-phase terminal marker) | park/escalate/complete survive death | working copy → branch | terminal transitions |
| outcome reset + decision append + commit | continue after human decision | working copy → branch | resume |
| salvage commit (`add -A`) | keep interrupted round's tail | working copy → branch | resume after death |
| cleanup commit (remove `.gnomish-task/`) | clean tip for merge, audit stays in history | working copy → branch | Completed |
| push / tracker statuses+reports / logs | visibility, coordination | origin / tracker / instance FS | factory-side, unchanged |

### Gnome (untrusted zone — already writes in-box natively, no channel needed)

Product files and own git commits (the work); decision file (ask a human);
out-of-branch residue in `$HOME`/caches/tmp (legit tooling or injection
persistence — reset by fresh-box). Forbidden/pointless: `.gnomish-task/`
(boundary check), pipeline law (read from factory clone), push (no creds).

### QC loop

Command-check side effects in working copy (mostly gitignored) + findings JSON
at a factory-allocated path; judge → stdout only (read-only tools); external →
CI platform, polled. QC memory (`state.json` attempts, `task.json` report) are
factory writes above. Feedback to the next round travels in the PROMPT, not a file.

## Key facts from code

- Round commit is ONE commit: factory `Files.writeString`s state into the
  worktree, then `git add -A && commit` (`GitAttemptPersistence`).
- Exactly 4 `Files.writeString` sites: `GitAttemptPersistence`,
  `TerminalWriteMarker`, `GitTaskRepository`, `TraceLineWriter` — the whole
  shared-FS coupling. `GitProcessRunner.run(root, args…)` is already
  channel-shaped (swap transport, callers unchanged).
- Domain ports are intent-level and sufficient; `Workspace` is an empty marker —
  `TaskExecutionEnvironment` can BE the workspace, engine untouched.
- `DecisionFileTransport` / findings file: `Files.createTempDirectory` in host
  tmpdir — meaningless inside a container.

## Conclusions so far (leanings, not yet specced)

1. **In-box FS integrity is unwinnable** (gnome may leave a background daemon
   that rewrites files after factory writes, before `git add` — bypassing the
   ".gnomish-task untouched" check, which only sees gnome COMMITS). Integrity
   moves to the boundary: **read-back verification** — after harvest, factory
   byte-compares harvested `state.json`/`trace.jsonl` against what it intended
   to write; mismatch = boundary violation → abort.
2. **Channel shape**: extend the `TaskExecutionEnvironment` port (FR1) with two
   narrow file ops — `putFile(relPath, content)` (factory-chosen paths,
   confined to working copy) and `readFile(path, sizeCap)` (only
   factory-allocated paths) — plus `exec` for git-in-box. Adapters: host =
   `Files` (+symlink resolve/refuse); docker/VM = `docker exec` with
   stdin/stdout; k8s = `kubectl exec -i`; GHA = via branch / workflow artifacts.
3. **Boundary-only invariant**: all six factory writes happen at round
   boundaries, never mid-round. Codify in the port contract — otherwise the
   GHA adapter (change E, no interactive exec) cannot implement it.
4. Factory-invoked in-box git runs with `-c core.hooksPath=<empty>` (argv-level,
   gnome can't override); factory writes run as the gnome user, not root
   (symlink tricks then can't overwrite root-owned cage configs).
5. **Channel security invariants (box → factory)**:
   - nothing read from the box is ever executed or interpolated (shell, git
     refspec/ref, factory FS path) — inert data in every sink (FR15 generalized);
   - box content never materializes as files on factory FS — bytes in memory,
     parse, discard; logs get sanitized capped excerpts only;
   - factory fixes all paths; box-reported paths ignored; host adapter resolves
     symlinks and refuses escapes (extend FR17 confinement to READS);
   - never `docker cp` (host-side tar extraction, CVE-2018-15664 class) —
     `docker exec cat` streams bytes instead;
   - every read is size-capped (NFR-C1 principle extended to the channel).
6. Decision/findings physical location in container mode: container-local
   `/tmp` (tmpfs) — outside volume and working copy, invisible to harvest,
   auto-cleaned on dispose. (For decisions, superseded if the in-branch
   variant below is adopted.)

## Option under discussion: decision file in the git branch

Gnome writes the request to `.gnomish-task/decisions/<stage>-a<attempt>.json`
inside the working copy instead of a temp path.

**Pros**: durability for free (round commit / salvage `add -A` already capture
it — a second instance resumes the escalation without replaying the round);
content reaches the factory via the hardened harvest path; request visible in
the PR during escalation; main stays clean via the existing
final `.gnomish-task/` cleanup commit (decided: no eager per-decision removal —
the cleanup commit on Completed is sufficient).

**Costs / decisions needed**:
1. Boundary-check carve-out: ".gnomish-task untouched by gnome" gets one narrow
   exception — exactly the `decisions/<stage>-a<N>.json` path ("one writer per
   file" holds: that file's writer IS the gnome).
2. Staleness: fixed paths would resurrect the stale-file problem the per-round
   temp path was designed against → the stage+attempt-named path makes files
   self-identifying; adapter matches against the current round only.
3. Manual-run in-place mode has no git and forbids runner files in the
   workspace → keeps the temp-file transport (per-mode fork of the protocol).
4. Honest accounting: this does NOT remove `readFile` from the port — the
   adapter still reads the file right after process exit (before persist) to
   construct `DecisionNeeded`, and the findings file stays transient (must not
   pollute rounds). The win is durability + protocol unification, not channel
   reduction.

**Decided (2026-08-03)**: adopt, with the self-identifying path and the narrow
boundary-check exception; no eager removal — the existing Completed cleanup
commit keeps main clean.

## Builtin checks → the snapshot-first round protocol (deferred #3)

Grounded fact: engine order today is round → verify → persist(ONE commit)
(`StageAttemptLoop`), so verification runs on the UNCOMMITTED tree. But the
change's own deltas (`verify-in: fresh-box`, judge-fresh-box) materialize
"from the attempt commit" — which does not exist at verify time. The
reordering is therefore forced independently of builtins.

**Sandboxed round protocol (crystallized)**:
1. round runs in box;
2. snapshot commit in box (`add -A`; includes decision file) — tail of the
   executor adapter's `execute()`, engine untouched; the concrete `Workspace`
   carries the attempt-commit ref to check runners;
3. harvest → factory holds attempt commit N;
4. verify: builtins = bare reads of the attempt commit in the factory clone
   (trusted, zero channel, one implementation for ALL adapters); command
   same-box = exec in round box; fresh-box command + judge = materialize from
   the attempt commit; external = push attempt commit → CI runs exactly it →
   poll (fixes today's fuzzy "triggered by mid-round pushes" semantics);
5. persist = state.json + trace via putFile → state commit in box → harvest →
   read-back verification.

**Costs accepted so far (pending user confirmation)**:
- two commits per round in sandboxed mode (snapshot + state) — breaks the
  deliberate one-commit invariant of add-git-workflow IN THAT MODE ONLY; host
  stays behavior-neutral with today's single commit;
- new resume intermediate state: "snapshot exists, no state record" = died
  during verify → resume re-verifies (no attempt burned);
- rejected alternative: builtins via exec/readFile against the box tree —
  untrusted answers, per-adapter code, and the reordering is forced anyway.

**Walker & boundary-check audit (2026-08-03, decided: two commits accepted)**:
- `UsageHistoryWalker` is UNAFFECTED: it walks state.json CONTENT via a
  path-filtered log (design D14, never commit messages) — snapshot commits
  don't touch state.json and are invisible to it; state commits carry exactly
  the "attempts grew" diffs it detects. Minor hardening note: a gnome/daemon
  commit touching state.json enters its log and may yield a garbage row or a
  parse throw in `gnomish usage` on a poisoned (already-aborted) branch —
  make the walker tolerant of unparseable state.
- `RoundBoundaryCheck` moves factory-side onto harvested refs: HEAD-on-branch
  → subsumed by the fixed-refspec ff-only harvest (in-box `symbolic-ref`
  before snapshot is advisory only); rewrite → the ff-only fetch refusal
  itself; `.gnomish-task/` untouched → `diff prevTip..harvestedTip` in the
  factory clone WITH the `decisions/<stage>-a<N>.json` carve-out.
- Two NEW trusted assertions close the in-box-daemon gap at the git level:
  (1) parent-check — the harvested state commit's parent MUST be the snapshot
  commit (daemon-inserted commits abort); (2) content read-back (above).
- Violation semantics preserved: detection at harvest, factory-side → Abort;
  evidence stays on the branch and in the kept (stopped) environment.

## FR19 scope: law binds per invocation (deferred #4, discussed 2026-08-03)

Grounded fact: law has TWO layers today — manifests are snapshotted at startup
into the immutable `PipelineDefinition`, but control files and judge criteria
are read LAZILY from the workspace root at use time
(`JudgeCriteriaPreflight.read(root, …)`, `ControlFilePreflight`) — the actual
hole FR19 targets, present in all modes.

**Reformulation**: pipeline law is bound per INVOCATION (visit):
1. Source by mode: tracker-driven (`take`/`serve`) and manual-run git mode →
   factory clone of the base branch, bare reads (in git mode the clone already
   exists: `--dir`, which the runner never mutates); in-place mode → workspace
   snapshot at invocation start (no better source exists).
2. Freshness: law is (re)read at each invocation start and frozen for its
   lifetime, INCLUDING the in-process outcome loop — matches manual-run's
   existing "loaded once at startup" semantics; FR19's "resume re-reads law"
   maps to "each invocation re-reads" (tracker resume = new invocation).
3. The lazy-read gap closes: control files and judge criteria are read from
   the law source, never from the working copy at use time — uniform across
   modes, and physically forced in sandboxed mode (factory can't see box FS;
   prompts are composed factory-side).
4. FR19's contract test is scoped to git modes (in-place has no gnome branch).

In-place mode is host-only BY CONSTRUCTION (no branch to materialize) and the
existing FR14 needs/passport reconciliation already fail-closes it when a repo
declares sandbox needs. Docs note the accepted trade-off: in-place law source
is the workspace itself; the "next invocation" is a human action.

## Prompt delivery: argv → stdin (resolved 2026-08-03)

Grounded fact: the prompt is passed as ONE argv argument (`claude -p <prompt>`,
`AgentCommandLine`). Linux caps a single argument at `MAX_ARG_STRLEN` = 128 KB
— and the round prompt carries the full feedback of ALL prior attempts plus
judge criteria and control files, so late attempts can realistically hit
`E2BIG`. This is a latent bug in host mode on Linux today, independent of the
sandbox. Argv prompts are also visible to any host user via `ps` (they embed
fenced untrusted findings).

**Decisions**:
1. The port's `exec` gains optional stdin: `exec(cmd, env, stdin?)` — needed
   for prompts, for git-with-content commands, and as the docker `putFile`
   transport (`exec cat > path`).
2. The prompt moves from argv to stdin in ALL modes (`claude -p` reads stdin
   when no prompt argument is given) — a bug fix, not just sandbox enablement.
3. Honest cost: fake-agent contract tests read the prompt from argv and must
   be updated; migration step 1 is not 100% behavior-neutral at this point —
   state it explicitly in the tasks rather than hiding it.

## Status

All deferred questions are resolved: channel shape (#2), decision file in
branch, snapshot-first protocol + builtins (#3), FR19 law-per-invocation (#4),
prompt stdin. Next: fold everything into the change's proposal/design/deltas
and delete this file.
