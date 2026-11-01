package com.github.oinsio.gnomish.domain.engine;

/**
 * The executor role a participant of a round was invoked for (design D14 of
 * define-executor-contract; glossary "Executor role"): {@link #TRANSFORM} changes the working copy,
 * {@link #VERDICT} judges it, {@link #DECISION} chooses among options, and {@link #EFFECT} acts
 * outside the branch (vocabulary reserved, not executed yet). It keys every {@link
 * ParticipantUsage} entry; "gnome", "judge" and "arbiter" are render labels for these roles, never
 * record vocabulary.
 *
 * <p>The domain's own constants, with no wire spelling: the one usage wire vocabulary spells them
 * through the contract's role tokens, and its mapper switches over both sets exhaustively in both
 * directions, so a constant added on either side fails to compile there.
 *
 * <p>Implements FR15 of define-executor-contract.
 */
public enum ExecutorRole {
    /** Change the working copy: a stage's executor. */
    TRANSFORM,
    /** Judge the working copy: one judge vote. */
    VERDICT,
    /** Choose among options. */
    DECISION,
    /** Act outside the branch; reserved. */
    EFFECT
}
