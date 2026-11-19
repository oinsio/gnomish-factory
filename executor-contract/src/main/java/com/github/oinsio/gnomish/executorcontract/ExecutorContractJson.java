package com.github.oinsio.gnomish.executorcontract;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.oinsio.gnomish.pendingkey.PendingKey;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.Map;

/**
 * The one JSON binding of the executor contract: every read and write of a request, result, answer
 * or describe document goes through the reader and writer this class hands out, so the contract's
 * evolution rules hold for every reader alike.
 *
 * <p><strong>Additive evolution.</strong> A field the reader does not know is dropped, never an
 * error ({@code FAIL_ON_UNKNOWN_PROPERTIES} off): a newer executor may write a field this factory
 * does not know, and an executor built against an older contract reads a request with a field added
 * since. Fields are never renamed, retyped or removed within a protocol version, so dropping an
 * unknown one loses nothing the reader could have used.
 *
 * <p><strong>A required number is never guessed.</strong> A primitive the schema marks required
 * (the describe's {@code protocolVersion} and {@code reportsUsage}, a model's four token counts, a
 * tool's call count) carries {@code @JsonProperty(required = true)} on its DTO, so an absent one
 * fails the read instead of reading as {@code 0} or {@code false}; a JSON {@code null} for any
 * primitive fails it too ({@code FAIL_ON_NULL_FOR_PRIMITIVES} on). Every optional number is a boxed,
 * nullable type, so neither rule touches an optional field. A document that fails here is malformed:
 * its reader reports it as such, never as a zero that would read as "nothing used" or "no usage
 * reported".
 *
 * <p><strong>Text an executor wrote is a carrier.</strong> A field typed {@link UntrustedText}
 * binds from a JSON string in the capture family of the file it was read from; only {@link
 * ResultFileReader} names that family, so such a field read through this reader directly fails
 * the read (FR6, NFR-S2). Written, a carrier is the JSON string it was read from.
 *
 * <p><strong>A pending key is written as its value.</strong> The {@link PendingKey} of the
 * dependency-free {@code :pendingkey} leaf carries no JSON binding, so this binding registers its
 * serializer: a key is the bare JSON string of its value. No deserializer is registered — a result's
 * key is read from the written string by {@link ResultReading}, the one reader that judges it
 * (design D20 of migrate-verify-to-executor-contract).
 *
 * <p>The reader and writer are immutable; the mapper behind them is never handed out, so no caller
 * can reconfigure the binding for another.
 *
 * <p>Implements FR4, FR6, NFR-R3, NFR-S2 of define-executor-contract; FR2 of
 * migrate-verify-to-executor-contract.
 */
public final class ExecutorContractJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .addModule(new SimpleModule("untrusted-text")
                    .addDeserializer(UntrustedText.class, new UntrustedTextDeserializer())
                    .addSerializer(UntrustedText.class, new UntrustedTextSerializer()))
            .addModule(new SimpleModule("pending-key").addSerializer(PendingKey.class, new PendingKeySerializer()))
            .build();

    private static final TypeReference<Map<String, Object>> VALUES = new TypeReference<>() {};

    private static final ObjectReader READER = MAPPER.reader();

    private static final ObjectWriter WRITER = MAPPER.writer();

    private ExecutorContractJson() {}

    /**
     * The contract's reader; {@code reader().forType(ExecutorResult.class)} reads a result.
     *
     * @return the immutable reader every contract document is read through
     */
    public static ObjectReader reader() {
        return READER;
    }

    /**
     * The contract's writer.
     *
     * @return the immutable writer every contract document is written through
     */
    public static ObjectWriter writer() {
        return WRITER;
    }

    /**
     * The law's settings for an executor — plain JDK values as the law loader maps them — as the
     * JSON tree a request carries and the settings schema judges ({@link SettingsSchema}): one
     * conversion, so the tree a schema accepted is the tree a request sends.
     *
     * @param settings the settings as plain JDK values; never null
     * @return the settings tree, a fresh object node; never null
     */
    public static ObjectNode settingsTree(Map<String, ?> settings) {
        return MAPPER.valueToTree(settings);
    }

    /**
     * The inverse of {@link #settingsTree}: a request's settings tree as the plain JDK values a
     * built-in executor reads them as — maps, lists, strings, numbers, booleans and {@code null} —
     * so an in-process built-in reads its settings as the law loader mapped them (design D18 of
     * migrate-verify-to-executor-contract).
     *
     * @param settings a request's settings tree; never null
     * @return the settings as plain values, a fresh map; never null
     */
    public static Map<String, Object> settingsValues(ObjectNode settings) {
        return MAPPER.convertValue(settings, VALUES);
    }

    /**
     * An answer as the JSON document a result carries in its answer slot ({@link
     * ExecutorResult#answer()}), written by the contract's one answer writer — the document an
     * external executor would have written, so an in-process built-in's answer is read back by
     * {@link Answer#read} exactly as a program's is (FR1 of migrate-verify-to-executor-contract).
     *
     * @param answer the answer; never null
     * @return the answer document, a fresh tree; never null
     */
    public static JsonNode answerTree(Answer answer) {
        return MAPPER.valueToTree(answer);
    }
}
