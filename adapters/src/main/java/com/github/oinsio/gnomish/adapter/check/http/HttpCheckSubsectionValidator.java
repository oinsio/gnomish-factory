package com.github.oinsio.gnomish.adapter.check.http;

import com.github.oinsio.gnomish.app.CheckSubsectionValidator;
import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Grades the {@code factory.check.http} operator subsection (FR4, design D12 of
 * add-plugin-architecture). This provider has no connection to configure — an http check carries its
 * whole target in the manifest — so the subsection holds exactly one thing: the egress allowlist
 * deciding which of those targets are reachable (NFR-S2, design D5).
 *
 * <p>The allowlist lives here and nowhere else on purpose. A stage manifest is repo-committed and
 * written by whoever the pipeline serves; operator configuration is the factory operator's. Putting
 * the sole SSRF guard on the operator's side is what makes "a manifest cannot widen it" true by
 * construction rather than by review.
 *
 * <p>Any other key is a mistake, and reported as one rather than ignored: an operator who writes a
 * base URL or a token here is expecting behavior the provider does not have, and a silently unread
 * key would leave that expectation intact until a check failed for a reason nothing in the config
 * explained. An <em>absent</em> allowlist is legal and means what it says: the provider is enabled
 * and may reach nothing until the operator says where.
 *
 * <p>Implements FR4, FR9, NFR-S2 of add-plugin-architecture.
 */
public final class HttpCheckSubsectionValidator implements CheckSubsectionValidator {

    @Override
    public List<ConfigError> validate(String file, String where, Map<String, Object> subsection) {
        List<ConfigError> errors = new ArrayList<>();
        for (String key : new TreeSet<>(subsection.keySet())) {
            if (!EgressAllowlist.ALLOWLIST_KEY.equals(key)) {
                errors.add(new ConfigError(
                        file,
                        where + "." + key,
                        ("unknown key '%s'; the http provider needs no connection configuration — its only"
                                        + " setting is '%s', and each check carries its own target in the manifest")
                                .formatted(key, EgressAllowlist.ALLOWLIST_KEY)));
            }
        }
        validateAllowlist(file, where, subsection, errors);
        return List.copyOf(errors);
    }

    /** Entries are bare hosts, optionally wildcarded as {@code *.example.com} — never URLs. */
    private static void validateAllowlist(
            String file, String where, Map<String, Object> subsection, List<ConfigError> errors) {
        String located = where + "." + EgressAllowlist.ALLOWLIST_KEY;
        Object declared = subsection.get(EgressAllowlist.ALLOWLIST_KEY);
        if (declared == null) {
            return;
        }
        if (!(declared instanceof List<?> entries)) {
            errors.add(new ConfigError(file, located, "must be a list of permitted hosts"));
            return;
        }
        for (Object entry : entries) {
            String host = String.valueOf(entry);
            if (host.isBlank() || host.contains("/") || host.strip().contains(" ") || carriesPort(host)) {
                errors.add(new ConfigError(
                        file,
                        located,
                        ("entry '%s' is not a host; write a bare host ('sonar.example.com') or a wildcard"
                                        + " ('%sexample.com'), never a URL, scheme or port")
                                .formatted(host, EgressAllowlist.WILDCARD_PREFIX)));
            }
        }
    }

    /**
     * Whether an entry carries a port (or any other colon-bearing shape). An IPv6 literal is written
     * bracketed, exactly as a URL's host component carries it, and is the one legal use of a colon:
     * a literal address is how an operator deliberately permits an otherwise blocked address class.
     */
    private static boolean carriesPort(String host) {
        String bare = host.strip();
        if (bare.startsWith("[") && bare.endsWith("]")) {
            return false;
        }
        return bare.contains(":");
    }
}
