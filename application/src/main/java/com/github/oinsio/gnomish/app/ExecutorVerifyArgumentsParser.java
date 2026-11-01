package com.github.oinsio.gnomish.app;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;

/**
 * Parses {@code gnomish executor verify <program> [--pending-settings=<json>]} into an {@link
 * ExecutorVerifyArguments} (FR12, UX2 of define-executor-contract). An unknown option is refused
 * first (FR8 of fix-operator-blockers); then exactly two positional words must follow {@code
 * executor}: the verb {@code verify} and the candidate program. The pending settings are one JSON
 * object, the operator's own text, read into plain values as the law's settings are; absent, they
 * are empty. Public because the kit's command lives in its own package of the composition root.
 *
 * <p>Implements FR12, UX2 of define-executor-contract; FR8 of fix-operator-blockers (through {@link
 * ArgumentsParsingSupport}).
 */
public final class ExecutorVerifyArgumentsParser {

    private static final String EXECUTOR_TOKEN = "executor";
    private static final String VERIFY = "verify";
    private static final String PENDING_SETTINGS = "pending-settings";
    private static final TypeReference<LinkedHashMap<String, Object>> SETTINGS = new TypeReference<>() {};
    private static final String FORM = "gnomish executor verify <program> [--pending-settings=<json object>]";

    /**
     * Refuses an unknown option or a malformed command line, then reads the program and the settings.
     *
     * @param args the raw application arguments, including the leading {@code executor} token
     * @return the validated arguments; never null
     * @throws UsageException naming what is wrong and the accepted form
     */
    public ExecutorVerifyArguments parse(ApplicationArguments args) {
        ArgumentsParsingSupport.rejectUnknownOptions(args, EXECUTOR_TOKEN, List.of(PENDING_SETTINGS), Map.of());
        List<String> words = ArgumentsParsingSupport.allPositionalsAfterSubcommand(args, EXECUTOR_TOKEN);
        if (words.size() != 2 || !VERIFY.equals(words.getFirst())) {
            throw new UsageException("expected " + FORM + "; got 'gnomish executor " + String.join(" ", words) + "'");
        }
        String settings = ArgumentsParsingSupport.singleValue(args, PENDING_SETTINGS);
        return new ExecutorVerifyArguments(words.getLast(), settings == null ? Map.of() : settings(settings));
    }

    private static Map<String, Object> settings(String text) {
        Map<String, Object> values;
        try {
            JsonNode tree = ExecutorContractJson.reader().readTree(text);
            if (tree == null || !tree.isObject()) {
                throw new UsageException("--" + PENDING_SETTINGS + " must be one JSON object, e.g. --"
                        + PENDING_SETTINGS + "='{\"defer\":true}'");
            }
            values = ExecutorContractJson.reader().forType(SETTINGS).readValue(tree);
        } catch (IOException malformed) {
            throw new UsageException("--" + PENDING_SETTINGS + " is not JSON: " + malformed.getMessage());
        }
        values.forEach((name, value) -> {
            if (value == null) {
                throw new UsageException("--" + PENDING_SETTINGS + " sets '" + name + "' to null; omit it instead");
            }
        });
        return values;
    }
}
