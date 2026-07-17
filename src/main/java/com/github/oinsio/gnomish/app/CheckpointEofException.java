package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.console.ConsoleClosedException;
import java.io.Serial;

/**
 * Case 2 of the EOF flows (design D2), at the manual checkpoint prompt: the operator
 * pressed Ctrl-D right at {@link RunnerOutcomeLoop#handlePaused}, a deliberate exit
 * distinct from an escalation's Case 1/2 EOF. {@link RunnerOutcomeLoop} catches the
 * {@link ConsoleClosedException} thrown by that prompt and rethrows this type —
 * carrying it as the cause — so {@link RunExitCodeMapper} can tell this apart from
 * {@link EscalationEofException} and map it to the {@code Paused}-family exit code
 * (11).
 *
 * <p>Implements FR12, D10 of add-manual-run.
 */
public final class CheckpointEofException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param cause the {@link ConsoleClosedException} the checkpoint prompt threw
     */
    public CheckpointEofException(ConsoleClosedException cause) {
        super("operator closed input at the manual checkpoint prompt", cause);
    }
}
