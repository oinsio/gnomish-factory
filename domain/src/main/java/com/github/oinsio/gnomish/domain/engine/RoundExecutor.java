package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import org.jspecify.annotations.Nullable;

/**
 * Which executor ran one round or one executor check, as its log line, ledger line and participant
 * entry name it (NFR-O2, UX3 of define-executor-contract; NFR-O1 of extract-agent-round-runner): the
 * registry entry the executor block resolved to — the name dispatch resolved, {@code claude} for an
 * {@code agent-cli} block — and that entry's describe {@code version}: {@code built-in} for a built-in,
 * the described version for a program, none for a program the run did not describe.
 *
 * <p>The version is the executor's own free text and stays a carrier (NFR-S2); the name is a registry
 * name, held non-blank. Built only by {@link DescribedExecutors#executorOf}, the one owner of the name
 * a record carries (design D12 of extract-agent-round-runner). Inert value data compared by content.
 *
 * <p>Implements NFR-O2, UX3 of define-executor-contract; NFR-O1, FR14 of extract-agent-round-runner.
 *
 * @param name the registry entry the block resolved to; never blank
 * @param version the entry's describe version; null for a program the run did not describe
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
