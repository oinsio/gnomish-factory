package com.github.oinsio.gnomish.httpegress;

import com.github.oinsio.gnomish.domain.egress.EgressRefusal;
import com.github.oinsio.gnomish.domain.egress.EgressRefusedException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.HashSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The guarded exchange every factory HTTP consumer calls (NFR-S2, design D5 of
 * add-plugin-architecture; FR15, FR17, design D16 of migrate-verify-to-executor-contract): every hop
 * — the first included — judged by the consumer's {@link EgressAllowlist} before it is sent, and the
 * chain of hops bounded.
 *
 * <p>Redirects are followed here rather than by the JDK client so each hop is re-judged: an
 * admitted host answering {@code 302 Location: http://169.254.169.254/…} is exactly the escape the
 * address-class rules exist to stop. A hop is built by {@link RedirectHop} (the method, body and
 * credential rules); a hop from {@code https} to {@code http} is refused as a downgrade, a hop onto
 * a URL the chain already visited is a loop, and a fourth redirect outruns {@link #MAX_REDIRECTS} —
 * the last two are {@link ExchangeLimitException}s, never refusals.
 *
 * <p>Implements NFR-S2 of add-plugin-architecture; FR15, FR17 of
 * migrate-verify-to-executor-contract.
 */
public final class GuardedHttpCheckExchange implements HttpCheckExchange {

    /**
     * How many redirects one exchange may follow. An endpoint that needs a fourth hop is
     * misconfigured, and an unbounded chain is a way to keep the factory busy.
     */
    public static final int MAX_REDIRECTS = 3;

    private static final String HTTPS = "https";

    private final HttpCheckExchange delegate;
    private final EgressAllowlist allowlist;

    public GuardedHttpCheckExchange(HttpCheckExchange delegate, EgressAllowlist allowlist) {
        this.delegate = delegate;
        this.allowlist = allowlist;
    }

    /**
     * Sends {@code request}, following admitted redirects up to the bound.
     *
     * @param request the composed first hop; never null
     * @return the first non-redirect response; never null
     * @throws EgressRefusedException if any hop is refused, a downgrade included
     * @throws ExchangeLimitException if the chain loops or outruns {@link #MAX_REDIRECTS}
     * @throws IOException if a host does not resolve or the exchange fails at the transport
     */
    @Override
    public Response send(HttpRequest request) throws IOException, InterruptedException {
        Set<URI> visited = new HashSet<>();
        HttpRequest hop = request;
        for (int redirects = 0; ; redirects++) {
            judge(hop.uri());
            visited.add(hop.uri().normalize());
            Response response = delegate.send(hop);
            URI next = redirectTarget(hop, response);
            if (next == null) {
                return response;
            }
            refuseUnfollowable(hop, next, visited, redirects);
            hop = RedirectHop.of(hop, response.status(), next);
        }
    }

    private void judge(URI target) throws IOException {
        EgressRefusal refusal = allowlist.refuse(target);
        if (refusal != null) {
            throw new EgressRefusedException(refusal);
        }
    }

    /** A downgrade is a refusal; a loop or a fourth redirect is an exchange limit. */
    private void refuseUnfollowable(HttpRequest hop, URI next, Set<URI> visited, int redirects) {
        if (HTTPS.equalsIgnoreCase(hop.uri().getScheme()) && !HTTPS.equalsIgnoreCase(next.getScheme())) {
            throw new EgressRefusedException(new EgressRefusal(
                    EgressRefusal.Reason.SCHEME,
                    next.toString(),
                    "a redirect from '%s' downgrades https to '%s'".formatted(hop.uri(), next.getScheme()),
                    allowlist.key()));
        }
        if (visited.contains(next.normalize())) {
            throw new ExchangeLimitException(
                    ExchangeLimitException.Limit.REDIRECT_LOOP,
                    next.toString(),
                    "'%s' redirects back to a URL this chain already visited".formatted(hop.uri()));
        }
        if (redirects >= MAX_REDIRECTS) {
            throw new ExchangeLimitException(
                    ExchangeLimitException.Limit.REDIRECT_LIMIT,
                    next.toString(),
                    "more than %d redirects followed from '%s'".formatted(MAX_REDIRECTS, hop.uri()));
        }
    }

    /** The absolute target of a redirect response, or {@code null} when this is the final hop. */
    private static @Nullable URI redirectTarget(HttpRequest hop, Response response) {
        String location = response.location();
        if (!RedirectHop.isRedirect(response.status()) || location == null || location.isBlank()) {
            return null;
        }
        return hop.uri().resolve(location);
    }
}
