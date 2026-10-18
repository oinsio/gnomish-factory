package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.subprocess.ProcessGroupSweep;
import com.github.oinsio.gnomish.subprocess.ProcessSupervisor;
import java.time.Duration;

/**
 * The shutdown step (design D19 of fix-terminal-receipt-convergence, fourth amendment; FR11, design
 * D9 of add-factory-serve): the cooperative snapshot kill of every descendant of the current JVM,
 * then a sweep of every process group the live group registry still holds, so no subprocess the
 * factory started outlives it on any exit path that runs a hook. Run by {@code serve}'s hook through
 * {@link ServeShutdown} and, for every command that owns no hook, by the generic signal hook through
 * {@code OrderedExit.onSignal}, before the context closes.
 *
 * <p>The first half kills what the snapshot of the JVM's descendants lists, and cooperatively first:
 * git and docker remove their lock and temporary files on a catchable signal and not on {@code
 * SIGKILL}. Its discipline — the cooperative signal, the forcible phase for what is still alive after
 * the grace, a re-snapshot that catches a child forked inside it, the reap — is the shared {@link
 * ProcessSupervisor}'s (FR14, design D14 of bound-subprocess-commands).
 *
 * <p>The second half is {@link ProcessGroupSweep#sweepLive()}: a member of a launch's group that the
 * child lost to reparenting is no descendant of the JVM, so no snapshot lists it, and the waiter that
 * would sweep its group runs only once its post-exit bound allows — or never, when the JVM exits
 * first. A child in its own session also receives nothing from the terminal, so a Ctrl-C in a
 * foreground {@code gnomish run} reaches it only here. A group its waiter sweeps at the same moment
 * costs one refused probe.
 *
 * <p>Bounded: the snapshot kill by twice the forcible wait, the sweep by at most five rounds per
 * registered group, each {@code kill} by its own bound; nothing here holds a lock.
 * {@code ShutdownSweepSpec} drives it on real processes, for a {@code run}-shaped hook and for
 * {@code serve}'s.
 *
 * <p>Implements FR11, D9 of add-factory-serve; FR14, D14 of bound-subprocess-commands; FR21 of
 * fix-terminal-receipt-convergence (D19, fourth amendment).
 */
public final class RealProcessTreeKiller implements ProcessTreeKiller {

    private static final Duration DEFAULT_FORCIBLE_WAIT = Duration.ofSeconds(1);

    private final ProcessSupervisor supervisor;
    private final ProcessGroupSweep sweep = new ProcessGroupSweep();

    public RealProcessTreeKiller() {
        this(DEFAULT_FORCIBLE_WAIT);
    }

    /** @param forcibleWait how long to give a destroyed descendant to exit before forcing it */
    RealProcessTreeKiller(Duration forcibleWait) {
        this.supervisor = new ProcessSupervisor(forcibleWait);
    }

    @Override
    public void killDescendants() {
        supervisor.terminateDescendants(ProcessHandle.current());
        sweep.sweepLive();
    }
}
