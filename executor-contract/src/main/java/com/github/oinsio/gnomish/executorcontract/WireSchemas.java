package com.github.oinsio.gnomish.executorcontract;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectReader;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.networknt.schema.resource.DisallowSchemaLoader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The one place the executor contract's four JSON Schema files are loaded, and the one judge of a
 * raw document against one of them (FR1, FR24 of define-executor-contract; design D19): the
 * conformance kit judges every document a candidate writes, and every request the factory renders,
 * on the text the factory read or wrote, independently of the readers' lenient unknown-token arms;
 * the schema round-trip spec holds the DTOs against the same loaded files.
 *
 * <p>The files are read once, from {@code schemas/} on this module's classpath, and each is
 * registered under its own {@code $id}, so the result's {@code $ref} to the answer schema resolves
 * to the loaded answer file. Nothing else is fetched: the factory has the same shape as {@link
 * SettingsSchema}'s, a loader that refuses every other schema behind the four, so no file can make
 * the factory reach a host. The validator's messages are rendered in its root locale.
 *
 * <p>The judged text is the executor's, so every message is an {@link UntrustedText} of the
 * subprocess family. A text that is no single JSON value — empty, cut, or followed by more — is one
 * violation of keyword {@link WireViolation#NOT_JSON}: no schema keyword could judge it; the parser's
 * words quote the text and are of the subprocess family too, the empty text's are the factory's.
 *
 * <p>Immutable after class initialisation; no I/O after it.
 *
 * <p>Implements FR1, FR24 of define-executor-contract.
 */
public final class WireSchemas {

    private static final ObjectReader STRICT_READER =
            ExecutorContractJson.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private static final SchemaValidatorsConfig CONFIG =
            SchemaValidatorsConfig.builder().locale(Locale.ROOT).build();

    private static final Map<WireDocument, JsonNode> DOCUMENTS = new EnumMap<>(WireDocument.class);

    private static final Map<WireDocument, JsonSchema> SCHEMAS = new EnumMap<>(WireDocument.class);

    static {
        Map<String, String> texts = new HashMap<>();
        Map<WireDocument, String> ids = new EnumMap<>(WireDocument.class);
        for (WireDocument kind : WireDocument.values()) {
            String text = resource(kind);
            JsonNode document = tree(text);
            String id = document.path("$id").asText();
            DOCUMENTS.put(kind, document);
            ids.put(kind, id);
            texts.put(id, text);
        }
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(
                SpecVersion.VersionFlag.V202012,
                builder -> builder.schemaLoaders(
                        loaders -> loaders.schemas(texts).add(DisallowSchemaLoader.getInstance())));
        ids.forEach((kind, id) -> {
            JsonSchema schema = factory.getSchema(SchemaLocation.of(id), CONFIG);
            schema.initializeValidators();
            SCHEMAS.put(kind, schema);
        });
    }

    private WireSchemas() {}

    /**
     * Judges {@code text} against the schema file of {@code kind}.
     *
     * @param kind which document the text is; never null
     * @param text the document as read or rendered, unparsed; never null
     * @return every violation, in the validator's evaluation order; empty when the schema accepts
     *     the document
     */
    public static List<WireViolation> judge(WireDocument kind, String text) {
        JsonNode document;
        try {
            document = STRICT_READER.readTree(text);
        } catch (JsonProcessingException notJson) {
            return List.of(notJson(UntrustedText.subprocess(String.valueOf(notJson.getOriginalMessage()))));
        }
        if (document == null || document.isMissingNode()) {
            return List.of(notJson(UntrustedText.factory("the document is empty")));
        }
        List<WireViolation> violations = new ArrayList<>();
        for (ValidationMessage message :
                Objects.requireNonNull(SCHEMAS.get(kind)).validate(document)) {
            violations.add(new WireViolation(
                    ViolationPath.of(message), message.getType(), UntrustedText.subprocess(message.getMessage())));
        }
        return List.copyOf(violations);
    }

    /**
     * The schema file of {@code kind} as loaded, for a reader of the file itself — its {@code $id},
     * its examples.
     *
     * @param kind which schema; never null
     * @return a copy of the parsed file, which the caller may change freely; never null
     */
    public static JsonNode document(WireDocument kind) {
        return Objects.requireNonNull(DOCUMENTS.get(kind)).deepCopy();
    }

    private static WireViolation notJson(UntrustedText reason) {
        return new WireViolation("", WireViolation.NOT_JSON, reason);
    }

    private static String resource(WireDocument kind) {
        String name = "/schemas/" + kind.schemaFile();
        try (InputStream stream = Objects.requireNonNull(
                WireSchemas.class.getResourceAsStream(name), () -> name + " is not on the classpath")) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static JsonNode tree(String text) {
        try {
            return STRICT_READER.readTree(text);
        } catch (JsonProcessingException malformed) {
            throw new IllegalStateException("a schema file of the executor contract is no JSON", malformed);
        }
    }
}
