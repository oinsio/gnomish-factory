# Rule: what a lock may be held across

Applies to every monitor and lock in production code — `synchronized` methods and blocks,
`ReentrantLock`, `Semaphore` used as a mutex.

## The rule

**A lock that guards in-memory state is never held across a blocking operation.** Blocking
means a subprocess, a network call, file I/O, a `sleep`, or a caller-supplied callback that
can do any of those. The shape is three phases: decide under the lock, do the work with
nothing held, take the lock again to record the outcome.

The industry statements of this are CERT LCK09-J ("Do not perform operations that can block
while holding a lock") and *Java Concurrency in Practice* ("avoid holding locks during
lengthy computations or operations at risk of not completing quickly, such as network or disk
I/O"). The harm is waiter starvation: threads that need the lock for a microsecond wait out
an operation bounded by a network deadline.

## The failure this rule exists for

`RemoteOutageGate.probeIfDue` (FR14 of `add-base-ref-resolution`) ran its recovery probe — a
`git ls-remote`, bounded by `factory.git-network-timeout`, five minutes by default — inside
the monitor that every serve slot's `openOnFailure`/`onSuccessfulRefresh` signal also takes.
Both signals are pure no-ops while the gate is open, so a dead remote stalled, for the whole
probe, exactly the threads that had nothing to do. Nothing was red: each method was correct
in isolation, and no single-threaded spec can see a lock being held too long.

## The exception: a lock whose purpose IS the I/O

Some locks exist precisely to serialize an external operation. They are not violations, and
they must not be "fixed" by moving the operation out — that would delete the mechanism. Two
in this codebase:

- `CloneMutationLock` — `fetch`/`push`/`worktree add|remove|prune` against one clone's shared
  `.git` serialize so concurrent slots never race git's own fail-fast locking.
- `FreshJudgeEnvironments` — one judge box per attempt commit; a second caller must wait for
  `materialize` rather than build a second box.

The bar for claiming this exception, stated in the class's own javadoc:

1. The guarded resource **is** the external thing (a repository, a container), not a field.
2. Waiting is the intended outcome for every waiter — they are all doing the same kind of
   work. A waiter that only wanted to read a flag disqualifies it.
3. The hold is **bounded**, and the javadoc names by what (a subprocess deadline, a timeout).

## The three-phase shape

```java
void act() {
    if (!begin()) {          // phase 1: decide, claim, under the lock
        return;
    }
    Answer answer;
    try {
        answer = blockingCall();   // phase 2: nothing held
    } catch (RuntimeException e) {
        abandon();           // the claim must not leak on the failure path
        throw e;
    }
    record(answer);          // phase 3: apply, under the lock
}
```

Phase 1 must claim as well as decide: the lock no longer spans the operation, so an
in-progress flag is what keeps two callers from both starting one. Whatever phase 1 sets,
**every** exit from phase 2 clears — the exception path included.

## Re-entry: revalidate, or prove you need not

Reacquiring a lock after calling out means the state the decision rested on may have moved.
Either revalidate it, or state in the javadoc why nothing could have changed it. Do not write
an unreachable guard to look careful: it is dead code, and it is an unkillable mutant under
the 100% gate (`testing.md`).

## Callbacks count as blocking

A caller-supplied `Runnable`/`Consumer` invoked under a lock is code this class does not
control: it may block, and it may take another lock in the other order. Invoke it after the
lock is released, or record in the javadoc what bounds it.

## Virtual threads are not a defence

Since JDK 24 (JEP 491) `synchronized` no longer pins the carrier thread, so a blocking call
under a monitor no longer starves the scheduler — and for that same reason, swapping
`synchronized` for `ReentrantLock` is not a fix for anything this rule covers. What this rule
is about is unchanged: the waiters, who are blocked either way.

## A lock inside the JDK: no pipe into a child

A child's standard input is never a pipe the factory writes into. The JDK's `Process` stdin is a
`BufferedOutputStream` whose `write()` holds the stream's monitor while it is parked on a full
pipe, so `close()` from another thread blocks on that monitor in `flush()`, an interrupt does
nothing, and the JDK's reaper thread blocks behind it in the `synchronized` `processExited`. A
descendant that inherited the read end and never drains it holds the pump forever, and no bound
can be put on it from outside. So a design that needs to hand a child bytes
passes them to the launch owner as a `StdinSource` (a file the child inherits, or `/dev/null`),
and a new stdin pump is refused at design; the `lifecycle/subprocess-supervision` capability owns
the rule ("A child's input is a file or nothing").

The failure this exists for: the factory's stdin writers — `GitExecStreams.feed`,
`ContainerFileChannel.pump`, `ChildProcessStdin.feed` and the calling-thread write of
`CaptureRunner.run` — were each parked for good by a descendant holding the child's stdin, and
every other launch left the JDK's default pipe open for one to inherit. An amendment of design
D15 of `fix-terminal-receipt-convergence` tried to bound the writers as the read side was then
bounded, by closing the write end; on JDK 25.0.3 the close blocked on the monitor. Design D20 of
that change removed every pump instead.

The read side is no exception. A `read()` parked on a pipe a descendant still holds is beyond
every JDK mechanism on Linux: `close()` from another thread and `Thread.interrupt()` wake
nothing, the thread stays parked until the holder dies, and while it holds the stream's monitor
the JDK's reaper blocks behind it in the `synchronized` `processExited` (JDK-8169565). So the
factory never parks in a read of a child's pipe and closes no pipe of a child: the one owner of
the readable side, `ChildOutput` in `:subprocess`, reads only what `available()` reports, waits
one tick on its own thread otherwise, stops on a flag, and leaves the pipe to the JDK's reaper,
which drains and closes it at the exit (`docs/adr/0018-non-parking-pipe-reads.md`; the
`lifecycle/subprocess-supervision` capability owns the requirement, "Post-exit output is bounded
without a parked read").

The failure this exists for: design D15 of `fix-terminal-receipt-convergence` bounded every
reader by closing the stream at the post-exit bound, on the premise that a close ends a read
parked on the pipe — measured on macOS only, where it does. On Linux it does not, and the first
Linux CI run after that change (run 39117545428) was red; the test doubles had modelled macOS.
`make-pipe-reads-non-parking` replaced the close with the non-parking read.

## Writers from different processes

A lock in the JVM covers one process. A step that two factory processes may run at once on one
clone — `take <ref>` beside a `serve` daemon's janitor, two `take`s of one finished task — is
guarded only by what the medium itself locks, and git locks its media unequally: an object write
takes no lock (content-addressed), a ref transaction takes the ref store's lock, retries it
(`reftable.lockTimeout`) and decides by compare-and-swap, and the worktree index takes
`index.lock` once, with no retry, no compare-and-swap, and no cleanup after a kill.

So a design that claims a step is safe across instances — an NFR "idempotent across instances",
a risk row "the loser stands down" — lists the step's sequence in `design.md` with the lock each
step takes and what decides the winner, the way `crash-consistency.md` item 1 lists kill windows.
**The first step that takes a lock with no compare-and-swap behind it is the race**: a loser
there has nothing to re-read, because the winner has not committed yet. Such a step is removed
from the sequence (an object-built commit needs no index) or moved behind the decision, never
retried. The table is paired with a race spec on real threads, named in the single-owner table
beside the identity spec. A `synchronized` block, a `ReentrantLock` or any other lock in the JVM
is no answer to this question: the second writer is another process.

The failure this exists for: the host delivered cleanup
(`fix-terminal-receipt-convergence`, task 3.3) ran `git rm` in the shared task worktree before
its compare-and-swap; the design's risk row said "the ref update is compare-and-swap on both
media", true of the last step only. Two host cleanups met on `index.lock`, and the loser
re-read a tip the winner had not yet moved and failed. Design D8 of that change removed the
step: the host cleanup is now built from objects through `GitObjectsTerminalCommits.cleanUp`
(a private index, no worktree), so the ref transaction is the first and only lock two writers
share; its step table is the model, and `DeliveredCleanupRaceSpec` is the race spec.

## Specs

The property is invisible to a single-threaded spec — the one that would catch it drives real
threads against a collaborator that blocks on a latch and asserts the waiter returns promptly
(`RemoteOutageGateProbeConcurrencySpec` is the in-repo model). A change that introduces the
three-phase shape writes that spec; without it the regression is silent.

## How this is checked

There is no build gate, and there will not be one: what sits inside a `synchronized` region
is invisible to Error Prone and ArchUnit, and CERT itself marks the rule **Detectable: No**.
So it is a review obligation — `/audit-codebase` checks every lock site against this file, and
a change that adds or widens one says in its artifacts which case it is: state-guarding lock
(the rule) or resource-serializing lock (the exception, with the three bar items answered).

## Checklist

1. Every `synchronized`/`lock()` region: is anything inside it a subprocess, a network call,
   file I/O, a sleep, or a callback?
2. If yes — is this the exception? Then the javadoc answers the three bar items.
3. If not the exception — split into the three phases, with a claim flag if two callers could
   otherwise both start the work.
4. Every exit from the unlocked phase clears the claim, exceptions included.
5. On re-entry, revalidate — or say why no revalidation is possible, rather than writing a
   guard that cannot fire.
6. A spec with real threads pins the waiter's promptness.
7. A step two factory processes may run at once: `design.md` lists its sequence with the lock
   each step takes, the first lock with no compare-and-swap behind it is removed or moved
   behind the decision, and a real-thread race spec is named in the single-owner table.
8. A child that needs input gets it through the launch owner's `StdinSource`, never from a
   thread writing into its stdin pipe.
9. A reader of a child's output takes a `ChildOutput`, never the raw stream: no thread parks
   in a read of a child's pipe, and nothing closes one.
