package com.github.oinsio.gnomish.adapter.console;

import com.github.oinsio.gnomish.domain.engine.PollStatus;
import com.github.oinsio.gnomish.domain.engine.port.ExternalCheckClient;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import java.util.List;

/**
 * The interactive {@link ExternalCheckClient}: a human plays the third-party
 * checker for one poll. Prints the check's identity, then prompts the operator
 * for {@code pass} / {@code fail} / {@code running} (UX1 re-prompts on
 * unrecognized input); {@code fail} opens the shared {@link FindingsDialog}.
 * Interval/timeout and the repeated-poll loop stay engine-owned (FR4) — this
 * adapter answers exactly one {@link #poll} call at a time.
 *
 * <p>Implements FR4 of add-manual-run.
 */
public final class InteractiveExternalCheckClient implements ExternalCheckClient {

    private static final String PASS_ANSWER = "pass";
    private static final String FAIL_ANSWER = "fail";
    private static final String RUNNING_ANSWER = "running";
    private static final List<String> ACCEPTED_ANSWERS = List.of(PASS_ANSWER, FAIL_ANSWER, RUNNING_ANSWER);

    private final DialogConsole console;
    private final FindingsDialog findingsDialog;

    public InteractiveExternalCheckClient(DialogConsole console) {
        this(console, new FindingsDialog());
    }

    InteractiveExternalCheckClient(DialogConsole console, FindingsDialog findingsDialog) {
        this.console = console;
        this.findingsDialog = findingsDialog;
    }

    /**
     * Prompts the operator once for this poll's verdict on {@code check}.
     *
     * <p>Implements FR4 of add-manual-run.
     *
     * @param check the external check being polled; its identity is printed so
     *     the operator knows what they are answering for
     * @param workspace unused — the interactive adapter has nothing to inspect
     * @return {@link PollStatus.Pass}, {@link PollStatus.Running}, or {@link
     *     PollStatus.Fail} carrying the collected findings
     */
    @Override
    public PollStatus poll(VerifyCheck.External check, Workspace workspace) {
        console.print("External check: " + check.checkId());
        String answer = console.ask("Poll result (pass/fail/running): ", ACCEPTED_ANSWERS);
        return switch (answer) {
            case PASS_ANSWER -> new PollStatus.Pass();
            case RUNNING_ANSWER -> new PollStatus.Running();
            default -> new PollStatus.Fail(findingsDialog.collect(console));
        };
    }
}
