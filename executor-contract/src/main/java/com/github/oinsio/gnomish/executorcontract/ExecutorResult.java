package com.github.oinsio.gnomish.executorcontract;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The outcome of one executor run: a status from the closed taxonomy, an optional answer, the usage
 * and the denials the run met. Wire shape ({@code executor-result.schema.json}): {@code {"status",
 * "failureClass"?, "pendingKey"?, "reason"?, "answer"?, "usage", "denials"?}}; {@code failureClass}
 * is present exactly when the status is {@code failed}, {@code pendingKey} exactly when it is
 * {@code pending}.
 *
 * <p>The answer is carried as the JSON document the executor wrote, unread: its kinds and their
 * malformed cases are the answer type's to judge, so a malformed answer never fails the read of the
 * result that carries it. {@link Answer#read} is the typed reading of it; the result does not apply
 * it itself, since what a malformed answer means depends on the role the caller asked for.
 *
 * <p>The executor's free text — the {@code reason} and every text field of a {@link Denial} — is an
 * {@link UntrustedText}, minted in the family of the file it was read from; such a result is read
 * through {@link ResultFileReader} only (FR6, NFR-S2).
 *
 * <p>A result is read through {@link ResultReading}, which applies the unknown-token arms of {@link
 * Status} and {@link FailureClass}: a status the taxonomy lacks, a {@code failed} with no known
 * class and a {@code pending} with no key each read as {@code failed/infrastructure} with a reason
 * naming what was wrong.
 *
 * <p>Implements FR1, FR2, FR6, NFR-R3, NFR-S2 of define-executor-contract.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonDeserialize(converter = ResultReading.class)
public record ExecutorResult(
        Status status,
        @Nullable FailureClass failureClass,
        @Nullable PendingKey pendingKey,
        @Nullable UntrustedText reason,
        @Nullable JsonNode answer,
        Usage usage,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Denial> denials) {

    /** Refuses a class without {@code failed}, a key without {@code pending}, and their absence there. */
    public ExecutorResult {
        if ((status == Status.FAILED) != (failureClass != null)) {
            throw new IllegalArgumentException("failureClass is present exactly when the status is failed");
        }
        if ((status == Status.PENDING) != (pendingKey != null)) {
            throw new IllegalArgumentException("pendingKey is present exactly when the status is pending");
        }
        denials = List.copyOf(denials);
    }
}
