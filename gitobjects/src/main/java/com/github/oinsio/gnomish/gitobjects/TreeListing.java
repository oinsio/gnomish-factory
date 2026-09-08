package com.github.oinsio.gnomish.gitobjects;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the output of {@code git ls-tree -z} into {@link TreeEntry} values for {@link
 * GitObjects#listTree}. One record per entry, {@code NUL}-terminated: {@code <mode> SP <type> SP
 * <object> TAB <name>}. The {@code -z} form is what makes the parse safe — without it git quotes
 * and escapes unusual names, so a name holding a quote or a newline would have to be un-escaped
 * here; with it, the name is the record's raw bytes after the tab.
 *
 * <p>A record that does not match the shape is refused rather than guessed at: the listing feeds a
 * law reader that decides what may be read, and a half-parsed entry there is a wrong answer, not a
 * missing one.
 *
 * <p>Implements FR11 of add-base-ref-resolution.
 */
final class TreeListing {

    /** {@code DOTALL} so a name holding a newline still matches — {@code -z} permits one. */
    private static final Pattern RECORD = Pattern.compile("(\\d+) [a-z]+ [0-9a-f]+\t(.+)", Pattern.DOTALL);

    /**
     * The record terminator. A {@link Pattern} rather than {@link String#split(String)}: the latter
     * re-compiles the pattern per call and is what Error Prone's {@code StringSplitter} check warns
     * about. Trailing empty results are dropped by both — {@link #parse} does not depend on that,
     * skipping every empty record explicitly.
     */
    private static final Pattern NUL = Pattern.compile("\0");

    private TreeListing() {}

    /** The entries {@code stdout} lists, in git's order; empty output is an empty listing. */
    static List<TreeEntry> parse(String stdout) {
        List<TreeEntry> entries = new ArrayList<>();
        for (String record : NUL.split(stdout)) {
            if (!record.isEmpty()) {
                entries.add(parseRecord(record));
            }
        }
        return List.copyOf(entries);
    }

    private static TreeEntry parseRecord(String record) {
        Matcher matcher = RECORD.matcher(record);
        if (!matcher.matches()) {
            throw new GitObjectsException("unparsable ls-tree record: " + record);
        }
        return new TreeEntry(matcher.group(2), TreeEntry.Kind.ofMode(matcher.group(1)));
    }
}
