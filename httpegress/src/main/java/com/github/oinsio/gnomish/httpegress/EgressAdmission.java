package com.github.oinsio.gnomish.httpegress;

import com.github.oinsio.gnomish.domain.egress.AdmittedEndpoint;
import com.github.oinsio.gnomish.domain.egress.ConnectionEndpoint;
import com.github.oinsio.gnomish.domain.egress.EgressMedium;
import com.github.oinsio.gnomish.domain.egress.EgressRefusal;
import com.github.oinsio.gnomish.domain.egress.EgressRefusedException;
import com.github.oinsio.gnomish.domain.egress.EndpointDeclaration;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Admission of a connection's endpoint by the medium its URL comes from (FR15, FR17, NFR-R5, UX4,
 * design D16 of migrate-verify-to-executor-contract as amended 2026-11-09), and the only place an
 * {@link AdmittedEndpoint} is minted ({@code EgressMediumMintingSpec}).
 *
 * <p>Load judges what needs no network: the scheme and host, the grant of a law-medium host, and
 * the <em>static</em> class of a host ({@link StaticAddress}). It resolves no name.
 *
 * <ul>
 *   <li>An operator-medium host is granted; it may be {@code http} or {@code https}.
 *   <li>A law-medium host is a request: granted by an entry of the connection's operator {@code
 *       egress} or of the {@code in-process} scope naming it, or by the vendor default host the
 *       consumer declares. A host whose only possible grant is an address-granting entry (a class
 *       word, a CIDR block) and whose class is not static is admitted conditionally: the grant is
 *       judged at connect, on the resolved addresses, and a miss there is the same refusal naming
 *       the same key. A request nobody can grant is refused, naming the two ways to grant it.
 *   <li>A law-medium connection is {@code https} unless its host is statically loopback and the
 *       class word {@value EgressEntries#LOOPBACK} is in its admitted set.
 *   <li>A host whose static class is blocked is refused unless an address-granting entry of the
 *       admitted set covers it; the cloud-metadata address is refused always.
 * </ul>
 *
 * <p>Implements FR15, FR17, NFR-R5, UX4 of migrate-verify-to-executor-contract.
 */
public final class EgressAdmission {

    private static final String HTTPS = "https";
    private static final String HTTP = "http";

    private EgressAdmission() {}

    /**
     * Admits {@code endpoint} for the consumer {@code declaration} names.
     *
     * @param endpoint the endpoint with the medium it was read from; never null
     * @param declaration the consumer's key, placed where the operator edits it, and its vendor
     *     default host; never null
     * @param operator the operator's {@code in-process} scope; never null
     * @return the admitted endpoint, carrying its admitted set; never null
     * @throws EgressRefusedException when load can already tell the endpoint is refused, naming the
     *     key
     */
    public static AdmittedEndpoint admit(
            ConnectionEndpoint endpoint, EndpointDeclaration declaration, OperatorEgress operator) {
        return admit(endpoint, declaration, operator, List.of());
    }

    /**
     * Admits {@code endpoint} for a consumer that also declares hosts of its own: a built-in's
     * describe {@code egress} (design D16, "A redirect target is an egress target"). The declared
     * entries join the admitted set beside the connection's {@code egress} and the {@code in-process}
     * scope, so a hop onto a host the consumer's platform is known to redirect to is admitted with no
     * operator entry.
     *
     * @param endpoint the endpoint with the medium it was read from; never null
     * @param declaration the consumer's key, placed where the operator edits it, and its vendor
     *     default host; never null
     * @param operator the operator's {@code in-process} scope; never null
     * @param declaredEgress the consumer's own declared hosts, in the egress entry grammar; never null,
     *     may be empty
     * @return the admitted endpoint, carrying its admitted set; never null
     * @throws EgressRefusedException when load can already tell the endpoint is refused, naming the
     *     key
     */
    public static AdmittedEndpoint admit(
            ConnectionEndpoint endpoint,
            EndpointDeclaration declaration,
            OperatorEgress operator,
            List<String> declaredEgress) {
        URI url = endpoint.url();
        String key = declaration.key();
        String host = url.getHost();
        if (host == null || host.isBlank()) {
            throw refusal(EgressRefusal.Reason.NOT_ALLOWLISTED, url, "the URL names no host", key);
        }
        String scheme = String.valueOf(url.getScheme()).toLowerCase(Locale.ROOT);
        if (!HTTPS.equals(scheme) && !HTTP.equals(scheme)) {
            throw refusal(EgressRefusal.Reason.SCHEME, url, "only http or https is reachable", key);
        }
        List<String> grants = new ArrayList<>(endpoint.egress());
        grants.addAll(declaredEgress);
        grants.addAll(operator.inProcess());
        boolean named = endpoint.medium() != EgressMedium.LAW
                || EgressEntries.normalized(host).equals(EgressEntries.normalized(declaration.vendorDefaultHost()))
                || grants.stream().anyMatch(entry -> EgressEntries.namesHost(entry, host));
        Optional<InetAddress> staticAddress = StaticAddress.of(host);
        if (!named && !grantedByAddress(grants, staticAddress)) {
            throw refusal(
                    EgressRefusal.Reason.NOT_ALLOWLISTED,
                    url,
                    ("host '%s' is requested by the law and granted by no operator entry; grant it with a %s"
                                    + " entry, or reference a 'connection:' profile that declares it")
                            .formatted(host, EgressAllowlist.IN_PROCESS_KEY),
                    key);
        }
        staticAddress.ifPresent(address -> judgeStaticClass(url, address, grants, key));
        if (HTTP.equals(scheme) && endpoint.medium() == EgressMedium.LAW && !loopbackGranted(staticAddress, grants)) {
            throw refusal(
                    EgressRefusal.Reason.SCHEME,
                    url,
                    "a URL the law names is https unless its host is loopback and granted by '" + EgressEntries.LOOPBACK
                            + "'",
                    key);
        }
        List<String> admitted = new ArrayList<>();
        if (named) {
            admitted.add(host);
        }
        admitted.addAll(grants);
        return new AdmittedEndpoint(url, key, admitted);
    }

    /**
     * Judges a stage manifest's http target at load (design D16, the law-medium bullet): a law
     * request with no connection, so its one admitted set is the {@code in-process} scope, it has no
     * vendor default, and it is {@code https} only. Nothing is minted: {@link
     * EgressAllowlist#of(OperatorEgress)} judges the target again, hop by hop, at connect.
     *
     * @param target the target, no {@code ${…}} reference in its authority; never null
     * @param operator the operator's {@code in-process} scope; never null
     * @throws EgressRefusedException when load can already tell the target is refused, naming
     *     {@value EgressAllowlist#IN_PROCESS_KEY}
     */
    public static void request(URI target, OperatorEgress operator) {
        String key = EgressAllowlist.IN_PROCESS_KEY;
        String host = target.getHost();
        if (host == null || host.isBlank()) {
            throw refusal(EgressRefusal.Reason.NOT_ALLOWLISTED, target, "the URL names no host", key);
        }
        if (!HTTPS.equalsIgnoreCase(String.valueOf(target.getScheme()))) {
            throw refusal(EgressRefusal.Reason.SCHEME, target, "a manifest target is https only", key);
        }
        List<String> grants = operator.inProcess();
        Optional<InetAddress> staticAddress = StaticAddress.of(host);
        if (grants.stream().noneMatch(entry -> EgressEntries.namesHost(entry, host))
                && !grantedByAddress(grants, staticAddress)) {
            throw refusal(
                    EgressRefusal.Reason.NOT_ALLOWLISTED,
                    target,
                    ("host '%s' is requested by the law and granted by no operator entry; grant it with a %s"
                                    + " entry naming the host, a '*.' suffix covering it, or its address class"
                                    + " word — a 'connection:' profile grants only its own connection's calls")
                            .formatted(host, key),
                    key);
        }
        staticAddress.ifPresent(address -> judgeStaticClass(target, address, grants, key));
    }

    /**
     * Whether an address-granting entry may grant the host: at load for a static class that one
     * covers, at connect (conditionally) for a name whose class only a lookup tells.
     */
    private static boolean grantedByAddress(List<String> grants, Optional<InetAddress> staticAddress) {
        return staticAddress
                .map(address -> grants.stream().anyMatch(entry -> EgressEntries.coversAddress(entry, address)))
                .orElseGet(() -> grants.stream().anyMatch(EgressEntries::grantsByAddress));
    }

    /** A statically blocked class is refused unless an address-granting entry covers it. */
    private static void judgeStaticClass(URI url, InetAddress address, List<String> grants, String key) {
        String blocked = BlockedAddressClass.of(address);
        if (blocked == null) {
            return;
        }
        boolean waived = !BlockedAddressClass.CLOUD_METADATA.equals(address.getHostAddress())
                && grants.stream().anyMatch(entry -> EgressEntries.coversAddress(entry, address));
        if (!waived) {
            throw refusal(
                    EgressRefusal.Reason.ADDRESS_CLASS,
                    url,
                    "host '%s' is %s, and no entry of its admitted set waives the class"
                            .formatted(url.getHost(), blocked),
                    key);
        }
    }

    private static boolean loopbackGranted(Optional<InetAddress> staticAddress, List<String> grants) {
        return staticAddress.filter(InetAddress::isLoopbackAddress).isPresent()
                && grants.stream().map(EgressEntries::normalized).anyMatch(EgressEntries.LOOPBACK::equals);
    }

    private static EgressRefusedException refusal(EgressRefusal.Reason reason, URI url, String detail, String key) {
        return new EgressRefusedException(new EgressRefusal(reason, url.toString(), detail, key));
    }
}
