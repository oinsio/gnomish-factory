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
        String located = errors.stream().map(error -> "  " + error.render()).collect(Collectors.joining("\n"));
        return "Task " + taskId + " is parked: the pipeline definition of its base failed to load.\n"
                + "Base ref: " + baseRef + "\n"
                + "Law commit: " + lawCommit.hex() + "\n"
                + "Located errors (" + errors.size() + "):\n"
                + located + "\n"
                + "No stage attempt was spent and the claim was not released: the failure is deterministic,"
                + " so re-claiming would only repeat it. Fix .gnomish/ on the base and return the task to"
                + " work.";
    }
}
