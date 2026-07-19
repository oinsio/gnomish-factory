package com.github.oinsio.gnomish.adapter.git;

import java.util.regex.Pattern;

/**
 * Deterministically sanitizes a tracker taskId into the single core string reused for both the
 * task branch name (FR2: {@code gnomish/<sanitized>}) and the worktree directory name (FR6:
 * {@code ~/.gnomish/worktrees/<project-name>/<sanitized>/}) — one algorithm, two callers, no
 * divergence between the two name spaces.
 *
 * <p>Algorithm (design D16), in order: every character outside {@code [A-Za-z0-9._-]} is
 * replaced by {@code -}; consecutive {@code -} collapse to one; leading/trailing {@code .} and
 * {@code -} are stripped. An empty result, or one ending in {@code .lock}, rejects the taskId —
 * sanitization is lossy and unguarded against collisions (real tracker ids are expected to be
 * ref-safe already); the authoritative taskId always lives in {@code task.json}, never parsed
 * back from a branch or directory name.
 *
 * <p>Implements FR2, FR7 of add-git-workflow.
 */
public final class TaskIdSanitizer {

    private static final Pattern UNSAFE_CHAR = Pattern.compile("[^A-Za-z0-9._-]");
    private static final Pattern REPEATED_DASH = Pattern.compile("-{2,}");
    private static final Pattern LEADING_TRAILING = Pattern.compile("^[.-]+|[.-]+$");
    private static final String BRANCH_PREFIX = "gnomish/";

    private TaskIdSanitizer() {}

    /**
     * Sanitizes {@code taskId} into the bare core string, with no {@code gnomish/} prefix.
     *
     * @param taskId the tracker's original taskId
     * @return the sanitized, ref/directory-safe core string
     * @throws InvalidTaskIdException if sanitization yields an empty string or one ending in
     *     {@code .lock}
     */
    public static String sanitize(String taskId) {
        String replaced = UNSAFE_CHAR.matcher(taskId).replaceAll("-");
        String collapsed = REPEATED_DASH.matcher(replaced).replaceAll("-");
        String stripped = LEADING_TRAILING.matcher(collapsed).replaceAll("");

        if (stripped.isEmpty()) {
            throw new InvalidTaskIdException(taskId, "sanitizes to an empty string");
        }
        if (stripped.endsWith(".lock")) {
            throw new InvalidTaskIdException(taskId, "sanitizes to a \".lock\"-suffixed name");
        }
        return stripped;
    }

    /**
     * Sanitizes {@code taskId} and prefixes it with {@code gnomish/} for use as the task branch
     * name (FR2).
     *
     * @param taskId the tracker's original taskId
     * @return the task branch name, {@code gnomish/<sanitized taskId>}
     * @throws InvalidTaskIdException if sanitization rejects {@code taskId}
     */
    public static String branchName(String taskId) {
        return BRANCH_PREFIX + sanitize(taskId);
    }
}
