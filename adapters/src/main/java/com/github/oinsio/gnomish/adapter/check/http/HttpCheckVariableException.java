package com.github.oinsio.gnomish.adapter.check.http;

import java.io.Serial;

/**
 * Raised when an http check's request cannot be composed because a {@code ${...}} reference in it has
 * no value (NFR-S2 of add-plugin-architecture): either the variable is outside the engine-defined
 * allowlist — which the load seam normally catches, so reaching here means a check bypassed it — or
 * it is allowlisted but this run cannot supply it (a manual run over a plain directory has no attempt
 * commit). The message names the reference, never a resolved value — the only text it carries is the
 * name the manifest itself wrote inside {@code ${...}}.
 *
 * <p>One of the two {@link HttpCheckRequestException} failures, which states the shared fail-closed
 * outcome: either way this check reports {@code CannotVerify}, because a URL built from a missing
 * value addresses something other than what the check meant to observe, and a confident verdict about
 * the wrong thing is worse than no verdict.
 *
 * <p>Implements NFR-S2 of add-plugin-architecture.
 */
final class HttpCheckVariableException extends HttpCheckRequestException {

    @Serial
    private static final long serialVersionUID = 1L;

    HttpCheckVariableException(String name, boolean allowlisted) {
        super(
                allowlisted
                        ? "http check interpolates '${%s}', which this run cannot supply".formatted(name)
                        : "http check interpolates '${%s}', which is not an interpolatable variable".formatted(name));
    }
}
