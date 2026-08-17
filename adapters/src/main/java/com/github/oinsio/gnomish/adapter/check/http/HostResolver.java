package com.github.oinsio.gnomish.adapter.check.http;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * The name-resolution seam of {@link EgressAllowlist} (NFR-S2, design D5 of add-plugin-architecture):
 * an allowlisted <em>name</em> says nothing about the <em>address</em> it points at, and the address
 * is what the SSRF rules judge, so the allowlist must resolve before it may permit.
 *
 * <p>It is a seam so the address-class rules can be specified over hosts that resolve exactly where a
 * spec wants them to — the cloud-metadata address, an RFC1918 range — without depending on the DNS a
 * build machine happens to have.
 *
 * <p>Implements NFR-S2 of add-plugin-architecture.
 */
@FunctionalInterface
interface HostResolver {

    /**
     * Resolves a host to every address it names.
     *
     * @param host the URL's host component; never null
     * @return the addresses, in resolution order; never null, never empty
     * @throws UnknownHostException if the host does not resolve — a refusal, never a pass
     */
    List<InetAddress> resolve(String host) throws UnknownHostException;

    /** The production resolver: the platform's own name service. */
    static HostResolver system() {
        return host -> List.of(InetAddress.getAllByName(host));
    }
}
