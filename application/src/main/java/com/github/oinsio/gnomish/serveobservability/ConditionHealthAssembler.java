package com.github.oinsio.gnomish.serveobservability;

import com.github.oinsio.gnomish.app.serve.DaemonCondition;
import com.github.oinsio.gnomish.app.serve.DaemonConditions;
import com.github.oinsio.gnomish.app.serve.RemoteOutageHealth;
import com.github.oinsio.gnomish.app.take.DaemonConditionKind;
import java.util.List;

/**
 * Builds the snapshot's {@code conditions} section (design D19, NFR-O2, UX4 of
 * add-sandbox-base-image) from the daemon's {@link DaemonConditions}: one entry per condition, in
 * the set's order, the remote's first; the remote's entry names its target. Mirrors {@link
 * RemoteHealthAssembler}'s role for the {@code remote} section. Stateless.
 *
 * <p>Implements NFR-O2, UX4 of add-sandbox-base-image.
 */
public final class ConditionHealthAssembler {

    private ConditionHealthAssembler() {}

    /**
     * Assembles the {@code conditions} section from every condition's current state.
     *
     * @param conditions the daemon's conditions; never null
     * @return one entry per condition, the remote's first; never null
     */
    public static List<ConditionHealth> assemble(DaemonConditions conditions) {
        return conditions.members().stream()
                .map(ConditionHealthAssembler::entry)
                .toList();
    }

    private static ConditionHealth entry(DaemonCondition condition) {
        RemoteOutageHealth health = condition.health();
        return new ConditionHealth(
                ConditionTokens.token(condition.kind()),
                condition.kind() == DaemonConditionKind.REMOTE ? health.target() : null,
                health.open(),
                health.openSince(),
                health.lastError(),
                health.nextProbeAt(),
                health.consecutiveFailures(),
                health.lastSuccessAt());
    }
}
