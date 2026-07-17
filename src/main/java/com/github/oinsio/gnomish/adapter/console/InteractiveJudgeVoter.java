package com.github.oinsio.gnomish.adapter.console;

import com.github.oinsio.gnomish.adapter.pipeline.PathSafety;
import com.github.oinsio.gnomish.adapter.workspace.DirectoryWorkspace;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.Verdict;
import com.github.oinsio.gnomish.domain.engine.port.JudgeVoter;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

/**
 * The interactive {@link JudgeVoter}: a human plays the LLM judge for one vote.
 * Prints the acceptance-criteria file content, then prompts the operator for
 * {@code pass} / {@code fail} (UX1 re-prompts on unrecognized input); {@code
 * fail} opens the shared {@link FindingsDialog}. Majority voting and
 * short-circuiting across multiple votes stay engine-owned (FR5) — this
 * adapter answers exactly one {@link #vote} call at a time.
 *
 * <p>Implements FR5 of add-manual-run.
 */
public final class InteractiveJudgeVoter implements JudgeVoter {

    private static final String PASS_ANSWER = "pass";
    private static final String FAIL_ANSWER = "fail";
    private static final List<String> ACCEPTED_ANSWERS = List.of(PASS_ANSWER, FAIL_ANSWER);

    private final DialogConsole console;
    private final FindingsDialog findingsDialog;

    public InteractiveJudgeVoter(DialogConsole console) {
        this(console, new FindingsDialog());
    }

    InteractiveJudgeVoter(DialogConsole console, FindingsDialog findingsDialog) {
        this.console = console;
        this.findingsDialog = findingsDialog;
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
     * @param workspace the workspace the criteria file is resolved against
     * @return a {@link Vote} carrying {@link Verdict.Pass} or {@link
     *     Verdict.Fail} with the collected findings, and no reported tokens
     */
    @Override
    public Vote vote(VerifyCheck.Judge check, TaskContext context, Workspace workspace) {
        console.print(readCriteriaFile(check, workspace));
        String answer = console.ask("Judge vote (pass/fail): ", ACCEPTED_ANSWERS);
        Verdict verdict =
                PASS_ANSWER.equals(answer) ? new Verdict.Pass() : new Verdict.Fail(findingsDialog.collect(console));
        return new Vote(verdict, null);
    }

    private String readCriteriaFile(VerifyCheck.Judge check, Workspace workspace) {
        if (!(workspace instanceof DirectoryWorkspace directoryWorkspace)) {
            return "(acceptance criteria unavailable: workspace is not a DirectoryWorkspace)";
        }
        String ref = check.criteriaFile();
        PathSafety.Resolution resolution = PathSafety.resolveWithinRoot(directoryWorkspace.root(), ref);
        if (resolution instanceof PathSafety.Escapes escapes) {
            return "(acceptance criteria could not be read: path escapes the workspace: " + escapes.ref() + ")";
        }
        PathSafety.Within within = (PathSafety.Within) resolution;
        try {
            return Files.readString(within.path());
        } catch (IOException e) {
            return "(acceptance criteria could not be read: " + ref + ")";
        }
    }
}
