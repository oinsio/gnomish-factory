package com.github.oinsio.gnomish.executorcontract;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.networknt.schema.resource.DisallowSchemaLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The one judge of a law's settings against the JSON Schema an executor's describe declared (FR9
 * of define-executor-contract; design D5, phase b): the describe preflight and the conformance kit
 * read a settings schema through this class alone, so both accept exactly the same settings.
 *
 * <p>The schema is the program's text, so it is judged as untrusted input. A schema without a
 * {@code $schema} is read as draft 2020-12, the draft the contract's own schema files declare; the
 * drafts the validator ships (4, 6, 7, 2019-09, 2020-12) are known by their identifiers. Nothing is
 * fetched: every {@code $ref} that leaves the document — a URL, a file, a classpath resource — and
 * every meta-schema the validator does not ship makes the schema {@linkplain
 * SettingsJudgement.Unusable unusable}, so a describe answer can neither make the factory reach a
 * host nor read one of its own files. A local {@code $ref} ({@code #/$defs/…}) resolves within the
 * document. The validator's messages are rendered in its root locale, the same words on every
 * machine. The answer that carries the schema was already capped at the describe read.
 *
 * <p>Pure: no state, no I/O.
 *
 * <p>Implements FR9, FR12, UX1, NFR-S2 of define-executor-contract.
 */
public final class SettingsSchema {

    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(
            SpecVersion.VersionFlag.V202012,
            builder -> builder.schemaLoaders(loaders -> loaders.add(DisallowSchemaLoader.getInstance())));

    private static final SchemaValidatorsConfig CONFIG =
            SchemaValidatorsConfig.builder().locale(Locale.ROOT).build();

    private SettingsSchema() {}

    /**
     * Judges {@code settings} against {@code schema}.
     *
     * @param schema the settings schema a describe answer declared; never null
     * @param settings the law's settings for the executor, as plain JDK values; never null
     * @return the judgement; never null
     */
    public static SettingsJudgement judge(JsonNode schema, Map<String, ?> settings) {
        Set<ValidationMessage> messages;
        try {
            messages = FACTORY.getSchema(schema, CONFIG).validate(ExecutorContractJson.settingsTree(settings));
        } catch (RuntimeException unusable) {
            // The validator compiles a schema lazily, so a refused `$ref`, an unknown meta-schema and
            // an uncompilable keyword value (a `pattern` that is no regular expression) surface while
            // validating, as a JsonSchemaException or as whatever the keyword's own parser throws:
            // the set is not closed, and every member means the same thing — the program's schema
            // cannot judge.
            return new SettingsJudgement.Unusable(UntrustedText.subprocess(String.valueOf(unusable.getMessage())));
        }
        if (messages.isEmpty()) {
            return new SettingsJudgement.Accepted();
        }
        List<SettingsViolation> violations = new ArrayList<>();
        for (ValidationMessage message : messages) {
            violations.add(new SettingsViolation(
                    ViolationPath.of(message), message.getType(), UntrustedText.subprocess(message.getError())));
        }
        return new SettingsJudgement.Rejected(violations);
    }
}
