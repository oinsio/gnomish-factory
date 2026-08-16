package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.domain.engine.port.JudgeVoter;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The majority loop of one {@link VerifyCheck.Judge} check, extracted from {@link
 * VerifyOrchestrator} so each class stays within the file-size cap. It casts single
 * votes through the injected {@link JudgeVoter} up to {@code check.votes()} times,
 * tallies them into one verdict, and accounts each vote's tokens for cost telemetry
 * (design D2, D5).
 *
 * <p>The loop early-stops the moment either verdict reaches a majority ({@code
 * votes/2 + 1}): the later votes are never requested, so a critical stage does not
 * spend model calls it cannot need (NFR-C1). A {@link Verdict.CannotVerify} vote
 * short-circuits the whole check to that infrastructure verdict regardless of the
 * running tally — a vote that could not be graded taints the majority (task 4.5).
 * The task's {@link TaskContext} is threaded through to every vote so the human
 * decisions on the task reach the judge prompt (FR7).
 *
 * <p>Package-private and reentrant: it holds only its immutable injected voter and
 * no mutable state, so one instance drives concurrent judge checks safely — all
 * tally state is local to {@link #vote} (NFR-R1).
 *
 * <p>Implements FR3, FR7, NFR-C1 of add-stage-engine.
 */
final class JudgeVoting {

    private final JudgeVoter judgeVoter;

    /**
     * Wires the single-vote port the majority loop casts through. Immutable, so this
     * collaborator carries no mutable state (NFR-R1).
     *
     * @param judgeVoter the port that casts one judge vote per call; never null
     */
    JudgeVoting(JudgeVoter judgeVoter) {
        this.judgeVoter = judgeVoter;
    }

    /**
     * The outcome of a judge check's majority loop: the {@link Verdict} the votes
     * collapse to and the per-cast-vote token maps in vote order — every cast vote
     * contributes an entry, its own map empty when that vote did not report tokens
     * (design D4, following {@link ExecutorUsage#tokensByModel()}'s map-only shape).
     * Inert value data compared by content.
     *
     * <p>Implements FR3, NFR-C1 of add-stage-engine; FR9, NFR-C1, D4 of
     * add-agent-executor.
     *
     * @param verdict the whole judge check's verdict; never null
     * @param perVote one per-model token map per cast vote, in vote order; copied,
     *     unmodifiable, possibly empty
     */
    record Result(Verdict verdict, List<Map<String, TokenUsage>> perVote) {

        Result {
            perVote = List.copyOf(perVote);
        }
    }

    /**
     * Casts up to {@code check.votes()} judge votes and tallies them into one {@link
     * Result}. Each vote is cast through {@link JudgeVoter#vote} with {@code context}
     * threaded through (FR7); every cast vote's token map — possibly empty — is
     * appended to the running {@code perVote} accounting (NFR-C1, design D4). The
     * tally is an exhaustive switch over the
     * sealed {@link Verdict}: a {@link Verdict.Pass} counts toward the pass total, a
     * {@link Verdict.Fail} counts toward the fail total and its findings join the
     * aggregate, and a {@link Verdict.CannotVerify} short-circuits the whole check to
     * that verdict at once (task 4.5). The moment either total reaches {@code
     * majority} ({@code votes/2 + 1}) the matching verdict is returned and no further
     * vote is cast (NFR-C1). The loop only falls through for an even {@code votes}
     * (manifests guarantee odd — a defensive tie path); there the tie resolves by
     * {@code pass >= fail}. All tally state is local, so the class stays reentrant
     * (NFR-R1).
     *
     * <p>Implements FR3, FR7, NFR-C1 of add-stage-engine.
     *
     * @param check the judge check carrying the vote count and model settings
     * @param context the task context threaded into every vote (FR7)
     * @param workspace the opaque working copy being graded
     * @return the check's verdict plus the per-vote token accounting
     */
    Result vote(VerifyCheck.Judge check, TaskContext context, Workspace workspace) {
        int majority = check.votes() / 2 + 1;
        var perVote = new ArrayList<Map<String, TokenUsage>>();
        var aggregated = new ArrayList<Finding>();
        int pass = 0;
        int fail = 0;
        for (int cast = 0; cast < check.votes(); cast++) {
            JudgeVoter.Vote vote = judgeVoter.vote(check, context, workspace);
            perVote.add(vote.tokensByModel());
            switch (vote.verdict()) {
                case Verdict.Pass ignored -> pass++;
                case Verdict.Fail f -> {
                    fail++;
                    aggregated.addAll(f.findings());
                }
                case Verdict.CannotVerify cv -> {
                    return new Result(cv, perVote);
                }
            }
            if (pass >= majority) {
                return new Result(new Verdict.Pass(), perVote);
            }
            if (fail >= majority) {
                return new Result(new Verdict.Fail(aggregated), perVote);
            }
        }
        Verdict verdict = pass >= fail ? new Verdict.Pass() : new Verdict.Fail(aggregated);
        return new Result(verdict, perVote);
    }
}
