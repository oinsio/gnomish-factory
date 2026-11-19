package com.github.oinsio.gnomish.httpegress;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Locale;
import java.util.Set;

/**
 * The next request of a redirect chain (FR15, design D16 of migrate-verify-to-executor-contract, "A
 * hop keeps the request's meaning, and credentials stay on their host"): the method and body rule
 * of RFC 9110 §15.4.2–15.4.4 as the JDK's {@code RedirectFilter} implements it ({@code
 * jdk/internal/net/http/RedirectFilter.java:89-96} on JDK 25.0.3+9: {@code case 301, 302 ->
 * orig.equals("POST") ? "GET" : orig; case 303 -> "GET"; case 307, 308 -> orig;}), and the header
 * rule: a hop to another host drops the credential deny-set and keeps every other header, a
 * same-host hop keeps everything. A subdomain is another host.
 *
 * <p>Implements FR15 of migrate-verify-to-executor-contract.
 */
final class RedirectHop {

    /**
     * The headers that never cross a host: the JDK's own five and the two proxy and challenge
     * credentials (lower case, as compared).
     */
    static final Set<String> CREDENTIAL_DENY_SET =
            Set.of("authorization", "cookie", "host", "origin", "referer", "proxy-authorization", "www-authenticate");

    private static final String GET = "GET";
    private static final String POST = "POST";
    private static final String CONTENT_PREFIX = "content-";

    private RedirectHop() {}

    /** The statuses that redirect: 304 and 305 send a request nowhere. */
    static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /**
     * The request the redirect {@code status} prescribes for {@code next}.
     *
     * @param previous the request that was answered with the redirect; never null
     * @param status a status {@link #isRedirect} accepts
     * @param next the absolute redirect target; never null
     * @return the next hop; never null
     */
    static HttpRequest of(HttpRequest previous, int status, URI next) {
        boolean toGet = status == 303 || ((status == 301 || status == 302) && POST.equals(previous.method()));
        HttpRequest.Builder builder = HttpRequest.newBuilder(next);
        previous.timeout().ifPresent(builder::timeout);
        if (toGet) {
            builder.method(GET, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(previous.method(), previous.bodyPublisher().orElseGet(HttpRequest.BodyPublishers::noBody));
        }
        boolean sameHost = next.getHost() != null
                && next.getHost().equalsIgnoreCase(previous.uri().getHost());
        previous.headers().map().forEach((name, values) -> {
            String folded = name.toLowerCase(Locale.ROOT);
            boolean dropped =
                    (!sameHost && CREDENTIAL_DENY_SET.contains(folded)) || (toGet && folded.startsWith(CONTENT_PREFIX));
            if (!dropped) {
                values.forEach(value -> builder.header(name, value));
            }
        });
        return builder.build();
    }
}
