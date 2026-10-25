package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.take.DaemonConditionKind;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;

/**
 * The one way a take ends on a claim-time fetch that origin never answered: the claim is released
 * through the plain {@link Tracker#release} — no abort marker, no comment, no park — and the run
 * ends {@link TakeResult.InfrastructureUnavailable}, so the outage is charged to the daemon and the
 * task's abort facts, backoff and K-fuse accounting stay exactly as they were (FR9, M4 of
 * add-base-ref-resolution). Every claim-time fetch classifies under that rule, of whatever ref; its
 * consumers are the fresh claim's base refresh ({@link FreshClaimBaseBinding}), the resume's
 * pinned-ref refresh ({@link ResumeLawBinding}) and the task-branch lookup ({@link
 * TakeClaimAndWork}, finding F6 of tighten-spec-contract).
 *
 * <p>An operator precondition refused after the claim is released by the same owner, ending {@link
 * TakeResult.OperatorPreconditionUnmet} instead (design D18 of add-sandbox-base-image), so the
 * fenced return {@code add-claim-return} brings here covers both arms; its consumer is {@link
 * ClaimedRunFailureDispatch}.
 *
 * <p>The release is best-effort: a tracker that cannot be written is itself a plausible part of
 * the outage, and it must not turn the classified release into an escaping exception (NFR-R2 of
 * add-tracker-port). Its failure is handed to the caller's {@link ReleaseFailure}, because the
 * deciding layer owns the log line and its catalog code — one code, one call site
 * ({@code .claude/rules/logging.md}).
 *
 * <p>Implements FR9 of add-base-ref-resolution; FR14 of add-sandbox-base-image.
 */
final class ClaimOutageRelease {

    private ClaimOutageRelease() {}

    /** Where a release that itself failed is reported: the deciding layer's own coded log line. */
    @FunctionalInterface
    interface ReleaseFailure {

        /**
         * Reports a release the tracker refused.
         *
         * @param unreleased what the tracker threw on the release
         */
        void report(RuntimeException unreleased);
    }

    /**
     * Releases {@code ref} plain and composes the released-on-outage result.
     *
     * @param tracker the tracker the claim is held through
     * @param ref the claimed task
     * @param unanswered what origin did not answer, as a phrase the result's sentence ends on, e.g.
     *     {@code the refresh of its resolved base ref 'main'}; the factory's own words
     * @param reason why it did not answer, as the fetch reported it
     * @param onFailure where a failed release is reported
     * @return the result the take ends on
     */
    static TakeResult.InfrastructureUnavailable release(
            Tracker tracker, TaskRef ref, String unanswered, UntrustedText reason, ReleaseFailure onFailure) {
        releasePlain(tracker, ref, onFailure);
        // The reason leaves its carrier through the log exit here rather than at each sink: what is
        // built is one message the sinks log whole, and minting the composed line keeps the field a
        // carrier so no sink can take it raw (design D4 of type-untrusted-text). The composed line
        // is the factory's own sentence around an already-neutralized quote, so it is minted in the
        // factory's family rather than in the one the quote came from (design D3).
        return new TakeResult.InfrastructureUnavailable(
                UntrustedText.factory("Task " + ref.id() + " claim released (the reaper returns it to Ready after"
                        + " the claim TTL): origin did not answer " + unanswered + ": " + reason.forLog()));
    }

    /**
     * Releases {@code ref} plain and composes the result of an operator precondition the claimed
     * run met (design D18 of add-sandbox-base-image).
     *
     * @param tracker the tracker the claim is held through
     * @param ref the claimed task
     * @param kind which precondition refused
     * @param refusal the check's message, naming the refusal and its fix; any quoted capture in it
     *     has already left its carrier through the log exit
     * @param onFailure where a failed release is reported
     * @return the result the take ends on
     */
    static TakeResult.OperatorPreconditionUnmet refused(
            Tracker tracker, TaskRef ref, DaemonConditionKind kind, String refusal, ReleaseFailure onFailure) {
        releasePlain(tracker, ref, onFailure);
        // The refusal is the check's own message around quotes it already neutralized, so the
        // composed line is minted in the factory's family, as the outage's sentence is above.
        return new TakeResult.OperatorPreconditionUnmet(
                UntrustedText.factory("Task " + ref.id() + " claim released (the reaper returns it to Ready after"
                        + " the claim TTL): " + refusal),
                kind);
    }

    private static void releasePlain(Tracker tracker, TaskRef ref, ReleaseFailure onFailure) {
        try {
            tracker.release(ref);
        } catch (RuntimeException unreleased) {
            onFailure.report(unreleased);
        }
    }
}
