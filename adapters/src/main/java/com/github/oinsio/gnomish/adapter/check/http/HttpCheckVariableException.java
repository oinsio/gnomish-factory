package com.github.oinsio.gnomish.adapter.check.http;

/**
 * Raised when an http check's request cannot be composed because a {@code ${...}} reference in it has
 * no value (NFR-S2 of add-plugin-architecture): either the variable is outside the engine-defined
 * whitelist — which the load seam normally catches, so reaching here means a check bypassed it — or
 * it is whitelisted but this run cannot supply it (a manual run over a plain directory has no attempt
 * commit).
 *
 * <p>Either way the check fails closed as {@code CannotVerify}: a URL built from a missing value
 * addresses something other than what the check meant to observe, and a confident verdict about the
 * wrong thing is worse than no verdict.
 *
 * <p>Implements NFR-S2 of add-plugin-architecture.
 */
final class HttpCheckVariableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String reason;

    HttpCheckVariableException(String name, boolean whitelisted) {
        this(
                whitelisted
                        ? "http check interpolates '${%s}', which this run cannot supply".formatted(name)
                        : "http check interpolates '${%s}', which is not an interpolatable variable".formatted(name));
    }

    private HttpCheckVariableException(String reason) {
        super(reason);
        this.reason = reason;
    }

    /** The reason reported as the check's {@code CannotVerify}. */
    String reason() {
        return reason;
    }
}
