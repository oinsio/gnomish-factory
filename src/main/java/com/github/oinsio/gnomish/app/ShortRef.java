package com.github.oinsio.gnomish.app;

import java.util.regex.Pattern;

/**
 * Recognizes whether a raw {@code <ref>} argument to {@code gnomish take} is a short ref (FR9 of
 * add-tracker-port): a bare non-negative integer or a {@code #}-prefixed one, e.g. {@code 42} or
 * {@code #42}. Anything else — including an already-canonical id like {@code
 * github:owner/repo#42} — is not a short ref and passes through unchanged.
 *
 * <p>This recognition rule is adapter-agnostic and needs no config: only the actual expansion of a
 * recognized short ref into a canonical id (owner/repo/api-url lookup) is adapter-specific, via
 * {@link TrackerAdapterFactory#expandRef}.
 *
 * <p>Implements FR9 of add-tracker-port.
 */
final class ShortRef {

    private static final Pattern SHORT_REF_PATTERN = Pattern.compile("^#?\\d+$");

    private ShortRef() {}

    /**
     * Returns true if {@code ref} matches the short-ref shape (optional leading {@code #} followed
     * by one or more digits, nothing else).
     *
     * @param ref the raw {@code <ref>} argument; never null
     */
    static boolean isShortRef(String ref) {
        return SHORT_REF_PATTERN.matcher(ref).matches();
    }

    /**
     * Extracts the numeric issue number from a recognized short ref.
     *
     * @param ref a ref for which {@link #isShortRef(String)} is true
     * @return the parsed issue number
     */
    static int issueNumberOf(String ref) {
        return Integer.parseInt(ref.startsWith("#") ? ref.substring(1) : ref);
    }
}
