package com.github.oinsio.gnomish.adapter.git;

/**
 * The operator-facing lines {@link ParkDeliveryFence} can contribute to a park report, kept
 * together because their difference is the whole point (UX2 of bound-subprocess-commands): one
 * asserts that {@code origin} is behind, the other admits that nobody knows. A push that was
 * killed on its deadline or cut short by a shutdown established no remote outcome, so claiming the
 * first for it is the fabricated note this change removes.
 *
 * <p>Implements FR7, UX2, UX3 of bound-subprocess-commands; UX2 of fix-lifecycle-push.
 */
final class ParkDeliveryNotes {

    private ParkDeliveryNotes() {}

    /**
     * The note for a delivery failure {@code origin} itself confirmed: it answered, and what it
     * holds does not contain the park. The note names the action too — a human reading a park is
     * deciding what to do next, and "origin is behind" is only actionable once they know that
     * resuming this task elsewhere would read stale state until the branch is pushed.
     *
     * @param branch the task branch that could not be delivered; never blank
     * @return the report line; never blank
     */
    static String behind(String branch) {
        return "Note: origin is behind this park — branch " + branch
                + " could not be pushed, so the remote does not yet carry the recorded outcome."
                + " Push it from this machine (git push origin " + branch
                + ") before resuming this task elsewhere; until then another instance would resume from stale state.";
    }

    /**
     * The note for a delivery whose outcome was never established: the push did not run to its own
     * exit, so the branch may or may not have reached {@code origin}. Says so, rather than picking
     * whichever guess reads better.
     *
     * @param branch the task branch whose delivery is unknown; never blank
     * @param reason what stopped the push, in the operator's words; never blank
     * @return the report line; never blank
     */
    static String unverified(String branch, String reason) {
        return "Note: this park's delivery to origin could not be verified — the push of branch " + branch + " "
                + reason + ", so the remote may or may not carry the recorded outcome."
                + " Check with (git ls-remote origin " + branch + ") and push it from this machine if it is missing,"
                + " before resuming this task elsewhere.";
    }
}
