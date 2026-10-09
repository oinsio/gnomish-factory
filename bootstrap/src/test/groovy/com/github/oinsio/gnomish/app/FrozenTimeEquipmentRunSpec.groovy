package com.github.oinsio.gnomish.app

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.GitVersionCheck
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.domain.branch.EnvelopePaths
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment
import com.github.oinsio.gnomish.sandbox.BindingProperties
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import com.github.oinsio.gnomish.sandbox.environment.GuardImageAvailability
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import org.springframework.boot.DefaultApplicationArguments
import spock.lang.IgnoreIf
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * The identity spec of design D20's single-owner row (the time equipment) of
 * supervise-daemon-loops-and-embed-dashboard: the shipped composition, assembled through {@link
 * AppAssemblyFixture} on one time equipment whose instant never advances, runs a fresh container-mode
 * {@code gnomish run} to completion — and every instant the run wrote into its task branch reads
 * that frozen instant. A component holding a second time source (a fixture's own clock, a factory
 * building real time for itself, a box stamping on the wall clock) writes some other instant and
 * fails this spec, which no component spec can see: each passes on whatever clock it was handed.
 *
 * <p>The graph has exactly one equipment: the runner, the assembly, the container supports and the
 * box timing they hand down, the board's and the dashboard's tracker wiring, and the git bundle
 * (built by the root's own bean method over the same value). Only the clock half is frozen; the
 * sleeper is the real one, because the box's guard self-check must really wait for the guard.
 *
 * <p>Roll-ups: on a frozen source a repeat-suppression period never elapses, so the run logs none,
 * whatever it repeats — the suppressor-on-one-source half of FR19 is driven where a period can
 * elapse, on a virtual source the spec advances ({@code HeartbeatOutageSuppressionSpec}).
 *
 * <p>Docker- and guard-image-gated, like every container end-to-end spec.
 *
 * <p>Implements FR18, FR19, FR22 of supervise-daemon-loops-and-embed-dashboard ("A frozen equipment
 * freezes the whole run").
 */
@Timeout(value = 420, unit = TimeUnit.SECONDS)
@IgnoreIf(
value = {
    !GuardImageAvailability.available()
},
reason = 'Docker daemon or guard image unavailable — Docker is a dev/CI prerequisite for the container E2E layer')
class FrozenTimeEquipmentRunSpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture {

    /** The instant the whole run is frozen at: far from the wall clock, so a leak cannot match it. */
    private static final Instant FROZEN = Instant.parse('2031-03-04T05:06:07Z')

    private static final String TASK_ID = 'frozen-time-1'

    /**
     * The fields copied from the egress guard's denial log: the guard process inside the box stamps
     * them on its own clock ({@code DenialCursor} positions are the guard's instants), so they are a
     * foreign medium the factory reads, not a stamp of any factory time source.
     */
    private static final Set<String> GUARD_STAMPED = ['denials', 'egressCursor'] as Set

    @TempDir
    Path tempDir

    def cleanup() {
        ContainerE2eDocker.removeTaskObjects(TASK_ID)
    }

    // FR18, FR19, FR22: one frozen equipment, one container run — every instant on the branch is it.
    def "a container run assembled on a frozen time equipment stamps only the frozen instant"() {
        given: 'a project with a one-stage container-bound pipeline'
        def projectRoot = projectWithOneStage()

        and: 'the shipped composition on one equipment whose clock never advances'
        // real-time-wiring: only the clock is under test, and it is frozen; the box's guard
        //     self-check must really wait, so the sleeper is the real one.
        def frozen = new TimeEquipment(new VirtualClock(FROZEN), new ThreadSleeper())
        def factoryProps = testProperties(agentCliBinary: FakeAgentSandboxImage.BINARY)
        def runner = newManualRunRunner(
                projectRoot,
                tempDir.resolve('home'),
                new SandboxProperties(FakeAgentSandboxImage.ensureBuilt('plain-round'),
                null, null, null, [], [], false, null, null, null, null),
                new BindingProperties(null, [:]),
                new ManualRunConfiguration().taskGit(new GitProcessRunner(), new ClaimEpochBook(), frozen),
                factoryProps,
                new TrackerWiring([:], MapSecretsProvider.NONE, TrackerValidatorStub.plainSource(), frozen),
                [:],
                new GitVersionCheck(new GitProcessRunner()),
                frozen)

        when:
        runner.run(new DefaultApplicationArguments(
                        "--dir=${projectRoot}".toString(), '--task=do the thing', "--task-id=${TASK_ID}"))

        then: 'the run wrote its task file and its state'
        def instants = envelopeInstants(projectRoot, "gnomish/${TASK_ID}")
        instants.keySet().any {
            it.endsWith(EnvelopePaths.TASK_FILE + ' createdAt')
        }
        instants.keySet().any { it.contains(EnvelopePaths.STATE_FILE) }

        and: 'every instant it wrote is the frozen one'
        instants.findAll { where, at -> at != FROZEN } == [:]
    }

    private Path projectWithOneStage() {
        def projectRoot = initWorkingRepo(tempDir, 'frozen-time-project')
        Files.createDirectories(projectRoot.resolve('.gnomish/stages/build'))
        Files.writeString(projectRoot.resolve('.gnomish/config.yaml'), 'schemaVersion: "1"\nautonomy:\n  attemptLimit: 3\n')
        Files.writeString(projectRoot.resolve('.gnomish/pipeline.yaml'), 'stages:\n  - build\n')
        Files.writeString(projectRoot.resolve('.gnomish/stages/build/instructions.md'), 'build it\n')
        Files.writeString(projectRoot.resolve('.gnomish/stages/build/stage.yaml'), '''\
purpose: build it
executor:
  type: agent-cli
  model: model-x
instructions: stages/build/instructions.md
verify:
  - type: builtin
    name: files_exist
    params:
      files: [output.txt]
advancement: auto
''')
        commitAll(projectRoot)
        projectRoot
    }

    /**
     * Every instant-valued field of the task file and the state file the factory stamps, in every
     * commit of the branch that carries them, keyed by commit, file and field path. The envelope leaves the tip at
     * delivery, so the history is where the run's stamps are.
     */
    private Map<String, Instant> envelopeInstants(Path repo, String branch) {
        Map<String, Instant> found = [:]
        gitOutput(repo, 'rev-list', branch).readLines().each { commit ->
            [
                EnvelopePaths.TASK_JSON_PATH,
                EnvelopePaths.STATE_JSON_PATH
            ].each { path ->
                def shown = new GitProcessRunner().run(repo, 'show', "${commit}:${path}".toString())
                if (shown.exitCode() == 0) {
                    collect(new ObjectMapper().readTree(shown.stdout().forParsing()), "${commit.take(8)} ${path}", found)
                }
            }
        }
        found
    }

    private static void collect(JsonNode node, String where, Map<String, Instant> found) {
        if (node.isObject()) {
            node.properties().findAll { !(it.key in GUARD_STAMPED) }.each {
                collect(it.value, "${where} ${it.key}".toString(), found)
            }
        } else if (node.isArray()) {
            node.eachWithIndex { JsonNode item, int i ->
                collect(item, "${where}[${i}]".toString(), found)
            }
        } else if (node.isTextual() && node.asText() ==~ /\d{4}-\d{2}-\d{2}T.*Z/) {
            found[where] = Instant.parse(node.asText())
        }
    }
}
