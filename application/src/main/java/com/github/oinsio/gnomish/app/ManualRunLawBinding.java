package com.github.oinsio.gnomish.app;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The law binding of a manual {@code gnomish run} in git mode (design D12 of
 * add-base-ref-resolution), host and container alike: with {@code --base} the law is read from
 * that ref's own commit out of git objects — offline, no fetch — and without it the clone's
 * working tree stays the law, so a pipeline author's uncommitted {@code .gnomish/} edit is still
 * what runs (FR8, UX3).
 *
 * <p>Selection is by fact, not by mode: the same rule that sends {@code take} and {@code serve} to
 * a law commit sends a based manual run there too, and only the un-based manual run — which
 * resolved no ref at all — keeps the working tree.
 *
 * <p>Implements FR8, FR11 of add-base-ref-resolution.
 */
final class ManualRunLawBinding {

    private ManualRunLawBinding() {}

    /**
     * The binding for one manual run.
     *
     * <p>Implements FR8, FR11 of add-base-ref-resolution.
     *
     * @param cloneDir the {@code --dir} project clone
     * @param base the {@code --base} override, or {@code null} for the clone's current state
     * @return the git-objects binding at {@code base}, or the clone's working tree; never null
     */
    static LawBinding of(Path cloneDir, @Nullable String base) {
        return base == null ? LawBinding.workingTree(cloneDir) : LawBinding.atRevision(cloneDir, base);
    }
}
