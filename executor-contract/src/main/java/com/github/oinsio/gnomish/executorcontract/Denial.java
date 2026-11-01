package com.github.oinsio.gnomish.executorcontract;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import org.jspecify.annotations.Nullable;

/**
 * One denial a run met — an egress or policy refusal — as its result reports it. Wire shape:
 * {@code {"message", "location"?, "details"?}}. Every field is the executor's own text, so each is a
 * carrier (FR6, NFR-S2).
 *
 * <p>Implements FR1, FR6, NFR-S2 of define-executor-contract.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Denial(
        UntrustedText message,
        @Nullable UntrustedText location,
        @Nullable UntrustedText details) {}
