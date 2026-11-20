package com.github.oinsio.gnomish.executorcontract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.util.StdConverter;
import com.github.oinsio.gnomish.pendingkey.PendingKey;
import com.github.oinsio.gnomish.untrustedtext.TextSafety;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * How a written result becomes an {@link ExecutorResult}: the unknown-token arms of the status
 * taxonomy. A status the taxonomy lacks (or none), a {@code failed} with no known class and a
 * {@code pending} with no usable key (blank, or one {@link PendingKey#unusable} names) each read as
 * {@code failed/infrastructure}, with a reason naming what was wrong ahead of the executor's own
 * reason. A {@code failureClass} or {@code pendingKey} another status carries is dropped; an absent
 * usage reads as {@link UsageProvenance#ABSENT}, absent denials as none, and a JSON {@code null}
 * answer as no answer. The answer and the {@code details} are carried unread.
 *
 * <p>The pending facts fail closed, as a key does (design D19 of
 * migrate-verify-to-executor-contract): a {@code pollAfterMillis} or a {@code pendingState} on a
 * status other than {@code pending}, and a {@code pollAfterMillis} that is no integer from 0 to
 * {@link ExecutorResult#MAX_POLL_AFTER_MILLIS}, read as {@code failed/infrastructure} naming the
 * field. A {@code pendingState} token outside {@link PendingState} — or no string at all — reads as
 * an absent state.
 *
 * <p>A reason this reading composes is the factory's sentence: an unread token it names is quoted
 * through the log exit's rendering ({@link TextSafety#forLog}), and the executor's own reason is
 * appended through its carrier's log exit, so the composed reason is a factory carrier holding no
 * raw executor text (NFR-S2).
 *
 * <p>Implements FR2, NFR-R3, NFR-S2 of define-executor-contract; FR2, NFR-R2 of
 * migrate-verify-to-executor-contract.
 */
final class ResultReading extends StdConverter<WrittenResult, ExecutorResult> {

    /** How much of an unread token a composed reason quotes. */
    static final int TOKEN_QUOTE_CAP_CHARS = 200;

    @Override
    public ExecutorResult convert(WrittenResult written) {
        Carried carried = new Carried(written);
        String status = written.status();
        Optional<Status> known = status == null ? Optional.empty() : Status.fromWire(status);
        if (known.isEmpty()) {
            return carried.infrastructure(
                    status == null ? "the result has no status" : "unknown status '" + quoted(status) + "'");
        }
        if (known.get() != Status.PENDING) {
            String misplaced = given(written.pollAfterMillis()) != null
                    ? "pollAfterMillis"
                    : given(written.pendingState()) != null ? "pendingState" : null;
            if (misplaced != null) {
                return carried.infrastructure(misplaced + " on a " + known.get().wire() + " result");
            }
        }
        if (known.get() == Status.COMPLETED) {
            return carried.as(Status.COMPLETED, null);
        }
        if (known.get() == Status.FAILED) {
            return failed(written.failureClass(), carried);
        }
        String key = written.pendingKey();
        if (key == null || key.isBlank()) {
            return carried.infrastructure("status pending without a pendingKey");
        }
        String unusable = PendingKey.unusable(key);
        if (unusable != null) {
            return carried.infrastructure("status pending with an unusable pendingKey: " + unusable);
        }
        JsonNode hint = given(written.pollAfterMillis());
        Long pollAfterMillis = null;
        if (hint != null) {
            if (!hint.isIntegralNumber() || !hint.canConvertToLong() || WireChecks.outOfHintRange(hint.longValue())) {
                return carried.infrastructure("pollAfterMillis is not an integer from 0 to "
                        + ExecutorResult.MAX_POLL_AFTER_MILLIS + ": " + quoted(hint.toString()));
            }
            pollAfterMillis = hint.longValue();
        }
        return carried.pending(new PendingKey(key), pollAfterMillis, state(written.pendingState()));
    }

    private static @Nullable PendingState state(@Nullable JsonNode written) {
        JsonNode token = given(written);
        if (token == null || !token.isTextual()) {
            return null;
        }
        return PendingState.fromWire(token.asText()).orElse(null);
    }

    /** {@code node}, or {@code null} when the field is absent or written as JSON {@code null}. */
    private static @Nullable JsonNode given(@Nullable JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : node;
    }

    private static ExecutorResult failed(@Nullable String token, Carried carried) {
        Optional<FailureClass> known = token == null ? Optional.empty() : FailureClass.fromWire(token);
        if (known.isEmpty()) {
            return carried.infrastructure(
                    token == null
                            ? "status failed without a failureClass"
                            : "unknown failureClass '" + quoted(token) + "'");
        }
        return carried.as(Status.FAILED, known.get());
    }

    private static String quoted(String token) {
        return TextSafety.forLog(token, TOKEN_QUOTE_CAP_CHARS);
    }

    /** What every status reading carries over from the written document unchanged. */
    // A class, not a record: the mutation engine cannot redefine a record's methods (testing.md,
    // JVMTI limit), and this is where the reading decides.
    private static final class Carried {

        private final @Nullable UntrustedText reason;
        private final @Nullable JsonNode answer;
        private final Usage usage;
        private final List<Denial> denials;
        private final @Nullable UntrustedText details;

        Carried(WrittenResult written) {
            JsonNode writtenAnswer = written.answer();
            Usage writtenUsage = written.usage();
            List<Denial> writtenDenials = written.denials();
            this.reason = written.reason();
            this.answer = writtenAnswer == null || writtenAnswer.isNull() ? null : writtenAnswer;
            this.usage = writtenUsage == null ? Usage.absent() : writtenUsage;
            this.denials = writtenDenials == null ? List.of() : writtenDenials;
            this.details = written.details();
        }

        ExecutorResult as(Status status, @Nullable FailureClass failureClass) {
            return new ExecutorResult(status, failureClass, null, reason, answer, usage, denials, null, null, details);
        }

        ExecutorResult pending(PendingKey key, @Nullable Long pollAfterMillis, @Nullable PendingState state) {
            return new ExecutorResult(
                    Status.PENDING, null, key, reason, answer, usage, denials, pollAfterMillis, state, details);
        }

        ExecutorResult infrastructure(String why) {
            UntrustedText named = UntrustedText.factory(reason == null ? why : why + ": " + reason.forLog());
            return new ExecutorResult(
                    Status.FAILED,
                    FailureClass.INFRASTRUCTURE,
                    null,
                    named,
                    answer,
                    usage,
                    denials,
                    null,
                    null,
                    details);
        }
    }
}
