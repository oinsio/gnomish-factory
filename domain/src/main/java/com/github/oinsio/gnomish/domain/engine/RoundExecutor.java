package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import org.jspecify.annotations.Nullable;

/**
 * Which executor ran one round, as the round's log line and ledger line name it (NFR-O2, UX3 of
 * define-executor-contract): the executor's {@code name} and, for an executor that answered
 * describe, its describe {@code version}. An executor with no describe (an {@code agent-cli} stage)
 * has no version, and its line names it by the stage's executor type alone.
 *
 * <p>The version is the executor's own free text and stays a carrier (NFR-S2); the name is a law name
 * or an executor type word, held non-blank. Built by {@link DescribedExecutors#executorOf} from the
 * stage's {@code executor.name} and the run's describe versions. Inert value data compared by content.
 *
 * <p>Implements NFR-O2, UX3 of define-executor-contract.
 *
 * @param name the executor's law name ({@code executor.name}), or the stage's executor type word when
 *     the stage names no executor; never blank
 * @param version the executor's describe version; null when the executor answered no describe
 */
public record RoundExecutor(String name, @Nullable UntrustedText version) {

    public RoundExecutor {
        requireName(name);
    }

    /**
     * Refuses a blank name: a round line must name what ran it. A static method, not inline, because
     * PIT's record filter would exempt a guard in the canonical constructor from the mutation gate.
     */
    private static void requireName(String name) {
        if (name.isBlank()) {
            throw new IllegalArgumentException("RoundExecutor.name must not be blank");
        }
    }
}
