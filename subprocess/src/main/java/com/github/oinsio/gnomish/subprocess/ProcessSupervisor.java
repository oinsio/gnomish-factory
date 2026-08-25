package com.github.oinsio.gnomish.subprocess;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * The supervision primitive: waits for a started {@link Process}, optionally under a hard deadline,
 * and on expiry or interruption terminates the whole process tree and reaps it before returning a
 * named {@link Termination}.
 *
 * <p>The primitive owns the wait and the kill, never the input/output. Streaming callers (an agent
 * CLI round, a container file channel, {@code GitExec}'s capped stdout) drive it directly and keep
 * their own readers; capture-shaped callers use {@link CaptureRunner}, which adds the concurrent
 * drains around this class. Nothing here logs — the outcome is the report (NG4).
 *
 * <p>The kill is also reachable without a wait: {@link #terminate} for a command the caller
 * started, {@link #terminateDescendants} for the shutdown case where the only thing known is that
 * nothing the JVM spawned may outlive it.
 *
 * <p>The kill is two-phase by design (D3): descendants are snapshotted <em>before</em> the parent
 * is signalled, because a dead parent no longer enumerates them; the whole tree is asked to stop
 * cooperatively, since git and docker remove their lock and temporary files on a catchable signal
 * and not on {@code SIGKILL}; only what is still alive after the kill grace is forced. The forcible
 * phase re-snapshots the parent's descendants so a child forked between the first snapshot and the
 * signal is caught too.
 *
 * <p>An interrupted wait restores the flag before terminating and nothing inside {@link #terminate}
 * consumes it again: the grace and the reap are uninterruptible bounded waits, so a shutdown still
 * gets the cooperative phase git and docker need to clean up after themselves, and the caller up
 * the stack still sees the interrupt. The cost is bounded by twice the kill grace, which is part of
 * NFR-R1's margin. That leaves this class exactly one interrupt-handling site — {@link #await}'s
 * own — driven deterministically by pre-interrupting the calling thread rather than by winning a
 * race, which is what lets the five per-module copies of this catch block drop their {@code
 * @DoNotMutate} timing-race exemptions (M5).
 *
 * <p>Implements FR3, FR6, FR9, FR14, NFR-R1, NFR-R2, G5 of bound-subprocess-commands.
 */
public final class ProcessSupervisor {

    /** How long a signalled tree is given to stop cooperatively before it is forced (design D3). */
    static final Duration DEFAULT_KILL_GRACE = Duration.ofSeconds(5);

    /**
     * Reported when a terminated process could not be observed to exit — the reap itself was
     * interrupted, or a handle outlived its bound. Diagnostic context only: {@link
     * Supervision#termination()} already says the invocation did not run to completion.
     */
    private static final int UNKNOWN_EXIT_CODE = -1;

    private final Duration killGrace;

    /** A supervisor with the default five-second kill grace. */
    public ProcessSupervisor() {
        this(DEFAULT_KILL_GRACE);
    }

    /**
     * @param killGrace how long a cooperatively signalled tree is given before it is forced; part
     *     of NFR-R1's margin, so it is bounded rather than generous
     */
    public ProcessSupervisor(Duration killGrace) {
        this.killGrace = killGrace;
    }

    /**
     * Waits for {@code process} and reports how the wait ended.
     *
     * <p>With no deadline the wait is unbounded — the local-command shape (NG3): a local git
     * plumbing command that hangs is a broken machine, not a broken remote. With a deadline, expiry
     * terminates the tree and reports {@link Termination#TIMED_OUT}; an interrupt terminates the
     * tree, restores the interrupt flag, and reports {@link Termination#INTERRUPTED}. Neither is
     * ever dressed up as an exit code.
     *
     * @param process the already-started process to supervise
     * @param deadline the hard bound on the wait, or {@code null} for an unbounded one
     * @return the named termination and the process's exit value
     */
    public Supervision await(Process process, @Nullable Duration deadline) {
        try {
            if (deadline == null) {
                return new Supervision(Termination.EXITED, process.waitFor());
            }
            if (process.waitFor(deadline.toMillis(), TimeUnit.MILLISECONDS)) {
                return new Supervision(Termination.EXITED, process.exitValue());
            }
            return new Supervision(Termination.TIMED_OUT, terminate(process));
        } catch (InterruptedException interrupted) {
            // Restored before the kill on purpose: the kill's own waits never consume it again, so
            // the caller up the stack still sees the interrupt (see the class comment).
            Thread.currentThread().interrupt();
            return new Supervision(Termination.INTERRUPTED, terminate(process));
        }
    }

    /**
     * Terminates {@code process} and everything it started, cooperatively first and forcibly after
     * the kill grace, then reaps the tree so no descendant is left behind (FR3, NFR-R2, G5).
     *
     * @param process the process whose tree is to be terminated
     * @return the process's exit value, or {@code -1} if it could not be observed to exit
     */
    public int terminate(Process process) {
        ProcessHandle parent = process.toHandle();
        // Snapshot first: once the parent is gone it no longer enumerates its descendants.
        List<ProcessHandle> tree = parent.descendants().toList();
        // The grace is spent on the parent alone: a descendant that ignores the request must not
        // hold the whole invocation at the bound when the process we started has already gone.
        killTree(List.of(parent), parent, tree);
        return process.isAlive() ? UNKNOWN_EXIT_CODE : process.exitValue();
    }

    /**
     * Terminates everything {@code parent} started, at any depth, under the same discipline {@link
     * #terminate} applies — and leaves {@code parent} itself running.
     *
     * <p>This is the shutdown shape rather than the per-command one: the caller is the JVM asking
     * that no subprocess it spawned outlive it, so there is no process to wait for afterwards and
     * no exit value to report. What the shared discipline adds over a hand-rolled destroy-sleep-
     * force is the reap — the method does not return while something it signalled is still dying —
     * and the re-snapshot that catches a child forked inside the grace (FR14, design D14).
     *
     * @param parent the process whose descendants are to be terminated; not itself signalled
     */
    public void terminateDescendants(ProcessHandle parent) {
        // The grace is spent on the descendants themselves here: they are the whole subject, and
        // there is no parent exit to wait for instead.
        List<ProcessHandle> descendants = parent.descendants().toList();
        killTree(descendants, parent, List.of());
    }

    /**
     * The two-phase kill of design D3, over a set of handles to signal directly ({@code primary})
     * and a snapshot of the tree below {@code root} taken before any of it was signalled.
     *
     * @param primary the handles the caller is killing outright, and the ones the kill grace is
     *     spent waiting on
     * @param root the handle whose descendants are re-enumerated for the forcible phase
     * @param tree descendants snapshotted before the signal, forced later even if {@code root} died
     *     in the meantime and stopped enumerating them
     */
    private void killTree(List<ProcessHandle> primary, ProcessHandle root, List<ProcessHandle> tree) {
        primary.forEach(ProcessHandle::destroy);
        tree.forEach(ProcessHandle::destroy);
        awaitExit(primary, killGrace);
        // Whatever is still alive is forced — unconditionally, because forcing a process that has
        // already exited is a no-op, and asking "did it exit?" first would be a branch no test can
        // tell apart from taking it. Re-snapshot while the root still lives: a child forked between
        // the snapshot above and the signal is reachable only here.
        List<ProcessHandle> forked = root.descendants().toList();
        forked.forEach(ProcessHandle::destroyForcibly);
        primary.forEach(ProcessHandle::destroyForcibly);
        // Descendants that ignored the cooperative signal — including any orphaned by a parent that
        // took it — are forced from the original snapshot, which survives the parent's death.
        tree.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        // The reap: nothing is reported as terminated before it has actually been observed to die.
        // The re-snapshot is reaped alongside the rest — a child forked inside the grace was
        // signalled by this method too, so returning while it is still dying would leave exactly
        // the descendant the re-snapshot exists to catch outliving the invocation.
        awaitExit(Stream.of(primary, tree, forked).flatMap(List::stream).toList(), killGrace);
    }

    /**
     * Waits — bounded, and deliberately without consuming an interrupt — for every handle to exit,
     * so neither the grace nor the reap can outlive its bound and neither consumes the interrupt
     * flag a caller further up the stack still has to see. {@link CompletableFuture#join()} is the
     * uninterruptible half of the pair and {@code completeOnTimeout} supplies the bound, which is
     * why this class has exactly one interrupt-handling site left — {@link #await}'s own.
     */
    private static void awaitExit(List<ProcessHandle> handles, Duration bound) {
        CompletableFuture.allOf(handles.stream().map(ProcessHandle::onExit).toArray(CompletableFuture[]::new))
                .completeOnTimeout(null, bound.toMillis(), TimeUnit.MILLISECONDS)
                .join();
    }
}
