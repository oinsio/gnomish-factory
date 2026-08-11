package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.domain.engine.AttemptKey;
import java.nio.file.Path;
import java.util.List;

/**
 * The sandboxed round-boundary protocol check, run factory-side on harvested
 * refs in the factory clone (FR21, FR23, design D16, D17) — the trusted twin of
 * the host worktree's {@link RoundBoundaryCheck}. Two of the host checks are
 * subsumed by the transport itself: HEAD-on-branch has no factory-side meaning
 * (the fetch names the ref explicitly), and history rewrite is refused by the
 * fast-forward-only harvest ({@link HarvestRefusedException}). What remains is
 * the state-directory rule: {@code .gnomish-task/} untouched by the gnome
 * between the previous tip and the harvested snapshot — with exactly one
 * carve-out, the current round's decision request {@code
 * .gnomish-task/decisions/<stage>-a<attempt>.json}, whose one writer <em>is</em>
 * the gnome (FR23). Files named for any other stage or attempt are stale by
 * construction and stay violations.
 *
 * <p>Implements FR21, FR23 of add-sandbox-core.
 */
public final class HarvestedBoundaryCheck {

    private final GitProcessRunner runner;
    private final Path cloneDir;

    /**
     * @param runner the git subprocess runner
     * @param cloneDir the factory clone holding the harvested refs; bare reads run here
     */
    public HarvestedBoundaryCheck(GitProcessRunner runner, Path cloneDir) {
        this.runner = runner;
        this.cloneDir = cloneDir;
    }

    /**
     * Verifies {@code .gnomish-task/} was untouched between {@code previousTip}
     * and {@code snapshotCommit}, allowing only the current round's decision
     * file.
     *
     * @param taskId the task being checked, for the violation message
     * @param previousTip the branch tip right after the previous round closed
     * @param snapshotCommit the harvested snapshot commit closing this round
     * @param key the current round's key; fixes the single permitted decision path
     * @throws RoundBoundaryViolationException if any other {@code .gnomish-task/} path changed
     * @throws GitPersistFailedException if the diff itself cannot be computed
     */
    public void verify(String taskId, String previousTip, String snapshotCommit, AttemptKey key) {
        GitCommandResult diff =
                runner.run(cloneDir, "diff", "--name-only", previousTip, snapshotCommit, "--", ".gnomish-task/");
        if (diff.exitCode() != 0) {
            throw new GitPersistFailedException(
                    taskId, key.stage(), key.attempt(), "harvested boundary diff", diff.stderr());
        }
        String allowed = decisionPath(key);
        List<String> touched = diff.stdout()
                .lines()
                .map(String::strip)
                .filter(HarvestedBoundaryCheck::isNonEmpty)
                .filter(line -> !line.equals(allowed))
                .toList();
        if (!touched.isEmpty()) {
            throw new RoundBoundaryViolationException(
                    taskId, ".gnomish-task/ was modified by the gnome: " + String.join(", ", touched));
        }
    }

    /**
     * Defensive blank-line guard for the diff output. {@code @DoNotMutate}: PIT's "replaced
     * boolean return with true" mutant is equivalent here, not a coverage gap — every line
     * {@code git diff --name-only <a> <b> -- .gnomish-task/} prints starts with the pathspec
     * prefix {@code .gnomish-task/}, so no reachable execution can put an empty (or
     * all-whitespace) line through this filter; it exists purely as defense in depth against a
     * hypothetical blank line in subprocess output. Keeping unreachable lines has zero
     * externally observable difference, so no unit test can kill the mutant (the same
     * equivalent-mutant category as {@code TakeBatchExitCode.isNewSmallestNonZero}, see the
     * pitest block in build.gradle).
     */
    @DoNotMutate
    private static boolean isNonEmpty(String line) {
        return !line.isEmpty();
    }

    /**
     * The single gnome-writable path under {@code .gnomish-task/} for one round
     * (FR23): {@code .gnomish-task/decisions/<stage>-a<attempt>.json}. Stage and
     * attempt in the name make stale files self-excluding.
     */
    public static String decisionPath(AttemptKey key) {
        return ".gnomish-task/decisions/" + key.stage() + "-a" + key.attempt() + ".json";
    }
}
