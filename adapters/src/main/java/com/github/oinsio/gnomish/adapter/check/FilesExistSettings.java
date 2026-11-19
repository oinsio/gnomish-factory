package com.github.oinsio.gnomish.adapter.check;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The settings of the {@code files-exist} built-in (FR6 of add-manual-run; FR1 of
 * migrate-verify-to-executor-contract): one key, {@code files}, a list of workspace-relative paths.
 * The one reader of those settings for the run, and the describe's settings schema that states the
 * same shape for the load-time preflight — kept side by side so the two cannot be read apart;
 * {@code FilesExistExecutorDescribeSpec} pins that they accept the same settings. The list has no
 * count limit: the answer's result cap is applied by {@link FilesExistOutcome#reported}.
 *
 * <p>Implements FR6 of add-manual-run; FR1 of migrate-verify-to-executor-contract.
 */
final class FilesExistSettings {

    /** The settings key holding the path list. */
    static final String FILES = "files";

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private FilesExistSettings() {}

    /**
     * The settings schema the describe declares: an object whose {@code files} is a list of strings,
     * required — the shape {@link #files} accepts.
     *
     * @return a fresh schema tree, so no holder shares another's; never null
     */
    static ObjectNode schema() {
        ObjectNode schema = NODES.objectNode().put("type", "object");
        schema.putArray("required").add(FILES);
        ObjectNode files = schema.putObject("properties").putObject(FILES).put("type", "array");
        files.putObject("items").put("type", "string");
        return schema;
    }

    /**
     * Reads the {@code files} setting as a list of strings.
     *
     * @param settings the check's settings as plain values; never null
     * @return the paths, in the order listed; never null
     * @throws MalformedSettingsException for a missing key, a non-list value, or a list with a non-string
     *     entry
     */
    static List<String> files(Map<String, ?> settings) {
        Object rawFiles = settings.get(FILES);
        if (rawFiles == null) {
            throw new MalformedSettingsException("files_exist requires a 'files' param");
        }
        if (!(rawFiles instanceof List<?> list)) {
            throw new MalformedSettingsException("files_exist 'files' param must be a list, got "
                    + rawFiles.getClass().getName());
        }
        List<String> files = new ArrayList<>(list.size());
        for (Object entry : list) {
            if (!(entry instanceof String stringEntry)) {
                throw new MalformedSettingsException(
                        "files_exist 'files' entries must all be strings, found " + describeType(entry));
            }
            files.add(stringEntry);
        }
        return files;
    }

    /**
     * Describes {@code entry}'s runtime type for a malformed-settings message, tolerating a {@code
     * null} list entry — a genuine possibility for externally supplied settings (a YAML or JSON
     * {@code null} literal in the list), even though the surrounding loop variable's inferred type is
     * non-null.
     */
    private static String describeType(@Nullable Object entry) {
        return entry == null ? "null" : entry.getClass().getName();
    }

    /** Signals malformed {@code files-exist} settings; the runner answers it as unanswerable. */
    static final class MalformedSettingsException extends RuntimeException {

        private final String reason;

        MalformedSettingsException(String reason) {
            super(reason);
            this.reason = reason;
        }

        String reason() {
            return reason;
        }
    }
}
