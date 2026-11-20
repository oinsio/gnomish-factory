package com.github.oinsio.gnomish.domain.egress;

/**
 * How a consumer names its HTTP endpoint (FR17, design D16 of
 * migrate-verify-to-executor-contract): the configuration key that holds it and the vendor's own
 * host, which a law-medium URL may name without an operator grant — a GitHub token is for GitHub.
 *
 * <p>Implements FR17 of migrate-verify-to-executor-contract.
 *
 * @param key the configuration key holding the endpoint — the subsection key as an adapter declares
 *     it ({@code api-url}), or the full key once the caller has placed it ({@link #under}); never
 *     blank
 * @param vendorDefaultHost the vendor's own host, e.g. {@code api.github.com}; never blank
 */
public record EndpointDeclaration(String key, String vendorDefaultHost) {

    public EndpointDeclaration {
        if (key.isBlank() || vendorDefaultHost.isBlank()) {
            throw new IllegalArgumentException("an endpoint declaration names a key and a vendor host");
        }
    }

    /**
     * The same declaration with its key placed under {@code prefix}, so a refusal names the full
     * configuration key the operator edits ({@code tracker.github.api-url}).
     *
     * @param prefix the subsection's own location, e.g. {@code tracker.github}; never blank
     * @return the placed declaration; never null
     */
    public EndpointDeclaration under(String prefix) {
        return new EndpointDeclaration(prefix + "." + key, vendorDefaultHost);
    }
}
