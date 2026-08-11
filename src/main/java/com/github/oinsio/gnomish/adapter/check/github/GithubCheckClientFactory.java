package com.github.oinsio.gnomish.adapter.check.github;

import com.github.oinsio.gnomish.adapter.check.ExternalCheckPinContributor;
import com.github.oinsio.gnomish.adapter.github.GithubHttpClient;
import com.github.oinsio.gnomish.adapter.secrets.EnvFileSecretsProvider;
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider;

/**
 * Assembles a live {@link GithubCheckExternalClient} from factory configuration (FR26): the
 * platform base URL and {@code owner/name} repository come from {@code factory.check.github.*},
 * and the token is resolved by name through the {@link SecretsProvider} — the env/file adapter
 * backs it with {@link #TOKEN_ENV_VAR}, replacing the earlier provisional direct env read. A
 * token that does not resolve fails closed at wiring time with an error naming the secret; no
 * stage ever runs with an unauthenticated adapter. This is the external-check analog of {@code
 * GithubTrackerAdapterFactory}, closing add-external-check-github-actions Q5.
 *
 * <p><b>Required scope (NFR-S1):</b> a fine-grained personal access token with {@code
 * actions: read} repository permission, or a classic token's {@code repo} scope used read-only —
 * enough to list workflow runs and fetch job logs. No write scope is needed; this adapter never
 * writes to the target repository.
 *
 * <p>{@link #TOKEN_ENV_VAR} is declared as a credential name (composition root wires it into the
 * run's credential list, e.g. {@code ManualRunAssembly.credentialNames}), so the variable can
 * never be admitted into a child-environment allowlist, matching the tracker token's treatment
 * (NFR-S1).
 *
 * <p>Implements FR26 of add-sandbox-core; FR8, NFR-S1 of add-external-check-github-actions.
 *
 * @param secretsProvider the seam through which {@link #TOKEN_ENV_VAR} is resolved by name (FR18
 *     of add-sandbox-core); never null — the composition root injects the installation-configured
 *     adapter, and tests a fake
 */
public record GithubCheckClientFactory(SecretsProvider secretsProvider) {

    /** {@code GNOMISH_GITHUB_ACTIONS_TOKEN}: the GHA check adapter's credential name (NFR-S1). */
    public static final String TOKEN_ENV_VAR = "GNOMISH_GITHUB_ACTIONS_TOKEN";

    /**
     * The production factory, resolving {@link #TOKEN_ENV_VAR} through the default env/file
     * {@link SecretsProvider} (FR18, NFR-S1 of add-sandbox-core).
     */
    public GithubCheckClientFactory() {
        this(new EnvFileSecretsProvider());
    }

    /**
     * Builds the client for {@code apiUrl} and {@code repo} ({@code owner/name}), resolving the
     * token through the {@link SecretsProvider} (FR26).
     *
     * @param apiUrl the platform base URL ({@code factory.check.github.api-url})
     * @param repo the {@code owner/name} repository the checks run in ({@code
     *     factory.check.github.repo})
     * @return the ready-to-poll client; never null
     * @throws GithubCheckTokenException if the token secret is missing or blank
     */
    public GithubCheckExternalClient create(String apiUrl, String repo) {
        String token = secretsProvider
                .find(TOKEN_ENV_VAR)
                .orElseThrow(() -> new GithubCheckTokenException(TOKEN_ENV_VAR
                        + " is required to use the GitHub Actions external-check adapter, but is missing or blank"));
        int slash = repo.indexOf('/');
        if (slash <= 0 || slash != repo.lastIndexOf('/') || slash == repo.length() - 1) {
            throw new IllegalArgumentException("factory.check.github.repo must be 'owner/name', got: '" + repo + "'");
        }
        String owner = repo.substring(0, slash);
        String name = repo.substring(slash + 1);
        return new GithubCheckExternalClient(new GithubHttpClient(apiUrl, token), owner, name);
    }

    /**
     * This adapter's pin-path contribution (FR16, D10): exactly the {@code checkId} workflow
     * file, delegated to {@link GithubCheckPinPaths}.
     */
    public ExternalCheckPinContributor pinContributor() {
        return GithubCheckPinPaths::contributedBy;
    }
}
