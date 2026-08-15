package com.github.oinsio.gnomish.app.port.secrets;

import java.util.Optional;

/**
 * The single seam through which the factory resolves a named secret (design
 * D12): the GitHub tracker token today, gateway/depot credentials as later
 * changes add them. Consumers obtain a value by name and are unaffected by
 * which adapter backs the port; installation config selects the adapter.
 *
 * <p>The port resolves one secret at a time and deliberately exposes <em>no</em>
 * enumeration of all secrets — the set of names in use is discoverable only
 * from the port's call sites, keeping one auditable seam (NFR-S1).
 *
 * <p>Resolution is fail-closed by contract: {@link #find(String)} returns an
 * empty {@link Optional} when the named secret is absent or blank, never a
 * silent empty string; the consumer decides whether an absent secret is a
 * startup configuration error or a use-time infrastructure failure and raises
 * an error naming the secret. Adapters SHALL never log a resolved value.
 *
 * <p>Implements FR18, NFR-S1 of add-sandbox-core.
 */
public interface SecretsProvider {

    /**
     * Resolves the secret named {@code name}, or returns empty when it is
     * absent or blank — the fail-closed contract (NFR-S1): a caller that
     * requires the secret turns empty into an error naming {@code name}, never
     * a default or empty value.
     *
     * @param name the secret's name (e.g. {@code GNOMISH_GITHUB_TOKEN}); never
     *     null
     * @return the resolved non-blank value, or empty when the secret is absent
     *     or blank
     */
    Optional<String> find(String name);
}
