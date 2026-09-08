package com.github.oinsio.gnomish.baseref;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The factory's one answer to "which ref does this task branch from".
 *
 * <p>One priority order, consulted top down, each tier deciding or abstaining — see {@link BaseRule},
 * whose declaration order is that order. The one tier that does not merely abstain is the
 * designator: a task that named a base the project does not allow, or named several, stops resolution
 * where it stands. Falling through to the configured default there would hand the task a base
 * nobody chose, which is the failure the whole tier exists to prevent.
 *
 * <p>The explicit {@code --base} argument is not held against the allowed bases. They bound what a
 * task's author may choose; an operator typing a ref at a terminal is not that author, and a list
 * that could veto them would make {@code --base v1.2.3} unusable on any project whose allowed bases
 * are branches.
 *
 * <p>Values in, a value out: no subprocess, no port, no clock, no I/O. Whether the resolved ref
 * exists, and how fresh it is, are the fetching layer's questions.
 *
 * <p>Implements FR4, FR5, FR8, FR10, NFR-C1 of add-base-ref-resolution.
 */
public final class BaseRefResolver {

    /**
     * The ref a manual run without {@code --base} branches from: the clone's current HEAD, whatever
     * a human has checked out. This is the one tier where the working copy is the answer, and the
     * only place in the factory where the literal remains legitimate.
     */
    public static final String LOCAL_HEAD_REF = "HEAD";

    private BaseRefResolver() {}

    /**
     * Resolves the base for one task.
     *
     * @param request everything the decision rests on
     * @return the decision, or the refusal the caller escalates on
     */
    public static BaseResolution resolve(BaseRefRequest request) {
        String explicit = request.explicitBase();
        if (explicit != null) {
            return resolved(explicit, BaseRule.EXPLICIT_ARGUMENT, "explicit --base argument");
        }
        BaseResolution fromDesignator = fromDesignator(request);
        if (fromDesignator != null) {
            return fromDesignator;
        }
        String configured = request.configuredDefault();
        if (configured != null) {
            return resolved(configured, BaseRule.CONFIGURED_DEFAULT, "the configured task-branch.base.default");
        }
        String defaultBranch = request.defaultBranch();
        if (defaultBranch != null) {
            return resolved(
                    defaultBranch,
                    BaseRule.REPOSITORY_DEFAULT_BRANCH,
                    "the repository default branch reported by the remote");
        }
        if (request.mode() == ResolutionMode.MANUAL) {
            return resolved(LOCAL_HEAD_REF, BaseRule.LOCAL_HEAD, "a manual run without --base: the clone's local HEAD");
        }
        return new BaseResolution.Underdetermined(
                UnderdeterminedCause.NO_DEFAULT_BRANCH,
                List.of(),
                "no base was named and the repository default branch is unknown; an autonomous run "
                        + "never falls back to the clone's local HEAD");
    }

    /**
     * The designator tier: a decision, a refusal, or null for "abstained, ask the next tier".
     *
     * <p>Null rather than an {@code Optional} of an outcome that is itself two-valued: the tier has
     * three answers and the caller distinguishes all three, so wrapping only doubles the unwrapping.
     */
    @Nullable
    private static BaseResolution fromDesignator(BaseRefRequest request) {
        return switch (request.designator().against(request.allowedBases())) {
            case DesignatorSelection.None ignored -> null;
            case DesignatorSelection.Accepted accepted ->
                resolved(
                        accepted.refName(),
                        BaseRule.DESIGNATOR,
                        "the task's base designator, accepted by allowed-base pattern '"
                                + accepted.entry().pattern().source() + "'");
            case DesignatorSelection.Refused refused ->
                new BaseResolution.Underdetermined(refused.cause(), refused.values(), refused.reason());
        };
    }

    private static BaseResolution resolved(String ref, BaseRule rule, String reason) {
        return new BaseResolution.Resolved(new BaseDecision(ref, rule, reason));
    }
}
