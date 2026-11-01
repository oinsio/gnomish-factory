package com.github.oinsio.gnomish.executorcontract;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Typed reads of the fields of a written JSON document, each refusing a field of the wrong JSON
 * type with an {@link IllegalArgumentException} naming the field — the reason a malformed answer
 * carries. An absent field and a JSON {@code null} read alike.
 *
 * <p>Implements FR3 of define-executor-contract.
 */
final class WrittenNodes {

    private WrittenNodes() {}

    /** The string {@code field} of {@code node}; refuses an absent or non-string one. */
    static String text(JsonNode node, String field, String where) {
        JsonNode value = node.path(field);
        if (!value.isTextual()) {
            throw new IllegalArgumentException(where + " has no string " + field);
        }
        return value.asText();
    }

    /** The string {@code field} of {@code node}, or {@code null} when absent; refuses a non-string. */
    static @Nullable String optionalText(JsonNode node, String field, String where) {
        JsonNode value = node.path(field);
        if (absent(value)) {
            return null;
        }
        return text(node, field, where);
    }

    /** The string array {@code field} of {@code node}, empty when absent; refuses anything else. */
    static List<String> texts(JsonNode node, String field, String where) {
        JsonNode value = node.path(field);
        if (absent(value)) {
            return List.of();
        }
        List<JsonNode> items = array(node, field, where);
        List<String> texts = new ArrayList<>();
        for (JsonNode item : items) {
            if (!item.isTextual()) {
                throw new IllegalArgumentException(where + " has a non-string item in " + field);
            }
            texts.add(item.asText());
        }
        return texts;
    }

    /** The items of the array {@code field} of {@code node}; refuses an absent or non-array one. */
    static List<JsonNode> array(JsonNode node, String field, String where) {
        JsonNode value = node.path(field);
        if (!value.isArray()) {
            throw new IllegalArgumentException(where + " has no array " + field);
        }
        List<JsonNode> items = new ArrayList<>();
        value.forEach(items::add);
        return items;
    }

    static boolean absent(JsonNode value) {
        return value.isMissingNode() || value.isNull();
    }
}
