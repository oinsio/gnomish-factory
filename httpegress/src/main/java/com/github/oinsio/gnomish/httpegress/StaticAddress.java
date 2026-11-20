package com.github.oinsio.gnomish.httpegress;

import java.net.InetAddress;
import java.util.Locale;
import java.util.Optional;

/**
 * The address class of a host as load may know it, with no lookup (FR17, design D16 of
 * migrate-verify-to-executor-contract, "Admission by medium at load"): an IP literal is its own
 * address, and {@code localhost} and every name under {@code .localhost} are loopback (RFC 6761
 * §6.3 reserves them). Every other name has no static class; its class is judged at connect, on
 * the addresses the guard resolves.
 *
 * <p>Implements FR17, NFR-R5 of migrate-verify-to-executor-contract.
 */
final class StaticAddress {

    private static final String LOCALHOST = "localhost";

    private StaticAddress() {}

    /**
     * The address standing for {@code host}'s class, when load can know it.
     *
     * @param host the URL's host, bracketed or not; never null
     * @return the literal's own address, the loopback address for a {@code localhost} name, or
     *     empty for a name whose class only a lookup can tell
     */
    static Optional<InetAddress> of(String host) {
        String normalized = EgressEntries.normalized(host);
        if (EgressEntries.isAddressLiteral(normalized)) {
            return Optional.of(InetAddress.ofLiteral(normalized));
        }
        String name = normalized.toLowerCase(Locale.ROOT);
        if (name.equals(LOCALHOST) || name.endsWith("." + LOCALHOST)) {
            return Optional.of(InetAddress.getLoopbackAddress());
        }
        return Optional.empty();
    }
}
