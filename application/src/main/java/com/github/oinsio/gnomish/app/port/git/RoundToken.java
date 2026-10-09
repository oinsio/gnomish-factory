package com.github.oinsio.gnomish.app.port.git;

import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The identity of one container-mode round (design D10 of make-checkpoint-gate-durable): the task
 * branch's tip commit at the moment the round opened. No later round of the task repeats it —
 * every round lands at least its snapshot commit, and every reset rides a lifecycle commit of its
 * own — so a decision request named by it is live for exactly the round that owns the name, and a
 * file under any other token is never read as the current round's question.
 *
 * <p>{@link #of} is the one parse of a commit id into a round identity, and the constructor is
 * private so no other spelling exists. It has exactly two producers (design D7): the round source
 * over the tip a fresh round opens on (a fresh round mints), and the snapshot-tip check over the
 * token a snapshot subject recorded (a resumed round reuses, never mints). Every consumer takes
 * the value from the run's {@link CurrentRound}; none re-reads the tip to obtain it. The value is
 * a commit id as git prints it: lowercase hexadecimal, never blank, because it is spelled into a
 * file name (the decision path) and must have one form.
 *
 * <p>A value type rather than a record only so the constructor can be private: a record's
 * canonical constructor is as visible as the record, which would leave a second parse open.
 *
 * <p>Implements FR13, FR15 of make-checkpoint-gate-durable.
 */
public final class RoundToken {

    private static final Pattern COMMIT_ID = Pattern.compile("[0-9a-f]+");

    private final String commit;

    private RoundToken(String commit) {
        this.commit = commit;
    }

    /**
     * Parses a commit id into a round's identity.
     *
     * @param commitId the commit id the round opened on, as git prints it
     * @return the round token over {@code commitId}
     * @throws IllegalArgumentException if {@code commitId} is blank or not lowercase hexadecimal —
     *     the value is deliberately not quoted, since a refused resolution is not a commit id and
     *     its text has no business in a message
     */
    public static RoundToken of(String commitId) {
        if (!COMMIT_ID.matcher(commitId).matches()) {
            throw new IllegalArgumentException("a round token must be a non-blank lowercase hexadecimal commit id");
        }
        return new RoundToken(commitId);
    }

    /** The commit id the round opened on; lowercase hexadecimal, non-blank. */
    public String commit() {
        return commit;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof RoundToken token && commit.equals(token.commit);
    }

    @Override
    public int hashCode() {
        return commit.hashCode();
    }

    @Override
    public String toString() {
        return "RoundToken[" + commit + "]";
    }
}
