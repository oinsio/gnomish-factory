package com.github.oinsio.gnomish.adapter.check.http;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The {@code http} provider's per-check selectors, read off one {@code external} check's manifest
 * {@code params} (FR7, FR10, FR11 of add-plugin-architecture). Everything a target needs travels
 * here — where to call, how to authorize, and what a pass or a still-pending response looks like —
 * because the provider serves arbitrary endpoints and has no per-vendor connection subsection to
 * draw a base URL from.
 *
 * <p>{@link Auth} names a credential; it never carries one. The committed manifest holds the
 * environment-variable name only, and {@link HttpExternalCheckClient} resolves it through {@code
 * SecretsProvider} at request time (FR11, NFR-S1) — while the same name is declared back to the
 * composition root so it is scrubbed from every child environment and refused in the passthrough
 * allowlist, exactly like a vendor plugin's constant (FR17, design D11).
 *
 * <p>Parsing is total: the shape has been graded by {@link HttpCheckParamsValidator} at the load
 * seam, so a check reaching this class is well-formed and missing optional keys simply take their
 * defaults.
 *
 * <p>A plain final class rather than a record, for the reason {@link HttpCheckCondition} carries in
 * full: PIT cannot redefine a record's bytecode in a loaded class, so every mutation of this type
 * came back RUN_ERROR and the type dropped out of the mutation gate (hcoles/pitest#1285).
 *
 * <p>Implements FR10, FR11 of add-plugin-architecture.
 */
final class HttpCheckParams {

    /** The endpoint to call. */
    private final String url;
    /** The HTTP method; {@code GET} unless declared. */
    private final String method;
    /** Non-secret request headers, in declaration order; possibly empty. */
    private final Map<String, String> headers;
    /** The credential reference, or {@code null} for an unauthenticated endpoint. */
    private final @Nullable Auth auth;
    /** The pass predicate; {@link HttpCheckCondition#alwaysMatching()} (2xx only) unless declared. */
    private final HttpCheckCondition passWhen;
    /** The not-yet-terminal predicate, or {@code null} — which makes the check a one-shot probe. */
    private final @Nullable HttpCheckCondition pendingWhen;

    /** The manifest keys, shared with {@link HttpCheckParamsValidator} so the two cannot drift. */
    static final String URL_KEY = "url";

    static final String METHOD_KEY = "method";
    static final String HEADERS_KEY = "headers";
    static final String AUTH_KEY = "auth";
    static final String PASS_WHEN_KEY = "pass-when";
    static final String PENDING_WHEN_KEY = "pending-when";

    /** The method used when the check declares none. */
    static final String DEFAULT_METHOD = "GET";

    HttpCheckParams(
            String url,
            String method,
            Map<String, String> headers,
            @Nullable Auth auth,
            HttpCheckCondition passWhen,
            @Nullable HttpCheckCondition pendingWhen) {
        this.url = url;
        this.method = method;
        this.headers = Map.copyOf(headers);
        this.auth = auth;
        this.passWhen = passWhen;
        this.pendingWhen = pendingWhen;
    }

    String url() {
        return url;
    }

    String method() {
        return method;
    }

    Map<String, String> headers() {
        return headers;
    }

    @Nullable
    Auth auth() {
        return auth;
    }

    HttpCheckCondition passWhen() {
        return passWhen;
    }

    @Nullable
    HttpCheckCondition pendingWhen() {
        return pendingWhen;
    }

    /**
     * Reads the selectors out of one check's raw params.
     *
     * @param params the check's {@code params} as parsed by Jackson (maps/lists/scalars); never null
     * @return the parsed selectors; never null
     */
    static HttpCheckParams from(Map<String, Object> params) {
        Map<String, Object> passWhen = submap(params, PASS_WHEN_KEY);
        Map<String, Object> pendingWhen = submap(params, PENDING_WHEN_KEY);
        String method = string(params, METHOD_KEY);
        return new HttpCheckParams(
                orEmpty(string(params, URL_KEY)),
                method == null ? DEFAULT_METHOD : method,
                headers(params),
                Auth.from(submap(params, AUTH_KEY)),
                passWhen.isEmpty() ? HttpCheckCondition.alwaysMatching() : HttpCheckCondition.from(passWhen),
                pendingWhen.isEmpty() ? null : HttpCheckCondition.from(pendingWhen));
    }

    /**
     * The credential reference of an authorized check: the secret's name plus the header shape it is
     * applied in. Implements FR11, NFR-S1 of add-plugin-architecture.
     *
     * <p>Its three values: {@code credential}, the environment-variable name the secret resolves
     * under; {@code header}, the header the resolved value is set on ({@code Authorization} unless
     * declared); and {@code scheme}, the prefix put before the value ({@code Bearer <value>}),
     * empty for a raw one.
     */
    static final class Auth {

        private final String credential;
        private final String header;
        private final String scheme;

        Auth(String credential, String header, String scheme) {
            this.credential = credential;
            this.header = header;
            this.scheme = scheme;
        }

        String credential() {
            return credential;
        }

        String header() {
            return header;
        }

        static final String CREDENTIAL_KEY = "credential";
        static final String HEADER_KEY = "header";
        static final String SCHEME_KEY = "scheme";

        static final String DEFAULT_HEADER = "Authorization";
        static final String DEFAULT_SCHEME = "Bearer";

        /** Parses the {@code auth} sub-map, or {@code null} when the check declares none. */
        static @Nullable Auth from(Map<String, Object> raw) {
            if (raw.isEmpty()) {
                return null;
            }
            String header = string(raw, HEADER_KEY);
            String scheme = string(raw, SCHEME_KEY);
            return new Auth(
                    orEmpty(string(raw, CREDENTIAL_KEY)),
                    header == null ? DEFAULT_HEADER : header,
                    scheme == null ? DEFAULT_SCHEME : scheme);
        }

        /** The header value for {@code secret}: {@code "<scheme> <secret>"}, or the bare secret. */
        String headerValue(String secret) {
            return scheme.isEmpty() ? secret : scheme + " " + secret;
        }
    }

    /** A string-valued key, or {@code null} when absent or not a string. */
    static @Nullable String string(Map<String, Object> raw, String key) {
        return raw.get(key) instanceof String value ? value : null;
    }

    /** A map-valued key as raw content, or an empty map when absent or not a map. */
    static Map<String, Object> submap(Map<String, Object> raw, String key) {
        if (!(raw.get(key) instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((k, v) -> copy.put(String.valueOf(k), v));
        return copy;
    }

    private static Map<String, String> headers(Map<String, Object> params) {
        Map<String, String> headers = new LinkedHashMap<>();
        submap(params, HEADERS_KEY).forEach((key, value) -> headers.put(key, String.valueOf(value)));
        return headers;
    }

    private static String orEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }
}
