package com.github.oinsio.gnomish.adapter.check.http;

import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Grades the {@code factory.check.http} operator subsection (FR4, design D12 of
 * add-plugin-architecture), which is empty by definition: this provider has no connection to
 * configure — an http check carries its whole target in the manifest — and the hosts those targets
 * may reach are the operator's {@code factory.egress.in-process} scope, read through the one egress
 * grammar (FR17, design D16 of migrate-verify-to-executor-contract). The list that used to live
 * here is a removed key, reported at startup with the scope that replaces it.
 *
 * <p>Any key is a mistake, and reported as one rather than ignored: an operator who writes a base
 * URL or a token here is expecting behavior the provider does not have, and a silently unread key
 * would leave that expectation intact until a check failed for a reason nothing in the config
 * explained. An empty or absent subsection is legal: the provider is enabled and may reach only
 * what {@code factory.egress.in-process} grants.
 *
 * <p>Implements FR4, FR9, NFR-S2 of add-plugin-architecture; FR17 of
 * migrate-verify-to-executor-contract.
 */
public final class HttpCheckSubsectionValidator {

    /**
     * Grades the subsection's content: every key is an error.
     *
     * @param file the operator configuration file
     * @param where the located field prefix ({@code factory.check.http})
     * @param subsection the resolved subsection content
     * @return one located problem per key; empty for an empty subsection
     */
    public List<ConfigError> validate(String file, String where, Map<String, Object> subsection) {
        return new TreeSet<>(subsection.keySet())
                .stream()
                        .map(key -> new ConfigError(
                                file,
                                where + "." + key,
                                ("unknown key '%s'; the http provider takes no configuration — each check carries its"
                                                + " own target in the manifest, and the hosts it may reach are"
                                                + " factory.egress.in-process")
                                        .formatted(key)))
                        .toList();
    }
}
