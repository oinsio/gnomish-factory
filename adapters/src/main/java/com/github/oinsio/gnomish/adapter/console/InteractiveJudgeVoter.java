package com.github.oinsio.gnomish.adapter.console;

import com.github.oinsio.gnomish.adapter.law.PipelineLaw;
import com.github.oinsio.gnomish.adapter.law.UnreadableLawFileException;
import com.github.oinsio.gnomish.app.console.DialogConsole;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.Verdict;
import com.github.oinsio.gnomish.domain.engine.port.JudgeVoter;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import java.util.List;
import java.util.Map;

/**
 * The interactive {@link JudgeVoter}: a human plays the LLM judge for one vote.
 * Prints the acceptance-criteria content from the invocation's frozen {@link
 * PipelineLaw} (D14, FR19 of add-sandbox-core) — not lazily from the working
 * copy — then prompts the operator for {@code pass} / {@code fail} (UX1
 * re-prompts on unrecognized input); {@code fail} opens the shared {@link
 * FindingsDialog}. Majority voting and short-circuiting across multiple votes
 * stay engine-owned (FR5) — this adapter answers exactly one {@link #vote} call
 * at a time.
 *
 * <p>Implements FR5 of add-manual-run; the frozen-law source per FR19, D14 of
 * add-sandbox-core.
 */
public record InteractiveJudgeVoter(DialogConsole console, PipelineLaw law, FindingsDialog findingsDialog)
        implements JudgeVoter {

    private static final String PASS_ANSWER = "pass";
    private static final String FAIL_ANSWER = "fail";
    private static final List<String> ACCEPTED_ANSWERS = List.of(PASS_ANSWER, FAIL_ANSWER);

    /**
     * @param console the dialog console this vote prompts on; never null
     * @param law the invocation's frozen pipeline law, the source of
     *     acceptance-criteria content (D14 of add-sandbox-core); never null
     */
    public InteractiveJudgeVoter(DialogConsole console, PipelineLaw law) {
        this(console, law, new FindingsDialog());
    }

    /**
     * Prints {@code check}'s acceptance-criteria file and prompts the operator
     * once for this vote's verdict.
     *
     * <p>Implements FR5 of add-manual-run.
     *
     * @param check the judge check being voted on; its criteria file is printed
     *     so the operator knows what they are grading
     * @param context unused — the interactive adapter has nothing further to
     *     read from the task context beyond what a human already sees
     * @param workspace unused — criteria content comes from the frozen law, not
     *     this workspace (D14 of add-sandbox-core); retained for the port signature
     * @return a {@link Vote} carrying {@link Verdict.Pass} or {@link
     *     Verdict.Fail} with the collected findings, and an empty token map — a
     *     human vote never reports tokens (FR9 of add-agent-executor)
     */
    @Override
    public Vote vote(VerifyCheck.Judge check, TaskContext context, Workspace workspace) {
        console.print(readCriteriaFile(check));
        String answer = console.ask("Judge vote (pass/fail): ", ACCEPTED_ANSWERS);
        Verdict verdict =
                PASS_ANSWER.equals(answer) ? new Verdict.Pass() : new Verdict.Fail(findingsDialog.collect(console));
        return new Vote(verdict, Map.of());
    }

    private String readCriteriaFile(VerifyCheck.Judge check) {
        String ref = check.criteriaFile();
        try {
            return law.controlFile(ref);
        } catch (UnreadableLawFileException e) {
            return "(acceptance criteria could not be read: " + ref + ")";
        }
    }
}
