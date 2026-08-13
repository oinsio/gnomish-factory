package com.github.oinsio.gnomish.domain.engine.port;

/**
 * The engine's precondition seam for external checks (FR21 of add-sandbox-core): before the
 * poll loop of an {@code external} check starts, the engine confirms the attempt commit under
 * verification is delivered to the remote — external checks are triggered by the task-branch
 * push (submission is deferred, add-stage-engine NG8), so polling an undelivered commit could
 * only ever expire as a bogus poll-timeout quality failure. An implementation re-attempts the
 * push when the commit is not yet on the remote; a commit that cannot be delivered reports
 * {@link Outcome.Undeliverable}, which the engine resolves as CannotVerify — an infrastructure
 * failure, no stage attempt burned.
 *
 * <p>Implements FR21 of add-sandbox-core.
 */
public interface AttemptDelivery {

    /**
     * Confirms the attempt commit carried by {@code workspace} is on the remote, re-attempting
     * the push if it is not (FR21).
     *
     * @param workspace the opaque working copy under verification; never null
     * @return {@link Outcome.Delivered}, or {@link Outcome.Undeliverable} naming why not
     */
    Outcome ensureDelivered(Workspace workspace);

    /**
     * The no-precondition implementation for assemblies whose external checks have no push
     * trigger to wait for: the git-less in-place mode, and the interactive client answering for
     * a human oracle. Sandboxed git assemblies wire a real push-verifying implementation
     * instead; this constant never belongs where a platform adapter polls CI of a pushed
     * commit.
     */
    static AttemptDelivery assumedDelivered() {
        return _ -> new Outcome.Delivered();
    }

    /** The two ways a delivery confirmation can end (FR21). */
    sealed interface Outcome permits Outcome.Delivered, Outcome.Undeliverable {

        /** The attempt commit is on the remote; polling may start. */
        record Delivered() implements Outcome {}

        /**
         * The attempt commit could not be delivered — the poll loop never starts and the check
         * resolves as CannotVerify (infrastructure failure, no stage attempt burned).
         *
         * @param reason the human-facing short cause; never blank
         * @param details free-text detail (e.g. the push stderr); never null, may be empty
         */
        record Undeliverable(String reason, String details) implements Outcome {

            public Undeliverable {
                requireNonBlank(reason);
            }

            /**
             * Fails fast on a blank {@code reason}: an undeliverable outcome that cannot name
             * its cause is useless to the escalation report. Kept as an explicit static method
             * rather than inline in the compact constructor: PIT's record filter suppresses all
             * mutations inside a record's canonical constructor, which would silently exempt
             * this validation from the 100% mutation gate.
             */
            private static void requireNonBlank(String value) {
                if (value.isBlank()) {
                    throw new IllegalArgumentException("Undeliverable.reason must not be blank");
                }
            }
        }
    }
}
