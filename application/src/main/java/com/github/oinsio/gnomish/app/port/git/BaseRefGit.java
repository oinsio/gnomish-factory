package com.github.oinsio.gnomish.app.port.git;

import java.nio.file.Path;

/**
 * The base-ref half of the task-git capability set (FR5, FR6 of add-base-ref-resolution): what the
 * factory clone's {@code origin} names as its default branch, and the narrow, fail-closed refresh of
 * one base ref before anything durable is cut from it.
 *
 * <p>Both answers are typed outcomes rather than exceptions, because their failure arms are the
 * whole point: a clone with no remote, a remote that named no default branch, a ref origin does not
 * hold, and a remote that never answered are four different facts, and only the last is an
 * infrastructure failure. The use-case layer classifies them by cause — a refusal parks or refuses,
 * an outage releases — and never by the step that observed them (FR9).
 *
 * <p>Startup ({@code serve}/{@code take}) uses both calls to bind the trusted configuration tier
 * from the refreshed default branch (design D14, D15); the claim path uses {@link #refresh} for the
 * resolved base (task 6.2 of add-base-ref-resolution).
 *
 * <p>Implements FR5, FR6, FR9 of add-base-ref-resolution.
 */
public interface BaseRefGit {

    /**
     * Asks {@code origin} which branch it names as its default — one bounded remote read, never a
     * hardcoded {@code main}.
     *
     * @param cloneDir the factory clone whose {@code origin} is asked; never null
     * @return the discovered branch, or the typed reason there is none; never null
     */
    DefaultBranchDiscovery discoverDefaultBranch(Path cloneDir);

    /**
     * Refreshes one base ref from {@code origin} with a narrow fetch of exactly that ref, touching
     * neither the working tree, the index, {@code HEAD}, nor any local branch of the clone.
     *
     * @param cloneDir the factory clone the fetch lands in; never null
     * @param ref the resolved base ref — a branch, a tag, or a bare commit id; never null
     * @return the refreshed commit, or the typed refusal or outage; never null
     */
    BaseRefreshOutcome refresh(Path cloneDir, String ref);

    /**
     * Resolves {@code ref}'s current tip for a resume rebind (FR12, design D13 of
     * add-base-ref-resolution): a remote-configured clone narrow-fetches it exactly as {@link
     * #refresh} does — a SHA pin costs no network there either — while a clone with no {@code
     * origin} at all binds from the ref's local tip instead of refusing, since a resume with
     * nothing to fetch from is a legitimate shape a fresh claim never sees.
     *
     * @param cloneDir the factory clone the resolution runs against; never null
     * @param ref the task's pinned base ref — a branch, a tag, or a bare commit id; never null
     * @return the resolved tip, or the typed refusal or outage; never null
     */
    ResumeBaseOutcome resolveForResume(Path cloneDir, String ref);

    /**
     * The tracker-free reachability probe the remote outage gate consults (FR14, task 7.3 of
     * add-base-ref-resolution): one bounded {@code git ls-remote origin HEAD} against {@code
     * cloneDir}, no object transfer, no ref written, no tracker call. Distinct from {@link
     * #refresh}: this answers only "did origin answer at all", never advances, interprets, or
     * classifies any ref.
     *
     * @param cloneDir the factory clone the probe runs from; never null
     * @return true only when {@code origin} answered the read
     */
    boolean probe(Path cloneDir);

    /**
     * The stand-in for a {@link TaskGit} bundle built without this capability — the port-fake unit
     * specs of the claim chain, which never reach a base-ref read. Fail-closed rather than a silent
     * answer: a spec that does reach one learns so from the exception instead of from a guessed
     * {@code main}. The resume chain (design D13, FR12 of add-base-ref-resolution) always resolves
     * its pinned base ref, so its own port-fake specs need a working {@link ResumeBaseOutcome}
     * stub in place of this constant, never this one.
     */
    BaseRefGit UNWIRED = new Unwired();

    /** The {@link #UNWIRED} realization: every call refuses with the missing capability named. */
    final class Unwired implements BaseRefGit {

        private Unwired() {}

        @Override
        public DefaultBranchDiscovery discoverDefaultBranch(Path cloneDir) {
            throw unwired("discoverDefaultBranch");
        }

        @Override
        public BaseRefreshOutcome refresh(Path cloneDir, String ref) {
            throw unwired("refresh");
        }

        @Override
        public ResumeBaseOutcome resolveForResume(Path cloneDir, String ref) {
            throw unwired("resolveForResume");
        }

        @Override
        public boolean probe(Path cloneDir) {
            throw unwired("probe");
        }

        private static IllegalStateException unwired(String call) {
            return new IllegalStateException("BaseRefGit." + call
                    + " reached a TaskGit bundle built without a base-ref capability; wire the real one");
        }
    }
}
