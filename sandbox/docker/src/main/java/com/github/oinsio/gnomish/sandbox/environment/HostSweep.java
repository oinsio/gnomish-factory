package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.subprocess.GroupSweep;
import com.github.oinsio.gnomish.subprocess.ProcessGroupSweep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The sandbox's waiters' half of the process group sweep (design D19 of
 * fix-terminal-receipt-convergence): the sweep itself is {@code :subprocess}'s, which does not log,
 * so the one DEBUG line a sweep that found a survivor owes (NFR-O3) is said here, for every waiter
 * of this module — {@link HostExecHandle} after each wait, {@link ContainerFileChannel} after its
 * drains, and {@link DockerCommandReport} for a management command's capture. A sweep that found the
 * group empty is the healthy case and says nothing.
 *
 * <p>Implements FR21, NFR-O3 of fix-terminal-receipt-convergence (D19).
 */
final class HostSweep {

    private static final Logger log = LoggerFactory.getLogger(HostSweep.class);

    /** Stateless, over the host's own {@code kill}. */
    private static final ProcessGroupSweep SWEEP = new ProcessGroupSweep();

    private HostSweep() {}

    /**
     * Sweeps the group {@code process} leads, once its wait has resolved, and reports what it found.
     *
     * @param process the started process, already exited or terminated
     * @param what the factory's own name for the invocation, for the DEBUG line
     */
    static void after(Process process, String what) {
        report(SWEEP.sweep(process), what);
    }

    /**
     * @param swept what a sweep found
     * @param what the factory's own name for the invocation, for the DEBUG line
     */
    static void report(GroupSweep swept, String what) {
        if (swept.foundSurvivors()) {
            log.debug("{}: {}", what, swept.phrase());
        }
    }
}
