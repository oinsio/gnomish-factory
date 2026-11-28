package com.github.oinsio.gnomish.adapter.agent;

import com.github.oinsio.gnomish.adapter.agent.claude.ClaudeEnvironment;
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist;
import java.util.List;

/**
 * Which agent credentials a round would carry (design D4 of add-doctor-command): the names of the
 * secret-carrying credentials the Claude describe declares ({@link ClaudeEnvironment#SECRETS}) that
 * resolve through the round's child-environment allowlist. It asks the exact question an agent round
 * and a judge vote ask, {@link ClaudeEnvironment#variables}, over the allowlist the composition root binds to the secrets port,
 * so a token in the host secrets folder counts as present here exactly when it reaches the agent
 * CLI, and a name the allowlist scrubs (a declared tracker or check credential) counts as absent
 * exactly when the round would lose it. No list of names lives here, and no environment is read:
 * the caller hands in the allowlist.
 *
 * <p>Only names leave this class, never a value (NFR-S1): the resolved values are dropped before
 * the result is built.
 *
 * <p>Implements FR1, NFR-S1 of add-doctor-command; NFR-S1 of extract-agent-round-runner.
 */
public final class AgentCredentials {

    private AgentCredentials() {}

    /**
     * Every agent credential name a round reads, in seam order: the names an operator may set, for
     * {@code doctor}'s fix line (FR1 of add-doctor-command). Names only.
     *
     * @return the credential names; never null
     */
    public static List<String> names() {
        return ClaudeEnvironment.SECRETS;
    }

    /**
     * The agent credential names that resolve through {@code childEnv}.
     *
     * @param childEnv the child-environment allowlist an agent round would use; never null
     * @return the present credential names, in seam order; empty when none resolves; never null
     */
    public static List<String> presentNames(ChildEnvAllowlist childEnv) {
        var present = ClaudeEnvironment.variables(childEnv).keySet();
        return ClaudeEnvironment.SECRETS.stream().filter(present::contains).toList();
    }
}
