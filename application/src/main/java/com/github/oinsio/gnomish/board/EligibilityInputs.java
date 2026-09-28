package com.github.oinsio.gnomish.board;

import java.time.Duration;
import java.time.Instant;

/**
 * The values the feed evaluates one ready task against when deciding whether it would claim it
 * now: the backoff shape ({@code base}, {@code cap}) and the instant to measure it at, plus the
 * open front against the WIP limit. Resolved once per board invocation by the caller, exactly as
 * the take feed resolves them, and handed whole to {@link BoardModel#build} and
 * {@link EligibilityPolicy#resolve} — the group recurs across both signatures and names one
 * domain concept, which is what earns it a record (design D4, D8 of add-parameter-count-gate).
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR6 of add-parameter-count-gate.
 *
 * @param base the backoff base for a single abort; never null
 * @param cap the maximum backoff delay; never null
 * @param now the instant to evaluate backoff against; never null
 * @param openFrontCount the current open-front count ({@code Working} + {@code AwaitingHuman}),
 *     i.e. the size of the fetched {@code listOpen} result
 * @param wipLimit the configured WIP limit; fresh tasks are held once {@code openFrontCount >=
 *     wipLimit}
 */
public record EligibilityInputs(Duration base, Duration cap, Instant now, int openFrontCount, int wipLimit) {}
