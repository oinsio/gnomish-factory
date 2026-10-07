package com.github.oinsio.gnomish.adapter.git;

import java.util.regex.Pattern;

/**
 * The identity of one container-mode round (design D10 of make-checkpoint-gate-durable): the task
 * branch's tip commit at the moment the round opened. No later round of the task repeats it —
 * every round lands at least its snapshot commit, and every reset rides a lifecycle commit of its
 * own — so a decision request named by it is live for exactly the round that owns the name, and a
 * file under any other token is never read as the current round's question.
 *
 * <p>Minted in one place, {@link SandboxRoundEnvironmentSource#openRound}, and handed to every
 * consumer through the per-run {@link RoundTokenRef}; a consumer never re-reads the tip to obtain
 * it. The value is a commit id as git prints it: lowercase hexadecimal, never blank, because it is
 * spelled into a file name ({@link HarvestedBoundaryCheck#decisionPath}) and must have one form.
 *
 * <p>Implements FR13 of make-checkpoint-gate-durable.
 *
 * @param commit the commit id the round opened on; lowercase hexadecimal, non-blank
 */
public record RoundToken(String commit) {

    private static final Pattern COMMIT_ID = Pattern.compile("[0-9a-f]+");

    /**
     * @throws IllegalArgumentException if {@code commit} is blank or not lowercase hexadecimal —
     *     the value is deliberately not quoted, since a refused resolution is not a commit id and
     *     its text has no business in a message
     */
    public RoundToken {
        if (!COMMIT_ID.matcher(commit).matches()) {
            throw new IllegalArgumentException("a round token must be a non-blank lowercase hexadecimal commit id");
        }
    }
}
