package com.github.oinsio.gnomish.config;

import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;

/**
 * The removed {@code factory.*} keys that name their replacement (FR17, design D16 of
 * migrate-verify-to-executor-contract): a key the factory no longer reads, keyed by the old key, to
 * the key an operator moves its value to. A source that sets one is refused at startup with a
 * violation naming both, so no alias survives and the old and the new list cannot drift.
 *
 * <p>Only a key with a replacement is listed. A key removed with nothing to replace it — {@code
 * factory.agent-cli-env-passthrough} — stays an unknown key, reported as one.
 *
 * <p>Implements FR17 of migrate-verify-to-executor-contract.
 */
final class RemovedKeys {

    /** Old key → the key that replaces it, compared as Spring binds. */
    private static final Map<ConfigurationPropertyName, String> REPLACED = Map.of(
            ConfigurationPropertyName.of("factory.sandbox.egress-allowlist"), "factory.egress.sandbox",
            ConfigurationPropertyName.of("factory.check.http.allowlist"), "factory.egress.in-process");

    private RemovedKeys() {}

    /** A removed key and its replacement. */
    record Removed(String key, String replacement) {}

    /**
     * The removed key {@code property} sets, if any.
     *
     * @param property a property name as a source spells it, list index included, e.g. {@code
     *     factory.sandbox.egress-allowlist[0]}
     * @return the removed key and its replacement; empty for any other property
     */
    static Optional<Removed> of(String property) {
        ConfigurationPropertyName name = ConfigurationPropertyName.adapt(property.replaceAll("\\[\\d+]", ""), '.');
        return REPLACED.entrySet().stream()
                .filter(entry -> entry.getKey().equals(name) || entry.getKey().isAncestorOf(name))
                .findFirst()
                .map(entry -> new Removed(entry.getKey().toString(), entry.getValue()));
    }
}
