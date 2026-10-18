package com.github.oinsio.gnomish.subprocess;

/**
 * The process group sweep (design D19 of fix-terminal-receipt-convergence): the one post-exit kill
 * of a host invocation's group. The launch owner starts every child as the leader of a new group
 * whose id is the child's pid, so a descendant the child already lost to reparenting — a silent
 * orphan no descendant snapshot lists any longer — still carries that id, and this is what ends it.
 *
 * <p>Run by every waiter once its wait has resolved, however it resolved: {@link CaptureRunner} and
 * {@code GitExec} after the drains are joined, {@code ContainerFileChannel} likewise, {@code
 * HostExecHandle} after each of its waits — the {@code docker} client of a box command included,
 * since it is a host process like any other; nothing inside a box is swept by the host (FR22).
 *
 * <p>The end rule is the probe, never the text of {@code kill}'s error, which differs between
 * util-linux and macOS's {@code /bin/kill} while both exit 1 for {@code ESRCH} and {@code EPERM}
 * alike: the zero-signal probe {@code kill -0 -- -<pid>} is sent first, and while it is answered a
 * round of {@code kill -KILL -- -<pid>} follows, at most {@value #MAX_ROUNDS} rounds — a member
 * forked between two rounds is already in the group, so the next round reaches it. An empty group
 * ends the sweep on its first probe. A killed member its parent has not reaped yet still answers,
 * so a sweep may end after its last round with the group not cleared; the orphans' parent is {@code
 * init}, which reaps at once, and the waiter's DEBUG line names what was found (NFR-O3).
 *
 * <p>The sweep runs only over a {@link Process} that exists — a launch that threw has none to hand
 * it — and asserts {@code pid > 1} before {@code -<pid>} is spelled: {@code kill -- -0} names the
 * factory's own group and {@code kill -- -1} every process it may signal (Node's pid-undefined
 * {@code kill(-0)}, nodejs/node#65054). A process with no pid of its own — a test double — is not
 * swept, and nothing is signalled for it.
 *
 * <p>A sweep whose probe is refused removes the group from the live group registry ({@link
 * LiveGroups}), the registry's one writer besides the launch owner; a group still answering after the
 * last round, or one no probe could reach, stays registered for the shutdown step, which sweeps every
 * registered group through {@link #sweepLive()} (design D19, fourth amendment).
 *
 * <p><strong>Its limit.</strong> The group kill is best-effort hygiene, not a fence: a member that
 * changed its uid is beyond the signal, and one that called {@code setsid} itself has left the
 * group; both outlive the invocation. Every member the factory starts runs under the factory's own
 * uid, which is why a refused probe means no process rather than no permission.
 *
 * <p>Implements FR21, FR22, NFR-O3 of fix-terminal-receipt-convergence (D19 as amended 2026-10-17;
 * the shutdown step by its fourth amendment).
 */
public final class ProcessGroupSweep {

    /** The most rounds of {@code SIGKILL} one sweep sends (design D19). */
    static final int MAX_ROUNDS = 5;

    /** How one signal reaches a group: the owner's ungrouped {@code kill}, or a spec's fake. */
    interface GroupSignal {

        /**
         * Sends {@code signal} to the process group {@code group}.
         *
         * @param signal the signal's name as {@code kill} spells it after the dash: {@code 0} or
         *     {@code KILL}
         * @param group the group's id, above one
         * @return the answer the signal got
         */
        Answer send(String signal, long group);
    }

    /** What one {@code kill} reported, by its exit code alone. */
    enum Answer {
        /** A member of the group received the signal: {@code kill} exited 0. */
        ANSWERED,
        /** No member could be signalled: {@code kill} exited non-zero. */
        REFUSED,
        /** No answer exists: {@code kill} could not be started or did not end in its bound. */
        UNSENT
    }

    private final GroupSignal signal;
    private final LiveGroups groups;

    /** The sweep over the launch owner's own {@code kill}, started ungrouped in its file. */
    public ProcessGroupSweep() {
        this(GroupedLaunch.groupSignal());
    }

    /**
     * @param signal how a signal reaches a group
     */
    ProcessGroupSweep(GroupSignal signal) {
        this(signal, LiveGroups.PROCESS);
    }

    /**
     * @param signal how a signal reaches a group
     * @param groups the live group registry a group found empty is removed from: {@link
     *     LiveGroups#PROCESS} in production, a spec's own otherwise
     */
    ProcessGroupSweep(GroupSignal signal, LiveGroups groups) {
        this.signal = signal;
        this.groups = groups;
    }

    /**
     * Sweeps the group {@code process} leads, once its wait has resolved.
     *
     * @param process the started process, already exited or terminated
     * @return what the sweep found: the rounds it sent, and whether the group was left empty
     */
    public GroupSweep sweep(Process process) {
        return sweepGroup(process.pid());
    }

    /**
     * The shutdown step's sweep (design D19, fourth amendment): sweeps, one after another, every
     * group the live group registry still holds — the groups no waiter has found empty yet, a waiter
     * still inside its post-exit bound or one the JVM's exit will never let finish. A group a waiter
     * sweeps at the same moment costs one refused probe. It takes no lock: it iterates a copy of the
     * registry, and its time is bounded by the rounds of each group, each {@code kill} by its own
     * bound. Run by {@code RealProcessTreeKiller.killDescendants} after the cooperative snapshot kill.
     */
    public void sweepLive() {
        groups.current().forEach(this::sweepGroup);
    }

    private GroupSweep sweepGroup(long group) {
        if (group <= 1) {
            return GroupSweep.NOTHING;
        }
        int rounds = 0;
        Answer probe = signal.send("0", group);
        while (probe == Answer.ANSWERED && rounds < MAX_ROUNDS) {
            signal.send("KILL", group);
            rounds++;
            probe = signal.send("0", group);
        }
        boolean cleared = probe == Answer.REFUSED;
        if (cleared) {
            // The group has no member left: forget it, so the shutdown step does not probe it again
            // and the pid's reuse window closes. A group still answering, or unanswered, stays.
            groups.deregister(group);
        }
        return new GroupSweep(rounds, cleared);
    }
}
