package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.gittransfer.QuarantineRef;
import com.github.oinsio.gnomish.gittransfer.Refspec;
import com.github.oinsio.gnomish.gittransfer.RegisterRef;

/**
 * The one spelling, from a taskId, of the factory-owned refs beside a task branch in the factory
 * clone (design D13, D18 of tighten-spec-contract): the factory register, the refspecs a touchpoint
 * fetch lands origin's register with, and the harvest quarantine. The writers ({@link
 * FactoryRegister}, {@link ClosingPush}) and every reader of the registered commit name the
 * register through {@link #of}, so they agree on one spelling ({@link RegisterRef} and {@link
 * QuarantineRef} hold the namespaces). The task branch's own refs are {@link TaskBranchRefs}'.
 *
 * <p>Implements FR13, NFR-S2 of tighten-spec-contract.
 */
final class FactoryRegisterRefs {

    private FactoryRegisterRefs() {}

    /**
     * The register of the task {@code taskId}: {@code refs/gnomish-factory/<sanitized taskId>}, the
     * same core string the branch {@code gnomish/<sanitized taskId>} carries.
     *
     * @param taskId the tracker's original taskId
     * @return the register
     */
    static RegisterRef of(String taskId) {
        return RegisterRef.forTask(TaskIdSanitizer.sanitize(taskId));
    }

    /**
     * The harvest quarantine of the task {@code taskId} (design D18): {@code
     * refs/gnomish-quarantine/<sanitized taskId>}, where a harvest lands the box's branch and the
     * admission ({@link HarvestAdmission}) reads it from.
     *
     * @param taskId the tracker's original taskId, or the core string a branch name carries
     * @return the quarantine
     */
    static QuarantineRef quarantine(String taskId) {
        return QuarantineRef.forTask(TaskIdSanitizer.sanitize(taskId));
    }

    /**
     * The refspec a touchpoint fetch that has no local branch to protect lands origin's register
     * with, straight into the clone's register: {@code +<register>:<register>}.
     */
    static Refspec fetchedInPlace(String taskId) {
        String name = of(taskId).name();
        return new Refspec("+" + name + ":" + name);
    }

    /**
     * The refspec a touchpoint fetch beside a local branch lands origin's register with: its
     * remote-tracking name, so a clone register ahead of origin's (a closing push origin has not
     * received yet) is never rolled back by a fetch.
     */
    static Refspec fetchedTracking(String taskId) {
        RegisterRef register = of(taskId);
        return new Refspec("+" + register.name() + ":" + register.trackingName());
    }
}
