package com.github.oinsio.gnomish;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns back into lists the lists Spring's Binder turned into maps inside a raw subsection. A
 * {@code factory.check.<provider>} or {@code factory.connections.<name>} subsection binds to {@code
 * Map<String, Object>}, and into an {@code Object} value the Binder has no list type to aim at, so
 * an operator's {@code allowlist: [a, b]} arrives as {@code {0: a, 1: b}} — from one source as from
 * several (finding F130 of tighten-spec-contract). Providers read their keys as the operator wrote
 * them ({@code HttpCheckSubsectionValidator} requires a {@code List}), so the bound value is
 * restored here, once, for every raw subsection.
 *
 * <p>A map is a list exactly when its keys are the strings {@code "0"} to {@code "n-1"}, each once:
 * that is the only shape the Binder produces from a sequence. Anything else stays a map — an empty
 * map (the Binder never produces one from a list, and an empty list carries nothing a map does
 * not), and a map with a gap in its indices ({@code 0} and {@code 2}), which no single source's
 * list produces and whose missing element nothing could supply. The restoration is recursive, so a
 * list of maps and a list of lists come back whole; the result is unmodifiable at every level, in
 * index order for a list and in bound order for a map.
 *
 * <p>Implements FR5 of add-project-registry; FR4 of add-plugin-architecture.
 */
final class IndexedLists {

    private IndexedLists() {}

    /**
     * Restores every value of one subsection; the subsection itself stays a map, whatever its keys.
     *
     * @param content one bound subsection's raw content; never null
     * @return the content with each index-keyed map, at any depth, turned into a list
     */
    static Map<String, Object> inSubsection(Map<String, Object> content) {
        var restored = new LinkedHashMap<String, Object>();
        content.forEach((key, value) -> restored.put(key, restore(value)));
        return restored;
    }

    private static Object restore(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return value;
        }
        if (isIndexed(map)) {
            var elements = new Object[map.size()];
            map.forEach((index, element) -> elements[Integer.parseInt((String) index)] = restore(element));
            return List.of(elements);
        }
        var copy = new LinkedHashMap<Object, Object>();
        map.forEach((key, element) -> copy.put(key, restore(element)));
        return Collections.unmodifiableMap(copy);
    }

    private static boolean isIndexed(Map<?, ?> map) {
        if (map.isEmpty()) {
            return false;
        }
        for (int index = 0; index < map.size(); index++) {
            if (!map.containsKey(String.valueOf(index))) {
                return false;
            }
        }
        return true;
    }
}
