package com.github.oinsio.gnomish.app;

import java.io.Serial;

/**
 * Signals Case 1 of the EOF flows (design D2): the operator's input ran out earlier, mid-stage,
 * inside an interactive adapter — the resulting {@link
 * com.github.oinsio.gnomish.adapter.console.ConsoleClosedException} was already caught by the
 * engine's existing infrastructure-failure rules and turned into this {@code Escalated} outcome.
 * By the time {@link RunnerOutcomeLoop} is about to prompt for a resume decision, {@link
 * com.github.oinsio.gnomish.adapter.console.DialogConsole#inputExhausted()} is already latched
 * {@code true} — re-entering the resume dialog would prompt a console that cannot produce any
 * more input, so the loop throws this instead of prompting (FR13, NFR-R1).
 *
 * <p>Distinct from a fresh {@link com.github.oinsio.gnomish.adapter.console.ConsoleClosedException}
 * thrown directly out of a runner-owned prompt (Case 2: the operator pressing Ctrl-D right at the
 * resume/checkpoint dialog) — that is a deliberate exit mapped to the outcome's own code (10/11),
 * left uncaught here by design. This type exists so the boundary (task 7.9) can tell the two cases
 * apart and map this one to exit code 4, distinct from both {@link InternalErrorException}'s exit 1
 * and a bare {@code ConsoleClosedException}'s outcome-specific code.
 *
 * <p>Unchecked: a later task (7.9) maps this type to exit code 4 via Spring Boot's {@code
 * ExitCodeExceptionMapper} (design D10); this task only defines the seam. The farewell line UX3
 * calls for is intentionally not printed here — task 7.9 owns all farewell-line printing, since it
 * is the single layer that maps every terminal outcome (including a bare {@code
 * ConsoleClosedException} from a runner prompt) to an exit code and output; duplicating farewell
 * text at each throw site would risk drift between the two EOF cases.
 *
 * <p>Implements FR13, NFR-R1, UX3, D2 of add-manual-run.
 */
public final class InputExhaustedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public InputExhaustedException() {
        super("input exhausted mid-stage");
    }
}
