package com.github.oinsio.gnomish.executorcontract;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A result document exactly as the executor wrote it, its tokens still unread: the intermediate
 * Jackson binds before {@link ResultReading} turns it into an {@link ExecutorResult}. Every field
 * may be absent, since a reader cannot trust a writer to follow the schema. The two pending facts
 * are bound as written nodes, so a hint that is no integer and a state that is no string reach the
 * reading's own arms rather than failing the bind.
 *
 * <p>Implements FR2, NFR-R3 of define-executor-contract; FR2, FR5 of
 * migrate-verify-to-executor-contract.
 */
record WrittenResult(
        @Nullable String status,
        @Nullable String failureClass,
        @Nullable String pendingKey,
        @Nullable UntrustedText reason,
        @Nullable JsonNode answer,
        @Nullable Usage usage,
        @Nullable List<Denial> denials,
        @Nullable JsonNode pollAfterMillis,
        @Nullable JsonNode pendingState,
        @Nullable UntrustedText details) {}
