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
