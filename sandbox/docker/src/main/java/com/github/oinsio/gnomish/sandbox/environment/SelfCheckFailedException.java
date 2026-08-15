package com.github.oinsio.gnomish.sandbox.environment;

/**
 * A mandatory environment self-check probe failed (FR8, design D5): the
 * environment is rejected and no gnome-product process may execute in it. An
 * infrastructure failure by classification — at task start the task does not
 * start; at verification time the affected check or judge vote classifies as
 * cannot-verify — never a quality failure, so no stage attempt is burned. The
 * message names the failed probe, so the operator sees one clear error instead
 * of a mid-task crash (UX2).
 *
 * <p>Implements FR8, UX2 of add-sandbox-core.
 */
public final class SelfCheckFailedException extends RuntimeException {

    private final String probe;

    /**
     * @param probe the failed probe's stable name (e.g. {@code direct-egress}); never blank
     * @param detail what the probe observed; never null
     */
    public SelfCheckFailedException(String probe, String detail) {
        super("environment self-check failed at probe '" + probe + "': " + detail);
        this.probe = probe;
    }

    /**
     * The failed probe's stable name (UX2).
     *
     * @return the probe name; never null
     */
    public String probe() {
        return probe;
    }
}
