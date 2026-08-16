package com.github.oinsio.gnomish.adapter.git;

import java.io.Serial;

/**
 * Thrown when the harvest fetch from a task environment fails for any reason
 * other than git's own fast-forward refusal (that one is {@link
 * HarvestRefusedException}) or a container runtime outage (that one is {@link
 * com.github.oinsio.gnomish.sandbox.environment.DockerUnavailableException},
 * an infrastructure failure): the transport command died, the container is
 * gone, the in-box repository is missing or corrupt. Resume's salvage protocol
 * treats a persistently unreachable environment as lost and falls back to the
 * last harvested branch state (FR6).
 *
 * <p>Implements FR5 of add-sandbox-core.
 */
public final class HarvestFailedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param branch the task branch whose harvest failed
     * @param stderr the fetch's error output, for the log trail
     */
    public HarvestFailedException(String branch, String stderr) {
        super("harvest failed for branch \"" + branch + "\": " + stderr.strip());
    }
}
