package com.github.oinsio.gnomish.adapter.environment

import com.github.oinsio.gnomish.ResourceLimits
import com.github.oinsio.gnomish.domain.engine.port.Clock
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Instant
import spock.lang.Specification

/**
 * FR3, FR10, NFR-R2, NFR-S3 of add-sandbox-core: the container adapter's Docker
 * orchestration verified without a daemon (a recording fake {@link DockerCli}) —
 * the materialize object sequence, the fail-fast on a failed create, the
 * required image, the best-effort idempotent dispose, and the pre-materialize
 * guards. Real {@code exec}/file streaming is covered by the Docker-gated
 * contract spec.
 */
class ContainerTaskExecutionEnvironmentUnitSpec extends Specification {

    static final String KEY = 'org-repo-7'
    static final ResourceLimits LIMITS = new ResourceLimits('2', '2g', 512L, '10g')
    static final Path SOURCE_CLONE = Path.of('/factory/project-clone')

    def docker = new RecordingDockerCli()
    def clock = { -> Instant.now() } as Clock
    List<List<String>> harvests = []
    def harvester = { String container, String branch -> harvests << [container, branch] } as ContainerHarvest

    private ContainerTaskExecutionEnvironment env(String image = 'gnomish/img') {
        new ContainerTaskExecutionEnvironment(
                docker, KEY, SOURCE_CLONE, harvester, image, 'runc', LIMITS, false, clock, ChildEnvAllowlist.none())
    }

    def setup() {
        // Default fake daemon: no container exists yet (inspect fails), every create succeeds —
        // the fresh-materialize baseline. Features override onRun for reattach and failure paths.
        docker.onRun = { List<String> args ->
            args[0] == 'inspect' ? new DockerResult(1, '', 'No such object') : new DockerResult(0, '', '')
        }
    }

    static final List<String> INSPECT = DockerCommands.inspectContainerState('gnomish-box-' + KEY)

    def "FR3: materialize inspects, then creates network, volume, seed clone, container, scratch dir, in order"() {
        when:
        env().materialize('gnomish/task-x', null)

        then:
        docker.runs == [
            INSPECT,
            DockerCommands.createNetwork(KEY),
            DockerCommands.createVolume(KEY),
            DockerCommands.seedClone(KEY, 'gnomish/img', '/factory/project-clone', 'gnomish/task-x', null),
            DockerCommands.runContainer(KEY, 'gnomish/img', 'runc', LIMITS, false, '/gnomish/work'),
            DockerCommands.exec(KEY, '/gnomish/work', [:], false, [
                'mkdir',
                '-p',
                '/gnomish/scratch'
            ]),
        ]
    }

    def "FR6: materialize reattaches to a stopped kept container — start it, never re-clone"() {
        given: 'the task container survived, stopped (keep semantics)'
        docker.onRun = { List<String> args ->
            args[0] == 'inspect'
            ? new DockerResult(0, 'false 2026-08-07T10:00:00Z\n', '')
            : new DockerResult(0, '', '')
        }

        when:
        env().materialize('gnomish/task-x', null)

        then: 'only a start — the surviving volume may hold the sole copy of unrecorded work'
        docker.runs == [
            INSPECT,
            DockerCommands.startContainer('gnomish-box-' + KEY),
        ]
    }

    def "FR6: materialize over a running container touches no docker object"() {
        given:
        docker.onRun = { List<String> args ->
            args[0] == 'inspect' ? new DockerResult(0, 'true 0001-01-01T00:00:00Z\n', '') : new DockerResult(0, '', '')
        }

        when:
        env().materialize('gnomish/task-x', null)

        then:
        docker.runs == [INSPECT]
    }

    def "FR6: a commit pin on reattach runs the idempotent seed helper to reset the working copy"() {
        given:
        docker.onRun = { List<String> args ->
            args[0] == 'inspect' ? new DockerResult(0, 'true 0001-01-01T00:00:00Z\n', '') : new DockerResult(0, '', '')
        }

        when:
        env().materialize('gnomish/task-x', 'pin99')

        then:
        docker.runs == [
            INSPECT,
            DockerCommands.seedClone(KEY, 'gnomish/img', '/factory/project-clone', 'gnomish/task-x', 'pin99'),
        ]
    }

