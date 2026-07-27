package com.github.oinsio.gnomish.adapter.tracker.github;

import com.github.oinsio.gnomish.DoNotMutate;
import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds and parses the GitHub adapter's canonical task id: {@code
 * github:owner/repo#42} when the configured {@code api-url} is the default
 * ({@code https://api.github.com}), or {@code github:host/owner/repo#42} when
 * it is anything else — port, path, or a different host all count as
 * non-default (design D7). The {@code github:} prefix is a fixed code
 * constant, never configuration.
 *
 * <p>Default-ness is decided by comparing a normalized {@code api-url}
 * against the normalized default: trim whitespace, lowercase the scheme and
 * host, drop a single trailing slash (design D7). Port and path are kept in
 * this comparison, so {@code https://api.github.com:8443} differs from the
 * default even though its host does not.
 *
 * <p>This class is pure string/URL logic: it does not call the GitHub API,
 * does not follow rename redirects (task 4.15 / design D8), and reuses the
 * unmodified {@link com.github.oinsio.gnomish.adapter.git.TaskIdSanitizer}
 * downstream for branch names — this class produces the string that
 * sanitizer consumes, generically, like any other id.
 *
 * <p>Implements FR16 of add-tracker-port.
 *
 * @param host the non-default host, or {@code ""} when the default
 *     {@code api.github.com} applies
 * @param owner the repository owner
 * @param repo the repository name
 * @param issueNumber the issue number
 */
public record GithubTaskId(String host, String owner, String repo, int issueNumber) {

    private static final String PREFIX = "github:";
    private static final URI DEFAULT_API_URL = URI.create("https://api.github.com");

    private static final Pattern CANONICAL_PATTERN =
            Pattern.compile("^github:(?:(?<host>[^/]+)/)?(?<owner>[^/#]+)/(?<repo>[^/#]+)#(?<number>\\d+)$");

    /**
     * Builds a canonical id from a configured {@code api-url} and issue
     * coordinates, including the host only when {@code apiUrl} is non-default
     * (design D7).
     *
     * @param apiUrl the configured {@code tracker.github.api-url}
     * @param owner the repository owner
     * @param repo the repository name
     * @param issueNumber the issue number
     * @return the built {@code GithubTaskId}
     */
    public static GithubTaskId build(String apiUrl, String owner, String repo, int issueNumber) {
        String host = isDefault(apiUrl) ? "" : hostOf(apiUrl);
        return new GithubTaskId(host, owner, repo, issueNumber);
    }

    /**
     * Parses a canonical id string back to its components (FR16 round-trip).
     *
     * @param canonicalId the canonical id, e.g. {@code github:owner/repo#42}
     *     or {@code github:host/owner/repo#42}
     * @return the parsed {@code GithubTaskId}
     * @throws IllegalArgumentException if {@code canonicalId} is not
     *     well-formed
     */
    public static GithubTaskId parse(String canonicalId) {
        Matcher matcher = CANONICAL_PATTERN.matcher(canonicalId);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("not a canonical GitHub task id: '" + canonicalId + "'");
        }
        String host = matcher.group("host");
        return new GithubTaskId(
                host == null ? "" : host,
                matcher.group("owner"),
                matcher.group("repo"),
                Integer.parseInt(matcher.group("number")));
    }

    /**
     * Renders this id's canonical string form (FR16).
     *
     * @return {@code github:owner/repo#N} or {@code github:host/owner/repo#N}
     */
    public String canonicalId() {
        String repoPart = owner + "/" + repo + "#" + issueNumber;
        return host.isEmpty() ? PREFIX + repoPart : PREFIX + host + "/" + repoPart;
    }

    private static boolean isDefault(String apiUrl) {
        return normalize(apiUrl).equals(normalize(DEFAULT_API_URL.toString()));
    }

    private static String normalize(String apiUrl) {
        String trimmed = apiUrl.trim();
        URI uri = URI.create(trimmed);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = normalizedHost(uri);
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return scheme + "://" + host + portSuffix(uri) + path;
    }

    // PIT M4 documented exception (build.gradle has the full rationale style): @DoNotMutate — a
    // host-bearing api-url (any "scheme://host..." string, the only shape `tracker.github.api-url`
    // is ever configured with in practice) always yields a non-null URI#getHost(); reaching the
    // null branch requires an opaque URI (e.g. "api.github.com" with no "//"), which also breaks
    // hostOf() below with an unconditional NPE — an unrelated, pre-existing contract of this class
    // for a malformed api-url, out of this task's scope to change. Isolated to its own method so
    // this provably-dead-in-practice branch has nowhere for a mutant to hide as a false SURVIVED.
    // The reachable behavior (lowercasing a real host) is covered by GithubTaskIdSpec's
    // case-tolerance scenario.
    @DoNotMutate
    private static String normalizedHost(URI uri) {
        return uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    }

    // PIT M4 documented exception (build.gradle has the full rationale style): @DoNotMutate — this
    // ternary is only ever read back through isDefault()'s equality comparison against one fixed
    // reference value; for every case the port differs at all (default's implicit -1 vs. any real
    // port), both branches of the ORIGINAL and the NEGATED mutant still disagree between the two
    // sides being compared, so the negation can never flip isDefault()'s true/false outcome — a
    // mutant unkillable through this method's only caller. GithubTaskIdSpec's "non-default port"
    // scenario already proves the observable behavior (host included, port folded into it) is
    // correct.
    @DoNotMutate
    private static String portSuffix(URI uri) {
        int port = uri.getPort();
        return port == -1 ? "" : ":" + port;
    }

    private static String hostOf(String apiUrl) {
        return URI.create(apiUrl.trim()).getHost().toLowerCase(Locale.ROOT);
    }
}
