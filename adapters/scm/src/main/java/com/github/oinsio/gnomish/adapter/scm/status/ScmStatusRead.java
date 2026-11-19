package com.github.oinsio.gnomish.adapter.scm.status;

import java.time.Duration;

/**
 * What one {@code scm-status} read of the attempt SHA got back (FR4, NFR-R2 of
 * migrate-verify-to-executor-contract): the verdicts the SCM holds, or a rate-limit answer the
 * GitHub core's own retry did not outlast. A read the platform could not answer otherwise is a
 * {@link ScmStatusReadException}, not a value of this type.
 *
 * <p>Implements FR4, NFR-R2 of migrate-verify-to-executor-contract.
 */
sealed interface ScmStatusRead {

    /** The SCM answered: its verdicts on the SHA, latest per context. */
    record Verdicts(ContextVerdicts contexts) implements ScmStatusRead {}

    /**
     * The SCM refused the read for its rate limit: a {@code 429}, or a {@code 403} carrying
     * GitHub's rate-limit signal, as the GitHub core's one judgement ({@code GithubRateLimit}) read
     * it (design D19).
     *
     * @param statusCode the status of the last answer
     * @param pause the wait the SCM asked for before the next read; never negative
     */
    record RateLimited(int statusCode, Duration pause) implements ScmStatusRead {}
}
