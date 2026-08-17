package com.github.oinsio.gnomish.adapter.check.http;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * The factory-side egress allowlist governing every request the {@code http} check provider makes
 * (NFR-S2, design D5 of add-plugin-architecture). It is the sole guard against the http check being
 * used as an SSRF or exfiltration vector: {@code EgressGuard} watches the agent sandbox, not the
 * factory's own outbound calls, and the factory's calls are the ones with credentials attached.
 *
 * <p>It is built from the operator's {@code factory.check.http.allowlist} and from nothing else. A
 * stage manifest — repo-committed, written by whoever the pipeline serves — declares a target but
 * can never widen what is reachable, which is the whole point of the split.
 *
 * <p>Four rules, applied to the first hop and re-applied to every redirect hop alike: only {@code
 * https}; only a host an allowlist entry names; and never an address class that means "inside"
 * ({@link BlockedAddressClass}) — waived only when the operator allowlisted that literal address,
 * which is the one way to say "yes, this internal endpoint, deliberately"; and a host that does not
 * resolve is refused rather than attempted.
 *
 * <p>An unset or empty allowlist permits nothing. Configuring the provider is enabling it; deciding
 * where it may call is a separate, explicit act.
 *
 * <p>Implements NFR-S2, UX2 of add-plugin-architecture.
 */
final class EgressAllowlist {

    /** The one key of the {@code factory.check.http} operator subsection. */
    static final String ALLOWLIST_KEY = "allowlist";

    /** The only permitted scheme: an http check sends a credential and reads a verdict. */
    static final String SCHEME = "https";

    /** The wildcard form an entry may take: {@code *.example.com} matches any subdomain of it. */
    static final String WILDCARD_PREFIX = "*.";

    private final List<String> entries;
    private final HostResolver resolver;

    EgressAllowlist(List<String> entries, HostResolver resolver) {
        this.entries = entries.stream().map(EgressAllowlist::normalize).toList();
        this.resolver = resolver;
    }

    /** The allowlist an operator subsection declares, over the platform name service. */
    static EgressAllowlist from(Map<String, Object> subsection) {
        return new EgressAllowlist(entriesOf(subsection), HostResolver.system());
    }

    /** The declared entries of a subsection, as text; anything else in it is a config error. */
    static List<String> entriesOf(Map<String, Object> subsection) {
        if (!(subsection.get(ALLOWLIST_KEY) instanceof List<?> declared)) {
            return List.of();
        }
        return declared.stream().map(String::valueOf).toList();
    }

    /**
     * Judges one target before any connection to it is attempted.
     *
     * @param target the absolute URL of the hop about to be requested; never null
     * @return the refusal, or {@code null} when the target is permitted
     */
    @Nullable
    EgressRefusal refuse(URI target) {
        if (!SCHEME.equalsIgnoreCase(target.getScheme())) {
            return new EgressRefusal(
                    EgressRefusal.Reason.SCHEME,
                    target.toString(),
                    "only '%s' is permitted, got '%s'".formatted(SCHEME, target.getScheme()));
        }
        String host = target.getHost();
        if (host == null || host.isBlank()) {
            return new EgressRefusal(EgressRefusal.Reason.NOT_ALLOWLISTED, target.toString(), "target names no host");
        }
        if (!permits(host)) {
            return new EgressRefusal(
                    EgressRefusal.Reason.NOT_ALLOWLISTED,
                    target.toString(),
                    "host '%s' is on no entry of factory.check.http.%s; allowed: %s"
                            .formatted(host, ALLOWLIST_KEY, new TreeSet<>(entries)));
        }
        return addressRefusal(target, host);
    }

    /** The address-class judgement on every address the host resolves to (NFR-S2). */
    private @Nullable EgressRefusal addressRefusal(URI target, String host) {
        List<InetAddress> addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            return new EgressRefusal(
                    EgressRefusal.Reason.UNRESOLVABLE, target.toString(), "host '%s' does not resolve".formatted(host));
        }
        for (InetAddress address : addresses) {
            String blocked = BlockedAddressClass.of(address);
            if (blocked != null && !allowlistsLiterally(host)) {
                return new EgressRefusal(
                        EgressRefusal.Reason.ADDRESS_CLASS,
                        target.toString(),
                        "host '%s' resolves to %s, which is %s".formatted(host, address.getHostAddress(), blocked));
            }
        }
        return null;
    }

    /** Whether any entry names {@code host} — exactly, or as a subdomain of a wildcard entry. */
    private boolean permits(String host) {
        String candidate = normalize(host);
        return entries.stream()
                .anyMatch(entry -> entry.startsWith(WILDCARD_PREFIX)
                        ? candidate.endsWith("." + entry.substring(WILDCARD_PREFIX.length()))
                        : entry.equals(candidate));
    }

    /**
     * Whether the operator allowlisted this host as a literal address — the deliberate opt-in that
     * waives the address-class block. A <em>name</em> never waives it: a name resolving inside is
     * precisely the rebinding case the block exists for.
     */
    private boolean allowlistsLiterally(String host) {
        String candidate = normalize(host);
        return entries.contains(candidate) && isAddressLiteral(candidate);
    }

    /** {@code InetAddress.ofLiteral} accepts the bracketed IPv6 form a URL's host carries. */
    private static boolean isAddressLiteral(String host) {
        try {
            InetAddress.ofLiteral(host);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String normalize(String host) {
        return host.trim().toLowerCase(Locale.ROOT);
    }
}
