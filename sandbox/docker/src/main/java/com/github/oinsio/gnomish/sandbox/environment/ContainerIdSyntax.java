package com.github.oinsio.gnomish.sandbox.environment;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The shape a container id must have before the factory treats it as a denial source's identity:
 * a bounded run of the characters docker's own ids and the factory's test doubles use, and nothing
 * else.
 *
 * <p>Why a syntax gate rather than a carrier (design D11 of type-untrusted-text). The id comes off
 * {@code docker inspect}'s stdout, so it is captured text — but it is used as an <em>identity</em>:
 * {@code DenialCursor.source} carries it into a task-branch document, and a later lease compares
 * what it reads back there against the id its own daemon reports. Comparison is not something the
 * carrier does — it answers emptiness, substring and length, and nothing else — so an identity is
 * the wrong <em>shape</em> for it, and a typed {@code source} would have to be un-typed again at
 * {@code GuardDenialReads}, which hands the same id to {@code DenialIdentity}. The same answer the
 * design gives for the agent's model id: where a value is compared rather than read, the way to
 * keep it out of the untrusted plane is to make it parsed, not to type it.
 *
 * <p>Two sides apply it, which is why it is public rather than package-private: {@link
 * GuardSourceIdentity} holds the daemon's answer to it when the id is minted, and the task-branch
 * readers in the git adapter hold a <em>restored</em> id to it when a cursor or a denial identity
 * comes back off a branch another instance wrote (task 5.3 of type-untrusted-text). Without the
 * second application the restored half of the comparison would have passed no gate at all.
 *
 * <p>What makes the accepted string inert: the character class admits no control character, no
 * ANSI escape, no line separator and no mention or fence markdown would act on, and the length
 * bound keeps a hostile answer from flooding a record. So an id that passes may be logged and
 * reported as-is; one that does not is refused, which the caller already degrades safely —
 * an unidentifiable source commits no cursor.
 *
 * <p>Implements FR10 of type-untrusted-text.
 */
public final class ContainerIdSyntax {

    /**
     * Docker's own ids are 64 hex characters, and the {@code sha256:}-prefixed and named forms the
     * factory's doubles use add {@code :}, {@code .}, {@code _} and {@code -}. The bound is
     * generous for the same reason the agent's model-id gate is: the point is a bounded inert
     * shape, not a proof of authenticity.
     */
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private ContainerIdSyntax() {}

    /**
     * The id, if {@code candidate} has the accepted shape.
     *
     * @param candidate the stripped answer of a container-id inspect, or an id restored from a
     *     task-branch document; never null
     * @return the id, or empty when it is blank or holds anything outside the accepted characters
     */
    public static Optional<String> of(String candidate) {
        return ID.matcher(candidate).matches() ? Optional.of(candidate) : Optional.empty();
    }
}
