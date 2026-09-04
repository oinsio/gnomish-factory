package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.domain.engine.port.Clock;
import com.github.oinsio.gnomish.logtext.OperatorEvent;
import com.github.oinsio.gnomish.sandbox.ExecHandle;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The container adapter's {@link ExecHandle}: the host handle plus one forensic
 * annotation (FR1, design D1 of polish-sandbox-forensics). When an in-box process
 * exits with {@value #FORCED_TERMINATE} — the shell's encoding of SIGKILL, which the
 * cgroup OOM killer also fires — the wrapper reads the container's {@code OOMKilled}
 * runtime state and, when it is set, says so at the point the exit code is surfaced,
 * so an operator raises {@code factory.sandbox} memory limits instead of bisecting a
 * build.
 *
 * <p>The exec seam is the one point every in-box process passes through — agent
 * rounds, command checks, self-check probes — which is why the annotation lives here
 * and not in the consumers that classify exit codes: those are deliberately
 * runtime-agnostic, and host mode has no OOM state to read at all.
 *
 * <p>Advisory only (NFR-R1): the exit code, the {@link Wait} outcome and every
 * classification downstream are exactly what they would be without the wrapper, and a
 * failed or unreadable inspect degrades to no annotation rather than to a new failure.
 * The claim is deliberately one-directional and worded "likely": on some
 * runtime/cgroup combinations an OOM kill of an exec'd child leaves the container's
 * own flag {@code false}, so a missing annotation is the status quo, never a denial.
 */
final class OomAnnotatedExecHandle implements ExecHandle {

    /** The exit code a SIGKILL'd process reports — 128 + 9; also what an OOM kill looks like. */
    static final int FORCED_TERMINATE = 137;

    private static final Logger log = LoggerFactory.getLogger(OomAnnotatedExecHandle.class);

    private final ExecHandle delegate;
    private final DockerCli docker;
    private final String container;

    /**
     * @param delegate the started process's host handle; never null
     * @param docker the docker subprocess seam the state inspect runs through; never null
     * @param container the concrete container name the process runs in, ready to paste into
     *     {@code docker logs} (FR2); never blank
     */
    OomAnnotatedExecHandle(ExecHandle delegate, DockerCli docker, String container) {
        this.delegate = delegate;
        this.docker = docker;
        this.container = container;
    }

    @Override
    public InputStream output() {
        return delegate.output();
    }

    @Override
    public Instant startedAt() {
        return delegate.startedAt();
    }

    @Override
    public Wait waitForExitOrTimeout(Duration timeout, Clock clock) {
        // Deliberately unannotated: `Wait.Exited` carries no exit code, so there is nothing here
        // to annotate — `waitForExit()` is the one seam that surfaces one (design D1).
        return delegate.waitForExitOrTimeout(timeout, clock);
    }

    @Override
    public int waitForExit() {
        int exitCode = delegate.waitForExit();
        if (exitCode == FORCED_TERMINATE) {
            annotate(exitCode);
        }
        return exitCode;
    }

    /**
     * Reads the container's OOM state and warns when it is set. Best-effort in both
     * directions (NFR-R1): a docker command that answers non-zero, and a runtime that is
     * unreachable altogether, both leave the failure reported exactly as it would be
     * without this call.
     */
    private void annotate(int exitCode) {
        try {
            DockerResult state = docker.run(DockerCommands.inspectContainerState(container));
            if (state.ok() && DockerCommands.oomKilled(state.stdout())) {
                log.warn(
                        OperatorEvent.CONTAINER_EXEC_LIKELY_OOM_KILLED.head()
                                + "a process in container {} exited {} and the container reports OOMKilled:"
                                + " likely container OOM — consider raising factory.sandbox memory limits",
                        container,
                        exitCode);
            }
        } catch (RuntimeException e) {
            log.debug("best-effort OOM state read of {} failed", container, e);
        }
    }
}
