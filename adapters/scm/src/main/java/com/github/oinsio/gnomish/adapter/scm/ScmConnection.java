package com.github.oinsio.gnomish.adapter.scm;

import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider;
import com.github.oinsio.gnomish.domain.egress.AdmittedEndpoint;
import com.github.oinsio.gnomish.githubapi.GithubCredential;
import java.util.Map;

/**
 * A built-in's operator connection, as {@link ScmConnectionReader} read and admitted it (FR17,
 * NFR-S2, design D16 of migrate-verify-to-executor-contract): the endpoint the guard admitted, with
 * its admitted set, and the subsection's keys with a {@code connection:} profile resolved into them,
 * from which the built-in reads its own keys (a repository, a credential name).
 *
 * @param endpoint the admitted endpoint; never null
 * @param settings the resolved subsection; never null
 */
public record ScmConnection(AdmittedEndpoint endpoint, Map<String, Object> settings) {

    public ScmConnection {
        settings = Map.copyOf(settings);
    }

    /**
     * The name the connection's token resolves under: the subsection's or the profile's {@code
     * credential}, or the built-in's default (FR16, FR17 of add-plugin-architecture).
     *
     * @param defaultName the built-in's default credential name; never null
     * @return the credential name; never null
     */
    public String credential(String defaultName) {
        return GithubCredential.nameOr(settings, defaultName);
    }

    /**
     * The connection's token, resolved by its credential name through the secrets port (NFR-S2 of
     * migrate-verify-to-executor-contract): never from the tracker's subsection, and never
     * defaulted — a name that does not resolve fails closed naming the secret and the connection.
     *
     * @param secrets the secrets port; never null
     * @param defaultName the built-in's default credential name; never null
     * @return the token; never blank
     * @throws ScmTokenException when the secret is missing or blank
     */
    public String token(SecretsProvider secrets, String defaultName) {
        String credential = credential(defaultName);
        return secrets.find(credential)
                .orElseThrow(() -> new ScmTokenException(credential + " is required by the connection at "
                        + endpoint.key() + ", but is missing or blank"));
    }
}
