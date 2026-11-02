package com.github.oinsio.gnomish.executorcontract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.util.StdConverter;
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
 * reason. A field another status carries is dropped; an absent
 * usage reads as {@link UsageProvenance#ABSENT}, absent denials as none, and a JSON {@code null}
 * answer as no answer. The answer itself is carried unread.
 *
 * <p>A reason this reading composes is the factory's sentence: an unread token it names is quoted
 * through the log exit's rendering ({@link TextSafety#forLog}), and the executor's own reason is
 * appended through its carrier's log exit, so the composed reason is a factory carrier holding no
 * raw executor text (NFR-S2).
 *
 * <p>Implements FR2, NFR-R3, NFR-S2 of define-executor-contract.
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
        if (known.get() == Status.COMPLETED) {
            return carried.as(Status.COMPLETED, null, null);
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
        return carried.as(Status.PENDING, null, new PendingKey(key));
    }

    private static ExecutorResult failed(@Nullable String token, Carried carried) {
        Optional<FailureClass> known = token == null ? Optional.empty() : FailureClass.fromWire(token);
        if (known.isEmpty()) {
            return carried.infrastructure(
                    token == null
                            ? "status failed without a failureClass"
                            : "unknown failureClass '" + quoted(token) + "'");
        }
        return carried.as(Status.FAILED, known.get(), null);
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

        Carried(WrittenResult written) {
            JsonNode writtenAnswer = written.answer();
            Usage writtenUsage = written.usage();
            List<Denial> writtenDenials = written.denials();
            this.reason = written.reason();
            this.answer = writtenAnswer == null || writtenAnswer.isNull() ? null : writtenAnswer;
            this.usage = writtenUsage == null ? Usage.absent() : writtenUsage;
            this.denials = writtenDenials == null ? List.of() : writtenDenials;
        }

        ExecutorResult as(Status status, @Nullable FailureClass failureClass, @Nullable PendingKey pendingKey) {
            return new ExecutorResult(status, failureClass, pendingKey, reason, answer, usage, denials);
        }

        ExecutorResult infrastructure(String why) {
            UntrustedText named = UntrustedText.factory(reason == null ? why : why + ": " + reason.forLog());
            return new ExecutorResult(Status.FAILED, FailureClass.INFRASTRUCTURE, null, named, answer, usage, denials);
        }
    }
}
