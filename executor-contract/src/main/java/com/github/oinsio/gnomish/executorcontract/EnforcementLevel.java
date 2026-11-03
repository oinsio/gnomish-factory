package com.github.oinsio.gnomish.executorcontract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Optional;

/**
 * How strongly an executor enforces the abstract policy, strongest first. Unknown-token arm: read as
 * {@link #PROMPT_ONLY}, the weakest level.
 *
 * <p>Implements FR4, FR13 of define-executor-contract.
 */
public enum EnforcementLevel implements WireToken {
    /** The operating system confines the run. */
    OS_SANDBOX("OS_SANDBOX"),
    /** The executor refuses a tool call the policy forbids. */
    TOOL_GATE("TOOL_GATE"),
    /** The agent asks before a forbidden act. */
    AGENT_ASKS("AGENT_ASKS"),
    /** The policy is only stated in the prompt. */
    PROMPT_ONLY("PROMPT_ONLY");

    private final String wire;

    EnforcementLevel(String wire) {
        this.wire = wire;
    }

    @Override
    @JsonValue
    public String wire() {
        return wire;
    }

    /** The level {@code token} names, or empty for a token outside the set. */
    public static Optional<EnforcementLevel> fromWire(String token) {
        return WireToken.lookup(values(), token);
    }

    @JsonCreator
    static EnforcementLevel read(String token) {
        return fromWire(token).orElse(PROMPT_ONLY);
    }
}
