package com.github.oinsio.gnomish.adapter.check.github;

import com.github.oinsio.gnomish.DoNotMutate;

/**
 * Temporary stand-in for add-sandbox-core's {@code SecretsProvider} (FR18): resolves the GitHub
 * Actions check adapter's token from {@link #TOKEN_ENV_VAR} once, at wiring time, mirroring
 * {@code GithubTrackerAdapterFactory}'s {@code requireToken()}/{@code TOKEN_ENV_VAR} pattern for
 * the tracker adapter. Once the real {@code SecretsProvider} port lands, this class is replaced
 * by a {@code SecretsProvider}-backed factory for this adapter; nothing downstream needs to
 * change, since {@link com.github.oinsio.gnomish.adapter.github.GithubHttpClient} already takes
 * the resolved token as a plain constructor parameter and never re-reads it (design D5 of
 * add-tracker-port).
 *
 * <p><b>Required scope (NFR-S1):</b> a fine-grained personal access token with {@code
 * actions: read} repository permission, or a classic token's {@code repo} scope used read-only —
 * enough to list workflow runs and fetch job logs. No write scope is needed; this adapter never
 * writes to the target repository.
 *
 * <p>Implements FR8, NFR-S1 of add-external-check-github-actions. Depends on add-sandbox-core
 * FR18 for its eventual replacement.
 */
public final class GithubCheckToken {

    /** {@code GNOMISH_GITHUB_ACTIONS_TOKEN}: the GHA check adapter's credential env var. */
    public static final String TOKEN_ENV_VAR = "GNOMISH_GITHUB_ACTIONS_TOKEN";

    private GithubCheckToken() {}

    /**
     * Resolves the token from {@link #TOKEN_ENV_VAR}. Callers read this once, at wiring time, and
     * pass the plain string into {@link com.github.oinsio.gnomish.adapter.github.GithubHttpClient};
     * it is never read again internally, so it cannot resurface from a stale environment read.
     *
     * @throws GithubCheckTokenException if the variable is missing or blank
     */
    // PIT M4 documented exception (mirrors GithubTrackerAdapterFactory#requireToken): @DoNotMutate
    // — the success and blank-but-present branches need GNOMISH_GITHUB_ACTIONS_TOKEN genuinely set
    // in the JVM's process environment, not reliably possible on a module-path JVM without
    // --add-opens. GithubCheckTokenSpec covers the missing-variable branch via the real (unset)
    // environment.
    @DoNotMutate
    public static String requireToken() {
        String token = System.getenv(TOKEN_ENV_VAR);
        if (token == null || token.isBlank()) {
            throw new GithubCheckTokenException(
                    TOKEN_ENV_VAR + " environment variable is required to use the GitHub Actions check adapter, but"
                            + " is missing or blank");
        }
        return token;
    }
}
