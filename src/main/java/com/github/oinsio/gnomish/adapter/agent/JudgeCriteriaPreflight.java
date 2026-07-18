package com.github.oinsio.gnomish.adapter.agent;

import com.github.oinsio.gnomish.domain.engine.Verdict;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The judge-side half of D8's control/criteria-file preflight (FR13, NFR-R1):
 * where {@link ControlFilePreflight} lets its {@link
 * ControlFilePreflight.UnreadableControlFileException} propagate uncaught for
 * the executor — whose caller ({@code RoundExecution}) catches any {@link
 * RuntimeException} and shapes it into {@code RoundOutcome.CannotExecute}
 * without burning an attempt — the {@link
 * com.github.oinsio.gnomish.domain.engine.port.JudgeVoter} port has no such
 * exception channel: {@code vote()} never throws, since judge degradation is
 * inverted and a returned {@link Verdict.CannotVerify} is the only correct
 * shape for "no verdict obtainable" (NFR-R1).
 *
 * <p>{@link #checkReadable} performs the same read {@link JudgePromptBuilder}
 * would, but stops short of building the rest of the prompt — the eventual
 * {@code CliJudgeVoter.vote()} (task 7.5) calls this first and returns the
 * mapped {@link Verdict.CannotVerify} immediately on a present result, never
 * reaching {@link JudgePromptBuilder#build} or spawning a process. Building
 * the full prompt just to catch an exception was considered and rejected as
 * wasteful and less direct than a dedicated readability check.
 *
 * <p>Implements FR13, NFR-R1, D8 of add-agent-executor.
 */
public final class JudgeCriteriaPreflight {

    private JudgeCriteriaPreflight() {}

    /**
     * Checks whether {@code check}'s acceptance-criteria file is readable
     * from {@code root}, without building any prompt content.
     *
     * <p>Implements FR13, NFR-R1, D8 of add-agent-executor.
     *
     * @param root the workspace root the criteria-file reference is resolved
     *     against
     * @param check the judge check whose {@code criteriaFile} is checked
     * @return {@link Optional#empty()} when the file is readable; otherwise a
     *     {@link Verdict.CannotVerify} whose {@code reason} names the
     *     criteria file and whose {@code details} carries the underlying
     *     cause message, ready for {@code CliJudgeVoter.vote()} to return as
     *     a normal {@code Vote} — never thrown
     */
    public static Optional<Verdict.CannotVerify> checkReadable(Path root, VerifyCheck.Judge check) {
        try {
            ControlFilePreflight.read(root, check.criteriaFile());
            return Optional.empty();
        } catch (ControlFilePreflight.UnreadableControlFileException e) {
            String message =
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return Optional.of(new Verdict.CannotVerify(message, message));
        }
    }
}
