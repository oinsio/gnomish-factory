package com.github.oinsio.gnomish.app.describe;

import com.github.oinsio.gnomish.domain.engine.ExecutorRole;
import com.github.oinsio.gnomish.executorcontract.Role;
import org.jspecify.annotations.Nullable;

/**
 * The one mapping between the domain's {@link ExecutorRole} and the executor contract's {@link Role}
 * (design D14 of define-executor-contract): both sets are switched over exhaustively in both
 * directions, so a constant added on either side fails to compile here rather than drifting. The usage
 * wire spells a participant's role through it, and the {@code command} built-in maps its owned roles
 * ({@code BuiltInExecutors.COMMAND_ROLES}) through it into its describe (design D12 of
 * extract-agent-round-runner). It sits in {@code :application} because the domain reaches no contract.
 *
 * <p>Implements FR15 of define-executor-contract; FR13 of extract-agent-round-runner.
 */
public final class ExecutorRoles {

    private ExecutorRoles() {}

    /**
     * The contract role of a domain role.
     *
     * @param role a domain role; never null
     * @return the contract role of its name; never null
     */
    public static Role contract(ExecutorRole role) {
        return switch (role) {
            case TRANSFORM -> Role.TRANSFORM;
            case VERDICT -> Role.VERDICT;
            case DECISION -> Role.DECISION;
            case EFFECT -> Role.EFFECT;
        };
    }

    /**
     * The domain role of a contract role.
     *
     * @param role a contract role, or {@code null} for a token outside the contract's set
     * @return the domain role of its name; never null
     * @throws IllegalArgumentException if {@code role} is null
     */
    public static ExecutorRole domain(@Nullable Role role) {
        if (role == null) {
            throw new IllegalArgumentException("usage entry names a role outside the contract's set");
        }
        return switch (role) {
            case TRANSFORM -> ExecutorRole.TRANSFORM;
            case VERDICT -> ExecutorRole.VERDICT;
            case DECISION -> ExecutorRole.DECISION;
            case EFFECT -> ExecutorRole.EFFECT;
        };
    }
}
