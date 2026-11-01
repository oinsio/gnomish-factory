package com.github.oinsio.gnomish.adapter.agent.program;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads the output of {@code git status --porcelain=v2 -z --branch} into comparable entries, and
 * names the paths two views differ by (FR11, NFR-S4 of define-executor-contract). Each NUL-ended
 * record is one entry; of the headers only {@code branch.oid} (the commit {@code HEAD} names) and
 * {@code branch.head} (the branch checked out) are kept, since the upstream headers move with a
 * fetch, not with the working copy. The entries stay strings compared whole: they are never
 * interpreted beyond naming a path, which is what makes them inert.
 *
 * <p>Implements FR11, NFR-S4 of define-executor-contract.
 */
final class StatusEntries {

    private static final String HEADER = "# ";

    private StatusEntries() {}

    /**
     * The entries of one view.
     *
     * @param porcelain git's NUL-separated output; never null
     * @return the entries in git's order; never null
     */
    static Set<String> of(String porcelain) {
        Set<String> entries = new LinkedHashSet<>();
        for (String record : porcelain.split("\0", -1)) {
            boolean kept = !record.startsWith(HEADER)
                    || record.startsWith("# branch.oid ")
                    || record.startsWith("# branch.head ");
            if (kept && !record.isEmpty()) {
                entries.add(record);
            }
        }
        return entries;
    }

    /**
     * The paths whose entry is in one view and not in the other: first what the second view gained,
     * then what it lost, each named once.
     *
     * @param before the view before the run; never null
     * @param after the view after the run; never null
     * @return the paths, empty when the views are equal; never null
     */
    static List<String> changedPaths(Set<String> before, Set<String> after) {
        Set<String> paths = new LinkedHashSet<>();
        for (String entry : after) {
            if (!before.contains(entry)) {
                paths.add(pathOf(entry));
            }
        }
        for (String entry : before) {
            if (!after.contains(entry)) {
                paths.add(pathOf(entry));
            }
        }
        return new ArrayList<>(paths);
    }

    /**
     * The path one entry names: the last field of an ordinary ({@code 1}), renamed ({@code 2}) or
     * unmerged ({@code u}) entry, the rest of an untracked ({@code ?}) one, the header itself for
     * {@code HEAD} and the branch, and the record whole otherwise — the original path that follows a
     * rename is a record of its own.
     */
    static String pathOf(String entry) {
        if (entry.startsWith(HEADER)) {
            return entry.substring(HEADER.length());
        }
        return switch (entry.charAt(0)) {
            case '1' -> lastField(entry, 9);
            case '2' -> lastField(entry, 10);
            case 'u' -> lastField(entry, 11);
            case '?' -> entry.substring(2);
            default -> entry;
        };
    }

    private static String lastField(String entry, int fields) {
        String[] split = entry.split(" ", fields);
        return split.length < fields ? entry : split[fields - 1];
    }
}
