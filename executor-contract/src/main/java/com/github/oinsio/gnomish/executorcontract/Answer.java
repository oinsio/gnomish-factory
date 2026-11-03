package com.github.oinsio.gnomish.executorcontract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The structured answer a result may carry in its answer slot ({@code executor-answer.schema.json}):
 * a {@link Verdict}, a {@link Select} or a {@link DecisionRequest}. Wire shape: {@code {"kind":
 * "verdict" | "select" | "decision-request", ...}}.
 *
 * <p>A result carries its answer unread ({@link ExecutorResult#answer()}), so a malformed answer
 * never fails the read of the result; {@link #read(JsonNode, Function)} is the one way the written document
 * becomes a typed answer, and it reports a malformed one as {@link AnswerReading.Malformed} with a
 * reason, never as an answer. A malformed answer is therefore never read as a pass. Unknown-token
 * arm: an unknown kind is a malformed answer. Jackson reads and writes the type through the same
 * reading ({@link AnswerJson}); there a malformed answer is a mapping error, and the family is the
 * one {@link ResultFileReader} opened the read with, so an answer read anywhere else fails.
 *
 * <p>Implements FR3, NFR-S2 of define-executor-contract.
 */
@JsonSerialize(using = AnswerJson.Writer.class)
@JsonDeserialize(using = AnswerJson.Reader.class)
public sealed interface Answer {

    /**
     * The answer {@code written} states, or why it is malformed; never throws on a written document.
     * Every text the answer carries, and the reason of a malformed one, is minted by {@code mint} —
     * the family of the file the answer was read from (NFR-S2).
     *
     * @param written the answer document as the executor wrote it
     * @param mint the capture family of its writer, e.g. {@code UntrustedText::agent}
     * @return the reading; never null
     */
    static AnswerReading read(JsonNode written, Function<String, UntrustedText> mint) {
        return AnswerReader.read(written, mint);
    }

    /**
     * Findings as a SARIF 2.1.0 log, reduced to what the engine reads: each run's tool and its
     * results. The executor declares no pass: the engine derives it from the result levels against
     * the check's threshold ({@link SarifLevel#passes}). Capped: a log of more than {@link
     * #MAX_RESULTS} results is refused, so the cap never drops a result the derivation needs.
     */
    record Verdict(List<SarifRun> runs) implements Answer {

        /** The only SARIF version the contract reads. */
        public static final String SARIF_VERSION = "2.1.0";

        /** The most results one verdict may carry, over all its runs. */
        public static final int MAX_RESULTS = 1000;

        /** Refuses a log of more than {@link #MAX_RESULTS} results. */
        public Verdict {
            runs = List.copyOf(runs);
            VerdictCap.check(runs);
        }
    }

    /** The option chosen among those the request offered, with an optional ranking and rationale. */
    record Select(
            UntrustedText chosen,
            List<UntrustedText> ranking,
            @Nullable UntrustedText rationale) implements Answer {

        /** Refuses a blank choice or a blank ranked option. */
        public Select {
            WireChecks.nonBlank(chosen, "select.chosen");
            ranking = List.copyOf(ranking);
            WireChecks.nonBlankTexts(ranking, "select.ranking[]");
        }
    }

    /**
     * A question the executor cannot answer itself, with at least two options and an optional
     * continuation key the reply resumes by. The question and options are shown to the human who
     * answers, so they are carriers; the key is an opaque token handed back to the same executor in
     * a later request, never shown or parsed, so it stays a string.
     */
    record DecisionRequest(
            UntrustedText question,
            List<UntrustedText> options,
            @Nullable String continuationKey) implements Answer {

        /** Refuses a blank question or option, a blank key, and fewer than two options. */
        public DecisionRequest {
            WireChecks.nonBlank(question, "decision-request.question");
            options = List.copyOf(options);
            WireChecks.nonBlankTexts(options, "decision-request.options[]");
            WireChecks.atLeastItems(options, 2, "decision-request.options");
            WireChecks.nonBlankOrAbsent(continuationKey, "decision-request.continuationKey");
        }
    }
}
