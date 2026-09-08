package com.github.oinsio.gnomish.baseref;

import java.util.Optional;

/**
 * The lexical rules a ref name — and a allowed-base pattern over ref names — must satisfy, in one owner so
 * that what a pattern may declare and what a designator value may name are the same alphabet.
 *
 * <p>The rules are the checkable subset of {@code git check-ref-format}: no whitespace or control
 * characters, none of git's own reserved punctuation, no {@code ..} and no at-brace sequence, no
 * empty path component, no component ending in {@code .lock}. A allowed-base pattern additionally admits
 * {@code *}; a value never does — {@code *} in a designator value would be a caller trying to name
 * a pattern where a ref is required.
 *
 * <p>Why a value is checked at all, rather than left for git to reject: the value comes from
 * tracker metadata a human (or an automation) typed, and it is matched against a allowed-base pattern whose
 * {@code *} spans path separators. Without this check, {@code release/*} would accept
 * {@code release/../../secrets} and the refresh fetch would carry it to the remote. Refusing
 * malformed values here keeps the policy fail-closed at the layer that decides, not at the layer
 * that executes.
 *
 * <p>Implements FR1, FR4, NFR-S3 of add-base-ref-resolution.
 */
final class RefNameSyntax {

    /** Git's reserved punctuation, plus the glob characters this grammar does not define. */
    private static final String FORBIDDEN_CHARACTERS = "~^:?[\\";

    private RefNameSyntax() {}

    /**
     * Checks one ref name or allowed-base pattern.
     *
     * @param candidate the text to check; may be blank, which is itself a violation
     * @param wildcardAllowed whether {@code *} is part of the alphabet (true for a allowed-base pattern,
     *     false for a concrete ref name)
     * @return the violation, phrased for a load error or an escalation report, or empty when the
     *     candidate is well formed
     */
    static Optional<String> violation(String candidate, boolean wildcardAllowed) {
        if (candidate.isBlank()) {
            return Optional.of("must not be blank");
        }
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            if (c <= ' ' || c == '\u007f') {
                return Optional.of("must not contain whitespace or control characters");
            }
            if (FORBIDDEN_CHARACTERS.indexOf(c) >= 0) {
                return Optional.of("must not contain any of " + FORBIDDEN_CHARACTERS);
            }
            if (c == '*' && !wildcardAllowed) {
                return Optional.of("must name one ref, not a pattern: '*' is not allowed here");
            }
        }
        if (candidate.contains("..") || candidate.contains("@{")) {
            return Optional.of("must not contain '..' or '@{'");
        }
        if (candidate.startsWith("/") || candidate.endsWith("/") || candidate.contains("//")) {
            return Optional.of("must not have an empty path component");
        }
        for (String component : candidate.split("/", -1)) {
            if (component.endsWith(".lock") || component.startsWith(".") || component.endsWith(".")) {
                return Optional.of("no path component may start or end with '.' or end with '.lock'");
            }
        }
        return Optional.empty();
    }

    /**
     * Whether a concrete ref name is well formed — the value half of {@link #violation}.
     *
     * @param refName the ref name a task's designator, or an allowed-base match, is about to accept
     * @return true when the name satisfies every rule above
     */
    static boolean isWellFormedRefName(String refName) {
        return violation(refName, false).isEmpty();
    }
}
