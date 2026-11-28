package com.github.oinsio.gnomish.adapter.agent.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;

/**
 * What a dialect made of a schema-enforced round's final reply (FR2, FR3, FR4, FR19 of
 * extract-agent-round-runner; design D15 part 2): the contract answer the reply states, no answer, or
 * a reply that does not match its schema. The dialect writes nothing: the runner writes a {@link
 * Replied} answer to the round's answer path and reads it back through the one answer read, which
 * judges it against the contract as it judges a file the agent wrote; {@link Malformed} it reports
 * as a malformed answer — never as a pass.
 *
 * <p>Implements FR2, FR4, FR19 of extract-agent-round-runner.
 */
public sealed interface DialectAnswer {

    /**
     * The reply states an answer.
     *
     * @param document the contract answer document derived from the schema-valid reply, as the
     *     runner writes it to the answer path; never null
     */
    record Replied(JsonNode document) implements DialectAnswer {}

    /** The reply states no answer: a {@code transform} round that asks nothing, or no result to read. */
    record NoReply() implements DialectAnswer {}

    /**
     * The stream's final output does not match the answer's schema.
     *
     * @param reason why, in the family of the text it judged; never null
     */
    record Malformed(UntrustedText reason) implements DialectAnswer {}
}
