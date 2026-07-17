package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.console.ConsoleClosedException;
import java.io.Serial;

/**
 * Case 2 of the EOF flows (design D2), at the escalation resume-decision prompt: the
 * operator pressed Ctrl-D right at {@link EscalationResumeDialog}'s resume prompt,
 * a deliberate exit distinct from {@link InputExhaustedException}'s Case 1 (input
 * exhausted earlier, mid-stage). {@link RunnerOutcomeLoop} catches the {@link
 * ConsoleClosedException} thrown by that prompt and rethrows this type — carrying it as
 * the cause — so {@link RunExitCodeMapper} can tell this apart from {@link
 * CheckpointEofException} (the analogous Case 2 at the {@code Paused} checkpoint prompt)
 * and map it to the {@code Escalated}-family exit code (10).
 *
 * <p>Implements FR12, D10 of add-manual-run.
 */
public final class EscalationEofException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param cause the {@link ConsoleClosedException} the resume-decision prompt threw
     */
    public EscalationEofException(ConsoleClosedException cause) {
        super("operator closed input at the escalation resume prompt", cause);
    }
}
