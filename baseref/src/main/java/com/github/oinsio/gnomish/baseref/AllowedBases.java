package com.github.oinsio.gnomish.baseref;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The project's allowed bases: the set of refs a task may branch from, as an ordered list of
 * patterns with roles.
 *
 * <p>The list governs the <em>per-task selection</em> only. A configured default and the repository
 * default branch are project-level choices already made by a human at merge time, and an explicit
 * {@code --base} is an operator standing at the terminal; none of the three is matched against the
 * allowed bases here. What the list exists to bound is the one input a task's author controls.
 *
 * <p>An empty list — the {@code task-branch.base} section absent, or declaring no entries —
 * therefore accepts no selection at all. That is the zero-configuration state, and it is
 * consistent: a project that has not said which bases are allowed has not allowed any task to
 * choose one.
 *
 * <p>Implements FR1, FR4 of add-base-ref-resolution.
 *
 * @param entries the declared entries, in the order the project wrote them — the order {@link #match}
 *     resolves ties by, and the order a report lists; never null, copied so the caller's list may
 *     change afterwards
 */
public record AllowedBases(List<AllowedBase> entries) {

    private static final AllowedBases EMPTY = new AllowedBases(List.of());

    /** Rejects a missing entry list and copies it, so the caller's list may change afterwards. */
    public AllowedBases {
        Objects.requireNonNull(entries, "entries");
        entries = List.copyOf(entries);
    }

    /**
     * What a project with no {@code task-branch.base} section, or one declaring no entries, allows.
     *
     * @return the empty list; accepts no per-task selection
     */
    public static AllowedBases empty() {
        return EMPTY;
    }

    /**
     * The allowed bases a project declared.
     *
     * @param entries the entries in declaration order; copied, so the caller's list may change
     *     afterwards
     * @return the allowed bases
     */
    public static AllowedBases of(List<AllowedBase> entries) {
        return new AllowedBases(entries);
    }

    /**
     * Whether nothing at all is allowed.
     *
     * @return true when no entry is declared
     */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * The first entry whose pattern accepts a ref name.
     *
     * <p>First rather than most-specific: declaration order is what the project can see and reorder,
     * and a specificity ranking would make two patterns' interaction a thing an operator has to
     * work out from the implementation.
     *
     * @param refName the candidate ref name a task selected
     * @return the matching entry, or empty when the name is allowed by none — including when it is
     *     not a well-formed ref name, and always when no base is allowed at all
     */
    public Optional<AllowedBase> match(String refName) {
        Objects.requireNonNull(refName, "refName");
        return entries.stream()
                .filter(entry -> entry.pattern().matches(refName))
                .findFirst();
    }

    /**
     * The allowed bases as an escalation report shows them, so the human reading "not allowed" can
     * see what actually is.
     *
     * @return the patterns in declaration order, comma-separated, or {@code (empty)} when no entry
     *     is declared
     */
    public String describe() {
        if (entries.isEmpty()) {
            return "(empty)";
        }
        return entries.stream().map(entry -> entry.pattern().source()).collect(Collectors.joining(", "));
    }
}
