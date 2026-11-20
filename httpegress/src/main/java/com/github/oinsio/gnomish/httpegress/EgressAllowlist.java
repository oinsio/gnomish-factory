package com.github.oinsio.gnomish.httpegress;

import com.github.oinsio.gnomish.domain.egress.AdmittedEndpoint;
import com.github.oinsio.gnomish.domain.egress.EgressRefusal;
import com.github.oinsio.gnomish.domain.egress.EgressRefusedException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * The connect-time judgement of one consumer's admitted set (NFR-S2, design D5 of
 * add-plugin-architecture; FR15, FR17, design D16 of migrate-verify-to-executor-contract): every hop
 * a consumer is about to send — the first included — is judged here before any socket to it is
 * opened. {@code EgressGuard} watches the agent sandbox, not the factory's own outbound calls, and
 * the factory's calls are the ones with credentials attached.
 *
 * <p>One allowlist per consumer: the {@code http} built-in's is the operator's {@value
 * #IN_PROCESS_KEY} scope ({@link #of(OperatorEgress)}), and a connection's is the admitted set its {@link
 * AdmittedEndpoint} carries ({@link #of(AdmittedEndpoint)}) — a class word waives its block for the
 * set it is listed in and no other. A refusal names the consumer's configuration key.
 *
 * <p>Every entry is read by the one egress grammar, {@link EgressEntries}; this class holds no
 * grammar of its own. Four rules: the scheme ({@code https}, or {@code http} for an endpoint
 * admitted on it); a host an entry grants — by name ({@link EgressEntries#namesHost}), or by address
 * when every address the host resolves to lies in a CIDR block or class word of the set; never an
 * address class that means "inside" ({@link BlockedAddressClass}) unless an entry waives it for that
 * address — the IP literal the target itself names, or a CIDR block or class word covering it; and
 * the cloud-metadata address waived by nothing. A <em>name</em> never waives the block: a name
 * resolving inside is precisely the rebinding case the block exists for. A host that does not
 * resolve is no refusal: the lookup's {@link UnknownHostException} is a transport failure.
 *
 * <p>Implements NFR-S2, UX2 of add-plugin-architecture; FR15, FR17, NFR-R5 of
 * migrate-verify-to-executor-contract.
 */
public final class EgressAllowlist {

    /** The operator key the {@code in-process} scope is read from. */
    public static final String IN_PROCESS_KEY = "factory.egress.in-process";

    /** The scheme every target may use. */
    public static final String SCHEME = "https";

    private static final String PLAIN = "http";

    private final List<String> entries;
    private final HostResolver resolver;
    private final String key;
    private final boolean plainHttp;

    /**
     * @param entries the admitted set, as entries of the grammar; never null
     * @param resolver the name service the address judgement resolves through; never null
     * @param key the consumer's configuration key every refusal names; never blank
     * @param plainHttp whether a target may be {@code http} — an endpoint admitted on it; never for a
     *     manifest target
     */
    public EgressAllowlist(List<String> entries, HostResolver resolver, String key, boolean plainHttp) {
        this.entries = entries.stream().map(EgressEntries::normalized).toList();
        this.resolver = resolver;
        this.key = key;
        this.plainHttp = plainHttp;
    }

    /**
     * The {@code http} built-in's allowlist: the operator's {@value #IN_PROCESS_KEY} scope over the
     * platform name service, {@code https} only. It takes the typed scope, never a bare list, so no
     * consumer can hand the guard a list the operator did not write.
     *
     * @param operator the operator's {@code in-process} scope; never null
     * @return the allowlist
     */
    public static EgressAllowlist of(OperatorEgress operator) {
        return new EgressAllowlist(operator.inProcess(), HostResolver.system(), IN_PROCESS_KEY, false);
    }

    /**
     * A connection's allowlist: the admitted set its endpoint carries, over the platform name
     * service, naming the endpoint's key; {@code http} is reachable when the endpoint was admitted on
     * it.
     *
     * @param endpoint the admitted endpoint; never null
     * @return the allowlist
     */
    public static EgressAllowlist of(AdmittedEndpoint endpoint) {
        return new EgressAllowlist(
                endpoint.admitted(),
                HostResolver.system(),
                endpoint.key(),
                PLAIN.equalsIgnoreCase(endpoint.url().getScheme()));
    }

    /**
     * Repeats the connect-time judgement of {@code endpoint}'s own URL and sends no request (the
     * {@code EGRESS} condition's probe; design D16 of migrate-verify-to-executor-contract, FR18):
     * the host resolved, every address judged against the endpoint's admitted set.
     *
     * @param endpoint the admitted endpoint; never null
     * @throws EgressRefusedException when the judgement refuses the endpoint now
     * @throws UncheckedIOException when the host does not resolve — no answer, not a refusal
     */
    public static void judge(AdmittedEndpoint endpoint) {
        EgressRefusal refusal;
        try {
            refusal = of(endpoint).refuse(endpoint.url());
        } catch (UnknownHostException e) {
            throw new UncheckedIOException(e);
        }
        if (refusal != null) {
            throw new EgressRefusedException(refusal);
        }
    }

    /** The configuration key a refusal of this allowlist names. */
    public String key() {
        return key;
    }

    /**
     * Judges one target before any connection to it is attempted.
     *
     * @param target the absolute URL of the hop about to be requested; never null
     * @return the refusal, or {@code null} when the target is permitted
     * @throws UnknownHostException when the host does not resolve — a transport failure, not a
     *     refusal (design D16, the outcome classes)
     */
    public @Nullable EgressRefusal refuse(URI target) throws UnknownHostException {
        String scheme = String.valueOf(target.getScheme()).toLowerCase(Locale.ROOT);
        if (!SCHEME.equals(scheme) && !(plainHttp && PLAIN.equals(scheme))) {
            return refusal(
                    EgressRefusal.Reason.SCHEME,
                    target,
                    "only '%s' is permitted, got '%s'".formatted(plainHttp ? "http' or 'https" : SCHEME, scheme));
        }
        String host = target.getHost();
        if (host == null || host.isBlank()) {
            return refusal(EgressRefusal.Reason.NOT_ALLOWLISTED, target, "target names no host");
        }
        boolean named = entries.stream().anyMatch(entry -> EgressEntries.namesHost(entry, host));
        if (!named && entries.stream().noneMatch(EgressEntries::grantsByAddress)) {
            return notAllowlisted(target, host);
        }
        List<InetAddress> addresses = resolver.resolve(host);
        if (!named && !addresses.stream().allMatch(this::coveredByAddressEntry)) {
            return notAllowlisted(target, host);
        }
        return addressRefusal(target, host, addresses);
    }

    private EgressRefusal notAllowlisted(URI target, String host) {
        return refusal(
                EgressRefusal.Reason.NOT_ALLOWLISTED,
                target,
                "host '%s' is on no entry of the admitted set; allowed: %s".formatted(host, new TreeSet<>(entries)));
    }

    /** The address-class judgement on every address the host resolves to (NFR-S2). */
    private @Nullable EgressRefusal addressRefusal(URI target, String host, List<InetAddress> addresses) {
        for (InetAddress address : addresses) {
            String blocked = BlockedAddressClass.of(address);
            if (blocked != null && !waives(host, address)) {
                return refusal(
                        EgressRefusal.Reason.ADDRESS_CLASS,
                        target,
                        "host '%s' resolves to %s, which is %s".formatted(host, address.getHostAddress(), blocked));
            }
        }
        return null;
    }

    private EgressRefusal refusal(EgressRefusal.Reason reason, URI target, String detail) {
        return new EgressRefusal(reason, target.toString(), detail, key);
    }

    /**
     * Whether an entry waives the block on {@code address}: the IP literal the host itself is, or a
     * CIDR block or class word covering the address. Nothing waives the cloud-metadata address.
     *
     * <p>A literal host reaches this judgement only granted — named by an entry, or covered by an
     * address entry — so its being a literal is the waiver; a name never is.
     */
    private boolean waives(String host, InetAddress address) {
        if (BlockedAddressClass.CLOUD_METADATA.equals(address.getHostAddress())) {
            return false;
        }
        return EgressEntries.isAddressLiteral(EgressEntries.normalized(host)) || coveredByAddressEntry(address);
    }

    private boolean coveredByAddressEntry(InetAddress address) {
        return entries.stream().anyMatch(entry -> EgressEntries.coversAddress(entry, address));
    }
}
