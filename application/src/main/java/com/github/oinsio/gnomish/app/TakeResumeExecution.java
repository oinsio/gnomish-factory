package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.lease.ClaimLossFlag;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.take.AbortFuse;
import com.github.oinsio.gnomish.app.take.AbortHandler;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import java.nio.file.Path;
import java.util.List;
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
 * <p>Implements FR9, FR12, D3 of add-tracker-port; FR12, D13 of add-base-ref-resolution.
 */
final class TakeResumeExecution {

    private final RunAssembly assembly;
    private final TaskGit git;
    private final Path worktreesRoot;
    private final AbortHandler abortHandler;
    private final int abortThreshold;
    private final List<String> credentialEnvVarsToScrub;
    private final ClaimLossFlag claimLossFlag;

    TakeResumeExecution(
            RunAssembly assembly,
            TaskGit git,
            Path worktreesRoot,
            AbortHandler abortHandler,
            int abortThreshold,
            List<String> credentialEnvVarsToScrub,
            ClaimLossFlag claimLossFlag) {
        this.assembly = assembly;
        this.git = git;
        this.worktreesRoot = worktreesRoot;
        this.abortHandler = abortHandler;
        this.abortThreshold = abortThreshold;
        this.credentialEnvVarsToScrub = credentialEnvVarsToScrub;
        this.claimLossFlag = claimLossFlag;
    }

    /**
     * Resolves the resumed law binding for {@code pinnedRef} and, once bound, drives {@code
     * continuation} over the freshly constructed {@link TakeEngineExecution}; a parked or released
     * outcome from the resolution short-circuits straight to its {@link TakeResult} instead.
     */
    TakeResult run(
            Path cloneDir,
            String pinnedRef,
            TaskState state,
            TaskRef ref,
            Tracker tracker,
            Function<TakeEngineExecution, TakeResult> continuation) {
        return ResumeLawBinding.resolve(
                git.baseRefs(),
                cloneDir,
                pinnedRef,
                state,
                ref,
                tracker,
                lawBinding -> continuation.apply(newExecution(lawBinding)));
    }

    private TakeEngineExecution newExecution(LawBinding lawBinding) {
        return new TakeEngineExecution(
                assembly,
                git,
                worktreesRoot,
                new AbortFuse(abortHandler, abortThreshold),
                credentialEnvVarsToScrub,
                claimLossFlag,
                lawBinding);
    }
}
