package com.github.oinsio.gnomish.executorcontract;

import com.networknt.schema.JsonNodePath;
import com.networknt.schema.ValidationMessage;

/**
 * Where a validator's message points inside the judged document, in the one form both judges of the
 * contract report ({@link SettingsSchema}, {@link WireSchemas}): the instance location, then the
 * property the rule names, when it names one — the unknown property of {@code
 * additionalProperties}, the missing one of {@code required} — since the instance location of such a
 * rule is the mapping holding it. Property names are joined by {@code .}, an array index is {@code
 * [n]}.
 *
 * <p>Implements FR9, FR24 of define-executor-contract.
 */
final class ViolationPath {

    private ViolationPath() {}

    static String of(ValidationMessage message) {
        StringBuilder path = new StringBuilder();
        JsonNodePath location = message.getInstanceLocation();
        for (int index = 0; index < location.getNameCount(); index++) {
            Object element = location.getElement(index);
            if (element instanceof Integer position) {
                path.append('[').append(position).append(']');
            } else {
                appendName(path, String.valueOf(element));
            }
        }
        String property = message.getProperty();
        if (property != null) {
            appendName(path, property);
        }
        return path.toString();
    }

    private static void appendName(StringBuilder path, String name) {
        if (!path.isEmpty()) {
            path.append('.');
        }
        path.append(name);
    }
}
