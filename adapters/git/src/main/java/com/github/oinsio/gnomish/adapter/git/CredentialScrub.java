package com.github.oinsio.gnomish.adapter.git;

import java.util.regex.Pattern;

/**
 * Removes URL userinfo — the {@code <user>:<password>@} or bare {@code <token>@} prefix a remote
 * URL may carry — from git's error output before anything the factory writes carries it (NFR-S2 of
 * fix-lifecycle-push).
 *
 * <p>Why this is needed at all: modern git already anonymizes remote URLs in its own transport
 * diagnostics (an unresolvable host, a 404, an authentication failure, and a non-fast-forward
 * rejection all print the URL with the whole userinfo stripped — verified against git 2.55). One
 * message is the exception, because it is a credential *prompt* failure rather than a transport
 * one:
 *
 * <pre>
 * fatal: could not read Password for 'https://ghp_REDACTED@github.com': Device not configured
 * </pre>
 *
 * <p>It echoes the username verbatim, and the factory's git runs are exactly the runs that hit it:
 * a clone whose {@code origin} is a {@code https://TOKEN@github.com/owner/repo.git} (the common
 * token-in-URL form) plus a subprocess with no controlling terminal makes git ask for the password
 * it has no way to obtain. The username *is* the token, and the stderr carrying it flows into
 * operator WARNs and into the {@code Undeliverable} detail that reaches a tracker comment.
 *
 * <p>The scrub is structural, not a token-shape guess: it matches {@code scheme://userinfo@} and
 * replaces the userinfo, so it holds for any host, any credential format, and any git message
 * — present or future — that prints a URL with credentials in it. Scp-style {@code git@host:path}
 * remotes are left alone: they carry no secret, only the {@code git} account name.
 *
 * <p>Implements NFR-S2 of fix-lifecycle-push.
 */
final class CredentialScrub {

    /** What a scrubbed userinfo is replaced with; recognizable in a log, never a valid credential. */
    static final String MASK = "***";

    // scheme://userinfo@ — the userinfo run is everything up to the '@' that is neither whitespace
    // nor a '/' (a '/' would mean the authority already ended, as in "https://host/a@b").
    private static final Pattern URL_USERINFO = Pattern.compile("([a-zA-Z][a-zA-Z0-9+.\\-]*://)([^\\s/@]+)@");

    private CredentialScrub() {}

    /**
     * Replaces the userinfo of every {@code scheme://userinfo@host} occurrence in {@code text}.
     *
     * @param text the raw output to scrub; never null
     * @return the same text with every URL userinfo replaced by {@link #MASK}
     */
    static String scrub(String text) {
        return URL_USERINFO.matcher(text).replaceAll("$1" + MASK + "@");
    }
}
