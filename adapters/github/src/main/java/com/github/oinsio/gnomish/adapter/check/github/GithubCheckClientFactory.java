package com.github.oinsio.gnomish.adapter.check.github;

import com.github.oinsio.gnomish.adapter.github.GithubCredential;
import com.github.oinsio.gnomish.adapter.github.GithubHttpClient;
import com.github.oinsio.gnomish.app.CheckClientFactory;
import com.github.oinsio.gnomish.app.CheckParamsValidator;
import com.github.oinsio.gnomish.app.CheckSubsectionValidator;
import com.github.oinsio.gnomish.app.port.check.ExternalCheckPinContributor;
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The GitHub Actions check provider (FR26 of add-sandbox-core): assembles a live {@link
 * GithubCheckExternalClient} from the {@code factory.check.github} operator subsection — the
 * platform base URL and {@code owner/name} repository — with the token resolved by name through the
 * {@link SecretsProvider} the composition root hands in, backing {@link #TOKEN_ENV_VAR}. A token
 * that does not resolve fails closed at wiring time with an error naming the secret; no stage ever
 * runs with an unauthenticated adapter.
 *
 * <p>One {@link CheckClientFactory} among the {@code ServiceLoader} registry, not a special case
 * (FR5, FR12 of add-plugin-architecture): a public no-arg constructor, the {@link SecretsProvider}
 * and the subsection arriving as method arguments (FR2, design D2), and the {@code
 * META-INF/services} entry in this module's jar is the only thing that makes it selectable — core
 * names this class nowhere.
 *
 * <p><b>Required scope (NFR-S1):</b> a fine-grained personal access token with {@code
 * actions: read} repository permission, or a classic token's {@code repo} scope used read-only —
 * enough to list workflow runs and fetch job logs. No write scope is needed; this adapter never
 * writes to the target repository.
 *
 * <p>{@link #TOKEN_ENV_VAR} is declared through {@link #credentialEnvVars} rather than named by
 * core, so the variable can never be admitted into a child-environment allowlist and is scrubbed
 * from every composed child environment, matching the tracker token's treatment (FR17, NFR-S1).
 *
 * <p>Implements FR26 of add-sandbox-core; FR8, NFR-S1 of add-external-check-github-actions; FR2,
 * FR4, FR5, FR15, FR17 of add-plugin-architecture.
 */
public final class GithubCheckClientFactory implements CheckClientFactory {

    /** {@code GNOMISH_GITHUB_ACTIONS_TOKEN}: the GHA check adapter's credential name (NFR-S1). */
    public static final String TOKEN_ENV_VAR = "GNOMISH_GITHUB_ACTIONS_TOKEN";

    /** {@code factory.check.github} — this provider's discovery discriminator (FR5). */
    public static final String PROVIDER = "github";

    /** Public and no-arg, as {@code ServiceLoader} instantiation requires (FR2, design D2). */
    public GithubCheckClientFactory() {}

    @Override
    public String provider() {
        return PROVIDER;
    }

    /**
     * Builds the client from the {@code factory.check.github} subsection's {@code api-url} and
     * {@code repo} keys, resolving the token through the {@link SecretsProvider} (FR26). Both keys
     * are already graded by {@link #subsectionValidator()} at the load seam, so a malformed
     * subsection has failed startup before this is reached, so the coordinate guard here only
     * catches a caller that bypassed the load seam.
     *
     * @throws GithubCheckTokenException if the token secret is missing or blank
     * @throws IllegalArgumentException if a connection key is absent, or {@code repo} is not {@code
     *     owner/name}
     */
    @Override
    public GithubCheckExternalClient create(SecretsProvider secrets, Map<String, Object> subsection) {
        String credential = GithubCredential.nameOr(subsection, TOKEN_ENV_VAR);
        String token = secrets.find(credential)
                .orElseThrow(() -> new GithubCheckTokenException(credential
                        + " is required to use the GitHub Actions external-check adapter, but is missing or blank"));
        String apiUrl = GithubCheckSubsectionValidator.stringValue(subsection, "api-url");
        String repo = GithubCheckSubsectionValidator.stringValue(subsection, "repo");
        if (GithubCheckSubsectionValidator.isNotOwnerName(repo)) {
            throw new IllegalArgumentException("factory.check.github.repo must be 'owner/name', got: '" + repo + "'");
        }
        int slash = repo.indexOf('/');
        return new GithubCheckExternalClient(
                new GithubHttpClient(apiUrl, token), repo.substring(0, slash), repo.substring(slash + 1));
    }

    /**
     * Declares this provider's sole credential (FR17, design D11): the composition root unions it
     * with every other selected provider's declaration and with the tracker's, and derives the scrub
     * / never-allowlist set from that union — core names the constant nowhere.
     *
     * <p>The name is {@link #TOKEN_ENV_VAR} unless the resolved connection renames it through {@code
     * credential} — which a named connection profile may (FR16, design D8) — so a profile-renamed
     * credential is scrubbed and barred from passthrough exactly like the default one.
     */
    @Override
    public List<String> credentialEnvVars(Map<String, Object> subsection) {
        return List.of(GithubCredential.nameOr(subsection, TOKEN_ENV_VAR));
    }

    /**
     * Exposes {@link GithubCheckSubsectionValidator} as this provider's own {@code
     * factory.check.github} content validator, so the load seam grades the subsection with the very
     * validator belonging to the factory that later builds the live client (FR4, design D1/D3/D12).
     */
    @Override
    public Optional<CheckSubsectionValidator> subsectionValidator() {
        return Optional.of(new GithubCheckSubsectionValidator());
    }

    /**
     * Exposes {@link GithubCheckParamsValidator} as this provider's manifest-side validator, so an
     * {@code external} check declaring a param this provider does not define is a located load
     * error naming the check and the offending key (FR5, FR6).
     */
    @Override
    public Optional<CheckParamsValidator> paramsValidator() {
        return Optional.of(new GithubCheckParamsValidator());
    }

    /**
     * This adapter's pin-path contribution (FR16, D10 of add-sandbox-core): exactly the {@code
     * checkId} workflow file, delegated to {@link GithubCheckPinPaths}.
     */
    @Override
    public ExternalCheckPinContributor pinContributor() {
        return GithubCheckPinPaths::contributedBy;
    }
}
