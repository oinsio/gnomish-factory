package com.github.oinsio.gnomish.executorcontract;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.github.oinsio.gnomish.pendingkey.PendingKey;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The outcome of one executor run: a status from the closed taxonomy, an optional answer, the usage
 * and the denials the run met. Wire shape ({@code executor-result.schema.json}): {@code {"status",
 * "failureClass"?, "pendingKey"?, "reason"?, "answer"?, "usage", "denials"?, "pollAfterMillis"?,
 * "pendingState"?, "details"?}}; {@code failureClass} is present exactly when the status is {@code
 * failed}, {@code pendingKey} exactly when it is {@code pending}.
 *
 * <p>A {@code pending} result may carry the two facts the engine decides on (design D19 of
 * migrate-verify-to-executor-contract): {@code pollAfterMillis}, the <em>wait hint</em> — a relative
 * lower bound on the next poll, from 0 to {@link #MAX_POLL_AFTER_MILLIS} — and {@code pendingState},
 * the <em>pending state</em> its timeout is classified by. Neither is allowed on another status, and
 * the constructor refuses both there and a hint out of range: an in-process built-in's result is
 * never serialized, so the constructor is where the rule binds it, as {@link ResultReading} binds it
 * on the wire. {@code details} is the text a result carries apart from its {@code reason} (a log
 * tail, an endpoint's answer); like the reason it is allowed on any status, and the engine reads it
 * for a {@code failed} or {@code pending} result only.
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
 * <p>Implements FR1, FR2, FR6, NFR-R3, NFR-S2 of define-executor-contract; FR2, FR5, NFR-R2 of
 * migrate-verify-to-executor-contract.
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
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Denial> denials,
        @Nullable Long pollAfterMillis,
        @Nullable PendingState pendingState,
        @Nullable UntrustedText details) {

    /** The longest wait hint a result may carry: one day. */
    public static final long MAX_POLL_AFTER_MILLIS = 86_400_000L;

    /**
     * Refuses a class without {@code failed}, a key without {@code pending}, and their absence there;
     * a wait hint or a pending state on any status but {@code pending}; and a wait hint outside 0 to
     * {@link #MAX_POLL_AFTER_MILLIS}.
     */
    public ExecutorResult {
        if ((status == Status.FAILED) != (failureClass != null)) {
            throw new IllegalArgumentException("failureClass is present exactly when the status is failed");
        }
        if ((status == Status.PENDING) != (pendingKey != null)) {
            throw new IllegalArgumentException("pendingKey is present exactly when the status is pending");
        }
        if (status != Status.PENDING && (pollAfterMillis != null || pendingState != null)) {
            throw new IllegalArgumentException(
                    "pollAfterMillis and pendingState are present only when the status is pending");
        }
        if (pollAfterMillis != null && WireChecks.outOfHintRange(pollAfterMillis)) {
            throw new IllegalArgumentException(
                    "pollAfterMillis is 0 to " + MAX_POLL_AFTER_MILLIS + ", got " + pollAfterMillis);
        }
        denials = List.copyOf(denials);
    }

    /**
     * A result with no wait hint, no pending state and no details: what a run that has none of them
     * reports.
     */
    public ExecutorResult(
            Status status,
            @Nullable FailureClass failureClass,
            @Nullable PendingKey pendingKey,
            @Nullable UntrustedText reason,
            @Nullable JsonNode answer,
            Usage usage,
            List<Denial> denials) {
        this(status, failureClass, pendingKey, reason, answer, usage, denials, null, null, null);
    }

    // @DoNotMutate, JVMTI redefinition limit (testing.md): PIT's "replaced return value with null"
    // mutant of this wither ends its minion with "class redefinition failed: attempted to change the
    // class ... Record ... attribute" (RUN_ERROR, hcoles/pitest#1285), so no test can observe it. The
    // wither holds no decision; PendingFactsReadSpec and ProgramResultFactsSpec pin every field kept.
    /**
     * This result with {@code replacement} as its answer, every other field kept.
     *
     * @param replacement the answer document, or {@code null} for none
     * @return the result; never null
     */
    @DoNotMutate
    public ExecutorResult withAnswer(@Nullable JsonNode replacement) {
        return new ExecutorResult(
                status,
                failureClass,
                pendingKey,
                reason,
                replacement,
                usage,
                denials,
                pollAfterMillis,
                pendingState,
                details);
    }

    // @DoNotMutate, JVMTI redefinition limit (testing.md): the same RUN_ERROR as withAnswer's for the
    // same mutant; the wither holds no decision, and the same two specs pin every field kept.
    /**
     * This result with {@code replacement} as its usage, every other field kept.
     *
     * @param replacement the usage; never null
     * @return the result; never null
     */
    @DoNotMutate
    public ExecutorResult withUsage(Usage replacement) {
        return new ExecutorResult(
                status,
                failureClass,
                pendingKey,
                reason,
                answer,
                replacement,
                denials,
                pollAfterMillis,
                pendingState,
                details);
    }
}
