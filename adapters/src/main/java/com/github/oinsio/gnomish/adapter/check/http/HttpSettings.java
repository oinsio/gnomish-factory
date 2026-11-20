package com.github.oinsio.gnomish.adapter.check.http;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import com.github.oinsio.gnomish.executorcontract.SettingsJudgement;
import com.github.oinsio.gnomish.executorcontract.SettingsSchema;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * The settings of the {@code http} built-in (FR6 of migrate-verify-to-executor-contract, design D4:
 * the params validator becomes a describe schema): the describe's settings schema, stating the
 * shape of the former {@code params} — {@code url}, {@code method}, {@code headers}, {@code auth},
 * {@code pass-when}, {@code pending-when} — and the run's own refusal of settings it cannot judge
 * with.
 *
 * <p>The run refuses whatever the schema refuses, judged by the contract's one judge ({@link
 * SettingsSchema}), and beyond it what a schema cannot state — a relative url, a disallowed {@code
 * ${…}} reference, an inline authorization header, an uncompilable pattern — by the same {@link
 * HttpCheckParamsValidator} the load seam runs. So a setting the describe preflight refuses never
 * reaches a request, and a setting the run accepts the preflight accepts too.
 *
 * <p>Implements FR6 of migrate-verify-to-executor-contract.
 */
final class HttpSettings {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private static final String TYPE = "type";
    private static final String STRING = "string";
    private static final String OBJECT = "object";
    private static final String PROPERTIES = "properties";
    private static final String ADDITIONAL = "additionalProperties";

    private HttpSettings() {}

    /**
     * The settings schema the describe declares.
     *
     * @return a fresh schema tree, so no holder shares another's; never null
     */
    static ObjectNode schema() {
        ObjectNode schema = closedObject();
        schema.putArray("required").add(HttpCheckParams.URL_KEY);
        ObjectNode properties = schema.putObject(PROPERTIES);
        properties.putObject(HttpCheckParams.URL_KEY).put(TYPE, STRING).put("minLength", 1);
        properties.putObject(HttpCheckParams.METHOD_KEY).put(TYPE, STRING);
        ObjectNode headers = properties.putObject(HttpCheckParams.HEADERS_KEY).put(TYPE, OBJECT);
        headers.putObject(ADDITIONAL).putArray(TYPE).add(STRING).add("number").add("boolean");
        properties.set(
                HttpCheckParams.AUTH_KEY,
                strings(
                        HttpCheckParams.Auth.CREDENTIAL_KEY,
                        HttpCheckParams.Auth.HEADER_KEY,
                        HttpCheckParams.Auth.SCHEME_KEY));
        properties.set(HttpCheckParams.PASS_WHEN_KEY, condition());
        properties.set(HttpCheckParams.PENDING_WHEN_KEY, condition());
        return schema;
    }

    /**
     * Why the run cannot judge with {@code settings}, or {@code null} when it can.
     *
     * @param settings the request's settings as plain values; never null
     * @return the refusal, quoting the settings' own keys; or {@code null}
     */
    static @Nullable UntrustedText refusal(Map<String, Object> settings) {
        if (SettingsSchema.judge(schema(), settings) instanceof SettingsJudgement.Rejected rejected) {
            return UntrustedText.manifest(HttpExecutor.NAME + " settings break its schema: "
                    + rejected.violations().stream()
                            .map(violation -> violation.path() + " (" + violation.rule() + ")")
                            .collect(Collectors.joining("; ")));
        }
        List<ConfigError> errors = new HttpCheckParamsValidator().validate(HttpExecutor.NAME, "settings", settings);
        if (errors.isEmpty()) {
            return null;
        }
        return UntrustedText.manifest(HttpExecutor.NAME + " settings are malformed: "
                + errors.stream()
                        .map(error -> error.where() + " " + error.message())
                        .collect(Collectors.joining("; ")));
    }

    /** A condition block: the two extractors and the value they are compared with. */
    private static ObjectNode condition() {
        return strings(HttpCheckCondition.JSON_PATH_KEY, HttpCheckCondition.REGEX_KEY, HttpCheckCondition.EQUALS_KEY);
    }

    /** A closed object whose members, all optional, are strings. */
    private static ObjectNode strings(String... names) {
        ObjectNode object = closedObject();
        ObjectNode properties = object.putObject(PROPERTIES);
        for (String name : names) {
            properties.putObject(name).put(TYPE, STRING);
        }
        return object;
    }

    private static ObjectNode closedObject() {
        return NODES.objectNode().put(TYPE, OBJECT).put(ADDITIONAL, false);
    }
}
