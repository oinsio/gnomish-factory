package com.github.oinsio.gnomish.adapter.git;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Classifies a task branch tip on resume (FR21, design D15): a tip carrying the
 * snapshot commit message — {@code gnomish: snapshot <stage>#<round>} — means
 * the factory died between the snapshot and the state commit, i.e. <em>during
 * verification</em>. The resuming instance re-runs verification against exactly
 * that attempt commit and the attempt counter is unchanged — no attempt burned
 * for a verdict that was never recorded. Any other tip (a state commit, a
 * lifecycle commit, the gnome's own commits from an interrupted round) resumes
 * through the ordinary salvage path.
 *
 * <p>Reads only the commit subject as a bare-object query in the factory clone
 * — no checkout, no hooks (FR17). The snapshot message is the one service
 * message that is deliberately a parsing contract (see {@link
 * ServiceCommitMessages#snapshot}).
 *
 * <p>Implements FR21 of add-sandbox-core.
 */
public final class SnapshotTipCheck {

    private static final String SUBJECT_PREFIX = "gnomish: snapshot ";

    private final GitProcessRunner runner;
    private final Path cloneDir;

    public SnapshotTipCheck(GitProcessRunner runner, Path cloneDir) {
        this.runner = runner;
        this.cloneDir = cloneDir;
    }

    /**
     * An interrupted verification found at the branch tip, if any.
     *
     * @param branch the task branch to inspect, e.g. {@link TaskIdSanitizer#branchName}
     * @return the pending verification's attempt commit and round, or empty when
     *     the tip is not a snapshot commit
     */
    public Optional<InterruptedVerification> inspect(String branch) {
        GitCommandResult tip = runner.run(cloneDir, "log", "-1", "--format=%H%x00%s", "refs/heads/" + branch);
        if (tip.exitCode() != 0) {
            return Optional.empty();
        }
        String[] parts = tip.stdout().strip().split("\u0000", 2);
        if (parts.length < 2 || !parts[1].startsWith(SUBJECT_PREFIX)) {
            return Optional.empty();
        }
        String stageAndRound = parts[1].substring(SUBJECT_PREFIX.length());
        int hash = stageAndRound.lastIndexOf('#');
        if (hash <= 0) {
            return Optional.empty();
        }
        int round;
        try {
            round = Integer.parseInt(stageAndRound.substring(hash + 1));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        return Optional.of(new InterruptedVerification(parts[0], stageAndRound.substring(0, hash), round));
    }

    /**
     * A snapshot commit sitting unrecorded at the branch tip: verification of
     * {@code attemptCommit} (round {@code round} of {@code stage}) never
     * produced a state commit and must re-run without burning the attempt.
     */
    public record InterruptedVerification(String attemptCommit, String stage, int round) {}
}