    def "FR6: a leftover network from a half-removed environment is reused, not a failure"() {
        given: 'no container, but network create reports a duplicate'
        docker.onRun = { List<String> args ->
            if (args[0] == 'inspect') {
                return new DockerResult(1, '', 'No such object')
            }
            args[0] == 'network' && args[1] == 'create'
                    ? new DockerResult(1, '', 'network with name gnomish-net-' + KEY + ' already exists')
                    : new DockerResult(0, '', '')
        }

        when:
        env().materialize('gnomish/task-x', null)

        then:
        noExceptionThrown()
        docker.runs.contains(DockerCommands.createVolume(KEY))
    }

    def "FR3: the seed helper mounts the factory clone read-only and the task container never mounts it"() {
        when:
        env().materialize('gnomish/task-x', null)

        then: 'the one-shot helper is --rm, off-network, with the factory clone ro beside the task volume'
        def seed = docker.runs[3]
        seed[0] == 'run'
        seed.contains('--rm')
        seed.contains('none')
        seed.contains('/factory/project-clone:/gnomish/src:ro')
        seed.contains('gnomish-vol-' + KEY + ':/gnomish/work')

        and: 'the seed script clones --no-hardlinks with the branch as a positional parameter, never interpolated'
        def script = seed[seed.indexOf('-c') + 1]
        script.contains('clone --no-hardlinks --single-branch --branch "$1"')
        script.contains('-c safe.directory=/gnomish/src')
        script.contains('git remote remove origin')
        script.contains('git config gc.auto 0')
        !script.contains('gnomish/task-x')
        seed.last() == 'gnomish/task-x'

        and: 'the task container run mounts only the task volume'
        def run = docker.runs[4]
        run.findAll { it.toString().contains(':') && it.toString().contains('/gnomish') } ==
        [
            'gnomish-vol-' + KEY + ':/gnomish/work'
        ]
    }

    def "FR3: a factory-chosen commit pin reaches the seed script as the second positional parameter"() {
        when:
        env().materialize('gnomish/task-x', 'abc123')

        then:
        def seed = docker.runs[3]
        seed == DockerCommands.seedClone(KEY, 'gnomish/img', '/factory/project-clone', 'gnomish/task-x', 'abc123')
        seed.last() == 'abc123'
        seed[seed.size() - 2] == 'gnomish/task-x'
        seed[seed.indexOf('-c') + 1].contains('git reset --hard "$2"')
    }

    def "FR3: a failed docker create surfaces as IllegalStateException naming the failure"() {
        given:
        docker.onRun = { args -> new DockerResult(1, '', 'no space left on device') }

        when:
        env().materialize('task/x', null)

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains('no space left on device')
    }

    def "NFR-R1: a daemon outage at materialize propagates as an infrastructure failure, not a quality failure"() {
        given:
        docker.onRun = { args -> throw new DockerUnavailableException('Cannot connect to the Docker daemon', null) }

        when:
        env().materialize('task/x', null)

        then: 'the caller sees the infrastructure-outage type — never an IllegalStateException it might read as a real failure'
        thrown(DockerUnavailableException)
    }

    def "FR3: the container adapter cannot be constructed without an image"() {
        when:
        env(image)

        then:
        thrown(IllegalStateException)

        where:
        image << [null, '', '   ']
    }

    def "NFR-R2: dispose removes container, guard, volume and network"() {
        when:
        env().dispose()

        then:
        docker.runs == [
            DockerCommands.removeContainer('gnomish-box-' + KEY),
            GuardCommands.removeGuard(KEY),
            DockerCommands.removeVolume('gnomish-vol-' + KEY),
            DockerCommands.removeNetwork('gnomish-net-' + KEY),
        ]
    }

    def "NFR-R2: dispose is best-effort — one failing step never stops the others or throws"() {
        given:
        docker.onRun = { args ->
            if (args == DockerCommands.removeContainer('gnomish-box-' + KEY)) {
                throw new DockerUnavailableException('down', null)
            }
            new DockerResult(0, '', '')
        }

        when:
        env().dispose()

        then: 'the failing container removal does not stop the volume and network removals, and nothing propagates'
        noExceptionThrown()
        docker.runs.contains(DockerCommands.removeVolume('gnomish-vol-' + KEY))
        docker.runs.contains(DockerCommands.removeNetwork('gnomish-net-' + KEY))
    }

