package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.adapter.git.AdmittedHarvest
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.ContainerHarvestFetch
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.Sandbox
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.e2e.gitea.GiteaAvailability
import com.github.oinsio.gnomish.sandbox.AdapterBindingRegistry
import com.github.oinsio.gnomish.sandbox.BindingProperties
import com.github.oinsio.gnomish.sandbox.BindingResolver
import com.github.oinsio.gnomish.sandbox.BindingTrustTable
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.HostBindingProvider
import com.github.oinsio.gnomish.sandbox.ResourceLimits
import com.github.oinsio.gnomish.sandbox.SegmentPlanner
import com.github.oinsio.gnomish.sandbox.StderrPolicy
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import spock.lang.IgnoreIf
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR12, FR13 of add-sandbox-core against a real daemon: a {@code requires-fresh} stage opens its
 * own segment, and the lease crosses into it by harvest, dispose and materialize — so whatever the
 * previous stage left outside the working copy (a shim in a declared home directory, a file in
 * {@code /tmp}) is gone, while everything it committed arrives through the branch. The plan comes
 * from the real {@link SegmentPlanner} over the container binding.
 */
@IgnoreIf({
    !GiteaAvailability.dockerAvailable()
})
class ContainerFreshSegmentSpec extends Specification implements BareGitRepoFixture {

    static final String BRANCH = 'gnomish/fresh-1'
    static final ResourceLimits LIMITS = new ResourceLimits('2', '512m', 256L, '10g')

    @TempDir
    Path tempDir

    private final GitProcessRunner runner = new GitProcessRunner()
    private EnvironmentLease lease

    def setupSpec() {
        GitSandboxImage.ensureBuilt()
    }

    def cleanup() {
        lease?.dispose()
    }

    private static StageDefinition stage(String name, boolean requiresFresh) {
        new StageDefinition(name, 'purpose', [], [],
        new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'm', [:], new Sandbox([], requiresFresh)),
        'instructions.md', [], new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    private static String inBox(TaskExecutionEnvironment e, String script) {
        def handle = e.exec(new ExecCommand(['sh', '-c', script], [:], null, StderrPolicy.MERGED))
        def out = new String(handle.output().stream().readAllBytes(), StandardCharsets.UTF_8).trim()
        handle.waitForExit()
        out
    }

    // Scenario: sandbox/execution-environment / Fresh box resets out-of-branch poisoning
    def "FR13: a requires-fresh stage starts in a box holding only the image and the branch state"() {
        given: 'a two-stage plan where the second stage requires a fresh box, over the container binding'
        def clone = initWorkingRepo(tempDir, 'factory-clone')
        commit(clone, 'seed.txt', 'seed')
        gitOutput(clone, 'branch', BRANCH)
        gitOutput(clone, 'update-ref', 'refs/gnomish-factory/fresh-1', 'refs/heads/' + BRANCH)
        def planner = new SegmentPlanner(new BindingResolver(new BindingProperties('container', [:]),
        AdapterBindingRegistry.ratified([
            new HostBindingProvider(),
            new ContainerBindingProvider()
        ], BindingTrustTable.firstParty())))
        def segments = planner.plan(new PipelineDefinition('1.0.0', new AutonomyLimits(3),
                [
                    stage('implement', false),
                    stage('review', true)
                ]))
        def key = 'fresh-' + System.nanoTime()
        lease = new EnvironmentLease({
            ->
            new ContainerTaskExecutionEnvironment(
                    BoxEquipmentFixture.forBox(new DockerCli(), GitSandboxImage.IMAGE, LIMITS, new VirtualClock()),
                    key, new BoxGitLink(clone, new ContainerHarvestFetch(runner, clone)), ChildEnvAllowlist.none(), 'proj-1')
        }, BRANCH, segments)

        when: 'the first stage poisons the box outside its working copy and commits its work'
        def first = lease.environmentFor('implement')
        // The poison goes where the gnome may write outside its working copy: a declared home
        //     directory and /tmp (the home itself and its startup files are root-owned, design D14 of
        //     add-sandbox-base-image).
        inBox(first, 'mkdir -p $HOME/.cache/shims /tmp/shims && echo "echo hijacked" > $HOME/.cache/shims/git && echo x > /tmp/shims/marker')
        def poisoned = inBox(first, 'ls $HOME/.cache/shims/git /tmp/shims/marker').readLines().size()
        inBox(first, "echo work > work.txt && git add -A && git commit -q -m 'stage one'")

        and: "the stage's work is admitted onto the branch, as its rounds' closes admit it (design D18)"
        first.harvest()
        assert AdmittedHarvest.admitted(AdmittedHarvest.admit(clone, 'fresh-1'))

        and: 'the fresh stage takes its box'
        def second = lease.environmentFor('review')

        then: 'the plan split the stages, and the second box is a different one'
        poisoned == 2
        segments.size() == 2
        !second.is(first)

        and: 'nothing the first stage left outside the working copy survived; the declared directory is the image\'s again'
        inBox(second, 'ls $HOME/.cache/shims /tmp/shims 2>/dev/null; true') == ''
        inBox(second, 'test -d $HOME/.cache && test -w $HOME/.cache && echo writable') == 'writable'

        and: 'the branch state arrived: the committed work, on top of the seed, under the image\'s own git'
        inBox(second, 'cat work.txt') == 'work'
        inBox(second, 'cat seed.txt') == 'seed'
        inBox(second, 'git log -1 --format=%s') == 'stage one'
    }
}
