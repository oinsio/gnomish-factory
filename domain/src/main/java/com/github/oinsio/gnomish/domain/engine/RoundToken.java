package com.github.oinsio.gnomish.domain.engine;

import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The identity of one round in a branch mode — host git or container (design D10 of
 * make-checkpoint-gate-durable; D13, D14 of define-executor-contract): the task branch's tip commit
 * at the moment the round opened. No later round of the task repeats it — every round lands at
 * least its record commit, and every reset rides a lifecycle commit of its own — so a decision
 * request named by it is live for exactly the round that owns the name, and a file under any other
 * token is never read as the current round's question. An in-place run has no task branch and so
 * no token: its rounds record none.
 *
 * <p>{@link #of} is the one parse of a commit id into a round identity, and the constructor is
 * private so no other spelling exists. It has exactly three producers: the container round source
 * and the git-mode host round source over the tip a fresh round opens on (a fresh round mints), and
 * the snapshot-tip check over the token a snapshot subject recorded (a resumed round reuses, never
 * mints). Every consumer takes the value from the run's current round, the engine through the
 * {@link com.github.oinsio.gnomish.domain.engine.port.RoundIdentity} port, which stamps it on the
 * round's {@link AttemptRecord}; none re-reads the tip to obtain it. The value is a commit id as git
 * prints it: lowercase hexadecimal, never blank, because it is spelled into a file name (the
 * decision path) and must have one form.
 *
 * <p>Lives in the domain (moved from {@code app.port.git} by task 5.1 of define-executor-contract)
 * so the engine can carry it on the record it writes; it is a pure value and knows nothing of git.
 * A value type rather than a record only so the constructor can be private: a record's canonical
 * constructor is as visible as the record, which would leave a second parse open.
 *
 * <p>Implements FR13, FR15 of make-checkpoint-gate-durable; FR14, FR21 of define-executor-contract.
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
