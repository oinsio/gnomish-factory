package com.github.oinsio.gnomish.app.port.tracker;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;

/**
 * The task's id/title/body frozen at first claim (FR11). Once captured into
 * {@code TaskContext}/{@code task.json}, later edits to the tracker issue never
 * affect the running or parked task — resume collects only human {@link
 * HumanReply decisions}, never re-reads the live tracker task.
 *
 * <p>{@code id} matches the {@link TaskRef} it was fetched for; {@code title}
 * and {@code body} are the tracker's free text, carried as the carrier the
 * adapter minted them in (design D3, D4 of type-untrusted-text) so a renderer
 * downstream picks the exit its reader needs. {@code body} may be empty
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
public record TaskSnapshot(String id, UntrustedText title, UntrustedText body) {

    public TaskSnapshot {
        id = requireNonBlank(id, "id");
        title = requireNonBlank(title, "title");
    }

    /**
     * The carrier half of the same fail-fast: a title that carries no text cannot anchor the
     * snapshot any better than a blank string could. Explicit static method for the same PIT
     * mutation-gate reason as {@link #requireNonBlank(String, String)}.
     */
    private static UntrustedText requireNonBlank(UntrustedText value, String component) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("TaskSnapshot." + component + " must not be blank");
        }
        return value;
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
