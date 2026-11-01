package com.github.oinsio.gnomish.executorcontract;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A result document exactly as the executor wrote it, its tokens still unread: the intermediate
 * Jackson binds before {@link ResultReading} turns it into an {@link ExecutorResult}. Every field
 * may be absent, since a reader cannot trust a writer to follow the schema.
 *
 * <p>Implements FR2, NFR-R3 of define-executor-contract.
 */
record WrittenResult(
        @Nullable String status,
        @Nullable String failureClass,
        @Nullable String pendingKey,
        @Nullable UntrustedText reason,
        @Nullable JsonNode answer,
        @Nullable Usage usage,
        @Nullable List<Denial> denials) {}
