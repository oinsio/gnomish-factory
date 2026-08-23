package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner;
import com.github.oinsio.gnomish.adapter.git.OriginRemote;
import com.github.oinsio.gnomish.app.git.ProjectIdentity;
import com.github.oinsio.gnomish.app.git.ProjectScope;
import com.github.oinsio.gnomish.app.lease.LivenessVerdict;
import com.github.oinsio.gnomish.app.sandboxlifecycle.Slf4jSweepVerdictListener;
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepSummaryListener;
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictFanout;
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictListener;
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass;
import com.github.oinsio.gnomish.sandbox.SandboxProperties;
import com.github.oinsio.gnomish.sandbox.environment.SandboxLifecycleSweep;
import com.github.oinsio.gnomish.sandbox.environment.SandboxLifecycleThresholds;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.NullMarked;

/**
 * Builds the real {@link SandboxLifecyclePass} (task 4.x of add-serve-sandbox-lifecycle): the
 * composition root is the only place {@code application} code's {@link SandboxLifecyclePass} seam
 * and {@code sandbox/docker}'s {@link SandboxLifecycleSweep} ever meet, exactly the same
 * dependency-inversion shape {@link ContainerRunSupportFactory} already uses for {@link
 * com.github.oinsio.gnomish.app.port.run.SandboxRunSupport}.
 *
 * <p>{@code cloneDir} is resolved per call (project identity needs it), not cached: {@code
 * serve}'s tick calls it repeatedly against the SAME directory for the daemon's lifetime, an
 * acceptable one-{@code git remote}-call-per-tick cost alongside the sweep's own Docker listings.
 */
// Null-marked explicitly (JSpecify): this module carries no package-info, and the application
// module's one does not reach this source root, so without the class-level marker the
// SandboxLifecyclePass overrides here read as unannotated against their null-marked supertype.
@NullMarked
final class SandboxLifecyclePassFactory {

    private SandboxLifecyclePassFactory() {}

    /**
     * Builds the real sweep-lifecycle pass, or {@link SandboxLifecyclePass#NONE}.
     *
     * @param sandboxProperties the operator sandbox config; {@link SandboxProperties#image()} null
     *     means a host-only install — no Docker objects exist, so {@link SandboxLifecyclePass#NONE}
     *     is returned
     * @param clock supplies the instant every object's age is measured against
     * @return the real pass, or {@link SandboxLifecyclePass#NONE} on a host-only install
     */
    static SandboxLifecyclePass create(SandboxProperties sandboxProperties, Clock clock) {
        if (sandboxProperties.image() == null) {
            return SandboxLifecyclePass.NONE;
        }
        SandboxLifecycleThresholds thresholds = new SandboxLifecycleThresholds(
                sandboxProperties.minimumAge(),
                sandboxProperties.keptReapAge(),
                sandboxProperties.manualRunningStopAge());
        return new RealPass(new GitProcessRunner(), sandboxProperties, thresholds, clock);
    }

    /**
     * The real, Docker-backed pass — a named class rather than a lambda so {@link #run} (a real
     * method, unlike a lambda body) can carry the exemption below precisely.
     */
    private record RealPass(
            GitProcessRunner runner,
            SandboxProperties sandboxProperties,
            SandboxLifecycleThresholds thresholds,
            Clock clock)
            implements SandboxLifecyclePass {

        @Override
        public String run(Path cloneDir, LivenessVerdict liveness) {
            return run(cloneDir, liveness, SweepVerdictListener.IGNORE);
        }

        /**
         * Resolves this project's identity scope — the stamped identity plus, during the
         * transition, the legacy alias objects created before URL normalization still carry (FR3
         * of normalize-project-identity-url) — fans the verdicts out to the SLF4J sink and the
         * caller's extra sink (the daemon's vitals + ledger writers, tasks 6.1/6.2), and returns
         * the tallied summary line. Only the sweep hand-off itself is exempt from mutation (see
         * {@link #sweepAndSummarize}); everything around it, this method's own return value
         * included, is asserted by {@code SandboxLifecyclePassFactorySpec} with no Docker daemon
         * involved.
         */
        @Override
        public String run(Path cloneDir, LivenessVerdict liveness, SweepVerdictListener extraSink) {
            ProjectScope scope = ProjectIdentity.resolveScope(
                    sandboxProperties.projectId(), new OriginRemote(runner).url(cloneDir), cloneDir);
            var summary = new SweepSummaryListener(
                    new SweepVerdictFanout(List.of(new Slf4jSweepVerdictListener(), extraSink)));
            return sweepAndSummarize(scope, liveness, clock.instant(), summary);
        }

        /**
         * {@code @DoNotMutate} for the "out-of-process delegation" reason of
         * `.claude/rules/testing.md`: the body is one hand-off into {@link SandboxLifecycleSweep} —
         * no decision, no branch, nothing computed here — followed by handing back the tally the
         * listener kept. The sweep constructs a real {@code
         * com.github.oinsio.gnomish.sandbox.environment.DockerCli}, so the hand-off's observable
         * effect in a fast, docker-daemon-free unit test is identical to the call never happening:
         * an empty or unreachable listing emits no verdict, so the returned line reads "nothing to
         * report" whether or not the sweep ran. Killing a "call removed" mutant here would require
         * either mocking the (deliberately package-private, unmockable) {@code DockerCli} or
         * depending on a live daemon's actual state. It exists as its own method so that exactly
         * this hand-off leaves the mutation gate: {@link #run(Path, LivenessVerdict,
         * SweepVerdictListener)} returns its result rather than calling it for effect, which keeps
         * both overloads' return values inside the gate.
         *
         * <p>The sweep's own decisions are mutation-covered in {@code sandbox/docker} by {@code
         * SandboxLifecycleSweepSpec} and the {@code SandboxLifecycleDecisionSpecBase} family
         * (tracked, manual, remnant, boundary, failed-action); the tally is covered by {@code
         * SweepSummaryListenerSpec}; and this line is exercised end to end by the Docker-gated
         * {@code SandboxLifecycleRemnantReapE2ESpec}, {@code SandboxLifecycleZombieE2ESpec},
         * {@code SandboxLifecycleProjectScopingE2ESpec}, {@code SandboxLifecycleLaunchRaceE2ESpec}
         * and {@code SandboxLifecycleCrossInstanceE2ESpec}.
         */
        @DoNotMutate
        private String sweepAndSummarize(
                ProjectScope scope, LivenessVerdict liveness, Instant now, SweepSummaryListener summary) {
            SandboxLifecycleSweep.create(summary).evaluate(scope, liveness, now, thresholds);
            return summary.summaryLine();
        }
    }
}
