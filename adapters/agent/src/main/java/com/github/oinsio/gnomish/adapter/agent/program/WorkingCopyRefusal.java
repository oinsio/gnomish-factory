package com.github.oinsio.gnomish.adapter.agent.program;

import com.github.oinsio.gnomish.executorcontract.ExecutorRequest;
import com.github.oinsio.gnomish.executorcontract.ExecutorResult;
import com.github.oinsio.gnomish.executorcontract.Policy;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.ArrayList;
import java.util.List;

/**
 * The words of a refusal by the working-copy-unchanged check (FR11, NFR-S4 of
 * define-executor-contract; NFR-S2 of extract-agent-round-runner): which obligation the run was under
 * — its role and the policy it was requested with — and what broke it: a view that could not be taken
 * before the run, one that could not be taken after it, or the paths the run changed, at most {@link
 * #NAMED_PATHS} of them by name. A {@code program} run and an agent round are refused in the same
 * words, naming the executor by its {@link ProgramFailures.Subject}.
 *
 * <p>Implements FR11, NFR-S4 of define-executor-contract; NFR-S2 of extract-agent-round-runner.
 */
public final class WorkingCopyRefusal {

    /** The most paths a refusal names. */
    static final int NAMED_PATHS = 5;

    private final ProgramFailures.Subject subject;
    private final String executor;
    private final String obligation;

    /**
     * @param subject what kind of executor ran; never null
     * @param executor the executor's name; never blank
     * @param request the run's request, whose role and policy the obligation names; never null
     */
    public WorkingCopyRefusal(ProgramFailures.Subject subject, String executor, ExecutorRequest request) {
        this.subject = subject;
        this.executor = executor;
        this.obligation = "a " + request.role().wire() + " run must leave the working copy unchanged (policy "
                + policy(request.policy()) + ")";
    }

    /**
     * The refusal of a run whose view could not be taken before it started: the run does not start.
     *
     * @param unreadable why no view was taken; never null
     * @return the refusal, {@code failed/infrastructure}; never null
     */
    public ExecutorResult before(WorkingCopyView.Unreadable unreadable) {
        return ProgramFailures.infrastructure(
                subject,
                executor,
                obligation + ", and its state could not be taken before the run: " + unreadable.why(),
                UntrustedText.factory(""));
    }

    /** The refusal of a run whose view could not be taken after it, its usage and denials kept. */
    ExecutorResult unverified(WorkingCopyView.Unreadable unreadable, ExecutorResult result) {
        return ProgramFailures.discarded(
                subject,
                executor,
                obligation + ", and it could not be verified after the run: " + unreadable.why(),
                result);
    }

    /** The refusal of a run that changed {@code changed}, its usage and denials kept. */
    ExecutorResult changed(List<String> changed, ExecutorResult result) {
        return ProgramFailures.discarded(subject, executor, obligation + "; the run changed " + named(changed), result);
    }

    static String named(List<String> paths) {
        List<String> shown = new ArrayList<>();
        for (String path : paths.subList(0, Math.min(NAMED_PATHS, paths.size()))) {
            shown.add(UntrustedText.subprocess(path).excerpt(256));
        }
        String named = String.join(", ", shown);
        return paths.size() > NAMED_PATHS ? named + " and " + (paths.size() - NAMED_PATHS) + " more" : named;
    }

    static String policy(Policy policy) {
        return switch (policy) {
            case Policy.ReadOnly ignored -> "readOnly";
            case Policy.WriteExactly exactly -> "writeExactly(" + exactly.path() + ")";
            case Policy.WorkspaceWrite ignored -> "workspaceWrite";
        };
    }
}
