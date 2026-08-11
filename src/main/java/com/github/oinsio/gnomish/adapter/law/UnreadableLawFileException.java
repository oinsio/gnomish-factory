package com.github.oinsio.gnomish.adapter.law;

import java.io.Serial;

/**
 * Thrown by {@link PipelineLaw#controlFile} when the requested law file — a stage's
 * control (instructions) file or a judge check's acceptance-criteria file — was
 * <em>unreadable at freeze time</em> and so has no frozen content to hand back: an
 * infrastructure failure of the round or vote, no stage attempt burned (FR19,
 * NFR-R1, D14 of add-sandbox-core).
 *
 * <p>The failure reaction is the caller's, exactly as before the pipeline-law
 * rework moved the read from the working copy to the frozen law source (task 2.5):
 * the CLI executor lets it propagate uncaught so {@code RoundExecution} shapes it
 * into {@code RoundOutcome.CannotExecute}; the judge side catches it and maps it to
 * {@code Verdict.CannotVerify}; the interactive adapters catch it and degrade to a
 * placeholder line. Unchecked, following this codebase's established idiom for
 * infrastructure-failure signaling (see {@code MissingResultEventException}).
 *
 * <p>Because pipeline law binds once per invocation (D14), an unreadable law file is
 * unreadable for the whole invocation: every round or vote that needs it fails the
 * same way, which is the correct shape for a genuinely missing law file.
 *
 * <p>Implements FR19, NFR-S2, D14 of add-sandbox-core.
 */
public final class UnreadableLawFileException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param ref the law-file reference exactly as declared in the stage manifest
     *     (relative to the {@code .gnomish/} law-source root)
     * @param reason a short, human-readable cause captured when the file was frozen,
     *     folded into the exception message for diagnosability
     */
    public UnreadableLawFileException(String ref, String reason) {
        super("law file could not be read: " + ref + " (" + reason + ")");
    }
}
