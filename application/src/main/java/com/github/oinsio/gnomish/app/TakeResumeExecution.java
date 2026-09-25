package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * The law-bound engine-execution tail {@link TakeResumeRunner} hands both of its resume entry
 * points to (FR12, D13 of add-base-ref-resolution): resolves the resumed law binding through
 * {@link ResumeLawBinding} and, only once a binding is actually established, builds the {@link
 * TakeEngineExecution} the caller's continuation drives — the one piece of {@link
 * TakeResumeRunner}'s job that is not resume-specific orchestration (bootstrap, salvage/discard,
 * decision commit) but the same "resolve, then construct the engine tail" step every resumed run
 * repeats.
 *
 * <p>Holds the slot's {@link SlotWiring} whole (D2 of introduce-slot-wiring): every member it
 * reads — assembly, task git, worktrees root, abort fuse, credential names, the tenure's
 * claim-loss flag — is the slot's equipment, not the resumed run's data.
 *
 * <p>Implements FR9, FR12, D3 of add-tracker-port; FR12, D13 of add-base-ref-resolution; FR4 of
 * introduce-slot-wiring.
 *
 * @param wiring the slot's equipment every engine execution this tail builds works with
 */
record TakeResumeExecution(SlotWiring wiring) {

    /**
     * Resolves the resumed law binding for {@code pinnedRef} and, once bound, drives {@code
     * continuation} over the freshly constructed {@link TakeEngineExecution}; a parked or released
     * outcome from the resolution short-circuits straight to its {@link TakeResult} instead.
     */
    TakeResult run(
            Path cloneDir,
            ResumeLawBinding.PinnedBase pinnedRef,
            TaskState state,
            TaskRef ref,
            Tracker tracker,
            Function<TakeEngineExecution, TakeResult> continuation) {
        return ResumeLawBinding.resolve(
                wiring.git().baseRefs(),
                cloneDir,
                pinnedRef,
                state,
                ref,
                tracker,
                lawBinding -> continuation.apply(newExecution(lawBinding)));
    }

    private TakeEngineExecution newExecution(LawBinding lawBinding) {
        return new TakeEngineExecution(
                wiring.assembly(),
                wiring.git(),
                wiring.worktreesRoot(),
                wiring.abort(),
                wiring.credentialEnvVarsToScrub(),
                wiring.tenure().lossFlag(),
                lawBinding);
    }
}
