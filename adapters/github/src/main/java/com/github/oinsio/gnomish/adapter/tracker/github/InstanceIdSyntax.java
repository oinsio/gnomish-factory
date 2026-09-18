package com.github.oinsio.gnomish.adapter.tracker.github;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The shape an instance id read back off a tracker comment must have before the factory treats it
 * as the identity of the instance that wrote the marker: a bounded run of characters that carry no
 * control sequence and that no tracker comment renderer acts on.
 *
 * <p>Why a syntax gate rather than a carrier (design D11 of type-untrusted-text). A marker's
 * {@code instance} comes out of a comment anyone who can comment may write, so it is captured
 * text — but the factory uses it as an <em>identity</em>: it is compared against this process's own
 * {@code instanceId} ({@code GithubHeartbeat}), against the claim holder at a comment boundary
 * ({@code GithubCommentBoundary}), and it becomes {@code ClaimResult.Held.otherInstance} and a
 * {@code TrackerTaskState.Working} holder. Carried as {@link
 * com.github.oinsio.gnomish.untrustedtext.UntrustedText} none of those comparisons could hold: one
 * side is a configured {@code String} the factory holds, and comparison is not something the
 * carrier does — it answers emptiness, substring and length, and nothing else. An identity is the
 * wrong shape for it. This is the same finding {@code ContainerIdSyntax} records one layer down,
 * and the same answer: where a value is compared as well as displayed, the way to keep it out of
 * the untrusted plane is to make it parsed, not to type it.
 *
 * <p>What makes the accepted string inert: {@code factory.instance-name} is operator-set and only
 * checked non-blank, so the gate admits anything an operator would plausibly name a machine and
 * refuses only the shapes that make a value dangerous — control characters (which includes every
 * ANSI escape introducer), line and paragraph separators, bidirectional overrides, and the
 * {@code @}, {@code #} and backtick a tracker comment would act on as a mention, an issue
 * reference or a fence. The length bound keeps a forged value from flooding a record. So an id
 * that passes may be logged, compared and reported as-is.
 *
 * <p>Implements FR10 of type-untrusted-text.
 */
final class InstanceIdSyntax {

    /** Longer than any plausible {@code <name>-<suffix>}, short enough that no record floods. */
    private static final int MAX_LENGTH = 128;

    /**
     * One rejected character: an ISO control (ESC and the C0/C1 ranges with it), a Unicode
     * separator that is not a plain space, a bidirectional override or embedding, or one of the
     * three characters a tracker comment renders as an action rather than as text.
     */
    private static final Pattern REJECTED = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}@#`]");

    private InstanceIdSyntax() {}

    /**
     * The instance id, if {@code candidate} has the accepted shape.
     *
     * @param candidate the {@code instance} field of a parsed marker; never null
     * @return the id, or empty when it is blank, over the length bound, or holds a rejected
     *     character
     */
    static Optional<String> of(String candidate) {
        if (candidate.isBlank() || candidate.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        return REJECTED.matcher(candidate).find() ? Optional.empty() : Optional.of(candidate);
    }
}
