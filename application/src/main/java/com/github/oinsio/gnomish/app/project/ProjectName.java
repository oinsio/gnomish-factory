package com.github.oinsio.gnomish.app.project;

import java.util.regex.Pattern;

/**
 * The name of a registered project: the folder {@code projects/<name>/} of the factory home and
 * the prefix of the instance identity (FR2, FR10). Lowercase so that one project never appears as
 * two folders on a case-insensitive file system, and a single path segment by construction.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR1, FR2 of add-project-registry.
 *
 * @param value the name; matches {@code [a-z0-9][a-z0-9._-]*}
 */
public record ProjectName(String value) {

    /** The accepted shape, quoted in the refusal so the operator can pick a valid name. */
    public static final String SHAPE = "[a-z0-9][a-z0-9._-]*";

    private static final Pattern PATTERN = Pattern.compile(SHAPE);

    public ProjectName {
        value = requireShape(value);
    }

    @Override
    public String toString() {
        return value;
    }

    /**
     * Refuses a name outside {@link #SHAPE}. A static method rather than inline in the compact
     * constructor: PIT's record filter suppresses every mutation inside a record's canonical
     * constructor, which would exempt this check from the mutation gate.
     */
    private static String requireShape(String value) {
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "invalid project name '" + value + "': a project name must match " + SHAPE);
        }
        return value;
    }
}
