package com.github.oinsio.gnomish.adapter.scm.status;

/**
 * Where one named verdict on the attempt SHA stands (FR4 of migrate-verify-to-executor-contract),
 * in the order of how far it is from a pass: a context two sources report takes the later state of
 * the two in this order ({@link ContextVerdicts}), so a running report keeps the check waiting and a
 * failed one is never hidden behind a pass.
 *
 * <p>Implements FR4 of migrate-verify-to-executor-contract.
 */
enum ScmContextState {
    /** Succeeded, or finished with nothing to say (a neutral or skipped check). */
    PASSED,
    /** Finished with a warning (Gitea's {@code warning} state): a SARIF {@code warning} result. */
    WARNED,
    /** Failed, errored, timed out or was cancelled, or reported a state this reader does not know. */
    FAILED,
    /** Queued, in progress or pending: no verdict yet. */
    RUNNING
}