    def "NFR-S3: a putFile path escaping the roots is refused before any docker exec"() {
        given:
        def e = env()
        e.materialize('task/x', null)

        when:
        e.putFile('../escape.json', 'x'.getBytes(StandardCharsets.UTF_8))

        then:
        thrown(PathEscapeException)
        docker.starts.isEmpty()
    }

    def "operations before materialize fail with a clear state error"() {
        when:
        env().scratchRoot()

        then:
        thrown(IllegalStateException)
    }

    // FR3: exec must be refused while no environment is bound — before materialize and again
    // after dispose — and the refusal happens before any docker process is ever started
    def "FR3: exec before materialize or after dispose is refused and never starts a docker process"() {
        given:
        def e = env()
        def command = new ExecCommand(['sh', '-c', 'true'], [:], null, false)

        when: 'exec before materialize'
        e.exec(command)

        then:
        thrown(IllegalStateException)
        docker.starts.isEmpty()

        when: 'exec after materialize + dispose'
        e.materialize('task/x', null)
        e.dispose()
        e.exec(command)

        then:
        thrown(IllegalStateException)
        docker.starts.isEmpty()
    }

    def "FR5: harvest delegates to the factory-side fetch with the factory-derived container name and the materialized branch"() {
        given:
        def e = env()
        e.materialize('gnomish/task-x', null)

        when:
        e.harvest()

        then: 'the fetch seam gets the container name from the factory naming scheme, never from the box'
        harvests == [
            [
                'gnomish-box-' + KEY,
                'gnomish/task-x'
            ]
        ]
    }

    def "FR5: harvest before materialize fails with a clear state error and never touches the fetch seam"() {
        when:
        env().harvest()

        then:
        thrown(IllegalStateException)
        harvests.isEmpty()
    }

    def "FR5: harvest after dispose fails — the volume is gone, there is nothing to fetch from"() {
        given:
        def e = env()
        e.materialize('gnomish/task-x', null)
        e.dispose()

        when:
        e.harvest()

        then:
        thrown(IllegalStateException)
        harvests.isEmpty()
    }

    def "the passport is the container passport, available before materialize"() {
        expect:
        env().passport() == CapabilityPassport.container()
    }

    def "scratchRoot is the fixed in-box scratch path once materialized"() {
        given:
        def e = env()
        e.materialize('task/x', null)

        expect:
        e.scratchRoot() == '/gnomish/scratch'
    }

    // FR9, D6, M3 (task 7.2): the exec child environment is the composed allowlist with an EMPTY
    // container base — passthrough names (values live from the factory environment) plus the
    // factory-set fragment become -e entries; no other factory variable reaches the argv.
    def "FR9: exec passes exactly the composed allowlist as --env entries, with an empty container base"() {
        given: 'an allowlist passing JAVA_HOME through, over a factory env that also holds a noise key'
        def factoryEnv = [JAVA_HOME: '/jdk', AWS_SECRET_ACCESS_KEY: 'never', PATH: '/usr/bin']
        def allowlist = ChildEnvAllowlist.over(['JAVA_HOME'], [], { -> factoryEnv })
        def e = new ContainerTaskExecutionEnvironment(
                docker, KEY, SOURCE_CLONE, harvester, 'gnomish/img', 'runc', LIMITS, false, clock, allowlist)
        e.materialize('task/x', null)

        when: 'an exec with a factory-set fragment starts (the recording fake then stops it)'
        e.exec(new ExecCommand(['sh', '-c', 'true'], [GNOMISH_FINDINGS_FILE: '/gnomish/scratch/f.json'], null, false))

        then: 'the argv carries exactly the passthrough and factory-set entries — nothing else'
        thrown(IllegalStateException)
        def argv = docker.starts.last()
        def envEntries = []
        argv.eachWithIndex { token, i ->
            if (token == '-e') {
                envEntries << argv[i + 1]
            }
        }
        envEntries.toSet() == [
            'JAVA_HOME=/jdk',
            'GNOMISH_FINDINGS_FILE=/gnomish/scratch/f.json'
        ].toSet()
    }
}
