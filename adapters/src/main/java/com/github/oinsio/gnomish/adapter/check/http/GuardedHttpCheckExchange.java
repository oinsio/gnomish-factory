package com.github.oinsio.gnomish.adapter.check.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * The {@link HttpCheckExchange} the {@code http} provider actually calls: every hop judged by the
 * {@link EgressAllowlist} before it is sent, and the chain of hops bounded (NFR-S2, design D5 of
 * add-plugin-architecture).
 *
 * <p>Redirects are followed here rather than by the JDK client precisely so each hop can be
 * re-judged: an allowlisted host answering {@code 302 Location: http://169.254.169.254/…} is exactly
 * the escape the address-class rules exist to stop, and a client that follows redirects internally
 * would take that hop before any guard saw it.
 *
 * <p>A cross-host redirect carries no headers forward. The declared headers of an http check include
 * its credential, and handing a credential to a host the manifest never named — merely one the
 * allowlist also permits — is the exfiltration case in miniature. A same-host redirect (a path
 * rewrite, a trailing slash) keeps them.
 *
 * <p>Total request time is bounded by construction rather than by a clock: each hop carries {@link
 * JdkHttpCheckExchange#REQUEST_TIMEOUT} and the hops are bounded by {@link #MAX_REDIRECTS}, so an
 * exchange cannot outlive {@link #MAX_TOTAL_DURATION}.
 *
 * <p>Implements NFR-S2 of add-plugin-architecture.
 */
final class GuardedHttpCheckExchange implements HttpCheckExchange {

    /**
     * How many redirects one exchange may follow. A status endpoint that needs a fourth hop is
     * misconfigured, and an unbounded chain is a way to keep the factory busy.
     */
    static final int MAX_REDIRECTS = 3;

    /** The bound the per-hop timeout and the hop bound together imply. */
    static final Duration MAX_TOTAL_DURATION = JdkHttpCheckExchange.REQUEST_TIMEOUT.multipliedBy(MAX_REDIRECTS + 1L);

    private final HttpCheckExchange delegate;
    private final EgressAllowlist allowlist;

    GuardedHttpCheckExchange(HttpCheckExchange delegate, EgressAllowlist allowlist) {
        this.delegate = delegate;
        this.allowlist = allowlist;
    }

    /**
     * Sends {@code request}, following permitted redirects up to the bound.
     *
     * @param request the composed first hop; never null
     * @return the first non-redirect response; never null
     * @throws EgressRefusedException if any hop — the first included — is refused, or if the chain
     *     outruns {@link #MAX_REDIRECTS}
     */
    @Override
    public Response send(HttpRequest request) throws IOException, InterruptedException {
        return send(request, MAX_REDIRECTS);
    }

    /**
     * One judged hop, recursing into the redirect it points at while hops remain. Recursion rather
     * than a counted loop is deliberate: the remaining budget is a parameter that only ever shrinks,
     * so the chain is bounded by the shape of the code rather than by a loop variable that has to be
     * trusted to advance.
     *
     * @param hop the request about to be judged and sent; never null
     * @param remaining how many further redirects may still be followed
     */
    private Response send(HttpRequest hop, int remaining) throws IOException, InterruptedException {
        EgressRefusal refusal = allowlist.refuse(hop.uri());
        if (refusal != null) {
            throw new EgressRefusedException(refusal);
        }
        Response response = delegate.send(hop);
        URI next = redirectTarget(hop, response);
        if (next == null) {
            return response;
        }
        if (remaining <= 0) {
            throw new EgressRefusedException(new EgressRefusal(
                    EgressRefusal.Reason.REDIRECT_LIMIT,
                    next.toString(),
                    "more than %d redirects followed from '%s'".formatted(MAX_REDIRECTS, hop.uri())));
        }
        return send(hopTo(hop, next), remaining - 1);
    }

    /** The absolute target of a redirect response, or {@code null} when this is the final hop. */
    private static @Nullable URI redirectTarget(HttpRequest hop, Response response) {
        String location = response.location();
        if (!isRedirect(response.status()) || location == null || location.isBlank()) {
            return null;
        }
        return hop.uri().resolve(location);
    }

    /** 3xx statuses that carry a {@code Location} — 304 and 305 do not redirect a check anywhere. */
    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /** The next hop: same method, headers only when the host is unchanged (no credential travels). */
    private static HttpRequest hopTo(HttpRequest previous, URI next) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(next)
                .timeout(previous.timeout().orElse(JdkHttpCheckExchange.REQUEST_TIMEOUT))
                .method(previous.method(), HttpRequest.BodyPublishers.noBody());
        if (next.getHost() != null
                && next.getHost().equalsIgnoreCase(previous.uri().getHost())) {
            previous.headers().map().forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
        }
        return builder.build();
    }
}
