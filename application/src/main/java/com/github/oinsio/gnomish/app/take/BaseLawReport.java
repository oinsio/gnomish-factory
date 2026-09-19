package com.github.oinsio.gnomish.app.take;

import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import com.github.oinsio.gnomish.gitobjects.ObjectId;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The configuration report a task parks with when its base's {@code .gnomish/} fails to load (FR13,
 * UX5 of add-base-ref-resolution): the base ref, the law commit the definition was read from, and
 * every located error — rendered line for line as {@link ConfigError#render()} renders them, which
 * is the shape a startup load failure prints, so an operator reads one report format wherever a
 * definition is refused.
 *
 * <p>Pure text assembly, no I/O: the tracker write and the log line are the caller's.
 *
 * <p>Every located error quotes text the target repository's own {@code .gnomish/} supplied, so
 * {@link ConfigError#render()} hands it over as an {@link com.github.oinsio.gnomish.untrustedtext.UntrustedText}
 * and it leaves through the comment plane's inline shape here — stripped, with mentions and issue
 * references broken, and no fence of its own (design D6, revised 2026-09-19; D10 of
 * type-untrusted-text). The fence states that everything between its markers is machine output,
 * which no one error can say about a list of many inside a report the factory assembled: the
 * "Located errors (n):" heading is what separates the manifest's words from the factory's, and n
 * consecutive labeled fences would add nothing to it. The base ref beside them is not untrusted
 * text: every ref entering the process is held to {@code RefNameSyntax} first (NG4).
 *
 * <p>Implements FR13, UX5 of add-base-ref-resolution.
 */
public final class BaseLawReport {

    private BaseLawReport() {}

    /**
     * Renders the report.
     *
     * @param taskId the parked task; never null
     * @param baseRef the base ref the task's law was bound to — the binding's revision; never null
     * @param lawCommit the commit the definition was read from; never null
     * @param errors every located problem the loader found, in aggregation order; never empty
     * @return the operator-facing report; never blank
     */
    public static String of(String taskId, String baseRef, ObjectId lawCommit, List<ConfigError> errors) {
        String located =
                errors.stream().map(error -> error.render().forCommentInline()).collect(Collectors.joining("\n"));
        return "Task " + taskId + " is parked: the pipeline definition of its base failed to load.\n"
                + "Base ref: " + baseRef + "\n"
                + "Law commit: " + lawCommit.hex() + "\n"
                + "Located errors (" + errors.size() + "):\n"
                + located + "\n"
                + ParkedBaseTrailer.withRemedy("Fix .gnomish/ on the base and return the task to work.");
    }
}
