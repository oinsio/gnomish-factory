package com.github.oinsio.gnomish.baseref;

import java.util.Objects;

/**
 * A resolved base: what to branch from, which tier said so, and a sentence a human can read.
 *
 * <p>The ref and the rule are pinned into the task-creation commit; the reason is not — it is
 * rebuilt from the ref and the rule whenever it is shown, so rephrasing it never breaks a stored
 * task.
 *
 * <p>Implements FR4, FR7, FR10 of add-base-ref-resolution.
 *
 * @param ref the ref to branch from — a branch name, a tag name, or a commit SHA. Not necessarily
 *     one that exists: whether it does is the fetching layer's answer, not the policy's
 * @param rule the tier that produced it
 * @param reason one sentence naming what was consulted, for an operator report
 */
public record BaseDecision(String ref, BaseRule rule, String reason) {

    /** All three components reach an operator or a durable pin; none of them may be missing. */
    public BaseDecision {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(reason, "reason");
    }
}
