package com.github.oinsio.gnomish.adapter.scm.status;

import java.util.regex.Pattern;

/**
 * The commit an {@code scm-status} read is pinned to (FR9 of migrate-verify-to-executor-contract,
 * design D9: the {@code AttemptSha} value type on the read): a git object name, so the read cannot
 * be handed a branch name or a ref the platform would resolve to whatever its tip is now. The value
 * reaches the read from the working copy's attempt commit or from a pending key, which is the
 * engine's record of the same commit.
 *
 * <p>Implements FR9 of migrate-verify-to-executor-contract.
 */
public final class AttemptSha {

    /**
     * A full object name, SHA-1 or SHA-256: an abbreviation is refused, since the platform reports
     * the full name and an abbreviated pin would never match it.
     */
    private static final Pattern OBJECT_NAME = Pattern.compile("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}");

    private final String value;

    private AttemptSha(String value) {
        this.value = value;
    }

    /**
     * Pins a read to {@code value}.
     *
     * @param value the commit's object name; never null
     * @return the attempt SHA; never null
     * @throws IllegalArgumentException when {@code value} is not an object name
     */
    public static AttemptSha of(String value) {
        if (!OBJECT_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("not a commit object name: '" + value + "'");
        }
        return new AttemptSha(value);
    }

    /** The object name, as given. */
    public String value() {
        return value;
    }

    /**
     * Whether the platform's reported commit is this one. Object names compare without case; a
     * platform that reports none has reported no commit at all.
     *
     * @param reported the commit the platform reported a verdict on; never null, may be blank
     * @return true when it is this commit
     */
    public boolean names(String reported) {
        return value.equalsIgnoreCase(reported);
    }
}
