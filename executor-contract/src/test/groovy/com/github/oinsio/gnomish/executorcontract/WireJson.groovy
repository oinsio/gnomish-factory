package com.github.oinsio.gnomish.executorcontract

import com.fasterxml.jackson.databind.JsonNode
import com.github.oinsio.gnomish.untrustedtext.UntrustedText

/**
 * The specs' spelling of the contract's one JSON binding: every production read or write a spec
 * exercises goes through {@link ExecutorContractJson}, never through a mapper of the spec's own, so a
 * spec sees the binding production uses (FR4, NFR-R3 of define-executor-contract). A typed read is
 * opened exactly as {@link ResultFileReader} opens one, with the agent family, so a document type
 * whose text fields are carriers binds here as it does in a result read (FR6, NFR-S2).
 */
final class WireJson {

    private static final ResultFileReader OWNER = new ResultFileReader(ResultFileReader.MAX_CAP_BYTES, UntrustedText.&agent)

    private WireJson() {}

    static <T> T readValue(String json, Class<T> type) {
        OWNER.binding(type).readValue(json)
    }

    static JsonNode readTree(String json) {
        ExecutorContractJson.reader().readTree(json)
    }

    static JsonNode readTree(InputStream stream) {
        stream.withCloseable { ExecutorContractJson.reader().readTree(it) }
    }

    static <T> T treeToValue(JsonNode node, Class<T> type) {
        OWNER.binding(type).readValue(node)
    }

    static String writeValueAsString(Object value) {
        ExecutorContractJson.writer().writeValueAsString(value)
    }

    static JsonNode valueToTree(Object value) {
        readTree(writeValueAsString(value))
    }

    /** The typed reading of an answer, in the agent family. */
    static AnswerReading answer(JsonNode written) {
        Answer.read(written, UntrustedText.&agent)
    }

    /** A carrier for an expected value; carriers compare by text alone. */
    static UntrustedText t(String text) {
        UntrustedText.agent(text)
    }
}
