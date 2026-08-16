package com.github.oinsio.gnomish.sandbox;

import java.util.Optional;

/**
 * The named requirements a stage may place on its environment (design D8), each
 * mapped to the {@link CapabilityPassport} dimension that satisfies it. The
 * factory reconciles a stage's declared {@code needs} against the bound adapter's
 * passport and refuses fail-closed on any unmet one (FR14); {@code SandboxReconciler}
 * uses this mapping to decide, per token, whether the passport satisfies it.
 *
 * <p>The token grammar is a closed vocabulary: a declared need whose token is not
 * one of these is treated as <em>unmet</em> by construction — an unrecognized
 * requirement cannot be proven satisfied by any passport, so fail-closed is the
 * only safe verdict (and the token is named in the error, UX2). Only {@code
 * docker-inside} is a spec-named example; the other three cover the remaining
 * passport dimensions so a repo can tighten on any of them.
 *
 * <p>Implements FR14 of add-sandbox-core.
 */
public enum SandboxNeed {

    /** Requires the adapter to support running Docker inside the environment (spec-named example). */
    DOCKER_INSIDE("docker-inside") {
        @Override
        public boolean satisfiedBy(CapabilityPassport passport) {
            return passport.dockerInside();
        }
    },

    /** Requires all egress to be forced through a default-deny guard. */
    EGRESS_CONTROL("egress-control") {
        @Override
        public boolean satisfiedBy(CapabilityPassport passport) {
            return passport.egressControlled();
        }
    },

    /** Requires one task's environment to be isolated from another's. */
    TASK_ISOLATION("task-isolation") {
        @Override
        public boolean satisfiedBy(CapabilityPassport passport) {
            return passport.taskToTaskBoundary();
        }
    },

    /** Requires any isolation boundary at all between the process and the factory host. */
    ISOLATION("isolation") {
        @Override
        public boolean satisfiedBy(CapabilityPassport passport) {
            return passport.isolation() != IsolationLevel.NONE;
        }
    };

    private final String token;

    SandboxNeed(String token) {
        this.token = token;
    }

    /**
     * The token this need is declared with in a stage manifest's {@code
     * sandbox.needs} list.
     *
     * @return the declaration token; never null
     */
    public String token() {
        return token;
    }

    /**
     * Whether {@code passport} satisfies this need.
     *
     * @param passport the bound adapter's passport; never null
     * @return {@code true} when the passport meets this requirement
     */
    public abstract boolean satisfiedBy(CapabilityPassport passport);

    /**
     * Resolves a declared need token to its enum constant, or empty when the token
     * is outside the closed vocabulary — an unknown need the reconciler must treat
     * as unmet (fail-closed).
     *
     * @param token the declared need token; never null
     * @return the matching need, or empty when unrecognized
     */
    public static Optional<SandboxNeed> fromToken(String token) {
        for (SandboxNeed need : values()) {
            if (need.token.equals(token)) {
                return Optional.of(need);
            }
        }
        return Optional.empty();
    }
}
