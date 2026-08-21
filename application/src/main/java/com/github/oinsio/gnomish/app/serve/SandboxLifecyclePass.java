package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.app.lease.LivenessVerdict;
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictListener;
import java.nio.file.Path;

/**
 * The construction seam every container-mode entry point (`run`, `take`, `serve`) evaluates the
 * sweep-lifecycle policy through (task 4.x of add-serve-sandbox-lifecycle): {@code application}
 * cannot name {@code sandbox/docker}'s {@code SandboxLifecycleSweep} directly (module boundary —
 * mirrors the existing {@link com.github.oinsio.gnomish.app.port.run.SandboxRunSupport}/{@link
 * TaskEnvironmentDisposal} dependency-inversion pattern), so the composition root binds the real
 * implementation and hands it down as this plain, application-typed interface. {@code cloneDir}
 * is passed per call, not baked in at construction, since {@code serve}/{@code take} only learn
 * their project directory once their arguments are parsed.
 *
 * <p>{@link #NONE} is the host-only-install no-op: an operator with no {@code
 * factory.sandbox.image} configured has no Docker objects to sweep.
 */
@FunctionalInterface
public interface SandboxLifecyclePass {

    /** No container objects to sweep (host-only install). */
    SandboxLifecyclePass NONE = (cloneDir, liveness) -> "";

    /**
     * Evaluates the sweep-lifecycle policy once for this project.
     *
     * @param cloneDir the factory clone the project identity is resolved from; never null
     * @param liveness the current tracked-object liveness verdict; never null
     * @return a one-line per-category summary ({@code take}'s finish-report line, NFR-O4); blank
     *     when nothing was evaluated (including {@link #NONE})
     */
    String run(Path cloneDir, LivenessVerdict liveness);

    /**
     * Evaluates the policy once, with {@code extraSink} receiving every verdict beside the
     * implementation's own logging sink — how the daemon attaches its snapshot vitals and ledger
     * writers to a pass it does not construct (tasks 6.1, 6.2). A default rather than a second
     * abstract method so this interface stays a lambda target for {@link #NONE} and for the
     * host-only and test passes, which have no verdicts to deliver anywhere.
     *
     * @param cloneDir the factory clone the project identity is resolved from; never null
     * @param liveness the current tracked-object liveness verdict; never null
     * @param extraSink an additional verdict sink; never null
     * @return the same one-line summary as {@link #run(Path, LivenessVerdict)}
     */
    default String run(Path cloneDir, LivenessVerdict liveness, SweepVerdictListener extraSink) {
        return run(cloneDir, liveness);
    }
}
