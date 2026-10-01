package com.github.oinsio.gnomish.app.project;

import com.github.oinsio.gnomish.app.UsageException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

/**
 * The format of a project file, {@code project.yaml}: one YAML document whose top-level {@code
 * clones:} map names each clone and its absolute path, beside the project's {@code factory:}
 * configuration (design D3). Parsed by {@link OperatorFile} — the parser the configuration is
 * read with, so one parser serves both — and extended by a text edit that keeps
 * the operator's comments and layout, checked by reading the result back before anything is
 * written.
 *
 * <p>Implements FR2, NFR-R1 of add-project-registry.
 */
final class ProjectFile {

    private static final String CLONES = "clones";
    private static final String CLONE_PREFIX = CLONES + ".";
    private static final Pattern CLONES_LINE = Pattern.compile("^" + CLONES + ":\\s*(#.*)?$");
    private static final String DEFAULT_INDENT = "  ";

    private ProjectFile() {}

    /**
     * The clones a project file's text registers, in file order.
     *
     * @param file the file the text came from, named in a refusal
     * @param text the file's content
     * @return clone name → registered path
     * @throws UsageException if the text is not YAML, or a clone's name or path is invalid
     */
    static Map<CloneName, Path> clones(Path file, String text) {
        return clones(file, OperatorFile.parse(file, text));
    }

    /**
     * The clones a parsed project file registers, in file order.
     *
     * @param file the file the document came from, named in a refusal
     * @param document the file's property sources, as {@link OperatorFile#parse} yields them
     * @return clone name → registered path
     * @throws UsageException if a clone's name or path is invalid
     */
    static Map<CloneName, Path> clones(Path file, List<PropertySource<?>> document) {
        Map<CloneName, Path> clones = new LinkedHashMap<>();
        for (PropertySource<?> source : document) {
            for (String key : ((EnumerablePropertySource<?>) source).getPropertyNames()) {
                if (key.startsWith(CLONE_PREFIX)) {
                    clones.put(cloneName(file, key), clonePath(file, key, source.getProperty(key)));
                }
            }
        }
        return clones;
    }

    /**
     * The file's text with one more clone entry under {@code clones:} — inserted as the map's first
     * entry at the indentation of the existing ones, or in a new {@code clones:} map at the top when
     * the file has none. Every other line is kept as the operator wrote it.
     *
     * @param text the current content; empty for a project's first clone
     * @param clone the new clone's name
     * @param path the new clone's absolute path
     * @return the new content
     */
    static String withClone(String text, CloneName clone, Path path) {
        List<String> lines = new ArrayList<>(text.lines().toList());
        String entry = quote(clone.value()) + ": " + quote(path.toString());
        int clonesLine = clonesLine(lines);
        if (clonesLine < 0) {
            lines.addAll(0, List.of(CLONES + ":", DEFAULT_INDENT + entry));
        } else {
            lines.add(clonesLine + 1, entryIndent(lines, clonesLine) + entry);
        }
        return String.join("\n", lines) + "\n";
    }

    private static int clonesLine(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (CLONES_LINE.matcher(lines.get(i)).matches()) {
                return i;
            }
        }
        return -1;
    }

    /** The indentation of the map's first entry, or two spaces when the map has none yet. */
    private static String entryIndent(List<String> lines, int clonesLine) {
        for (String line : lines.subList(clonesLine + 1, lines.size())) {
            String stripped = line.strip();
            if (stripped.isEmpty() || stripped.startsWith("#")) {
                continue;
            }
            String indent =
                    line.substring(0, line.length() - line.stripLeading().length());
            return indent.isEmpty() ? DEFAULT_INDENT : indent;
        }
        return DEFAULT_INDENT;
    }

    /** A single-quoted YAML scalar: no character of a path or a folder name needs more. */
    private static String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static CloneName cloneName(Path file, String key) {
        try {
            return new CloneName(key.substring(CLONE_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new UsageException(file + ": " + e.getMessage());
        }
    }

    private static Path clonePath(Path file, String key, @Nullable Object value) {
        Path path = Path.of(String.valueOf(value));
        if (!path.isAbsolute()) {
            throw new UsageException(file + ": " + key + " must be an absolute path, found '" + value + "'");
        }
        return path.normalize();
    }
}
