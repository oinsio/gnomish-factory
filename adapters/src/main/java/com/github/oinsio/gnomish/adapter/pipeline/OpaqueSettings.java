package com.github.oinsio.gnomish.adapter.pipeline;

import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * The one copy of every opaque map the loader carries into the domain (FR11, D5a of
 * load-pipeline-config) — an executor's and a declaration's {@code settings}, a judge's {@code
 * settings}, a built-in or external check's {@code params}: a defensive plain-JDK copy, absent
 * mapping to empty.
 *
 * <p>A key written with no value is YAML {@code null}, which no immutable JDK map holds: each such
 * key is a located {@link ConfigError} at {@code <where>.<key>} appended to the caller's errors and
 * left out of the copy, so a load reports it instead of failing with an exception. The appended
 * error aborts the definition ({@link PipelineMapper#map}), so the copy without the key never
 * reaches a settings schema or a round.
 *
 * <p>Implements FR11 of load-pipeline-config; UX2 of add-command-executor (design D10).
 */
final class OpaqueSettings {

    private OpaqueSettings() {}

    /**
     * Copies {@code settings}, reporting each key that has no value instead of copying it.
     *
     * @param file the file the map was declared in, stamped into any located error
     * @param where the map's location in that file ({@code executor.settings}, {@code verify[0].params})
     * @param settings the parsed map, or {@code null} when absent
     * @param errors the caller's mutable error accumulator
     * @return the immutable copy without the keys that had no value; empty for an absent map
     */
    static Map<String, Object> copy(
            String file, String where, @Nullable Map<String, Object> settings, List<ConfigError> errors) {
        if (settings == null) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        // Keys in name order: the parsed map's order is the parser's, the report must not depend on it.
        for (Map.Entry<String, Object> entry : new TreeMap<>(settings).entrySet()) {
            if (entry.getValue() == null) {
                errors.add(new ConfigError(
                        file, where + "." + entry.getKey(), "missing value for '%s'".formatted(entry.getKey())));
            } else {
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        return Map.copyOf(copy);
    }
}
