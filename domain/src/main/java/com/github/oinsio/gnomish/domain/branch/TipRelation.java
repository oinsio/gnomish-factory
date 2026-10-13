package com.github.oinsio.gnomish.domain.branch;

/**
 * The one fact the branch tip contributes to classification once the envelope is read at the
 * task's registered commit (design D13 of tighten-spec-contract): where the tip stands relative to
 * the factory register, {@code refs/gnomish-factory/<task>}. Every other fact comes from the
 * registered commit, a commit only a factory closing push names, so a record the gnome writes above
 * it changes no shape.
 *
 * <p>Four cases, closed:
 *
 * <ul>
 *   <li>{@link AtRegister} — the tip is the registered commit: the shape is the one its envelope
 *       describes, exactly as a tip read described it before the register existed;
 *   <li>{@link AboveRegister} — the tip descends from it: the commits above are the open round's
 *       gnome range, admitted or salvaged by the round-close owner and never classified;
 *   <li>{@link Diverged} — the registered commit is not an ancestor of the tip: the branch is
 *       quarantined, never guessed;
 *   <li>{@link NoRegister} — the clone holds no register for the task: a branch that never had a
 *       factory closing commit, or one from before the register existed. Only a tip that carries no
 *       envelope at all is the {@code Bare} branch it then can be; any other is quarantined.
 * </ul>
 *
 * <p>Implements FR13, NFR-S2 of tighten-spec-contract.
 */
public sealed interface TipRelation {

    /** The branch tip is the registered commit. */
    record AtRegister() implements TipRelation {}

    /** The branch tip descends from the registered commit and is not equal to it. */
    record AboveRegister() implements TipRelation {}

    /**
     * The registered commit is neither the tip nor one of its ancestors.
     *
     * @param register the registered commit's id
     * @param tip the branch tip's id
     */
    record Diverged(String register, String tip) implements TipRelation {}

    /**
     * The clone holds no register for the task.
     *
     * @param register the register's ref name, which resolved to nothing
     * @param tip the branch tip's id
     * @param tipCarriesEnvelope whether the tip's tree carries the {@code .gnomish-task/} directory
     *     — a STARTED commit's trace; read for presence only, never for content
     */
    record NoRegister(String register, String tip, boolean tipCarriesEnvelope) implements TipRelation {}
}
