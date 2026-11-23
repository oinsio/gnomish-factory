package com.github.oinsio.gnomish.adapter.agent;

import com.github.oinsio.gnomish.adapter.law.PipelineLaw;
import com.github.oinsio.gnomish.adapter.law.UnreadableLawFileException;
import com.github.oinsio.gnomish.executorcontract.ExecutorResult;
import com.github.oinsio.gnomish.executorcontract.FailureClass;
import com.github.oinsio.gnomish.executorcontract.Status;
import com.github.oinsio.gnomish.executorcontract.Usage;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;
import java.util.Optional;

/**
 * The criteria preflight of a {@code judge} check (FR13, NFR-R1 of add-agent-executor; design D2 of
 * extract-agent-round-runner): ahead of the check round's request — before its box is materialized
 * and before any prompt is built — the check's acceptance-criteria file must have been frozen
 * readable in the invocation's {@link PipelineLaw} (D14 of add-sandbox-core: the criteria come from
 * the gnome-unwritable law source, never the working copy). "Never a criteria-less vote."
 *
 * <p>An unreadable file is the request's answer, {@code failed/infrastructure} naming the file with
 * the read failure as its details, which the engine derives to a cannot-verify through the one
 * verdict derivation and reports once, at its decision; nothing is logged here (one failure, one
 * log). Building the full prompt just to catch the exception was considered and rejected as
 * wasteful and less direct than a dedicated readability check.
 *
 * <p>Implements FR13, NFR-R1, D8 of add-agent-executor; FR19, D14 of add-sandbox-core; FR4 of
 * extract-agent-round-runner.
 */
public final class JudgeCriteriaPreflight {

    private JudgeCriteriaPreflight() {}

    /**
     * The refusal of a judge check whose acceptance-criteria file was not frozen readable in {@code
     * law}, without building any prompt content.
     *
     * <p>Implements FR13, NFR-R1, D8 of add-agent-executor; FR4 of extract-agent-round-runner.
     *
     * @param law the invocation's frozen pipeline law the criteria are read from; never null
     * @param criteriaFile the judge check's criteria file, relative to the {@code .gnomish/} root
     * @return {@link Optional#empty()} when the file is readable; otherwise a {@code
     *     failed/infrastructure} result whose reason names the criteria file and whose details carry
     *     the underlying cause — never thrown
     */
    public static Optional<ExecutorResult> refusal(PipelineLaw law, String criteriaFile) {
        try {
            law.controlFile(criteriaFile);
            return Optional.empty();
        } catch (UnreadableLawFileException e) {
            // The exception always names the file and the read failure (its one constructor).
            String message = String.valueOf(e.getMessage());
            // The criteria file is the target repository's own: a path its `.gnomish/` manifest
            // named, and whatever the read failure said about it (design D3's manifest family).
            return Optional.of(new ExecutorResult(
                    Status.FAILED,
                    FailureClass.INFRASTRUCTURE,
                    null,
                    UntrustedText.manifest("the judge's acceptance criteria file '" + criteriaFile + "' is unreadable"),
                    null,
                    Usage.absent(),
                    List.of(),
                    null,
                    null,
                    UntrustedText.manifest(message)));
        }
    }
}
