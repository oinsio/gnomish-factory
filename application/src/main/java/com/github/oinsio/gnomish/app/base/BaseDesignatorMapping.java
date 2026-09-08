package com.github.oinsio.gnomish.app.base;

import com.github.oinsio.gnomish.app.port.tracker.Designator;
import com.github.oinsio.gnomish.app.port.tracker.TaskDesignators;
import com.github.oinsio.gnomish.baseref.BaseDesignator;
import java.util.List;

/**
 * Maps the tracker port's designator fact for kind {@code base} onto the resolution policy's own
 * input value (design D5 of add-base-ref-resolution).
 *
 * <p>The two types name the same three shapes on purpose, and the mapping exists rather than the
 * types being one because {@code :baseref} may import nothing at all — that empty allowlist is what
 * constructively guarantees the policy can know nothing of trackers (NFR-S3). This is also where
 * "kind {@code base}" stops being one of several open kinds and becomes the only thing the policy
 * is ever handed.
 *
 * <p>No decision happens here: a conflict stays a conflict, an absent stays absent. Grading a value
 * against the project's allowed bases, and refusing what is underdetermined, is the resolver's job.
 *
 * <p>Implements FR3 of add-base-ref-resolution.
 */
public final class BaseDesignatorMapping {

    /** The designator kind the base-resolution policy consumes. */
    public static final String BASE_KIND = "base";

    private BaseDesignatorMapping() {}

    /**
     * Reads the {@code base} designator out of a task's facts.
     *
     * @param designators the task's designator facts as the adapter classified them; never null
     * @return the policy's input value for this task; absent when the task named no base, or when
     *     no adapter rule extracts the kind at all
     */
    public static BaseDesignator baseOf(TaskDesignators designators) {
        return switch (designators.forKind(BASE_KIND)) {
            case Designator.Absent ignored -> BaseDesignator.absent();
            case Designator.Single(String value) -> BaseDesignator.single(value);
            case Designator.Conflict(List<String> values) -> BaseDesignator.conflict(values);
        };
    }
}
