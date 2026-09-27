package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit
import com.github.oinsio.gnomish.app.port.pipeline.PipelineSource
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import java.time.Duration
import spock.lang.Specification

/**
 * {@link SlotWiringFactory}: the one construction of a {@link SlotWiring} that {@code take} and
 * {@code serve} used to spell each by hand (design D9 of collapse-composition-roots) — the built
 * wiring carries the factory's equipment and the caller's own bound tracker, git and heartbeat.
 *
 * Implements FR1 of collapse-composition-roots.
 */
class SlotWiringFactorySpec extends Specification implements RunChainFakes {

    private static final TrackerConfig CONFIG = new TrackerConfig('github', 5)

    def "the wiring carries the factory's equipment and the caller's bound tracker, git and heartbeat"() {
        given:
        def plain = Mock(RunAssembly)
        def listened = Mock(RunAssembly)
        def sourced = Stub(RunAssembly)
        def source = Stub(PipelineSource)
        def support = ContainerTakeSupport.hostOnly()
        def tracker = Stub(Tracker)
        def adapterFactory = Stub(TrackerAdapterFactory) {
            credentialEnvVars(CONFIG) >> ['GNOMISH_GITHUB_TOKEN']
        }
        def bound = new BoundTracker(pipeline(), DEFAULT_TRUSTED_BASE, CONFIG, adapterFactory, tracker, INSTANCE)
        def git = new TaskGit(Stub(TaskStoreGit), Stub(TaskBranchGit), Stub(TaskWorktreeGit), new ClaimEpochBook())
        def heartbeat = TakeHeartbeat.forRun(tracker, CONFIG, { Duration d -> } as Sleeper)
        def factory = new SlotWiringFactory(plain, WORKTREES_ROOT, 'taskId', FIXED_CLOCK, support, source)

        when:
        def wiring = factory.slotWiring(bound, git, heartbeat)

        then: "the assembly is the heartbeat-listening copy over the factory's pipeline source"
        1 * plain.withExtraListener(heartbeat.progress()) >> listened
        1 * listened.withPipelineSource(source) >> sourced
        wiring.assembly().is(sourced)

        and: "the caller's git and heartbeat tenure, the factory's equipment"
        wiring.git().is(git)
        wiring.tenure() == heartbeat.tenure()
        wiring.worktreesRoot() == WORKTREES_ROOT
        wiring.taskIdMdcKey() == 'taskId'
        wiring.containerTakeSupport().is(support)

        and: "the bound tracker's members: the abort handler writes to the tracker the slot claims through"
        wiring.abort().handler().tracker().is(tracker)
        wiring.abort().handler().clock().is(FIXED_CLOCK)
        wiring.abort().threshold() == 5
        wiring.credentialEnvVarsToScrub() == ['GNOMISH_GITHUB_TOKEN']
        wiring.trustedBase().is(DEFAULT_TRUSTED_BASE)
    }
}
