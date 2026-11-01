package com.github.oinsio.gnomish.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.app.ContainerE2eDocker
import com.github.oinsio.gnomish.sandbox.environment.GuardImageAvailability
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import spock.lang.IgnoreIf
import spock.lang.Specification
import spock.lang.Timeout

/**
 * The packaged-jar end-to-end suite's {@code program} stage (task 8.3 of define-executor-contract,
 * FR10): the real {@code gnomish run} process, bound to the container adapter, runs the
 * {@code e2e-program} fixture, whose one stage names the declared {@code program} executor
 * {@code reference} — the reference executor of the stand-in library (design D18), baked into the
 * box by {@link ReferenceExecutorSandboxImage}. The round goes end to end through the contract: the
 * describe and the {@code start} run inside the box, the request on standard input, the result read
 * from the file the factory named, the egress guard's denials and the usage recorded on the round's
 * own commit.
 *
 * <p>Everything is read from the durable medium the process left behind — the task branch on the
 * bare origin — never from the process's internals.
 *
 * <p>Docker- and guard-image-gated: skips cleanly with no daemon or no pullable mitmproxy image.
 */
@Timeout(value = 420, unit = TimeUnit.SECONDS)
@IgnoreIf(
value = {
    !GuardImageAvailability.available()
},
reason = 'Docker daemon or guard image unavailable — Docker is a dev/CI prerequisite for the container E2E layer')
class ProgramStageContainerE2eSpec extends Specification {

    private static final ObjectMapper JSON = new ObjectMapper()

    private final E2eProcessHarness harness = new E2eProcessHarness()
    private final String task = "e2e-program-${System.nanoTime() % 1_000_000}"

    def cleanup() {
        ContainerE2eDocker.removeTaskObjects(task)
    }

    // FR10: a program stage in role transform, end to end through the contract, in a box behind the guard
    // Scenario: executor/conformance-kit / Factory end-to-end uses the reference executor
    // Scenario: executor/result-channel / Executor runs through the environment seam
    def "FR10: the packaged factory runs a program stage through the reference executor inside the box, behind the egress guard"() {
        given: 'the e2e-program fixture published to a bare origin and bound to the container adapter over the reference image'
        def image = ReferenceExecutorSandboxImage.ensureBuilt()
        Path clone = E2eGitTree.copyOf('e2e-program')
        Path origin = clone.resolveSibling('origin.git')
        E2eGitTree.publish(clone, origin)
        E2eProcessHarness.projectConfig(clone,
                "factory:\n  bindings:\n    default: container\n  sandbox:\n    image: ${image}\n")

        and: 'the declared program exists only inside the box, so a factory-side spawn of it could not succeed'
        !Files.exists(Path.of(ReferenceExecutorSandboxImage.REFERENCE))

        when:
        def result = harness.execute('run', clone, [
            '--dir=' + clone,
            '--task=transform through the reference executor',
            '--task-id=' + task
        ], [])

        then: 'the round completed and the pipeline with it'
        result.exitCode() == 0
        result.stdout().contains('Stage: pipeline complete')
        !result.stderr().contains('\tat ')

        and: 'the describe ran in the box: the task pinned the reference executor at the version its describe answered'
        def state = roundState(origin)
        state.get('executorVersions').get('reference').asText() == '1'

        and: 'the round is the reference executor\'s, in role transform, its result read from the file it wrote'
        def attempt = state.get('attempts').find {
            it.get('stage').asText() == 'build'
        }
        attempt.get('result').asText() == 'passed'
        attempt.get('usage').size() == 1
        with(attempt.get('usage').get(0)) {
            get('executor').asText() == 'reference'
            get('role').asText() == 'transform'
            get('provenance').asText() == 'absent'
        }

        and: 'the egress guard was in force: its denial log was read for the round, from a live guard container'
        state.get('egressCursor').get('source').asText()
        !attempt.get('denials').isEmpty()
        attempt.get('denials').every {
            it.get('message').asText().startsWith('egress denied: ')
        }
        attempt.get('denials').every {
            it.get('identity').get('source').asText() == state.get('egressCursor').get('source').asText()
        }
    }

    /** The {@code state.json} of the build stage's first round commit on the task branch at {@code origin}. */
    private JsonNode roundState(Path origin) {
        String branch = "gnomish/${task}"
        String commit = E2eGitTree.git(origin, 'log', '--format=%H', '--grep', '^gnomish: round build#0$', branch)
        assert commit && !commit.contains('\n'): "one round commit expected on ${branch}: ${commit}"
        JSON.readTree(E2eGitTree.git(origin, 'show', "${commit}:.gnomish-task/state.json".toString()))
    }
}
