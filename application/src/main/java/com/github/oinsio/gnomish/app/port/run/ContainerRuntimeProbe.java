package com.github.oinsio.gnomish.app.port.run;

/**
 * Whether the container runtime answers at all — the container-mode prerequisite the
 * execution-mode decision consults before it plans a container run. An unreachable runtime is a
 * fail-closed refusal naming the ways out (install or start the runtime, or bind host mode
 * explicitly), never a silent fallback to host mode (FR14, D13 of add-sandbox-core).
 *
 * <p>A role interface the application owns, rather than the JDK functional boolean supplier: the type
 * names the one question it answers, so a probe cannot be confused with any other boolean seam,
 * and specs double a type of their own (design D22 of supervise-daemon-loops-and-embed-dashboard).
 * The composition root binds it to the Docker adapter's probe; specs script it.
 *
 * <p>Implements FR18 of supervise-daemon-loops-and-embed-dashboard; FR14, D13 of add-sandbox-core.
 */
@FunctionalInterface
public interface ContainerRuntimeProbe {

    /**
     * Asks the container runtime whether it answers.
     *
     * @return true iff the container runtime answered; false when it is not installed or not
     *     reachable
     */
    boolean available();
}
