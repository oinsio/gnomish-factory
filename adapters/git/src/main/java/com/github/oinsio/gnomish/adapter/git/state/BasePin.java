package com.github.oinsio.gnomish.adapter.git.state;

import com.github.oinsio.gnomish.baseref.BaseRule;
import org.jspecify.annotations.Nullable;

/**
 * The {@code (ref, rule)} half of a task's durable base pin (FR7 of add-base-ref-resolution) — the
 * SHA travels separately as {@link TaskJsonDto#baseCommit()}, unchanged since before this pin
 * existed. Bundled into one parameter object so {@link TaskJsonMapper#toDto} stays within the
 * project's 7-parameter limit (`.claude/rules/process-invariants.md`) rather than taking {@code
 * ref} and {@code rule} as two more positional arguments.
 *
 * <p>Both components are null together for an unpinned document (a legacy {@code baseCommit}-only
 * file, or a write made before this pin existed) — never one without the other, since a ref with
 * no rule or a rule with no ref is not a fact any resolution can produce.
 *
 * @param ref the resolved ref the task branch was created from, or {@code null} when unpinned
 * @param rule the tier that produced {@code ref}, or {@code null} when unpinned
 */
public record BasePin(@Nullable String ref, @Nullable BaseRule rule) {

    /** The unpinned pin: both components absent, for a legacy or pre-pin document. */
    public static final BasePin UNPINNED = new BasePin(null, null);
}
