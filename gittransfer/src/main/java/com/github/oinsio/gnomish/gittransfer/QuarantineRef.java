package com.github.oinsio.gnomish.gittransfer;

import java.util.Objects;

/**
 * The harvest quarantine of one task as a ref name: {@code refs/gnomish-quarantine/<task>}, in the
 * factory clone only (design D18 of tighten-spec-contract). A harvest fetches the box's task branch
 * here and nowhere else, so what the gnome committed in its box exists factory-side as objects and
 * one scratch ref, and becomes branch state only when the git adapter's admission has inspected the
 * range and moved the branch to it — git's own receive quarantine, where objects wait until the
 * pre-receive check passed.
 *
 * <p>This leaf is the one spelling of the namespace, as {@link RegisterRef} is of the register's; the
 * harvest refspec is built here too ({@link #harvestRefspec}), and {@link GitTransfer#harvest} takes
 * only this value as a destination, so no harvest can name the task branch. The refspec is forced:
 * the quarantine mirrors the box, whatever it holds; whether the box's line descends from the branch
 * is the admission's question, answered against the branch tip rather than against whatever the
 * previous harvest left here.
 *
 * <p>Implements FR13, NFR-S2 of tighten-spec-contract.
 *
 * @param name the full ref name, {@code refs/gnomish-quarantine/<task>}
 */
public record QuarantineRef(String name) {

    /** The namespace every quarantine lives under, in the factory clone. */
    public static final String NAMESPACE = "refs/gnomish-quarantine/";

    public QuarantineRef {
        if (!name.startsWith(NAMESPACE)) {
            throw new IllegalArgumentException("a harvest quarantine lives under " + NAMESPACE + ": " + name);
        }
        String task = name.substring(NAMESPACE.length());
        if (task.isBlank() || task.contains("/") || task.contains(":") || task.startsWith("-")) {
            throw new IllegalArgumentException(
                    "a harvest quarantine names one task, with no '/', ':' or leading '-': " + name);
        }
    }

    /**
     * The quarantine of {@code task}.
     *
     * @param task the task's ref-safe core string, the part of its branch name after the prefix
     * @return the quarantine value
     */
    public static QuarantineRef forTask(String task) {
        Objects.requireNonNull(task, "task");
        return new QuarantineRef(NAMESPACE + task);
    }

    /**
     * The harvest's refspec: the box's {@code refs/heads/<boxBranch>}, forced into this quarantine.
     *
     * @param boxBranch the task branch as the box names it, e.g. {@code gnomish/T-1}
     * @return the refspec {@code +refs/heads/<boxBranch>:<name>}
     */
    public Refspec harvestRefspec(String boxBranch) {
        Objects.requireNonNull(boxBranch, "boxBranch");
        return new Refspec("+refs/heads/" + boxBranch + ":" + name);
    }
}
