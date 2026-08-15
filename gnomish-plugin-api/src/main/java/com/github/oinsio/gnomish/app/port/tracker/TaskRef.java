package com.github.oinsio.gnomish.app.port.tracker;

/**
 * A task's canonical identity: an opaque string handed to and returned from the
 * {@code Tracker} port. Core never parses or interprets the string — the
 * canonical shape ({@code github:owner/repo#42}, host included only for a
 * non-default {@code api-url}) is minted and read only by adapters (design D1,
 * FR16). Kept as a thin wrapper rather than a bare {@code String} parameter so
 * the port signatures cannot be confused with a title, a report, or any other
 * free-text value.
 *
 * <p>Inert value data compared by content; a blank id is rejected because a
 * task with no identity cannot be claimed, fetched, or reported on.
 *
 * <p>Implements FR1, FR16 of add-tracker-port.
 *
 * @param id the canonical, adapter-minted task identity; never blank
 */
public record TaskRef(String id) {

    public TaskRef {
        id = requireNonBlank(id);
    }

    /**
     * Fails fast on a blank {@code id}: an opaque identity with no content cannot
     * round-trip through the port (FR1). Kept as an explicit static method rather
     * than inline in the compact constructor: PIT's record filter suppresses all
     * mutations inside a record's canonical constructor, which would silently
     * exempt this validation from the 100% mutation gate.
     */
    private static String requireNonBlank(String value) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("TaskRef.id must not be blank");
        }
        return value;
    }
}
