package com.github.oinsio.gnomish.adapter.agent.claude;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.oinsio.gnomish.adapter.settings.RoundTimeout;
import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import com.github.oinsio.gnomish.executorcontract.SettingsJudgement;
import com.github.oinsio.gnomish.executorcontract.SettingsSchema;
import com.github.oinsio.gnomish.executorcontract.SettingsViolation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The Claude dialect's settings schema and its load-time judge (FR7, design D6 of
 * extract-agent-round-runner): exactly the keys the dialect consumes — {@code allowedTools} and
 * {@code disallowedTools} (lists of tool names), {@code maxTurns} (a number) and {@code roundTimeout}
 * (a number of seconds or an ISO-8601 duration) — and nothing else, so a typo, a permission-mode
 * key, an MCP knob, an allowlist knob or a {@code model} key is refused at load.
 *
 * <p>The schema is a JSON Schema its describe declares ({@link ClaudeDialect#describe}); {@link
 * #validate} judges a settings map against it through the contract's one settings judge, {@link
 * SettingsSchema}, and hands a {@code roundTimeout} value the schema admitted to that key's one shape
 * owner, {@link RoundTimeout} — a JSON Schema cannot state the duration grammar, and a pattern here
 * would be a second copy of it. The composition root binds {@link #validate} as the loader's agent
 * settings judge, which this module's dependency rules keep it from implementing by name.
 *
 * <p>Implements FR7, D6 of extract-agent-round-runner; FR11, UX2, D7 of add-agent-executor.
 */
public final class ClaudeSettings {

    static final String ALLOWED_TOOLS = "allowedTools";
    static final String DISALLOWED_TOOLS = "disallowedTools";
    static final String MAX_TURNS = "maxTurns";

    private ClaudeSettings() {}

    /**
     * The settings schema: an object holding only the four keys, each in its shape. A fresh node per
     * call, so no holder can change another's.
     *
     * @return the schema; never null
     */
    public static ObjectNode schema() {
        JsonNodeFactory json = JsonNodeFactory.instance;
        ObjectNode properties = json.objectNode();
        for (String key : List.of(ALLOWED_TOOLS, DISALLOWED_TOOLS)) {
            ObjectNode list = properties.putObject(key).put("type", "array");
            list.putObject("items").put("type", "string");
        }
        properties.putObject(MAX_TURNS).put("type", "number");
        properties.putObject(RoundTimeout.KEY).putArray("type").add("number").add("string");
        ObjectNode schema = json.objectNode().put("type", "object");
        schema.set("properties", properties);
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * Judges one settings map at load: every violation is a located error at {@code where.<key>} of
     * {@code file}, in key order.
     *
     * @param file the stage manifest the settings were declared in; never null
     * @param where the settings' location in it ({@code executor.settings}, {@code
     *     verify[0].settings}); never null
     * @param settings the settings as the YAML tree produced them; never null
     * @return every located error; empty when the settings are accepted; never null
     */
    public static List<ConfigError> validate(String file, String where, Map<String, Object> settings) {
        List<ConfigError> errors = new ArrayList<>();
        // The schema is this class's constant, pinned usable by its spec, so a judgement is either
        // Accepted or Rejected; only a rejection has anything to locate.
        if (SettingsSchema.judge(schema(), settings) instanceof SettingsJudgement.Rejected rejected) {
            for (SettingsViolation violation : rejected.violations()) {
                // Every rule of the schema names a key: the judged document is always an object.
                errors.add(new ConfigError(
                        file,
                        where + "." + violation.path(),
                        "settings break the Claude executor's schema rule '%s': %s"
                                .formatted(
                                        violation.rule(),
                                        violation.explanation().forLog())));
            }
        }
        String roundTimeoutAt = where + "." + RoundTimeout.KEY;
        boolean shapeRefused = errors.stream().anyMatch(error -> error.where().equals(roundTimeoutAt));
        if (settings.containsKey(RoundTimeout.KEY) && !shapeRefused) {
            String problem = RoundTimeout.malformation(settings.get(RoundTimeout.KEY));
            if (problem != null) {
                errors.add(new ConfigError(file, roundTimeoutAt, problem));
            }
        }
        errors.sort(Comparator.comparing(ConfigError::where));
        return List.copyOf(errors);
    }
}
