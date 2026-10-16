package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.BranchAdoption;
import com.github.oinsio.gnomish.app.port.git.BranchLocation;
import com.github.oinsio.gnomish.app.port.git.BranchLocationRefusedException;
import com.github.oinsio.gnomish.app.port.git.BranchLocationUnavailableException;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one way a task branch a clone holds only as a remote-tracking ref becomes local (design D9 of
 * fix-terminal-receipt-convergence): one {@code update-ref --stdin} transaction creating {@code
 * refs/heads/gnomish/<task>} at the tracking tip under a compare-and-swap on absence. No {@code git
 * branch}, no checkout, no name for git to resolve, and the register is not touched — the
 * touchpoint fetch the locate ran already landed origin's register in the clone's.
 *
 * <p>Two adopters of one clone race only at that {@code create}, the first and only lock behind a
 * decision ({@code lock-scope.md}, "Writers from different processes"): the loser re-reads through
 * the same {@link TaskBranchLocator} parse (crash-consistency item 14) and answers {@link
 * BranchAdoption.AlreadyLocal}; a refusal that leaves no local branch is not a lost race and
 * throws. Nothing undoes an adoption (design D12): a cleanup whose push origin rejects leaves the
 * adopted head as a diverged local line, which stands: origin carries the cleanup, every reader
 * judges the task finished, and no recovery owner removes the line.
 *
 * <p>Implements FR7, FR9 of fix-terminal-receipt-convergence.
 */
public final class TaskBranchAdoption {

    private static final Logger log = LoggerFactory.getLogger(TaskBranchAdoption.class);

    private final GitRunner runner;
    private final TaskBranchLocator locator;
    private final FactoryRegister register;

    /**
     * @param runner the git subprocess seam; never null
     * @param locator the locator a lost {@code create} re-reads the branch through; never null
     */
    public TaskBranchAdoption(GitRunner runner, TaskBranchLocator locator) {
        this.runner = runner;
        this.locator = locator;
        this.register = new FactoryRegister(runner);
    }

    /**
     * Makes {@code taskId}'s branch local over the location a lookup just answered.
     *
     * @param cloneDir the clone; never null
     * @param taskId the tracker's original taskId; never null
     * @param location what {@link TaskBranchLocator#locate} answered for {@code taskId}; never null
     * @return adopted at the tracking tip, already local, or nowhere
     * @throws BranchLocationUnavailableException when the location is unestablished
     * @throws BranchLocationRefusedException when object validation refused the branch
     * @throws IllegalStateException when the {@code create} was refused and no local branch exists
     */
    public BranchAdoption adopt(Path cloneDir, String taskId, BranchLocation location) {
        return switch (location) {
            case BranchLocation.Local ignored -> new BranchAdoption.AlreadyLocal(taskId);
            case BranchLocation.RemoteTracking(String trackingRef) -> create(cloneDir, taskId, trackingRef);
            case BranchLocation.NotFound ignored -> new BranchAdoption.Nowhere();
            case BranchLocation.Unavailable(UntrustedText reason) ->
                throw new BranchLocationUnavailableException(taskId, reason);
            case BranchLocation.Refused(UntrustedText report) ->
                throw new BranchLocationRefusedException(taskId, report);
        };
    }

    private BranchAdoption create(Path cloneDir, String taskId, String trackingRef) {
        String tip = VerifiedTip.required(
                trackingRef, "rev-parse", runner.run(cloneDir, "rev-parse", "--verify", "--quiet", trackingRef));
        GitCommandResult created = register.createHead(cloneDir, taskId, tip);
        String head = TaskBranchRefs.head(taskId);
        if (created.succeeded()) {
            // An anchor, not chatter: this clone now continues work another instance pushed, and
            // nothing else in the log says the local line was adopted rather than continued (FR5
            // of harden-logging-observability).
            log.info("adopted branch {} for task {} from {} at {}", head, taskId, trackingRef, tip);
            return new BranchAdoption.Adopted(taskId);
        }
        // The effect decides, never the exit code: a lost compare-and-swap leaves the winner's head.
        if (locator.locate(cloneDir, taskId) instanceof BranchLocation.Local) {
            log.debug("another adopter created {} first; continuing on it", head);
            return new BranchAdoption.AlreadyLocal(taskId);
        }
        // git's stderr leaves its carrier through the log exit, where it enters this message.
        throw new IllegalStateException("could not adopt branch " + head + " from " + trackingRef + ": "
                + created.failureDetail("ref transaction").forLog());
    }
}
