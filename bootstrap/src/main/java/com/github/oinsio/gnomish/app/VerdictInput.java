package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.agent.JudgeCriteriaPreflight;
import com.github.oinsio.gnomish.adapter.agent.JudgePromptBuilder;
import com.github.oinsio.gnomish.adapter.law.PipelineLaw;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import com.github.oinsio.gnomish.executorcontract.ExecutorResult;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The {@code verdict} role input of a check round's request, taken from the check (design D2 of
 * extract-agent-round-runner): for a {@code judge} check — a check whose round view carries {@link
 * VerifyCheck.Token#JUDGE} and its criteria file as its identifier — the criteria prompt {@link
 * JudgePromptBuilder} renders from the run's frozen law and the task's briefing; for every other
 * check the text {@code check '<id>'}. The criteria preflight of a judge ({@link
 * JudgeCriteriaPreflight}) runs ahead of its request, before the request's environment is bound.
 *
 * <p>Holds the run's frozen law and nothing that changes, so one instance serves every check round
 * of a run.
 *
 * <p>Implements FR4 of extract-agent-round-runner.
 */
final class VerdictInput {

    /** The role input of a run that has no law to render a judge from: a check's identifier only. */
    static final VerdictInput IDENTIFIER_ONLY = new VerdictInput(null);

    private final @Nullable PipelineLaw law;

    private VerdictInput(@Nullable PipelineLaw law) {
        this.law = law;
    }

    /**
     * The role input of a run's check rounds over its frozen law.
     *
     * @param law the run's frozen law, the source of a judge's acceptance criteria; never null
     * @return the role input; never null
     */
    static VerdictInput of(PipelineLaw law) {
        return new VerdictInput(law);
    }

    /**
     * The answer of a judge check whose criteria cannot be read, ahead of its request.
     *
     * @param check the check, in its round view; never null
     * @return the {@code failed/infrastructure} refusal naming the criteria file, or empty for a
     *     readable judge and for every other check
     */
    Optional<ExecutorResult> refusal(VerifyCheck.Executor check) {
        if (check.token() != VerifyCheck.Token.JUDGE) {
            return Optional.empty();
        }
        return JudgeCriteriaPreflight.refusal(judgeLaw(), check.checkId());
    }

    /**
     * The role input text of {@code check}'s request.
     *
     * @param check the check, in its round view; never null
     * @param context the task's identity, goal and decisions, a judge's briefing; never null
     * @return the text; never null
     */
    String text(VerifyCheck.Executor check, TaskContext context) {
        if (check.token() != VerifyCheck.Token.JUDGE) {
            return "check '" + check.checkId() + "'";
        }
        return new JudgePromptBuilder(judgeLaw()).verdictInput(check.checkId(), context);
    }

    private PipelineLaw judgeLaw() {
        if (law == null) {
            throw new IllegalStateException("a judge check round needs the run's frozen law, and this one has none");
        }
        return law;
    }
}
