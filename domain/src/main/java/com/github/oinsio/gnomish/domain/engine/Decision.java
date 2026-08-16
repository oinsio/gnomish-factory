package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.DoNotMutate;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A single human decision recorded for a task. Decisions are context, never
 * commands (design D6): the engine carries the free-text {@code body} verbatim
 * through to executor and judge requests and never parses directives out of it.
 * The optional metadata locates the decision — which stage it pertains to, who
 * made it, and when — but the engine never interprets any of it either.
 *
 * <p>Inert value data compared by content; a blank {@code body} is rejected
 * because a decision with no message is meaningless context.
 *
 * <p>Implements FR7 of add-stage-engine.
 *
 * @param body the free-text decision message, carried verbatim; never blank
 * @param stage the stage the decision pertains to, or {@code null} if unscoped
 * @param author who made the decision, or {@code null} if unattributed
 * @param time when the decision was recorded, or {@code null} if unknown
 */
public record Decision(
        String body,
        @Nullable String stage,
        @Nullable String author,
        @Nullable Instant time) {

    public Decision {
        body = requireNonBlank(body, "body");
    }

    /**
     * Fails fast on a blank {@code body}: a decision that carries no message is
     * useless context (FR7). Kept as an explicit static method rather than inline
     * in the compact constructor: PIT's record filter suppresses all mutations
     * inside a record's canonical constructor, which would silently exempt this
     * validation from the 100% mutation gate.
     *
     * <p>PIT M4 documented exception (build.gradle has the full rationale):
     * {@code @DoNotMutate} because PIT's Gregor engine crashes its own minion JVM
     * (RUN_ERROR, not a real test gap) mutating some bytecode shapes of this
     * record's component-adjacent private methods on JDK 17+
     * (hcoles/pitest#1285, a JVMTI RedefineClasses restriction on
     * NestHost/NestMembers/Record attributes — not fixable via PIT config).
     * Otherwise fully covered by DecisionSpec.
     */
    @DoNotMutate
    private static String requireNonBlank(String value, String component) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("Decision." + component + " must not be blank");
        }
        return value;
    }
}
