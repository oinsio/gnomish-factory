package com.github.oinsio.gnomish.app.port.tracker;

/**
 * The task's id/title/body frozen at first claim (FR11). Once captured into
 * {@code TaskContext}/{@code task.json}, later edits to the tracker issue never
 * affect the running or parked task — resume collects only human {@link
 * HumanReply decisions}, never re-reads the live tracker task.
 *
 * <p>{@code id} matches the {@link TaskRef} it was fetched for; {@code title}
 * and {@code body} are free text carried verbatim. {@code body} may be empty
 * (many tracker issues have no description) but {@code id} and {@code title}
 * are required non-blank — a task with no title is not a meaningful snapshot.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR11 of add-tracker-port.
 *
 * @param id the canonical task id this snapshot was captured from; never blank
 * @param title the task title at first claim; never blank
 * @param body the task body/description at first claim; never null, may be empty
 */
public record TaskSnapshot(String id, String title, String body) {

    public TaskSnapshot {
        id = requireNonBlank(id, "id");
        title = requireNonBlank(title, "title");
    }

    /**
     * Fails fast on a blank {@code id}/{@code title}: a snapshot with no identity or
     * no title cannot anchor a task's frozen record (FR11). Kept as an explicit
     * static method rather than inline in the compact constructor: PIT's record
     * filter suppresses all mutations inside a record's canonical constructor,
     * which would silently exempt this validation from the 100% mutation gate.
     */
    private static String requireNonBlank(String value, String component) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("TaskSnapshot." + component + " must not be blank");
        }
        return value;
    }
}
