package com.github.oinsio.gnomish.executorcontract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The contract role an executor run is requested in. Unknown-token arm: a role outside this set is
 * read as no role — a request naming one is refused, and a describe listing one ignores it, so a use
 * site that needs it is refused as undeclared. {@link #EFFECT} is reserved and not executed in this
 * version.
 *
 * <p>Implements FR1, FR11 of define-executor-contract.
 */
public enum Role implements WireToken {
    /** Change the working copy. */
    TRANSFORM("transform"),
    /** Judge the working copy. */
    VERDICT("verdict"),
    /** Choose among options. */
    DECISION("decision"),
    /** Act outside the branch; reserved. */
    EFFECT("effect");

    private final String wire;

    Role(String wire) {
        this.wire = wire;
    }

    @Override
    @JsonValue
    public String wire() {
        return wire;
    }

    /** The role {@code token} names, or empty for a token outside the set. */
    public static Optional<Role> fromWire(String token) {
        return WireToken.lookup(values(), token);
    }

    /**
     * Whether {@code inputs} carry what this role requires: instructions for transform, criteria for
     * verdict, a question and at least two options for decision; effect takes none in this version.
     */
    public boolean accepts(RoleInputs inputs) {
        if (this == TRANSFORM) {
            return inputs.instructions() != null;
        }
        if (this == VERDICT) {
            return inputs.criteria() != null;
        }
        if (this == DECISION) {
            return inputs.question() != null && inputs.options().size() >= 2;
        }
        return true;
    }

    @JsonCreator
    static @Nullable Role readOrNull(String token) {
        return fromWire(token).orElse(null);
    }
}
