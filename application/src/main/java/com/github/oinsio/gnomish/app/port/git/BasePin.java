package com.github.oinsio.gnomish.app.port.git;

import com.github.oinsio.gnomish.baseref.BaseRule;
import org.jspecify.annotations.Nullable;

/**
 * A task's durable base pin (FR7 of add-base-ref-resolution): the ref name its branch was cut for,
 * the namespace origin held that name in, and the tier that produced the name. The commit itself
 * travels separately as the task record's {@code baseCommit}, which is also the branch's start
 * point (design D12, revised 2026-09-10) — the pin is <em>metadata about</em> that commit, never a
 * second way of finding it.
 *
 * <p>A port-level value: it crosses {@link com.github.oinsio.gnomish.app.port.TaskRepository
 * #createTask} as one parameter rather than three, which is what keeps that port within the
 * project's 7-parameter limit (`.claude/rules/process-invariants.md`) and what stops a caller from
 * transposing two same-typed strings.
 *
 * <p>{@code ref} and {@code rule} are null together for an unpinned document (a legacy {@code
 * baseCommit}-only file, or a write made before this pin existed) — never one without the other,
 * since a ref with no rule or a rule with no ref is not a fact any resolution can produce. {@code
 * kind} is separately optional: it is the remote's own answer, established only where a refresh
 * classified the ref (D7, revised 2026-09-10), so a manual run's offline pin and every pin written
 * before the kind existed carry the ref and the rule without it, and resume classifies as it did
 * before.
 *
 * @param ref the resolved ref the task branch was created for, or {@code null} when unpinned
 * @param kind the namespace origin held {@code ref} in, or {@code null} when the pin was written
 *     without one — resume then classifies the name at resume time, as it did before the kind
 *     existed
 * @param rule the tier that produced {@code ref}, or {@code null} when unpinned
 */
public record BasePin(
        @Nullable String ref,
        @Nullable BaseRefKind kind,
        @Nullable BaseRule rule) {

    /** The unpinned pin: every component absent, for a legacy or pre-pin document. */
    public static final BasePin UNPINNED = new BasePin(null, null, null);
}
