package com.github.oinsio.gnomish.app;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The law binding of a manual {@code gnomish run --resume} in git mode (design D13 of
 * add-base-ref-resolution), host and container alike: the resumed task binds its law from the
 * LOCAL tip of the ref its branch is pinned to, so a task pinned to {@code release/1.18} keeps
 * reading {@code release/1.18}'s law even when the operator's clone has another branch checked
 * out. No fetch runs on this path — {@code run --resume} holds no claim and has no tracker to
 * park or release a task with, so the offline manual contract (FR8, UX3) is kept and the tip is
 * whatever the clone already has.
 *
 * <p>The manual counterpart of {@link ResumeLawBinding}, whose narrow fetch, park and release
 * arms all belong to the tracker-driven paths; the pinned-ref selection itself is the same rule,
 * taken from {@link ResumeLawBinding#pinnedRef} so both resume flavors name the same ref. A
 * manual run started without {@code --base} pinned the literal {@code HEAD} ref, so its resume
 * still binds the clone's checkout exactly as before this class existed.
 *
 * <p>Implements FR7, FR12, D13 of add-base-ref-resolution.
 */
final class ManualResumeLawBinding {

    private ManualResumeLawBinding() {}

    /**
     * The binding for one manual resume.
     *
     * <p>Implements FR7, FR12, D13 of add-base-ref-resolution.
     *
     * @param cloneDir the {@code --dir} project clone the pinned ref is resolved in
     * @param baseRef the branch's durable pin, or {@code null} for a legacy branch that carries
     *     none
     * @param baseCommit the commit the task branch was created from — the legacy fallback
     * @return the git-objects binding at the pinned ref's local tip; never null
     */
    static LawBinding of(Path cloneDir, @Nullable String baseRef, String baseCommit) {
        return LawBinding.atRevision(cloneDir, ResumeLawBinding.pinnedRef(baseRef, baseCommit));
    }
}
