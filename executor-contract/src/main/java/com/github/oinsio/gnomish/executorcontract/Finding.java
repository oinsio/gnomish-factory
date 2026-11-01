package com.github.oinsio.gnomish.executorcontract;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

/**
 * One finding of a previous attempt, fed forward in a request. Wire shape: {@code {"message",
 * "location"?, "details"?}}.
 *
 * <p>Implements FR1 of define-executor-contract.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Finding(
        String message, @Nullable String location, @Nullable String details) {}
