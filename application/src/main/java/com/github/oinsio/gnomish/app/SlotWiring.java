package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.project.RegisteredClone;
import com.github.oinsio.gnomish.app.take.AbortFuse;
import com.github.oinsio.gnomish.app.take.TerminalWriteRetry;
import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment;
import java.util.List;

/**
 * The <em>slot wiring</em>: the equipment one take slot works with, fixed for as long as the slot
 * exists — once per {@code gnomish take} invocation, or once per {@code serve} daemon and shared
 * by all of its slots (design D1 of introduce-slot-wiring). It is the counterpart of the {@link
 * TakeOrder}: the order is what one invocation works <em>to</em> and stays a parameter; the wiring
 * is what it works <em>with</em> and becomes fields of the components that use it.
 *
 * <p>Built only where every member exists — after the tracker is provisioned and the run's
 * heartbeat and listener-augmented assembly are in hand — so exactly two assembly points build
 * one (design, "Where a {@code SlotWiring} is built"). Its two named sub-groups, {@link AbortFuse}
 * and {@link ClaimTenure}, are the pairs their consumers already take together. It carries no
 * claim epoch: {@link TaskGit#epochs()} is that book's single owner (design D5).
 *
 * <p><b>One time source per slot, by construction.</b> The slot's time is its assembly's {@link
 * RunAssembly#timeEquipment()} and nothing else: {@link #time()} reads it, and the time-built
 * policy every terminal write runs under, {@link #terminalWriteRetry()}, is derived from it here
 * rather than carried as a member — so no wiring can hold a retry measured on a clock other than
 * the one its resume paths stamp with (task 3.9 of supervise-daemon-loops-and-embed-dashboard).
 * The slot's abort fuse is built over the same clock by its one production producer, {@link
 * SlotWiringFactory}.
 *
 * <p>Never log a wiring whole: the record {@code toString} renders the credential variable names
 * it carries (NFR-S1). It carries names only, never a credential value.
 *
 * <p>Implements FR1 of introduce-slot-wiring; FR9 of add-project-registry; FR18 of
 * supervise-daemon-loops-and-embed-dashboard.
 *
 * @param assembly the run assembly (listener-augmented with the heartbeat's progress listener)
 * @param git the task-git capability set, including the claim-epoch book
 * @param registeredClone the registered clone the slot works in; its task worktrees live in the clone's own
 *     worktree folder (FR9 of add-project-registry)
 * @param taskIdMdcKey the MDC key the task id is bound under while the slot works a task
 * @param abort the abort handler and its threshold K
 * @param credentialEnvVarsToScrub the declared credential variable names scrubbed from agent
 *     environments; names only
 * @param containerTakeSupport the container-mode seam of the take chain
 * @param tenure the claim beat and claim-loss flag of the run's heartbeat
 * @param trustedBase the trusted tier bound once at startup
 */
public record SlotWiring(
        RunAssembly assembly,
        TaskGit git,
        RegisteredClone registeredClone,
        String taskIdMdcKey,
        AbortFuse abort,
        List<String> credentialEnvVarsToScrub,
        ContainerTakeSupport containerTakeSupport,
        ClaimTenure tenure,
        TrustedBaseContext trustedBase) {

    /**
     * The slot's one time equipment: its assembly's (design D20 of
     * supervise-daemon-loops-and-embed-dashboard). Every component of the slot that reads "now" or
     * waits takes it from here, so the slot cannot run on two time sources.
     *
     * <p>Implements FR18, FR22 of supervise-daemon-loops-and-embed-dashboard.
     *
     * @return the assembly's time equipment; never null
     */
    TimeEquipment time() {
        return assembly.timeEquipment();
    }

    /**
     * The bounded retry every terminal tracker write (finish, park, and their reconciled re-drives)
     * runs under: {@link TerminalWriteRetry#DEFAULT_BOUND} measured on {@link #time()} — the one
     * production construction of the retry, derived from the slot's own time so the two cannot
     * diverge (task 3.9 of supervise-daemon-loops-and-embed-dashboard).
     *
     * <p>Implements FR18, FR22 of supervise-daemon-loops-and-embed-dashboard; FR10, D10 of
     * add-claim-heartbeat.
     *
     * @return the slot's terminal-write retry; never null
     */
    public TerminalWriteRetry terminalWriteRetry() {
        return new TerminalWriteRetry(time(), TerminalWriteRetry.DEFAULT_BOUND);
    }

    /**
     * The slot's outcome dispatch: the one construction site of {@link TakeOutcomeDispatch}
     * (design D22 of supervise-daemon-loops-and-embed-dashboard), over this wiring's own terminal
     * write retry and abort fuse, so every run of the slot dispatches its terminal outcome under the
     * retry derived from the slot's one time — no engine execution builds a retry of its own.
     *
     * <p>Implements FR18, FR22 of supervise-daemon-loops-and-embed-dashboard.
     *
     * @return the dispatch shared by every run of this slot; never null
     */
    TakeOutcomeDispatch outcomeDispatch() {
        return new TakeOutcomeDispatch(terminalWriteRetry(), abort);
    }
}
