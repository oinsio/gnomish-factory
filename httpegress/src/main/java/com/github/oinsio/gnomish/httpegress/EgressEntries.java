package com.github.oinsio.gnomish.httpegress;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The one grammar of an egress entry (FR17, design D16 of migrate-verify-to-executor-contract), and
 * the one place an entry is matched against a host or an address. Every egress list the factory
 * reads is parsed here: the operator's {@code factory.egress.sandbox} and {@code
 * factory.egress.in-process}, a law's {@code executors.<name>.egress}, an executor's describe
 * {@code egress} and, with design D16's admission, a connection's {@code egress}.
 *
 * <p>An entry, after stripping surrounding blanks and folding to lower case, is one of:
 *
 * <ul>
 *   <li>an exact host name ({@code sonar.example.com});
 *   <li>a wildcard {@code *.suffix}, matching a host at <em>any depth</em> below the suffix and
 *       <strong>never the apex</strong>: {@code *.blob.core.windows.net} matches {@code
 *       abc.blob.core.windows.net} and {@code a.b.blob.core.windows.net}, never {@code
 *       blob.core.windows.net} (Cilium {@code toFQDNs} and Gitea {@code ALLOWED_HOST_LIST}
 *       semantics);
 *   <li>an IP literal, IPv6 written bare or bracketed ({@code [fd00::1]} reads as {@code fd00::1});
 *   <li>a CIDR block ({@code 10.20.0.0/16}, {@code fd00::/8}), granting a host whose every address
 *       lies inside it;
 *   <li>an address-class word: {@value #LOOPBACK} (the loopback addresses) or {@value #PRIVATE}
 *       (RFC 1918 and IPv6 unique-local), granting a host whose every address lies in that class.
 * </ul>
 *
 * <p>A port is not part of the grammar (NG8). The <strong>{@code sandbox} subset</strong> — exact
 * host, IP literal and {@code *.suffix}, {@link #isSandboxEntry} — is what the box guard can apply:
 * it matches names only and resolves nothing, so {@code factory.egress.sandbox} and a law's {@code
 * executors.<name>.egress} refuse a CIDR block or a class word at load.
 *
 * <p>Kept in sync with {@code EgressGuardConfig} (module {@code :sandbox:docker}): the guard's
 * mitmproxy addon matches the {@code sandbox} subset by hand, in Python inside the box, where no
 * Java class can run — an exact host by string equality and {@code *.suffix} as a suffix that
 * never matches the apex; {@link #namesHost} must answer exactly as the addon does for every name
 * of that subset. {@code EgressGuardMatcherIdentitySpec} drives both over one table of names.
 *
 * <p>Implements FR17 of migrate-verify-to-executor-contract; FR7 of add-sandbox-core; FR4, FR9 of
 * define-executor-contract.
 */
public final class EgressEntries {

    /** The wildcard prefix: {@code *.example.com} names every host below {@code example.com}. */
    public static final String WILDCARD_PREFIX = "*.";

    /** The class word granting the loopback addresses. */
    public static final String LOOPBACK = "loopback";

    /** The class word granting the private addresses: RFC 1918 and IPv6 unique-local. */
    public static final String PRIVATE = "private";

    private static final Pattern NAME = Pattern.compile("[a-z0-9._-]+");
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private EgressEntries() {}

    /**
     * Whether {@code entry} is an entry of the full grammar once normalized.
     *
     * @param entry the raw entry as written; never null
     * @return true for an exact host, a wildcard, an IP literal, a CIDR block or a class word
     */
    public static boolean isEntry(String entry) {
        String normalized = normalized(entry);
        return isSandboxEntry(normalized) || isClassWord(normalized) || isCidr(normalized);
    }

    /**
     * Whether {@code entry} is in the {@code sandbox} subset the box guard can apply.
     *
     * @param entry the raw entry as written; never null
     * @return true for an exact host, a wildcard or an IP literal; false for a CIDR block or a class
     *     word, which the box guard cannot match
     */
    public static boolean isSandboxEntry(String entry) {
        String normalized = normalized(entry);
        if (isClassWord(normalized)) {
            return false;
        }
        if (normalized.startsWith(WILDCARD_PREFIX)) {
            return NAME.matcher(normalized.substring(WILDCARD_PREFIX.length())).matches();
        }
        return NAME.matcher(normalized).matches() || isAddressLiteral(normalized);
    }

    /**
     * The entry as it is matched and rendered: surrounding blanks stripped, folded to lower case,
     * and an IPv6 literal unbracketed, so an entry and a host compare in one spelling.
     *
     * @param entry the raw entry as written; never null
     * @return the normalized entry
     */
    public static String normalized(String entry) {
        String folded = entry.strip().toLowerCase(Locale.ROOT);
        return folded.length() > 2 && folded.startsWith("[") && folded.endsWith("]")
                ? folded.substring(1, folded.length() - 1)
                : folded;
    }

    /**
     * Whether {@code entry} names {@code host} itself — exactly, as an IP literal spelled the same
     * way, or as a subdomain at any depth of a wildcard's suffix, never its apex. A CIDR block or a
     * class word names no host: it grants by address ({@link #coversAddress}).
     *
     * @param entry an entry of the grammar; never null
     * @param host the host as a URL carries it, bracketed or not; never null
     * @return true when the entry names the host
     */
    public static boolean namesHost(String entry, String host) {
        String rule = normalized(entry);
        String candidate = normalized(host);
        if (rule.startsWith(WILDCARD_PREFIX)) {
            return candidate.endsWith(rule.substring(1));
        }
        return !grantsByAddress(rule) && rule.equals(candidate);
    }

    /**
     * Whether {@code entry} grants by address rather than by name: a CIDR block or a class word.
     *
     * @param entry an entry of the grammar; never null
     * @return true for a CIDR block or a class word
     */
    public static boolean grantsByAddress(String entry) {
        String normalized = normalized(entry);
        return isClassWord(normalized) || isCidr(normalized);
    }

    /**
     * Whether {@code address} lies in what an address-granting entry covers: inside a CIDR block,
     * or in the class a class word names. A name, a wildcard or an IP literal covers no address.
     *
     * @param entry an entry of the grammar; never null
     * @param address one resolved address; never null
     * @return true when the entry covers the address
     */
    public static boolean coversAddress(String entry, InetAddress address) {
        String rule = normalized(entry);
        return switch (rule) {
            case LOOPBACK -> address.isLoopbackAddress();
            case PRIVATE -> isPrivate(address);
            default -> isCidr(rule) && Cidr.parse(rule).contains(address);
        };
    }

    /**
     * Whether {@code address} is in the class {@value #PRIVATE} names: RFC 1918 or IPv6
     * unique-local ({@code fc00::/7}), which the JDK's own site-local predicate does not report.
     */
    static boolean isPrivate(InetAddress address) {
        if (address instanceof Inet4Address) {
            return address.isSiteLocalAddress();
        }
        return address instanceof Inet6Address && (address.getAddress()[0] & 0xfe) == 0xfc;
    }

    private static boolean isClassWord(String normalized) {
        return LOOPBACK.equals(normalized) || PRIVATE.equals(normalized);
    }

    private static boolean isCidr(String normalized) {
        return Cidr.isCidr(normalized);
    }

    /** An IPv4 dotted quad or an IPv6 literal — the forms a URL's host carries. */
    static boolean isAddressLiteral(String normalized) {
        if (!IPV4.matcher(normalized).matches() && !normalized.contains(":")) {
            return false;
        }
        try {
            InetAddress.ofLiteral(normalized);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
