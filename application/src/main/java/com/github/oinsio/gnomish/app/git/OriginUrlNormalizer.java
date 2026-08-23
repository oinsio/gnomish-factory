package com.github.oinsio.gnomish.app.git;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Rewrites a clone's {@code origin} remote URL into the one spelling every equivalent form of that
 * remote shares, so {@link ProjectIdentity}'s digest names the project rather than the particular
 * string the URL happens to be written as (design D1, D2 of normalize-project-identity-url).
 *
 * <p>The fold: URL userinfo removed, scheme and host lower-cased, an explicitly written default
 * port of the scheme dropped ({@code http} 80, {@code https} 443, {@code ssh} 22, {@code git}
 * 9418 — the same set git's own {@code urlmatch} and libgit2 fold), one trailing {@code /}
 * removed, a trailing {@code .git} removed, and the scp-style {@code [user@]host:path} form
 * rendered in the shape of the equivalent {@code ssh://host/path} URL. Remotes differing in host,
 * path, non-default port, or scheme (beyond case) keep distinct spellings and so distinct
 * identities (FR2).
 *
 * <p>Total by construction (NFR-R1): the function is lexical — no {@link java.net.URI}, which
 * rejects the scp-style form outright — and anything these two shapes do not recognize is returned
 * unchanged, so an unusual remote costs identity stability, never a failed run or a failed sweep
 * pass. Returned unchanged, among others: a bare local path, an {@code ext::} transport, and a
 * bracketed IPv6-literal host ({@code ssh://[::1]/repo}), whose colons the authority shape below
 * does not admit.
 *
 * <p>The scp fold is deliberately lossy: to git, {@code host:path} names a path relative to the
 * remote login's home directory while {@code ssh://host/path} names the absolute {@code /path},
 * so two technically distinct remotes can collapse into one identity. The error direction is a
 * wider shared sweep scope, never a lost object, and {@code docs/guides/operator-guide-sandbox.md}
 * states the fold so an operator can predict it.
 *
 * <p>Not shared with {@code adapters/git}'s {@code CredentialScrub}, which strips the same {@code
 * scheme://userinfo@} construct (NFR-S2 of fix-lifecycle-push): that one masks arbitrarily many
 * occurrences inside free-text git stderr and leaves a visible {@code ***} behind, this one
 * removes the userinfo of exactly one URL and leaves nothing in its place. They also sit on
 * opposite sides of the module boundary — {@code adapters/git} depends on {@code application},
 * never the reverse — so sharing would mean hoisting a text-scrubbing concern into the domain for
 * two small call sites (design D3).
 *
 * <p>Implements FR1, FR2, NFR-R1, NFR-S1 of normalize-project-identity-url.
 */
final class OriginUrlNormalizer {

    /**
     * {@code scheme://[userinfo@]host[:port][/path]} — the only shape with a foldable authority. The
     * host excludes {@code :}, so a bracketed IPv6 literal does not match and falls through to the
     * verbatim return.
     */
    private static final Pattern AUTHORITY_URL =
            Pattern.compile("([A-Za-z][A-Za-z0-9+.\\-]*)://(?:([^/@]+)@)?([^/:@]+)(?::([0-9]+))?(/.*)?");

    /**
     * {@code [user@]host:path} — git's scp-style remote. Only tried when the string carries no
     * {@code ://} at all, which is what keeps {@code file:///srv/repo} and an {@code ext::}
     * transport out of it; a path opening with {@code :} or {@code \} is likewise not a remote
     * (an {@code ext::}-style prefix, a Windows drive letter).
     */
    private static final Pattern SCP_LIKE = Pattern.compile("(?:([^/@]+)@)?([A-Za-z0-9._\\-]+):([^:\\\\].*)");

    /** The port each scheme implies, and so the only port whose explicit spelling is redundant. */
    private static final Map<String, String> DEFAULT_PORTS =
            Map.of("http", "80", "https", "443", "ssh", "22", "git", "9418");

    private OriginUrlNormalizer() {}

    /**
     * Folds {@code url} into its canonical spelling, or returns it unchanged when it matches
     * neither recognized shape.
     *
     * @param url the clone's {@code origin} remote URL, verbatim; never null
     * @return the normalized spelling, or {@code url} itself when unrecognized; never null
     */
    static String normalize(String url) {
        String canonical = url.contains("://") ? foldAuthorityUrl(url) : foldScpLike(url);
        return canonical == null ? url : canonical;
    }

    /**
     * Folds {@code [user@]host:path} by way of the {@code ssh://host/path} it is equivalent to;
     * {@code null} when the string is not that shape. Only the caller's "no {@code ://} anywhere"
     * guard makes this safe to try — it is what keeps {@code file:///srv/repo} and an {@code ext::}
     * transport from reading as a host and a path.
     */
    private static @Nullable String foldScpLike(String url) {
        Matcher m = SCP_LIKE.matcher(url);
        return m.matches()
                ? foldAuthorityUrl("ssh://" + m.group(2) + "/" + m.group(3).replaceFirst("^/+", ""))
                : null;
    }

    /** The fold itself; {@code null} when {@code url} has no {@code scheme://host} authority to fold. */
    private static @Nullable String foldAuthorityUrl(String url) {
        Matcher m = AUTHORITY_URL.matcher(url);
        if (!m.matches()) {
            return null;
        }
        String scheme = m.group(1).toLowerCase(Locale.ROOT);
        String host = m.group(3).toLowerCase(Locale.ROOT);
        String port = m.group(4);
        String authority = port == null || port.equals(DEFAULT_PORTS.get(scheme)) ? host : host + ":" + port;
        return scheme + "://" + authority + trimmedPath(m.group(5));
    }

    /** Drops one trailing {@code /} and then a trailing {@code .git} — neither names a different remote. */
    private static String trimmedPath(@Nullable String path) {
        if (path == null) {
            return "";
        }
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        return trimmed.endsWith(".git") ? trimmed.substring(0, trimmed.length() - ".git".length()) : trimmed;
    }
}
