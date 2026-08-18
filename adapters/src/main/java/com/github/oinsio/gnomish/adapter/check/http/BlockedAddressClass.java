package com.github.oinsio.gnomish.adapter.check.http;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import org.jspecify.annotations.Nullable;

/**
 * The address classes an http check may never reach, whatever the manifest wrote and whatever DNS
 * answered (NFR-S2, design D5 of add-plugin-architecture). These are the addresses that mean
 * "inside": the loopback the factory itself listens on, the link-local range holding every cloud's
 * instance-metadata endpoint, and the private ranges of the network the factory runs in.
 *
 * <p>The judgement is on the <em>resolved address</em>, not on the name, which is what makes it an
 * SSRF defence rather than a spelling rule: a public name answering with {@code 169.254.169.254} is
 * refused exactly like the literal address, and so is a redirect into one, because every hop is
 * judged here.
 *
 * <p>Implements NFR-S2 of add-plugin-architecture.
 */
final class BlockedAddressClass {

    /** The address every major cloud serves instance credentials from — named on its own (UX2). */
    static final String CLOUD_METADATA = "169.254.169.254";

    private BlockedAddressClass() {}

    /**
     * Names the blocked class {@code address} falls in.
     *
     * @param address one resolved address of the target host; never null
     * @return the class name for a refusal message, or {@code null} when the address is reachable
     */
    static @Nullable String of(InetAddress address) {
        if (CLOUD_METADATA.equals(address.getHostAddress())) {
            return "cloud-metadata";
        }
        if (address.isLoopbackAddress()) {
            return "loopback";
        }
        if (address.isLinkLocalAddress()) {
            return "link-local";
        }
        if (address.isAnyLocalAddress()) {
            return "any-local";
        }
        if (address.isMulticastAddress()) {
            return "multicast";
        }
        if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
            return "private (RFC1918)";
        }
        return ipv6Internal(address);
    }

    /**
     * The IPv6 ranges that mean the same as RFC1918 but that the JDK's own predicates miss: unique
     * local addresses ({@code fc00::/7}), which {@link InetAddress#isSiteLocalAddress()} does not
     * report — it answers for the deprecated {@code fec0::/10} site-local range, covered here too.
     */
    private static @Nullable String ipv6Internal(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return null;
        }
        if (address.isSiteLocalAddress()) {
            return "site-local (IPv6)";
        }
        int first = address.getAddress()[0] & 0xff;
        return (first & 0xfe) == 0xfc ? "unique-local (IPv6)" : null;
    }
}
